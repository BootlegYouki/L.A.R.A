package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class AnnouncementRequest(
    @SerialName("classroom_id") val classroomId: String,
    val title: String,
    val content: String,
    @SerialName("allow_comments") val allowComments: Boolean? = null,
)

class AnnouncementApi(private val sessions: EnrollmentApi, private val client: HubClient) {
    suspend fun post(userId: String, classroomId: String, title: String, content: String,
        allowComments: Boolean?): String = withContext(Dispatchers.IO) {
        val request = AnnouncementRequest(classroomId, title.trim(), content.trim(), allowComments)
        val response = client.postAnnouncement(sessions.connection(userId),
            Json.encodeToString(AnnouncementRequest.serializer(), request))
        Json.parseToJsonElement(response).jsonObject.getValue("id").jsonPrimitive.content
    }
}
