package com.sadad.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** The WorkManager boundary, injected by the graph so tests never schedule real network work. */
interface SyncScheduling {
  fun syncNow(storeUserId: String)

  fun schedulePeriodic(storeUserId: String)

  fun cancel(storeUserId: String)
}

class SyncScheduler(context: Context) : SyncScheduling {
  private val work = WorkManager.getInstance(context)
  private val connected =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  override fun syncNow(storeUserId: String) {
    val request =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.STORE_USER_ID to storeUserId))
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    work.enqueueUniqueWork("sync-$storeUserId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  override fun schedulePeriodic(storeUserId: String) {
    val request =
        PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf(SyncWorker.STORE_USER_ID to storeUserId))
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    work.enqueueUniquePeriodicWork(
        "sync-periodic-$storeUserId",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
  }

  override fun cancel(storeUserId: String) {
    work.cancelUniqueWork("sync-$storeUserId")
    work.cancelUniqueWork("sync-periodic-$storeUserId")
  }
}
