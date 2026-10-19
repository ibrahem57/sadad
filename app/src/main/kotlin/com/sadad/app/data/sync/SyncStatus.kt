package com.sadad.app.data.sync

sealed interface SyncStatus {
  data object Idle : SyncStatus

  data object Running : SyncStatus

  data object Offline : SyncStatus

  data object SignedOut : SyncStatus

  data object AwaitingApproval : SyncStatus

  data object Revoked : SyncStatus

  data object Suspended : SyncStatus

  data object PasswordChangeRequired : SyncStatus

  data object Retry : SyncStatus

  data class Failed(val code: String) : SyncStatus
}
