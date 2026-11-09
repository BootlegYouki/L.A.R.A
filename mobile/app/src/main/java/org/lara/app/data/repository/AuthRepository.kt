package org.lara.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.lara.app.data.local.dao.AuthDao
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.domain.model.User
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class AuthRepository(private val api: AuthApi, private val tokens: SessionTokenStore,
    private val accountDao: suspend (String) -> AuthDao) {
    constructor(api: AuthApi, tokens: SessionTokenStore, dao: AuthDao) : this(api, tokens, { dao })
    fun reachability(endpoint: HubEndpoint?, networkChanges: Flow<Unit> = emptyFlow()) =
        observeHubReachability(networkChanges) {
            endpoint?.let { org.lara.app.data.remote.discovery.HubProbe().responds(it) { url ->
                url.openConnection() as java.net.HttpURLConnection
            } } ?: false
        }
    suspend fun restore(endpoint: HubEndpoint?): User? = withContext(Dispatchers.IO) {
        val token = try { tokens.load() } catch (_: Exception) { return@withContext null }
            ?: return@withContext null
        val dao = accountDao(token.userId)
        if (dao.currentUserId() != token.userId) return@withContext null
        if (endpoint?.restBase != token.hub) return@withContext null
        // Expiry limits Hub requests, not access to already downloaded classroom resources.
        dao.user(token.userId)?.takeIf { it.role in setOf("STUDENT", "TEACHER") }?.toDomain()
    }
    /** Saved identity for sign-out controls only; it does not authorize a different Hub. */
    suspend fun cachedUser(): User? = withContext(Dispatchers.IO) {
        val token = try { tokens.load() } catch (_: Exception) { null } ?: return@withContext null
        val dao = accountDao(token.userId)
        if (dao.currentUserId() != token.userId) return@withContext null
        dao.user(token.userId)?.takeIf { it.role in setOf("STUDENT", "TEACHER") }?.toDomain()
    }
    suspend fun authenticate(endpoint: HubEndpoint, id: String, pin: String, name: String?): User = withContext(Dispatchers.IO) {
        val session = api.authenticate(endpoint, id, pin, name)
        val old = try { tokens.load() } catch (_: Exception) { null }
        if (old != null && old.userId != session.user.id) throw AuthFailure(409)
        val dao = accountDao(session.user.id)
        val cachedUser = dao.currentUserId()
        if (cachedUser != null && cachedUser != session.user.id) throw AuthFailure(409)
        // A verified same-user login can renew a DHCP-changed address without clearing cached work.
        // A different account requires explicit sign-out first and its own offline mirror.
        val previous = dao.user(session.user.id)
        val now = System.currentTimeMillis()
        val profile = UserEntity(session.user.id, session.user.lrnOrId, session.user.fullName, session.user.role,
            previous?.createdAt ?: now, now)
        dao.saveProfile(profile)
        tokens.save(StoredToken(session.token, profile.id, session.expiresAt, endpoint.restBase))
        profile.toDomain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeHubReachability(networkChanges: Flow<Unit>, probe: suspend () -> Boolean): Flow<Boolean> =
    merge(flow {
        while (true) { emit(Unit); delay(10_000) }
    }, networkChanges).conflate().map { probe() }.distinctUntilChanged()
