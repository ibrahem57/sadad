package com.sadad.app.data.sync

import com.sadad.app.testing.TestGraph
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
  @Test
  fun aPendingEnrollmentStopsBeforeLeasePushAndPull() = runTest {
    TestGraph.unsigned().use { fixture ->
      val scope = fixture.signIn()
      fixture.server.takeRequest()
      fixture.server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"installation":{"installationId":"${scope.account.installationId}","generation":1,"status":"pending","label":"Test Device","staffName":"","permissions":{}}}"""
              )
              .build()
      )
      val engine =
          SyncEngine(fixture.graph.accounts, fixture.graph.sessions, fixture.graph.ledgerApi) {
            error("pending enrollment must not open the lease store")
          }

      assertEquals(SyncStatus.AwaitingApproval, engine.pass(scope))
      assertEquals("/v1/mobile/enroll", fixture.server.takeRequest().target)
      assertEquals(2, fixture.server.requestCount)
      assertEquals(
          "pending",
          fixture.graph.accounts.account(scope.account.storeUserId)!!.installation!!.status,
      )
      assertEquals(SyncStatus.AwaitingApproval, engine.status(scope.account.storeUserId).value)
    }
  }
}
