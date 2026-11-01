package org.lara.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lara.app.data.local.dao.AuthDao
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.sync.SyncRepository

sealed interface SignOutResult {
    data class NeedsConfirmation(val count: Int) : SignOutResult
    data object SignedOut : SignOutResult
}

class SettingsRepository(private val dao: AuthDao, private val tokens: SessionTokenStore,
    private val sync: SyncRepository, private val endpoint: () -> HubEndpoint?,
    private val logout: suspend (HubEndpoint, StoredToken) -> Unit = { hub, token -> AuthApi().logout(hub, token) }) {
    suspend fun signOut(userId: String, confirmedCount: Int? = null): SignOutResult = withContext(Dispatchers.IO) {
        var result: SignOutResult = SignOutResult.SignedOut
        sync.refreshRoster(userId) {
            val count = dao.queuedCount(userId)
            if (count > 0 && (confirmedCount == null || count > confirmedCount)) {
                result = SignOutResult.NeedsConfirmation(count)
            } else {
                val token = tokens.load()
                check(token == null || token.userId == userId)
                // Keep the cache owner and every row: another account must not inherit this offline mirror.
                tokens.clear()
                val hub = endpoint()
                if (token != null && hub?.restBase == token.hub) {
                    try { logout(hub, token) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                }
            }
        }
        result
    }
}
