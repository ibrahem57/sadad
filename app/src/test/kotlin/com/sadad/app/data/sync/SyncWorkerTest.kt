package com.sadad.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sadad.app.testing.STORE_A
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
  @Test
  fun workerRetriesTransientStatusesAndFinishesStableStatuses() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    for (status in
        listOf(
            SyncStatus.Offline,
            SyncStatus.Retry,
            SyncStatus.Failed("temporary_service_failure"),
            SyncStatus.Idle,
            SyncStatus.SignedOut,
            SyncStatus.AwaitingApproval,
        )) {
      val factory =
          object : WorkerFactory() {
            override fun createWorker(
                context: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                SyncWorker(context, workerParameters) { id ->
                  assertEquals(STORE_A, id)
                  status
                }
          }
      val worker =
          TestListenableWorkerBuilder<SyncWorker>(context)
              .setInputData(workDataOf(SyncWorker.STORE_USER_ID to STORE_A))
              .setWorkerFactory(factory)
              .build()
      val expected =
          if (
              status == SyncStatus.Offline ||
                  status == SyncStatus.Retry ||
                  status is SyncStatus.Failed
          )
              ListenableWorker.Result.retry()
          else ListenableWorker.Result.success()
      assertEquals(expected, worker.doWork())
    }
  }

  @Test
  fun workerRunsTheRealEngineForTheInputStore() = runTest {
    SyncFixture.create().use { f ->
      f.page()
      val factory =
          object : WorkerFactory() {
            override fun createWorker(
                context: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                SyncWorker(context, workerParameters) { id ->
                  assertEquals(STORE_A, id)
                  f.engine.pass(f.scope)
                }
          }
      val worker =
          TestListenableWorkerBuilder<SyncWorker>(f.test.context)
              .setInputData(workDataOf(SyncWorker.STORE_USER_ID to STORE_A))
              .setWorkerFactory(factory)
              .build()
      assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
  }
}
