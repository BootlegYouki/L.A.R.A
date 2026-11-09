package org.lara.app.data.media

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.lara.app.domain.*

internal interface MediaResponse : AutoCloseable {
    val status: Int
    fun header(name: String): String?
    val input: InputStream
}

/** Fixed-size disk writes; partial bytes and validators survive process death, never enter Room. */
class DownloadManager internal constructor(private val root: File,
    private val open: suspend (String, Long, Long, String?) -> MediaResponse,
    private val availableBytes: () -> Long = { root.usableSpace }) {
    private val mutex = Mutex()
    companion object { internal const val CHUNK = 256L * 1024; internal const val MAX = 250L * 1024 * 1024 }

    internal fun target(key: String) = File(root, "$key.video")
    private fun part(key: String) = File(root, "$key.part")
    private fun validator(key: String) = File(root, "$key.validator")
    private inline fun <T> disk(action: () -> T): T = try { action() }
        catch (_: IOException) { throw VideoFailure(VideoProblem.STORAGE) }
        catch (_: SecurityException) { throw VideoFailure(VideoProblem.STORAGE) }

    internal suspend fun prune(keys: Set<String>, saved: Set<String>) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val names = keys.flatMap { listOf("$it.part", "$it.validator", "$it.video") }.toSet()
            root.listFiles().orEmpty().filter { it.isFile && it.name !in names && it.path !in saved }.forEach { it.delete() }
        } finally { mutex.unlock() }
    }

    internal fun progress(key: String, size: Long): VideoDownload {
        val bytes = part(key).takeIf { it.isFile }?.length() ?: 0
        return VideoDownload(if (bytes > 0) DownloadPhase.PAUSED else DownloadPhase.IDLE, bytes, size)
    }

    internal suspend fun remove(key: String, saved: File?) = mutex.withLock { withContext(Dispatchers.IO) {
        val keys = setOfNotNull(key, saved?.nameWithoutExtension)
        val files = keys.flatMap { listOf(part(it), validator(it), target(it)) }
        if (keys.any { !it.matches(Regex("[a-f0-9]{64}")) } || files.any {
            it.canonicalFile.parentFile != root.canonicalFile || it.canonicalFile.name != it.name
        }) throw VideoFailure(VideoProblem.STORAGE)
        disk {
            files.forEach { if (it.exists() && (!it.isFile || !it.delete())) throw VideoFailure(VideoProblem.STORAGE) }
        }
    } }

    internal suspend fun download(id: String, key: String, size: Long,
        progress: (VideoDownload) -> Unit): File = mutex.withLock { withContext(Dispatchers.IO) {
        if (size !in 1..MAX) throw VideoFailure(VideoProblem.INVALID_FILE)
        if (!root.isDirectory && !root.mkdirs()) throw VideoFailure(VideoProblem.STORAGE)
        target(key).takeIf { it.isFile && it.length() == size && isVideo(it) }?.let { return@withContext it }
        val part = part(key)
        val metadata = validator(key)
        if (part.length() > size) { part.delete(); metadata.delete() }
        if (availableBytes() < size - part.length() + CHUNK) throw VideoFailure(VideoProblem.STORAGE)
        var offset = part.length()
        var identity = disk { metadata.takeIf { it.isFile && it.length() <= 1024 }?.readText() }
        var restarted = false
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            open(id, offset, minOf(offset + CHUNK - 1, size - 1), identity).use { response ->
              coroutineScope {
                val closeOnCancel = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { response.close() }
                }
                try {
                when (response.status) {
                    416 -> {
                        // A complete partial is finalized below, never inferred solely from a 416.
                        part.delete(); metadata.delete()
                        throw VideoFailure(VideoProblem.CHANGED)
                    }
                    200, 206 -> Unit
                    404 -> throw VideoFailure(VideoProblem.UNAVAILABLE)
                    else -> throw IOException("Media response unavailable")
                }
                val nextIdentity = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified")
                if (response.status == 200) {
                    if (restarted) throw VideoFailure(VideoProblem.INVALID_FILE)
                    // A Range-ignoring server returns the entire representation; never append it.
                    disk { RandomAccessFile(part, "rw").use { it.setLength(0) } }
                    offset = 0
                    restarted = true
                } else {
                    val range = org.lara.app.data.remote.ByteRange.parse(response.header("Content-Range"))
                        ?: throw VideoFailure(VideoProblem.INVALID_FILE)
                    if (range.start != offset || range.total != size || range.end >= offset + CHUNK)
                        throw VideoFailure(VideoProblem.INVALID_FILE)
                    if (identity != null && nextIdentity != identity) {
                        part.delete(); metadata.delete(); throw VideoFailure(VideoProblem.CHANGED)
                    }
                }
                identity = nextIdentity
                if (identity != null && requireNotNull(identity).length > 1024) throw VideoFailure(VideoProblem.INVALID_FILE)
                disk { if (identity != null) metadata.writeText(requireNotNull(identity)) else metadata.delete() }
                val length = response.header("Content-Length")?.toLongOrNull()
                val expected = if (response.status == 200) size else
                    requireNotNull(org.lara.app.data.remote.ByteRange.parse(response.header("Content-Range"))).let { it.end - it.start + 1 }
                if (length != null && length != expected) throw VideoFailure(VideoProblem.INVALID_FILE)
                val start = offset
                disk { RandomAccessFile(part, "rw") }.use { output ->
                    output.seek(offset)
                    val buffer = ByteArray(32 * 1024)
                    while (offset - start < expected) {
                        currentCoroutineContext().ensureActive()
                        val count = try { response.input.read(buffer, 0, minOf(buffer.size.toLong(), expected - (offset - start)).toInt()) }
                            catch (e: IOException) { currentCoroutineContext().ensureActive(); throw e }
                        currentCoroutineContext().ensureActive()
                        if (count < 0) throw IOException("Interrupted media transfer")
                        try { output.write(buffer, 0, count) }
                        catch (_: IOException) { throw VideoFailure(VideoProblem.STORAGE) }
                        offset += count
                        progress(VideoDownload(DownloadPhase.DOWNLOADING, offset, size))
                    }
                    if (response.input.read() != -1) {
                        output.setLength(start)
                        throw VideoFailure(VideoProblem.INVALID_FILE)
                    }
                    try { output.fd.sync() } catch (_: IOException) { throw VideoFailure(VideoProblem.STORAGE) }
                }
                } finally { closeOnCancel.cancel() }
              }
            }
        }
        currentCoroutineContext().ensureActive()
        if (part.length() != size || !isVideo(part)) {
            part.delete(); metadata.delete(); throw VideoFailure(VideoProblem.INVALID_FILE)
        }
        val target = target(key)
        disk {
            try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        metadata.delete()
        target
    } }

    internal fun isVideo(file: File): Boolean = file.inputStream().use { input ->
        val bytes = ByteArray(12)
        var count = 0
        while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }
        (count >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") ||
            (count >= 4 && bytes.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()))
    }
}
