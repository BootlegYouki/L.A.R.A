package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.remote.dto.ClassroomDto
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.domain.enrollment.EnrollmentStatus

@Serializable
data class JoinReceipt(@SerialName("classroom_id") val classroomId: String, val status: EnrollmentStatus)
@Serializable
private data class JoinRequest(@SerialName("class_code") val code: String)

data class EnrollmentConnection(val endpoint: HubEndpoint, val session: StoredToken) {
    override fun toString() = "EnrollmentConnection([redacted])"
}

class EnrollmentApi(
    private val client: HubClient,
    private val tokens: SessionTokenStore,
    private val endpoint: () -> HubEndpoint?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connection(userId: String): EnrollmentConnection = withContext(Dispatchers.IO) {
        val hub = endpoint() ?: throw java.io.IOException("Hub unavailable")
        val saved = tokens.load() ?: throw HubFailure(401, "UNAUTHORIZED")
        if (saved.userId != userId || saved.hub != hub.restBase || saved.expiresAt <= now())
            throw HubFailure(401, "UNAUTHORIZED")
        EnrollmentConnection(hub, saved)
    }

    suspend fun join(userId: String, displayedCode: String): JoinReceipt = withContext(Dispatchers.IO) {
        val code = ClassCode.canonicalOrNull(displayedCode) ?: throw HubFailure(404, "CLASS_CODE_INVALID")
        val c = connection(userId)
        json.decodeFromString(JoinReceipt.serializer(), client.enrollment(c.endpoint, c.session,
            "POST", json.encodeToString(JoinRequest.serializer(), JoinRequest(code)))).also {
            require(it.classroomId.isNotBlank())
        }
    }

    suspend fun classrooms(userId: String): List<ClassroomDto> = withContext(Dispatchers.IO) {
        val c = connection(userId)
        json.decodeFromString(ListSerializer(ClassroomDto.serializer()), client.enrollment(c.endpoint, c.session, "GET"))
    }
}
