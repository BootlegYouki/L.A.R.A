package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AnnouncementRequest(
    @SerialName("classroom_id") val classroomId: String,
    val title: String,
    val content: String,
    @SerialName("allow_comments") val allowComments: Boolean,
)

class AnnouncementApi(private val sessions: EnrollmentApi, private val client: HubClient) {
    suspend fun post(userId: String, classroomId: String, title: String, content: String,
        allowComments: Boolean) = withContext(Dispatchers.IO) {
        val request = AnnouncementRequest(classroomId, title.trim(), content.trim(), allowComments)
        client.postAnnouncement(sessions.connection(userId),
            Json.encodeToString(AnnouncementRequest.serializer(), request))
    }
}
