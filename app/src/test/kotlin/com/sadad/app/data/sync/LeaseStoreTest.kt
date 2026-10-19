package com.sadad.app.data.sync

import com.sadad.app.BuildConfig
import com.sadad.app.data.crypto.Base64Url
import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.net.Lease
import com.sadad.app.data.net.LeasePayload
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.testAccount
import com.sadad.ledger.LedgerJson
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LeaseStoreTest {
  @get:Rule val temporary = TemporaryFolder()
  private val signer = SoftwareSigner("lease-server")
  private val account = testAccount(STORE_A).copy(installation = StoredInstallation("active", 1))

  @Test
  fun aVerifiedLeaseExpiresAndNeverSurvivesAnotherBoot() = runTest {
    val job = SupervisorJob()
    var boot = 10
    var now = 1800000000000L
    val store =
        LeaseStore(
            temporary.newFolder().resolve("lease.preferences_pb"),
            CoroutineScope(job + Dispatchers.IO),
            LeaseVerifier(signer.publicKey.x, signer.publicKey.y),
            { boot },
            { now },
        )
    try {
      store.save(account, signedLease(signer, account, now))
      assertTrue(store.allows(account))
      assertFalse(store.needsRenewal(account))
      boot++
      assertFalse(store.allows(account))
      assertTrue(store.needsRenewal(account))
      boot--
      now += 6 * DAY + 1
      assertTrue(store.allows(account))
      assertTrue(store.needsRenewal(account))
      now += DAY
      assertFalse(store.allows(account))
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun emptyPinnedKeyWrongIdentityAndNewGenerationFailClosed() = runTest {
    val job = SupervisorJob()
    val now = 1800000000000L
    try {
      val empty =
          LeaseStore(
              temporary.newFolder().resolve("lease.preferences_pb"),
              CoroutineScope(job + Dispatchers.IO),
              LeaseVerifier("", ""),
              { 1 },
              { now },
          )
      empty.save(account, signedLease(signer, account, now))
      assertFalse(empty.allows(account))
      val store =
          LeaseStore(
              temporary.newFolder().resolve("lease.preferences_pb"),
              CoroutineScope(job + Dispatchers.IO),
              LeaseVerifier(signer.publicKey.x, signer.publicKey.y),
              { 1 },
              { now },
          )
      store.save(account, signedLease(signer, account, now))
      assertFalse(store.allows(account.copy(installation = StoredInstallation("active", 2))))
      assertFalse(store.allows(account.copy(storeUserId = "other")))
      assertFalse(store.allows(account.copy(installation = StoredInstallation("revoked", 1))))
      store.clear()
      assertFalse(store.allows(account))
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun reopensTheSavedLeaseAndVerifiesItAgain() = runTest {
    val file = temporary.newFolder().resolve("lease.preferences_pb")
    val now = 1800000000000L
    val verifier = LeaseVerifier(signer.publicKey.x, signer.publicKey.y)
    val firstJob = SupervisorJob()
    val first =
        LeaseStore(file, CoroutineScope(firstJob + Dispatchers.IO), verifier, { 2 }, { now })
    first.save(account, signedLease(signer, account, now))
    firstJob.cancelAndJoin()
    val secondJob = SupervisorJob()
    try {
      val second =
          LeaseStore(file, CoroutineScope(secondJob + Dispatchers.IO), verifier, { 2 }, { now })
      second.load(account)
      assertTrue(second.allows(account))
    } finally {
      secondJob.cancelAndJoin()
    }
  }
}

internal const val DAY = 86400000L

internal fun signedLease(signer: SoftwareSigner, account: Account, issuedAt: Long): Lease {
  val payload =
      LeasePayload(
          1,
          URI(BuildConfig.SUPABASE_URL).host.substringBefore('.'),
          42L,
          account.storeUserId,
          account.installationId,
          account.installation?.generation ?: 1,
          issuedAt,
          issuedAt + 7 * DAY,
          listOf("contact.create", "debt.create", "payment.create"),
      )
  val bytes = LedgerJson.encodeToString(LeasePayload.serializer(), payload).toByteArray()
  return Lease(payload, Base64Url.encode(bytes), Base64Url.encode(signer.sign(bytes)))
}
