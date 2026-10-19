package com.sadad.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.sadad.app.data.crypto.DataKey
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.ledger.Outbox
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.net.ApiHttpClient
import com.sadad.app.data.net.AuthApi
import com.sadad.app.data.net.ErrorMessages
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.net.LedgerApi
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.session.Installations
import com.sadad.app.data.session.KeystoreInstallations
import com.sadad.app.data.session.SessionHost
import com.sadad.app.data.session.Sessions
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.LeaseStore
import com.sadad.app.data.sync.SyncEngine
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.ui.AwaitingApproval
import com.sadad.app.ui.Home
import com.sadad.app.ui.Login
import com.sadad.app.ui.PasswordChange
import com.sadad.app.ui.Welcome
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The app's one manual DI container. It owns the shared clients and the currently open store, and
 * answers which screen the app starts on. One store is open at a time; its scope is never shared.
 */
class AppGraph
internal constructor(
    private val context: Context,
    val client: OkHttpClient,
    val dataKey: DataKey,
    val accounts: Accounts,
    val authApi: AuthApi,
    val ledgerApi: LedgerApi,
    private val installations: Installations,
    private val openDatabase: (String) -> LedgerDatabase,
    private val deleteDatabase: (String) -> Unit,
    private val online: () -> Boolean,
    private val deviceLabel: String,
    private val scope: CoroutineScope,
    private val syncScheduler: SyncScheduling? = null,
    leaseDirectory: File? = null,
    private val leaseVerifier: LeaseVerifier =
        LeaseVerifier(BuildConfig.LEASE_PUBLIC_KEY_X, BuildConfig.LEASE_PUBLIC_KEY_Y),
    private val bootCount: () -> Int = {
      Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    },
) : SessionHost {
  val errorMessages: ErrorMessages by lazy { ErrorMessages(context) }

  val sessions: Sessions by lazy {
    Sessions(accounts, authApi, installations, this, deviceLabel)
  }

  private val mutableCurrent = MutableStateFlow<StoreScope?>(null)
  private val storeMutex = Mutex()
  private val stores = mutableMapOf<String, StoreScope>()
  private val syncUsers = mutableMapOf<StoreScope, Int>()
  private val leaseDirectory = leaseDirectory ?: File(context.cacheDir, "sync-${UUID.randomUUID()}")
  private val leases = ConcurrentHashMap<String, LeaseStore>()
  private val syncEngine by lazy { SyncEngine(accounts, sessions, ledgerApi, ::leaseFor) }

  fun syncStatus(storeUserId: String): StateFlow<SyncStatus> = syncEngine.status(storeUserId)

  private fun leaseFor(storeUserId: String): LeaseStore =
      leases.computeIfAbsent(storeUserId) {
        LeaseStore(
            File(leaseDirectory, "lease-$it.preferences_pb"),
            scope,
            leaseVerifier,
            bootCount,
        )
      }

  /** Background sync reuses a store's scope without changing the active account or UI. */
  suspend fun syncStore(storeUserId: String): SyncStatus =
      withContext(Dispatchers.IO) {
        val opened = storeMutex.withLock {
          val account = accounts.account(storeUserId) ?: return@withContext SyncStatus.SignedOut
          (stores[storeUserId] ?: createStore(account).also { stores[storeUserId] = it }).also {
            syncUsers[it] = (syncUsers[it] ?: 0) + 1
          }
        }
        try {
          syncEngine.pass(opened)
        } finally {
          withContext(NonCancellable) {
            storeMutex.withLock {
              val remaining = (syncUsers[opened] ?: 1) - 1
              if (remaining == 0) syncUsers.remove(opened) else syncUsers[opened] = remaining
              if (
                  remaining == 0 &&
                      mutableCurrent.value !== opened &&
                      stores[storeUserId] === opened
              ) {
                stores.remove(storeUserId)
                opened.close()
              }
            }
          }
        }
      }

  val current: StateFlow<StoreScope?> = mutableCurrent.asStateFlow()

  /**
   * The first screen: Welcome when no store was ever added, Login without a session, then the
   * password change the server asked for, then approval, then Home.
   */
  suspend fun startRoute(): Any {
    val file = accounts.file()
    if (file.accounts.isEmpty()) return if (file.welcomeShown) Login else Welcome
    val activeId = file.activeStoreUserId ?: file.accounts.first().storeUserId
    val account = file.accounts.firstOrNull { it.storeUserId == activeId } ?: return Login
    val session = sessions.inMemorySession(activeId)
    if (session == null && account.sealedRefreshToken == null) return Login
    if (session?.forcePasswordChange == true) return PasswordChange
    if (account.installation?.status != "active") return AwaitingApproval
    if (mutableCurrent.value?.account?.storeUserId != account.storeUserId) openStore(account)
    return Home
  }

  /** Closes the open store, then opens [account]'s database, outbox and repository. */
  suspend fun openStore(account: Account): StoreScope =
      withContext(Dispatchers.IO) {
        storeMutex.withLock {
          closeCurrent()
          val opened =
              stores[account.storeUserId]
                  ?: createStore(account).also { stores[account.storeUserId] = it }
          mutableCurrent.value = opened
          opened
        }
      }

  private suspend fun createStore(account: Account): StoreScope {
    val signer =
        checkNotNull(installations.load(account.keyAlias, account.installationId)) {
          "the installation key for store ${account.storeUserId} is missing"
        }
    val lease = leaseFor(account.storeUserId)
    lease.load(account)
    val database = openDatabase(account.storeUserId)
    val store = LedgerStore(database)
    val outbox = Outbox(database.outboxDao(), dataKey.key)
    val storeJobs =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val storeScope =
        StoreScope(account, database, store, outbox, signer, accounts, online, storeJobs, lease) {
          syncScheduler?.syncNow(account.storeUserId)
        }
    try {
      storeScope.repository.load()
      storeScope.setEpoch(database.metaDao().get()?.epoch)
      syncScheduler?.schedulePeriodic(account.storeUserId)
      return storeScope
    } catch (error: Throwable) {
      withContext(NonCancellable) { storeScope.close() }
      throw error
    }
  }

  suspend fun closeStore() = storeMutex.withLock { closeCurrent() }

  private suspend fun closeCurrent() {
    val open = mutableCurrent.value ?: return
    mutableCurrent.value = null
    stores.remove(open.account.storeUserId)
    open.close()
  }

  suspend fun switchStore(storeUserId: String) = sessions.switchTo(storeUserId)

  suspend fun unsentCount(): Int = sessions.unsentCount()

  /**
   * Removes a store from this phone: its key, database file and account. A queued outbox refuses
   * unless the caller has confirmed twice.
   */
  suspend fun removeStore(storeUserId: String, confirmedTwice: Boolean = false): Boolean =
      withContext(Dispatchers.IO) {
        storeMutex.withLock {
          val account = accounts.account(storeUserId) ?: return@withContext true
          if (queuedCount(storeUserId) > 0 && !confirmedTwice) return@withContext false
          syncScheduler?.cancel(storeUserId)
          leases[storeUserId]?.clear()
          val open = stores.remove(storeUserId)
          if (mutableCurrent.value?.account?.storeUserId == storeUserId) {
            sessions.leave(storeUserId)
            mutableCurrent.value = null
          }
          open?.close()
          installations.delete(account.keyAlias)
          deleteDatabase(storeUserId)
          accounts.remove(storeUserId)
          true
        }
      }

  override suspend fun open(account: Account) {
    openStore(account)
  }

  override suspend fun close() {
    closeStore()
  }

  override suspend fun unsentCount(storeUserId: String): Int = storeMutex.withLock {
    queuedCount(storeUserId)
  }

  private suspend fun queuedCount(storeUserId: String): Int {
    val open = stores[storeUserId]
    if (open?.account?.storeUserId == storeUserId) {
      return open.outbox.snapshot().count { it.state == OutboxState.QUEUED }
    }
    val database = openDatabase(storeUserId)
    return try {
      Outbox(database.outboxDao(), dataKey.key).snapshot().count { it.state == OutboxState.QUEUED }
    } finally {
      withContext(Dispatchers.IO) { database.close() }
    }
  }

  companion object {
    fun create(context: Context): AppGraph {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val dataKey = DataKey.get(context)
      val client = ApiHttpClient.client
      val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
      return AppGraph(
          context = context,
          client = client,
          dataKey = dataKey,
          accounts =
              Accounts.create(scope, File(context.filesDir, Accounts.FILE_NAME), dataKey.key),
          authApi = AuthApi(client, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
          ledgerApi =
              LedgerApi(client, BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
          installations = KeystoreInstallations,
          openDatabase = { LedgerDatabase.open(context, it) },
          deleteDatabase = { context.deleteDatabase("ledger-$it.db") },
          online = { isOnline(context) },
          deviceLabel = Build.MODEL,
          scope = scope,
          syncScheduler = SyncScheduler(context),
          leaseDirectory = context.filesDir,
          bootCount = { boot },
      )
    }

    private fun isOnline(context: Context): Boolean {
      val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
      val network = manager.activeNetwork ?: return false
      return manager
          .getNetworkCapabilities(network)
          ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
  }
}
