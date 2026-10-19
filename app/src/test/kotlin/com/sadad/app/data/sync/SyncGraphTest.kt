package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncGraphTest {
  @Test
  fun aBackgroundStoreDoesNotReplaceTheOpenStoreOrItsSession() = runTest {
    TestGraph.signedIn(STORE_A).use { f ->
      val installationA = f.graph.current.value!!.account.installationId
      f.signIn(STORE_B)
      f.activate(STORE_B)
      repeat(2) { f.server.takeRequest() }
      f.server.enqueue(refusalResponse("store_permission_denied")) // No lease in this fixture.
      f.server.enqueue(pageResponse(Events.page(emptyList())))
      assertEquals(SyncStatus.Idle, f.graph.syncStore(STORE_A))
      val leaseRequest = f.server.takeRequest()
      val changesRequest = f.server.takeRequest()
      assertEquals(installationA, leaseRequest.headers["x-sadad-installation"])
      assertEquals(installationA, changesRequest.headers["x-sadad-installation"])
      assertEquals(STORE_B, f.graph.current.value!!.account.storeUserId)
      assertEquals(STORE_B, f.graph.accounts.file().activeStoreUserId)
      assertEquals(STORE_B, f.graph.sessions.syncSession(STORE_B)!!.userId)
      assertEquals(SyncStatus.Idle, f.graph.syncStatus(STORE_A).value)
    }
  }

  @Test
  fun closingTheStoreCancelsItsInFlightWorkBeforeClosingTheDatabase() = runTest {
    TestGraph.signedIn().use { f ->
      val scope = f.graph.current.value!!
      val started = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      val running = async {
        scope.runWhileOpen {
          started.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            cancelled.complete(Unit)
          }
        }
      }
      started.await()
      f.graph.closeStore()
      cancelled.await()
      running.join()
      assertTrue(running.isCancelled)
      assertFalse(scope.database.isOpen)
      assertNull(scope.origin())
    }
  }
}
