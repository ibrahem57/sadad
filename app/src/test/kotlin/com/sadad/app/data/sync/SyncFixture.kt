package com.sadad.app.data.sync

import com.sadad.app.data.crypto.SoftwareSigner
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.receiptOf
import com.sadad.app.data.net.LeaseVerifier
import com.sadad.app.data.net.LedgerApi
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.MockResponse

internal class SyncFixture private constructor(val test: TestGraph) : AutoCloseable {
  val scope
    get() = test.graph.current.value!!

  val signer = SoftwareSigner("lease-server")
  val job = SupervisorJob()
  var boot = 5
  var now = System.currentTimeMillis()
  val lease =
      LeaseStore(
          File(test.context.cacheDir, "lease-${UUID.randomUUID()}.preferences_pb"),
          CoroutineScope(job + Dispatchers.IO),
          LeaseVerifier(signer.publicKey.x, signer.publicKey.y),
          { boot },
          { now },
      )
  val client =
      test.graph.client
          .newBuilder()
          .retryOnConnectionFailure(false)
          .readTimeout(3, TimeUnit.SECONDS)
          .build()
  val engine =
      SyncEngine(
          test.graph.accounts,
          test.graph.sessions,
          LedgerApi(client, test.server.url("/").toString(), "publishable"),
      ) {
        lease
      }
  val server
    get() = test.server

  suspend fun queue(count: Int, note: String = ""): List<LedgerCommand> {
    repeat(count) { scope.repository.addContact("شخص $it", null, null, note, null).getOrThrow() }
    return scope.outbox.queued(Int.MAX_VALUE)
  }

  fun page(page: Changes = Events.page(emptyList())) {
    server.enqueue(pageResponse(page))
  }

  suspend fun freshLease() {
    lease.save(scope.account, signedLease(signer, scope.account, now))
  }

  override fun close() {
    runBlocking { job.cancelAndJoin() }
    client.connectionPool.evictAll()
    test.close()
  }

  companion object {
    suspend fun create(fresh: Boolean = true): SyncFixture {
      val fixture = SyncFixture(TestGraph.signedIn())
      fixture.server.takeRequest()
      if (fresh) fixture.freshLease()
      return fixture
    }
  }
}

internal fun pageResponse(page: Changes): MockResponse =
    MockResponse.Builder()
        .body("""{"changes":${LedgerJson.encodeToString(Changes.serializer(), page)}}""")
        .build()

internal fun receiptResponse(commands: List<LedgerCommand>, reject: Int = -1): MockResponse {
  val receipts = commands.mapIndexed { index, command ->
    receiptOf(
        command,
        if (index == reject) ReceiptStatus.REJECTED else ReceiptStatus.ACCEPTED,
        if (index == reject) "store_permission_denied" else null,
        index + 1L,
    )
  }
  return MockResponse.Builder()
      .body(
          """{"receipts":${LedgerJson.encodeToString(ListSerializer(Receipt.serializer()), receipts)}}"""
      )
      .build()
}

internal fun refusalResponse(code: String, status: Int = 403): MockResponse =
    MockResponse.Builder().code(status).body("""{"code":"$code"}""").build()
