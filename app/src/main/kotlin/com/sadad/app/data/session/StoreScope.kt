package com.sadad.app.data.session

import com.sadad.app.data.crypto.InstallationSigner
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.Outbox
import com.sadad.app.data.ledger.RepositorySession
import com.sadad.app.data.sync.LeaseStore
import com.sadad.ledger.Origin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open store: its database, sealed outbox, repository and installation signer. Nothing here is
 * shared with another store; [close] cancels its jobs and closes its database file.
 */
class StoreScope(
    val account: Account,
    val database: LedgerDatabase,
    val store: LedgerStore,
    val outbox: Outbox,
    val signer: InstallationSigner,
    private val accounts: Accounts,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val lease: LeaseStore? = null,
    private val queueSync: () -> Unit = {},
) : RepositorySession {
  override val staffName: Flow<String> =
      accounts.accounts.map { list ->
        list.firstOrNull { it.storeUserId == account.storeUserId }?.staffName.orEmpty()
      }

  // Declared after staffName: LedgerRepository's view combines it during construction.
  val repository: LedgerRepository = LedgerRepository(store, outbox, this, scope)

  @Volatile private var epoch: String? = null

  @Volatile private var closed = false

  // Enroll and session refresh save the installation after the store opened; follow the file.
  @Volatile private var installation: StoredInstallation? = account.installation

  init {
    scope.launch {
      accounts.accounts.collect { list ->
        installation = list.firstOrNull { it.storeUserId == account.storeUserId }?.installation
      }
    }
  }

  override val selfInstallationId: String
    get() = account.installationId

  /** The epoch of the Ledger state this store loaded or last applied; sync keeps it current. */
  override fun origin(): Origin? {
    if (closed) return null
    val installation = installation ?: return null
    if (installation.status != "active") return null
    val currentEpoch = epoch ?: return null
    return Origin(account.installationId, installation.generation, currentEpoch)
  }

  override fun isOnline(): Boolean = online()

  override fun offlineAllowed(): Boolean =
      !closed && lease?.allows(account.copy(installation = installation)) == true

  override fun onQueued() = queueSync()

  fun updateInstallation(value: StoredInstallation) {
    installation = value
  }

  suspend fun <T> runWhileOpen(block: suspend () -> T): T {
    check(!closed) { "store scope is closed" }
    val task = scope.async(Dispatchers.IO) { block() }
    return try {
      task.await()
    } finally {
      task.cancel()
    }
  }

  fun setEpoch(value: String?) {
    epoch = value
  }

  suspend fun close() {
    if (closed) return
    closed = true
    scope.coroutineContext[Job]?.cancelAndJoin()
    withContext(Dispatchers.IO) { database.close() }
  }
}
