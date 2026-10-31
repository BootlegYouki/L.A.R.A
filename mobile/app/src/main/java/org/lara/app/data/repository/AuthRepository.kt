package org.lara.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lara.app.data.local.dao.AuthDao
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.domain.model.User
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class AuthRepository(private val api: AuthApi, private val tokens: SessionTokenStore, private val dao: AuthDao) {
    fun reachability(endpoint: HubEndpoint?) = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(endpoint?.let { org.lara.app.data.remote.discovery.HubProbe().responds(it) { url ->
                url.openConnection() as java.net.HttpURLConnection
            } } ?: false)
            kotlinx.coroutines.delay(10_000)
        }
    }
    suspend fun restore(endpoint: HubEndpoint?): User? = withContext(Dispatchers.IO) {
        val token = try { tokens.load() } catch (_: Exception) { return@withContext null }
            ?: return@withContext null
        if (dao.currentUserId() != token.userId) return@withContext null
        if (endpoint?.restBase != token.hub) return@withContext null
        // Expiry limits Hub requests, not access to already downloaded classroom resources.
        dao.user(token.userId)?.takeIf { it.role in setOf("STUDENT", "TEACHER") }?.toDomain()
    }
    suspend fun authenticate(endpoint: HubEndpoint, id: String, pin: String, name: String?): User = withContext(Dispatchers.IO) {
        val session = api.authenticate(endpoint, id, pin, name)
        val old = try { tokens.load() } catch (_: Exception) { null }
        val cachedUser = dao.currentUserId()
        if (cachedUser != null && cachedUser != session.user.id) throw AuthFailure(409)
        // Account/cache switching needs the queued-work policy from the settings/sync issues.
        if (old != null && (old.userId != session.user.id || old.hub != endpoint.restBase)) throw AuthFailure(409)
        val previous = dao.user(session.user.id)
        val now = System.currentTimeMillis()
        val profile = UserEntity(session.user.id, session.user.lrnOrId, session.user.fullName, session.user.role,
            previous?.createdAt ?: now, now)
        dao.saveProfile(profile)
        tokens.save(StoredToken(session.token, profile.id, session.expiresAt, endpoint.restBase))
        profile.toDomain()
    }
}
