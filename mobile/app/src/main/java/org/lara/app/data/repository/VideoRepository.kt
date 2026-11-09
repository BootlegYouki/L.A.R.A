package org.lara.app.data.repository

import java.io.File
import java.security.MessageDigest
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.MaterialEntity
import org.lara.app.data.media.DownloadManager
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.MediaHttp
import org.lara.app.domain.*

class RoomVideoRepository(private val db: LaraDatabase, private val api: EnrollmentApi,
    private val root: File, private val userId: String) : VideoRepository {
    private val http = MediaHttp(api, userId)
    private val downloads = http.downloads(root)
    private val transfers = Mutex()
    suspend fun prune() {
        if (!transfers.tryLock()) return
        try {
            val rows = db.classworkDao().videos()
            downloads.prune(rows.map(::key).toSet(), rows.mapNotNull { it.localFilePath }.toSet())
        } finally { transfers.unlock() }
    }
    override fun observe(id: String) = db.classworkDao().observeMaterial(id).map { it?.toDomain() }
    private fun key(row: MaterialEntity) = MessageDigest.getInstance("SHA-256")
        .digest("${row.id}:${row.fileSizeBytes}:${row.updatedAt}".toByteArray()).joinToString("") { "%02x".format(it) }
    private suspend fun row(id: String): MaterialEntity {
        if (db.authDao().currentUserId() != userId) throw VideoFailure(VideoProblem.SIGN_IN)
        return db.classworkDao().material(id)
            ?.takeIf { it.fileType == "VIDEO" && it.fileSizeBytes in 1..DownloadManager.MAX }
            ?: throw VideoFailure(VideoProblem.UNAVAILABLE)
    }
    private fun local(row: MaterialEntity): File? {
        val file = row.localFilePath?.let(::File)?.canonicalFile ?: return null
        return file.takeIf { it.parentFile == root.canonicalFile && it.isFile && it.length() == row.fileSizeBytes &&
            runCatching { downloads.isVideo(it) }.getOrDefault(false) }
    }
    override suspend fun source(id: String): VideoSource = withContext(Dispatchers.IO) {
        val row = row(id)
        local(row)?.let { return@withContext VideoSource.Local(it.path) }
        if (row.localFilePath != null) db.classworkDao().clearFile(id, row.localFilePath)
        http.connection()
        VideoSource.Hub(id, row.fileSizeBytes)
    }
    override suspend fun progress(id: String): VideoDownload = withContext(Dispatchers.IO) {
        val row = row(id)
        if (local(row) != null) VideoDownload(DownloadPhase.COMPLETE, row.fileSizeBytes, row.fileSizeBytes)
        else downloads.progress(key(row), row.fileSizeBytes)
    }
    override suspend fun remove(id: String) = transfers.withLock { withContext(Dispatchers.IO + NonCancellable) {
        val original = row(id)
        val saved = original.localFilePath?.let(::File)
        if (saved != null && (saved.canonicalFile.parentFile != root.canonicalFile ||
            saved.name != saved.canonicalFile.name || !saved.name.matches(Regex("[a-f0-9]{64}\\.video")) ||
            db.classworkDao().videos().any { it.id != id && it.localFilePath == original.localFilePath }))
            throw VideoFailure(VideoProblem.STORAGE)
        try { downloads.remove(key(original), saved) }
        finally {
            // A partial failure must never leave Room pointing at a file already removed.
            if (saved != null && !saved.exists()) db.classworkDao().clearFile(id, saved.path)
        }
    } }
    override suspend fun save(id: String, progress: (VideoDownload) -> Unit) = transfers.withLock { withContext(Dispatchers.IO) {
        val original = row(id)
        if (local(original) != null) {
            progress(VideoDownload(DownloadPhase.COMPLETE, original.fileSizeBytes, original.fileSizeBytes))
            return@withContext
        }
        val connection = http.connection()
        val file = downloads.download(id, key(original), original.fileSizeBytes, progress)
        try {
            val current = http.connection()
            if (current.session != connection.session || current.endpoint.restBase != connection.endpoint.restBase)
                throw VideoFailure(VideoProblem.SIGN_IN)
            db.withTransaction {
                if (db.authDao().currentUserId() != userId ||
                    db.classworkDao().saveFile(id, original.updatedAt, file.path,
                        if (file.inputStream().use { it.read() } == 0x1a) "video/webm" else "video/mp4") != 1)
                    throw VideoFailure(VideoProblem.CHANGED)
            }
        } catch (e: Exception) { file.delete(); throw e }
        progress(VideoDownload(DownloadPhase.COMPLETE, original.fileSizeBytes, original.fileSizeBytes))
    } }
}
