package com.sadad.app.data.session

import com.sadad.app.data.net.ApiException
import com.sadad.app.data.net.AuthApi
import com.sadad.app.data.net.AuthSession
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a session needs from the graph that owns the open store. */
interface SessionHost {
  suspend fun open(account: Account)

  suspend fun close()

  suspend fun unsentCount(storeUserId: String): Int
}

/**
 * Sign-in, the in-memory access token and the sealed refresh token. One store is open at a time;
 * the access token never reaches disk and the refresh token is sealed under its store's AAD.
 */
class Sessions(
    private val accounts: Accounts,
    private val auth: AuthApi,
    private val installations: Installations,
    private val host: SessionHost,
    private val deviceLabel: String,
    private val now: () -> Instant = Instant::now,
) {
  private val access = MutableStateFlow<AuthSession?>(null)
  private val syncAccess = ConcurrentHashMap<String, AuthSession>()
  private val mutex = Mutex()

  /** Signs in, creates the store's account on first sign-in, and opens its scope. */
  suspend fun signIn(username: String, password: String): AuthSession = mutex.withLock {
    val session = auth.signIn(username, password)
    val account = accounts.account(session.userId) ?: newAccount(session.userId, username)
    storeLocked(session)
    accounts.setActive(account.storeUserId)
    host.open(account)
    session
  }

  /**
   * A token with more than 60 seconds left, or a refreshed one. A refused refresh (400/401) signs
   * the store out; being offline keeps the token and the sealed refresh token.
   */
  suspend fun accessToken(): String? = mutex.withLock { validLocked()?.accessToken }

  /**
   * A worker requests this store's session without switching the store shown on screen. IO failures
   * propagate to sync; forceRefresh is used once after an authorization refusal.
   */
  suspend fun syncSession(storeUserId: String, forceRefresh: Boolean = false): AuthSession? =
      mutex.withLock {
        val refresh = accounts.refreshToken(storeUserId) ?: return@withLock null
        val cached = syncAccess[storeUserId]
        if (
            !forceRefresh &&
                cached != null &&
                Duration.between(now(), cached.expiresAt) >= REFRESH_MARGIN
        ) {
          return@withLock cached
        }
        try {
          val refreshed = auth.refresh(refresh)
          if (refreshed.userId != storeUserId) throw ApiException(401, "authorization_required")
          accounts.saveRefreshToken(storeUserId, refreshed.refreshToken)
          syncAccess[storeUserId] = refreshed
          if (accounts.file().activeStoreUserId == storeUserId) access.value = refreshed
          refreshed
        } catch (refused: ApiException) {
          if (refused.status != 400 && refused.status != 401) throw refused
          invalidateLocked(storeUserId)
          null
        }
      }

  /** Forget refused credentials only for the worker's store; never close another store's scope. */
  suspend fun invalidateForSync(storeUserId: String) = mutex.withLock {
    invalidateLocked(storeUserId)
  }

  private suspend fun invalidateLocked(storeUserId: String) {
    accounts.clearRefreshToken(storeUserId)
    syncAccess.remove(storeUserId)
    if (access.value?.userId == storeUserId) access.value = null
  }

  /** Changes the password, then refreshes and stores the new tokens. */
  suspend fun changePassword(current: String, new: String): Unit = mutex.withLock {
    val session = validLocked() ?: error("not signed in")
    auth.changePassword(session.accessToken, current, new)
    refreshLocked(session.refreshToken) ?: return@withLock
  }

  /** Best effort: an offline sign-out still clears the local tokens and leaves the store's data. */
  suspend fun signOut(): Unit = mutex.withLock {
    val session = access.value
    if (session != null) {
      try {
        auth.signOut(session.accessToken)
      } catch (_: IOException) {
        // Offline is fine: the local session is what must end.
      } catch (_: ApiException) {
        // The server already dropped it.
      }
    }
    val storeUserId = accounts.file().activeStoreUserId
    if (storeUserId != null) invalidateLocked(storeUserId)
    access.value = null
    host.close()
  }

  /** Closes the open scope and opens [storeUserId]; without a refresh token it stays at Login. */
  suspend fun switchTo(storeUserId: String): Unit = mutex.withLock {
    val account = accounts.account(storeUserId) ?: return@withLock
    access.value = null
    host.close()
    accounts.setActive(storeUserId)
    if (account.sealedRefreshToken != null) host.open(account)
  }

  /** The open store's queued outbox rows, for the sign-out and remove warnings. */
  suspend fun unsentCount(): Int {
    val storeUserId = accounts.file().activeStoreUserId ?: return 0
    return host.unsentCount(storeUserId)
  }

  /** Drops the in-memory token for a store the graph is removing. */
  internal fun leave(storeUserId: String) {
    syncAccess.remove(storeUserId)
    if (access.value?.userId == storeUserId) access.value = null
  }

  internal fun inMemorySession(storeUserId: String): AuthSession? =
      access.value?.takeIf { it.userId == storeUserId }

  private suspend fun newAccount(storeUserId: String, username: String): Account {
    val account =
        Account(
            storeUserId = storeUserId,
            username = username,
            installationId = UUID.randomUUID().toString(),
            keyAlias = "installation-$storeUserId",
            label = deviceLabel,
        )
    installations.create(account.keyAlias, account.installationId)
    accounts.add(account)
    return account
  }

  private suspend fun storeLocked(session: AuthSession) {
    accounts.saveRefreshToken(session.userId, session.refreshToken)
    syncAccess[session.userId] = session
    access.value = session
  }

  private suspend fun validLocked(): AuthSession? {
    val session = access.value ?: restoreLocked() ?: return null
    if (Duration.between(now(), session.expiresAt) >= REFRESH_MARGIN) return session
    return try {
      refreshLocked(session.refreshToken)
    } catch (_: IOException) {
      session
    }
  }

  private suspend fun restoreLocked(): AuthSession? {
    val storeUserId = accounts.file().activeStoreUserId ?: return null
    val token = accounts.refreshToken(storeUserId) ?: return null
    return try {
      refreshLocked(token)
    } catch (_: IOException) {
      null
    }
  }

  private suspend fun refreshLocked(refreshToken: String): AuthSession? =
      try {
        auth.refresh(refreshToken).also { storeLocked(it) }
      } catch (refused: ApiException) {
        if (refused.status == 400 || refused.status == 401) {
          signedOutLocked()
          null
        } else {
          throw refused
        }
      }

  private suspend fun signedOutLocked() {
    val storeUserId = accounts.file().activeStoreUserId
    if (storeUserId != null) invalidateLocked(storeUserId)
    access.value = null
    host.close()
  }

  private companion object {
    val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)
  }
}
