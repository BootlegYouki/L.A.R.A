package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lara.app.data.remote.discovery.HubEndpoint

@Serializable
data class AuthUser(val id: String, @SerialName("lrn_or_id") val lrnOrId: String,
    @SerialName("full_name") val fullName: String, val role: String)
@Serializable
data class AuthSession(val token: String, @SerialName("expires_at") val expiresAt: Long, val user: AuthUser) {
    override fun toString() = "AuthSession([redacted])"
}
@Serializable
private data class Credentials(@SerialName("lrn_or_id") val id: String, val pin: String,
    @SerialName("full_name") val name: String? = null)

class AuthFailure(val status: Int, val retryAfterSeconds: Long = 0) : Exception()

/** Fixed auth paths and no redirects: credentials never leave the selected LAN Hub. */
class AuthApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    suspend fun authenticate(endpoint: HubEndpoint, id: String, pin: String, name: String?): AuthSession =
        withContext(Dispatchers.IO) {
            require(pin.matches(Regex("[0-9]{4}")))
            val path = if (name == null) "/api/auth/login" else "/api/auth/register"
            val body = HubClient().postAuth(endpoint, path,
                json.encodeToString(Credentials.serializer(), Credentials(id, pin, name)))
                json.decodeFromString(AuthSession.serializer(), body).also {
                    if (it.user.role !in setOf("STUDENT", "TEACHER")) throw AuthFailure(403)
                    require(it.token.isNotBlank() && it.user.id.isNotBlank())
                }
        }
}
