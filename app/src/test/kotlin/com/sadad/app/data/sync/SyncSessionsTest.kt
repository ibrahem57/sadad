package com.sadad.app.data.sync

import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSessionsTest {
  @Test
  fun aBackgroundRefreshUsesOnlyTheRequestedStoreAndDoesNotSwitchTheScreen() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A, refreshToken = "refresh-a")
      fixture.signIn(STORE_B, refreshToken = "refresh-b")
      fixture.server.takeRequest()
      fixture.server.takeRequest()
      fixture.enqueueSession(STORE_A, refreshToken = "rotated-a")
      val refreshed = fixture.graph.sessions.syncSession(STORE_A, forceRefresh = true)!!
      assertEquals(STORE_A, refreshed.userId)
      assertEquals("""{"refresh_token":"refresh-a"}""", fixture.server.takeRequest().body!!.utf8())
      assertEquals(STORE_B, fixture.graph.current.value!!.account.storeUserId)
      assertEquals(STORE_B, fixture.graph.accounts.file().activeStoreUserId)
      assertEquals("refresh-b", fixture.graph.accounts.refreshToken(STORE_B))
      assertEquals("rotated-a", fixture.graph.accounts.refreshToken(STORE_A))
      assertEquals(STORE_B, fixture.graph.sessions.syncSession(STORE_B)!!.userId)
      assertEquals(3, fixture.server.requestCount)
    }
  }

  @Test
  fun aRefusedBackgroundSessionNeverSignsOutTheOtherStore() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      fixture.signIn(STORE_B)
      fixture.server.enqueue(
          MockResponse.Builder().code(401).body("""{"code":"invalid_grant"}""").build()
      )
      assertNull(fixture.graph.sessions.syncSession(STORE_A, forceRefresh = true))
      assertNull(fixture.graph.accounts.refreshToken(STORE_A))
      assertNotNull(fixture.graph.accounts.refreshToken(STORE_B))
      assertEquals(STORE_B, fixture.graph.sessions.syncSession(STORE_B)!!.userId)
      assertEquals(STORE_B, fixture.graph.current.value!!.account.storeUserId)
    }
  }

  @Test
  fun aRefreshAnswerForAnotherStoreIsNeverStored() = runTest {
    TestGraph.unsigned().use { fixture ->
      fixture.signIn(STORE_A)
      fixture.enqueueSession(STORE_B, refreshToken = "wrong-store-token")
      assertNull(fixture.graph.sessions.syncSession(STORE_A, forceRefresh = true))
      assertNull(fixture.graph.accounts.refreshToken(STORE_A))
      assertNull(fixture.graph.accounts.account(STORE_B))
    }
  }
}
