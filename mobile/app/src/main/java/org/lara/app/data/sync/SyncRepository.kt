package org.lara.app.data.sync

import androidx.room.withTransaction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.dto.*

@Serializable
data class UploadReceipt(val status: String, val reason: String?, val fingerprint: String, val hubId: String)
internal data class PendingUpload(val table: String, val id: String, val fingerprint: String,
    val comment: AnnouncementCommentEntity? = null, val privateComment: PrivateCommentEntity? = null,
    val attempt: QuizAttemptEntity? = null)

class SyncRepository(private val db: LaraDatabase, private val transport: SyncTransport) {
    private val mutex = Mutex()
    private val store = SyncStore(db)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun pull(userId: String) = mutex.withLock { pullPages(userId) }
    suspend fun pushQueued(userId: String): Int = mutex.withLock { pushBatches(userId) }
    suspend fun synchronize(userId: String) = mutex.withLock {
        pullPages(userId)
        if (pushBatches(userId) > 0) pullPages(userId)
    }
    private suspend fun pullPages(userId: String) {
        var rescanning = false
        do {
            val expected = store.request(userId)
            var request = expected
            var page = transport.pull(userId, request) // No transaction or DB changes while the socket is open.
            if (request.cursor == 0L || page.reset) rescanning = true
            if (!rescanning && store.needsFullSnapshot(userId, page)) {
                // Newly visible memberships can expose records older than the current sequence.
                // Rescan without wiping local work; the page and cursor still commit atomically.
                request = request.copy(cursor = 0)
                page = transport.pull(userId, request)
                rescanning = true
            }
            store.apply(userId, request, page, expectedState = expected)
        } while (page.hasMore)
    }
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun receiptKey(row: PendingUpload) = "upload_receipt:${row.table}:${row.id}"
    private suspend fun eligible(row: PendingUpload, hubId: String): Boolean {
        val bound = db.syncStateDao().get("queued_hub:${row.table}:${row.id}")?.value
        if (bound != null && bound != hubId) return false
        val stored = db.syncStateDao().get(receiptKey(row))?.value?.let {
            runCatching { json.decodeFromString<UploadReceipt>(it) }.getOrNull()
        }
        return stored?.let { it.hubId != hubId || it.fingerprint != row.fingerprint || it.status != "REJECTED" } ?: true
    }
    private suspend fun snapshot(userId: String, hubId: String): List<PendingUpload> = withContext(Dispatchers.IO) {
        db.withTransaction {
            store.checkOwner(userId)
            val result = mutableListOf<PendingUpload>()
            val dao = db.syncDao()
            suspend fun <T> collect(read: suspend (Int) -> List<T>, convert: (T) -> PendingUpload) {
                var offset = 0
                do {
                    val page = read(offset)
                    for (value in page) {
                        val row = convert(value)
                        if (eligible(row, hubId)) result.add(row)
                        if (result.size >= 50) return
                    }
                    offset += page.size
                } while (page.size == 50)
            }
            collect({ dao.queuedComments(userId, it) }) {
                PendingUpload("announcement_comments", it.id, fingerprint(json.encodeToString(it.toPushDto()) + it.updatedAt), comment = it)
            }
            if (result.size < 50) collect({ dao.queuedPrivateComments(userId, it) }) {
                PendingUpload("private_comments", it.id, fingerprint(json.encodeToString(it.toPushDto()) + it.updatedAt), privateComment = it)
            }
            if (result.size < 50) collect({ dao.queuedAttempts(userId, it) }) {
                PendingUpload("quiz_attempts", it.id, fingerprint(json.encodeToString(QuizAttemptPushDto(it.id, it.quizId, it.startedAt, requireNotNull(it.submittedAt), json.decodeFromString<List<AnswerDto>>(it.answersJson))) + "|" + it.updatedAt), attempt = it)
            }
            check(result.map { it.id }.distinct().size == result.size)
            result
        }
    }
    private suspend fun pushBatches(userId: String): Int = withContext(Dispatchers.IO) {
        store.checkOwner(userId)
        val hubId = db.syncStateDao().get("hub_id")?.value ?: return@withContext 0
        var acknowledged = 0
        while (true) {
            val rows = snapshot(userId, hubId)
            if (rows.isEmpty()) break
            val request = SyncPushRequestDto(
                comments = rows.mapNotNull { it.comment?.toPushDto() },
                privateComments = rows.mapNotNull { it.privateComment?.toPushDto() },
                quizAttempts = rows.mapNotNull { it.attempt?.let { a -> QuizAttemptPushDto(a.id, a.quizId,
                    a.startedAt, requireNotNull(a.submittedAt), json.decodeFromString<List<AnswerDto>>(a.answersJson)) } },
            )
            val response = transport.push(userId, request)
            check(response.acknowledged)
            require(response.receipts.map { it.id }.distinct().size == response.receipts.size)
            require(response.receipts.all { receipt -> rows.any { it.id == receipt.id } && receipt.status in setOf("SYNCED", "REJECTED") })
            db.withTransaction {
                store.checkOwner(userId)
                check(db.syncStateDao().get("hub_id")?.value == hubId)
                for (receipt in response.receipts) {
                    val row = rows.single { it.id == receipt.id }
                    val reason = receipt.reason?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                    db.syncStateDao().put(SyncStateEntity(receiptKey(row),
                        json.encodeToString(UploadReceipt(receipt.status, reason, row.fingerprint, hubId)), System.currentTimeMillis()))
                    if (receipt.status == "SYNCED") {
                        row.comment?.let { db.syncDao().acknowledgeComment(it.id, it.content, it.updatedAt) }
                        row.privateComment?.let { db.syncDao().acknowledgePrivateComment(it.id, it.content, it.updatedAt) }
                        row.attempt?.let { db.syncDao().acknowledgeAttempt(it.id, it.answersJson, requireNotNull(it.submittedAt), it.updatedAt) }
                        acknowledged++
                    }
                }
            }
            // A missing receipt stays queued; let WorkManager retry rather than spin on a partial response.
            if (response.receipts.size < rows.size) throw java.io.IOException("Incomplete sync receipts")
        }
        acknowledged
    }
    suspend fun receipt(table: String, id: String): UploadReceipt? = withContext(Dispatchers.IO) {
        db.syncStateDao().get("upload_receipt:$table:$id")?.value?.let { json.decodeFromString<UploadReceipt>(it) }
    }
}
