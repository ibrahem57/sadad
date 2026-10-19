package com.sadad.app.data.sync

import com.sadad.app.data.ledger.LedgerReset
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.net.ApiException
import com.sadad.app.data.net.AuthSession
import com.sadad.app.data.net.LedgerApi
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.session.Sessions
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.session.StoredInstallation
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialized per-store passes; every network and database operation belongs to the store's job. */
class SyncEngine(
    private val accounts: Accounts,
    private val sessions: Sessions,
    private val api: LedgerApi,
    private val leases: (String) -> LeaseStore,
) {
  private class Progress {
    val mutex = Mutex()
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
  }

  private val progress = ConcurrentHashMap<String, Progress>()

  private fun progress(id: String) = progress.computeIfAbsent(id) { Progress() }

  fun status(storeUserId: String): StateFlow<SyncStatus> =
      progress(storeUserId).status.asStateFlow()

  suspend fun pass(scope: StoreScope): SyncStatus = scope.runWhileOpen {
    val id = scope.account.storeUserId
    val progress = progress(id)
    progress.mutex.withLock {
      progress.status.value = SyncStatus.Running
      val result =
          try {
            Pass(scope).run()
          } catch (stopped: Stopped) {
            stopped.status
          } catch (refused: ApiException) {
            refusal(refused)
          } catch (_: IOException) {
            SyncStatus.Offline
          } catch (cancelled: CancellationException) {
            progress.status.value = SyncStatus.Retry
            throw cancelled
          }
      if (
          result in
              listOf(
                  SyncStatus.SignedOut,
                  SyncStatus.Revoked,
                  SyncStatus.Suspended,
                  SyncStatus.PasswordChangeRequired,
              )
      ) {
        leases(id).clear()
      }
      progress.status.value = result
      result
    }
  }

  private inner class Pass(private val scope: StoreScope) {
    private val id = scope.account.storeUserId
    private lateinit var session: AuthSession
    private var refreshed = false

    suspend fun run(): SyncStatus {
      session = sessions.syncSession(id) ?: return SyncStatus.SignedOut
      if (!scope.isOnline()) return SyncStatus.Offline
      if (session.forcePasswordChange) return SyncStatus.PasswordChangeRequired
      var account = accounts.account(id) ?: return SyncStatus.SignedOut
      if (account.installation?.status != "active") {
        val installation = call {
          api.enroll(it, scope.signer, scope.signer.publicKey, account.label, account.staffName)
        }
        if (installation.installationId != account.installationId)
            return SyncStatus.Failed("invalid_input")
        accounts.update(id) {
          it.copy(
              installation =
                  StoredInstallation(
                      installation.status,
                      installation.generation,
                      installation.permissions,
                  )
          )
        }
        scope.updateInstallation(
            StoredInstallation(
                installation.status,
                installation.generation,
                installation.permissions,
            )
        )
        if (installation.status == "revoked") return SyncStatus.Revoked
        if (installation.status != "active") return SyncStatus.AwaitingApproval
        account = accounts.account(id) ?: return SyncStatus.SignedOut
      }
      val lease = leases(id)
      lease.load(account)
      if (lease.needsRenewal(account)) {
        try {
          lease.save(account, call { api.lease(it, scope.signer) })
        } catch (refused: ApiException) {
          lease.clear()
          // A lease refusal only disables offline writes. Store/session refusals still stop sync.
          if (refusal(refused) !is SyncStatus.Failed) throw refused
        }
      }
      push()
      pull()
      return SyncStatus.Idle
    }

    private suspend fun <T> call(request: suspend (AuthSession) -> T): T {
      try {
        return request(session)
      } catch (refused: ApiException) {
        if (refused.status != 401 || refused.code != "authorization_required") throw refused
        if (refreshed) {
          sessions.invalidateForSync(id)
          throw Stopped(SyncStatus.SignedOut)
        }
        refreshed = true
        session =
            sessions.syncSession(id, forceRefresh = true) ?: throw Stopped(SyncStatus.SignedOut)
        if (session.forcePasswordChange) throw Stopped(SyncStatus.PasswordChangeRequired)
        try {
          return request(session)
        } catch (again: ApiException) {
          if (again.status == 401 && again.code == "authorization_required") {
            sessions.invalidateForSync(id)
            throw Stopped(SyncStatus.SignedOut)
          }
          throw again
        }
      }
    }

    private suspend fun push() {
      while (true) {
        val eligible =
            scope.database
                .outboxDao()
                .all()
                .filter { it.state == "queued" && it.recoveryPermit == null }
                .mapTo(HashSet()) { it.operationId }
        val queued =
            scope.outbox
                .snapshot()
                .filter { it.state == OutboxState.QUEUED && it.operationId in eligible }
                .take(OperationsRequest.MAX_BATCH)
                .map { it.command }
        if (queued.isEmpty()) return
        val batch = batchWithinLimit(queued)
        if (batch.isEmpty()) throw Stopped(SyncStatus.Failed("invalid_input"))
        val receipts = call { api.operations(it, scope.signer, batch) }
        // Never settle an unrelated row or loop forever on a partial/malformed answer.
        if (
            receipts.map { it.operationId }.toSet() != batch.map { it.operationId }.toSet() ||
                receipts.size != batch.size
        ) {
          throw Stopped(SyncStatus.Failed("temporary_service_failure"))
        }
        scope.outbox.settle(receipts)
      }
    }

    private suspend fun pull() {
      var reset = false
      while (true) {
        val cursor = scope.database.metaDao().get()?.cursor ?: 0L
        val page = call { api.changes(it, scope.signer, cursor, 500) }
        try {
          scope.repository.applyPage(page)
          scope.setEpoch(page.epoch)
        } catch (changed: LedgerReset) {
          scope.setEpoch(null)
          if (reset) throw Stopped(SyncStatus.Failed("epoch_changed"))
          reset = true
          continue
        }
        if (!page.hasMore) return
        if (page.nextCursor <= cursor) throw Stopped(SyncStatus.Failed("temporary_service_failure"))
      }
    }
  }

  private fun batchWithinLimit(queued: List<LedgerCommand>): List<LedgerCommand> {
    val result = mutableListOf<LedgerCommand>()
    for (command in queued) {
      val candidate = result + command
      val bytes =
          LedgerJson.encodeToString(OperationsRequest.serializer(), OperationsRequest(candidate))
              .toByteArray(Charsets.UTF_8)
              .size
      if (bytes > 524288) break
      result += command
    }
    return result
  }

  private class Stopped(val status: SyncStatus) : Exception()

  private fun refusal(error: ApiException): SyncStatus =
      when (error.code) {
        "authorization_required" -> SyncStatus.SignedOut
        "installation_or_session_revoked",
        "installation_revoked" -> SyncStatus.Revoked
        "store_suspended" -> SyncStatus.Suspended
        "password_change_required" -> SyncStatus.PasswordChangeRequired
        "request_rate_limited" -> SyncStatus.Retry
        else -> SyncStatus.Failed(error.code)
      }
}
