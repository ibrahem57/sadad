package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.app.data.net.Lease
import com.sadad.ledger.LedgerJson
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPullTest {
  @Test
  fun anIdlePassMakesExactlyOneChangesRequest() = runTest {
    SyncFixture.create().use { f ->
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals(2, f.server.requestCount) // One sign-in before this pass.
    }
  }

  @Test
  fun pagesSurviveAFailureAndResumeAtTheLastSavedCursor() = runTest {
    SyncFixture.create().use { f ->
      f.page(Events.page(emptyList(), 30, 10).copy(hasMore = true))
      f.page(Events.page(emptyList(), 30, 20).copy(hasMore = true))
      f.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
      assertEquals(SyncStatus.Offline, f.engine.pass(f.scope))
      assertEquals(20L, f.scope.database.metaDao().get()!!.cursor)
      repeat(3) { f.server.takeRequest() }
      f.page(Events.page(emptyList(), 30, 30))
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=20&limit=500", f.server.takeRequest().target)
      assertEquals(30L, f.scope.database.metaDao().get()!!.cursor)
    }
  }

  @Test
  fun anotherEpochResetsAndPullsOnceFromZero() = runTest {
    SyncFixture.create().use { f ->
      f.scope.repository.applyPage(Events.page(emptyList(), 10, 10))
      val changed = Events.page(emptyList()).copy(epoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
      f.page(changed)
      f.page(changed)
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=10&limit=500", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals(changed.epoch, f.scope.origin()!!.epoch)
    }
  }

  @Test
  fun aMissingLeaseIsRenewedBeforePulling() = runTest {
    SyncFixture.create(fresh = false).use { f ->
      val signed = signedLease(f.signer, f.scope.account, f.now)
      f.server.enqueue(
          MockResponse.Builder()
              .body("""{"lease":${LedgerJson.encodeToString(Lease.serializer(), signed)}}""")
              .build()
      )
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/lease", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertTrue(f.lease.allows(f.scope.account))
    }
  }

  @Test
  fun aLeaseRefusalDisablesOfflineSavesButStillPulls() = runTest {
    SyncFixture.create(fresh = false).use { f ->
      f.server.enqueue(refusalResponse("store_permission_denied"))
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertFalse(f.lease.allows(f.scope.account))
    }
  }

  @Test
  fun authorizationRefusalRefreshesOnceAndRepeatsOnlyTheFailedStep() = runTest {
    SyncFixture.create().use { f ->
      f.server.enqueue(refusalResponse("authorization_required", 401))
      f.test.enqueueSession(refreshToken = "rotated")
      f.page()
      assertEquals(SyncStatus.Idle, f.engine.pass(f.scope))
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals("/auth/v1/token?grant_type=refresh_token", f.server.takeRequest().target)
      assertEquals("/v1/mobile/changes?after=0&limit=500", f.server.takeRequest().target)
      assertEquals("rotated", f.test.graph.accounts.refreshToken(f.scope.account.storeUserId))
    }
  }

  @Test
  fun aSecondAuthorizationRefusalSignsOutWithoutAnotherRefresh() = runTest {
    SyncFixture.create().use { f ->
      f.server.enqueue(refusalResponse("authorization_required", 401))
      f.test.enqueueSession()
      f.server.enqueue(refusalResponse("authorization_required", 401))
      assertEquals(SyncStatus.SignedOut, f.engine.pass(f.scope))
      assertNull(f.test.graph.accounts.refreshToken(f.scope.account.storeUserId))
      assertEquals(4, f.server.requestCount)
    }
  }
}
