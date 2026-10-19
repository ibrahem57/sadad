package com.sadad.app.data.sync

import com.sadad.app.data.ledger.OutboxState
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPushTest {
  @Test
  fun oneHundredFiftyCommandsKeepTheirOrderInTwoBatches() = runTest {
    SyncFixture.create().use { f ->
      val commands = f.queue(150)
      f.server.enqueue(receiptResponse(commands.take(100)))
      f.server.enqueue(receiptResponse(commands.drop(100)))
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      val sent =
          (0..1).map {
            LedgerJson.decodeFromString(
                    OperationsRequest.serializer(),
                    f.server.takeRequest().body!!.utf8(),
                )
                .operations
          }
      assertEquals(listOf(100, 50), sent.map { it.size })
      assertEquals(commands.map { it.operationId }, sent.flatten().map { it.operationId })
      assertTrue(f.scope.outbox.snapshot().all { it.state == OutboxState.ACCEPTED })
    }
  }

  @Test
  fun byteLimitCountsUtf8OfTheWholeOperationsRequest() = runTest {
    SyncFixture.create().use { f ->
      val commands = f.queue(100, "ع".repeat(4000))
      val bodies = mutableListOf<String>()
      f.server.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
              if (request.method == "GET")
                  return pageResponse(com.sadad.app.data.db.Events.page(emptyList()))
              val body = request.body!!.utf8()
              bodies += body
              return receiptResponse(
                  LedgerJson.decodeFromString(OperationsRequest.serializer(), body).operations
              )
            }
          }
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertTrue(bodies.size > 1)
      assertTrue(bodies.all { it.toByteArray(Charsets.UTF_8).size <= 524288 })
      val sent = bodies.flatMap {
        LedgerJson.decodeFromString(OperationsRequest.serializer(), it).operations
      }
      assertEquals(commands.map { it.operationId }, sent.map { it.operationId })
    }
  }

  @Test
  fun lostAnswerResendsTheSameBodyAndOperationIds() = runTest {
    SyncFixture.create().use { f ->
      val commands = f.queue(2)
      f.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
      assertEquals(SyncStatus.Offline, f.engine.pass(f.scope))
      val first = f.server.takeRequest().body!!.utf8()
      assertTrue(f.scope.outbox.snapshot().all { it.state == OutboxState.QUEUED })
      f.server.enqueue(receiptResponse(commands))
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals(first, f.server.takeRequest().body!!.utf8())
      assertTrue(f.scope.outbox.snapshot().all { it.state == OutboxState.ACCEPTED })
    }
  }

  @Test
  fun rejectedReceiptsSettleIndividually() = runTest {
    SyncFixture.create().use { f ->
      val commands = f.queue(3)
      f.server.enqueue(receiptResponse(commands, reject = 1))
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals(
          listOf(OutboxState.ACCEPTED, OutboxState.REJECTED, OutboxState.ACCEPTED),
          f.scope.outbox.snapshot().map { it.state },
      )
      assertEquals("store_permission_denied", f.scope.outbox.snapshot()[1].code)
    }
  }

  @Test
  fun callerRefusalsLeaveQueuedRowsUntouched() = runTest {
    for ((code, expected) in
        listOf(
            "request_rate_limited" to SyncStatus.Retry,
            "installation_or_session_revoked" to SyncStatus.Revoked,
        )) {
      SyncFixture.create().use { f ->
        val commands = f.queue(2)
        f.server.enqueue(refusalResponse(code))
        assertEquals(expected, f.engine.pass(f.scope))
        assertEquals(commands, f.scope.outbox.queued())
      }
    }
  }

  @Test
  fun aPartialReceiptResponseNeverSettlesAnIncompleteBatch() = runTest {
    SyncFixture.create().use { f ->
      val commands = f.queue(2)
      f.server.enqueue(receiptResponse(commands.take(1)))
      assertEquals(SyncStatus.Failed("temporary_service_failure"), f.engine.pass(f.scope))
      assertEquals(commands, f.scope.outbox.queued())
    }
  }
}
