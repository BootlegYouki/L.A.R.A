package org.lara.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.lara.app.data.remote.*
import org.lara.app.data.remote.dto.*

interface SyncTransport {
    suspend fun pull(userId: String, request: SyncPullRequestDto): SyncPullResponseDto
    suspend fun push(userId: String, request: SyncPushRequestDto): SyncPushResponseDto
}
class HubSyncTransport(private val sessions: EnrollmentApi, private val client: HubClient) : SyncTransport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    override suspend fun pull(userId: String, request: SyncPullRequestDto): SyncPullResponseDto = withContext(Dispatchers.IO) {
        val c = sessions.connection(userId)
        json.decodeFromString(SyncPullResponseDto.serializer(), client.sync(c, "pull", json.encodeToString(SyncPullRequestDto.serializer(), request)))
    }
    override suspend fun push(userId: String, request: SyncPushRequestDto): SyncPushResponseDto = withContext(Dispatchers.IO) {
        val c = sessions.connection(userId)
        json.decodeFromString(SyncPushResponseDto.serializer(), client.sync(c, "push", json.encodeToString(SyncPushRequestDto.serializer(), request)))
    }
}
