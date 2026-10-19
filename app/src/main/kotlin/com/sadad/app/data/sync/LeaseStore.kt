package com.sadad.app.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.data.net.Lease
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.session.Account
import com.sadad.ledger.LedgerJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** One store's signed lease. Only verified bytes are cached for the synchronous write guard. */
class LeaseStore(
    file: File,
    scope: CoroutineScope,
    private val verifier: LeaseVerifier,
    private val bootCount: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
  private val data = PreferenceDataStoreFactory.create(scope = scope) { file }
  @Volatile private var verified: Record? = null

  suspend fun load(account: Account) {
    val raw = data.data.first()[KEY]
    val record =
        try {
          raw?.let { LedgerJson.decodeFromString(Record.serializer(), it) }
        } catch (_: IllegalArgumentException) {
          null
        }
    verified = record?.takeIf {
      verifier.verify(it.lease, account.installationId, account.storeUserId)
    }
  }

  suspend fun save(account: Account, lease: Lease) {
    if (!verifier.verify(lease, account.installationId, account.storeUserId)) {
      clear()
      return
    }
    val record = Record(lease, bootCount())
    data.edit { it[KEY] = LedgerJson.encodeToString(Record.serializer(), record) }
    verified = record
  }

  suspend fun clear() {
    verified = null
    data.edit { it.remove(KEY) }
  }

  fun allows(account: Account): Boolean {
    val record = verified ?: return false
    val payload = record.lease.payload
    val boot = bootCount()
    val at = now()
    return boot >= 0 &&
        record.boot == boot &&
        payload.userId == account.storeUserId &&
        payload.installationId == account.installationId &&
        account.installation?.status == "active" &&
        payload.generation == account.installation.generation &&
        payload.issuedAt <= at &&
        at < payload.expiresAt &&
        payload.allowed.containsAll(OFFLINE_TYPES)
  }

  fun needsRenewal(account: Account): Boolean =
      !allows(account) || verified!!.lease.payload.expiresAt - now() < RENEW_BEFORE

  @Serializable private data class Record(val lease: Lease, val boot: Int)

  private companion object {
    val KEY = stringPreferencesKey("lease")
    const val RENEW_BEFORE = 86400000L
    val OFFLINE_TYPES = listOf("contact.create", "debt.create", "payment.create")
  }
}
