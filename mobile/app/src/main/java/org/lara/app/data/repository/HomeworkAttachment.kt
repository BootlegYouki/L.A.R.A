package org.lara.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HomeworkAttachment {
    const val MAX_ATTACHMENTS = 5
    const val MAX_DOCUMENT_BYTES = 250L * 1024 * 1024
    const val MAX_PHOTO_BYTES = HomeworkPhoto.MAX_BYTES

    fun type(file: File): String? = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png" -> "IMAGE"
        "pdf", "docx" -> "DOCUMENT"
        else -> null
    }

    fun maxBytes(file: File): Long = if (type(file) == "IMAGE") MAX_PHOTO_BYTES else MAX_DOCUMENT_BYTES

    // Keep the unique photo filename and strip the private storage prefix from picked documents.
    fun displayName(file: File): String = if (type(file) == "IMAGE") file.name
        else file.name.substringAfter('_', file.name)

    fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    internal fun hasExpectedDocumentContent(file: File): Boolean = try {
        when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> file.inputStream().use { input ->
                val header = ByteArray(5)
                input.read(header) == header.size && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
            }
            "docx" -> ZipFile(file).use { zip ->
                zip.getEntry("[Content_Types].xml") != null && zip.getEntry("word/document.xml") != null
            }
            else -> false
        }
    } catch (_: IOException) { false }

    suspend fun copyDocument(context: Context, uri: Uri, directory: File): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val providerName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val originalName = providerName?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        val extension = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("pdf", "docx"))
        val providerType = resolver.getType(uri)?.substringBefore(';')?.lowercase(Locale.ROOT)
        require(providerType == null || providerType in setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/zip",
            "application/octet-stream",
        ))

        check(directory.isDirectory || directory.mkdirs())
        val safeBase = originalName.substringBeforeLast('.', originalName).take(80)
            .filter { it.isLetterOrDigit() || it in " ._-()" }.trim().trimEnd('.').ifBlank { "homework" }
        val target = File(directory, "submission-${UUID.randomUUID()}_$safeBase.$extension")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open selected file")
            input.use { source ->
                target.outputStream().buffered(32 * 1024).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DOCUMENT_BYTES)
                        output.write(buffer, 0, count)
                    }
                    require(total > 0)
                }
            }
            require(hasExpectedDocumentContent(target))
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}
