package org.lara.app.domain

import kotlinx.coroutines.flow.Flow
import org.lara.app.domain.model.Material

sealed interface VideoSource {
    data class Local(val path: String) : VideoSource
    data class Hub(val materialId: String, val size: Long) : VideoSource
}

enum class VideoProblem { HUB_UNAVAILABLE, SIGN_IN, UNAVAILABLE, STORAGE, INVALID_FILE, CHANGED }
class VideoFailure(val problem: VideoProblem) : java.io.IOException()
enum class DownloadPhase { IDLE, DOWNLOADING, PAUSED, COMPLETE, FAILED }
data class VideoDownload(val phase: DownloadPhase = DownloadPhase.IDLE, val bytes: Long = 0,
    val total: Long = 0, val problem: VideoProblem? = null)

interface VideoRepository {
    fun observe(id: String): Flow<Material?>
    suspend fun source(id: String): VideoSource
    suspend fun progress(id: String): VideoDownload
    suspend fun save(id: String, progress: (VideoDownload) -> Unit)
}
