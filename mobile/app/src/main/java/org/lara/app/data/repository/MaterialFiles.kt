package org.lara.app.data.repository

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.HubFailure
import org.lara.app.data.remote.isAllowedHubUrl

/** Files are account-scoped; a failed transfer never replaces a readable cached copy. */
class MaterialFiles(private val db: LaraDatabase, private val api: EnrollmentApi,
    private val root: File, private val userId: String) {
    private val mutex = Mutex()
    suspend fun prune() = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val kept = db.classworkDao().savedFiles().toSet()
            root.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && it.path !in kept }
                .forEach { it.delete() }
        } finally { mutex.unlock() }
    }
    suspend fun open(id: String): File = mutex.withLock { withContext(Dispatchers.IO) {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val row = db.classworkDao().material(id) ?: throw IOException("Material unavailable")
        row.localFilePath?.let { path ->
            val file = File(path).canonicalFile
            if (file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) return@withContext file
        }
        val c = api.connection(userId)
        val url = c.endpoint.restBase + "/api/materials/$id/download"
        require(isAllowedHubUrl(url))
        root.mkdirs()
        val key = MessageDigest.getInstance("SHA-256").digest("$id:${row.updatedAt}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(root, key)
        val part = File.createTempFile("download-", ".part", root)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer ${c.session.token}")
            if (connection.responseCode != 200) throw HubFailure(connection.responseCode, "MATERIAL_UNAVAILABLE")
            val max = 250L * 1024 * 1024
            require(connection.contentLengthLong <= max)
            connection.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= max)
                    output.write(buffer, 0, count)
                }
                require(total > 0)
                if (connection.contentLengthLong >= 0) require(total == connection.contentLengthLong)
            } }
            check(api.connection(userId) == c)
            val header = part.inputStream().use { input ->
                val bytes = ByteArray(12)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                bytes.copyOf(length)
            }
            val mime = when {
                header.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> "application/pdf"
                header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "image/png"
                header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "image/jpeg"
                header.take(3).toByteArray().contentEquals("GIF".toByteArray()) -> "image/gif"
                header.size >= 12 && String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WEBP" -> "image/webp"
                else -> "application/octet-stream"
            }
            check(part.renameTo(target))
            if (db.classworkDao().saveFile(id, row.updatedAt, target.path, mime) != 1) {
                target.delete(); throw IOException("Material changed")
            }
            target
        } finally { part.delete(); connection.disconnect() }
    } }
}
