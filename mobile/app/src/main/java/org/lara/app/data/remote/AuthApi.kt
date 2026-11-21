package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lara.app.domain.model.AuthPin
import org.lara.app.data.remote.discovery.HubEndpoint

@Serializable
data class AuthUser(val id: String, @SerialName("lrn_or_id") val lrnOrId: String,
    @SerialName("full_name") val fullName: String, val role: String)
@Serializable
data class AuthSession(val token: String, @SerialName("expires_at") val expiresAt: Long, val user: AuthUser) {
    override fun toString() = "AuthSession([redacted])"
}
@Serializable
private data class Credentials(@SerialName("lrn_or_id") val id: String, val pin: String)

class AuthFailure(val status: Int, val retryAfterSeconds: Long = 0) : Exception()

/** Fixed auth paths and no redirects: credentials never leave the selected LAN Hub. */
class AuthApi {
    suspend fun logout(endpoint: HubEndpoint, session: StoredToken) = withContext(Dispatchers.IO) {
        HubClient().logout(endpoint, session)
        Unit
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    suspend fun authenticate(endpoint: HubEndpoint, id: String, pin: String): AuthSession =
        withContext(Dispatchers.IO) {
            require(AuthPin.isValid(pin))
            val body = HubClient().postAuth(endpoint,
                json.encodeToString(Credentials.serializer(), Credentials(id, pin)))
                json.decodeFromString(AuthSession.serializer(), body).also {
                    if (it.user.role !in setOf("STUDENT", "TEACHER")) throw AuthFailure(403)
                    require(it.token.isNotBlank() && it.user.id.isNotBlank())
                }
        }
}
