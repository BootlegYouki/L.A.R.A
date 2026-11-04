package org.lara.app.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.dto.*
import org.lara.app.data.remote.ClassroomSnapshot
import org.lara.app.data.repository.RoomEnrollmentNoticeRepository
import org.lara.app.domain.enrollment.EnrollmentNotice
import org.lara.app.domain.enrollment.EnrollmentStatus

internal data class PendingUpload(val table: String, val id: String,
    val comment: AnnouncementCommentEntity? = null, val privateComment: PrivateCommentEntity? = null,
    val attempt: QuizAttemptEntity? = null)

class SyncRepository(private val db: LaraDatabase, private val transport: SyncTransport,
    private val onPulled: suspend () -> Unit = {}) {
    private val mutex = Mutex()
    private val store = SyncStore(db)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    internal suspend fun refreshRoster(userId: String, refresh: suspend () -> Unit) = mutex.withLock {
        store.checkOwner(userId)
        refresh()
    }

    suspend fun refreshClassrooms(userId: String,
        fetch: suspend () -> ClassroomSnapshot): ClassroomSnapshot = mutex.withLock {
        store.checkOwner(userId)
        val snapshot = fetch()
        db.withTransaction {
            store.checkOwner(userId)
            val notices = RoomEnrollmentNoticeRepository(db)
            for (row in snapshot.rows) notices.save(userId, EnrollmentNotice(row.id, row.name,
                EnrollmentStatus.valueOf(requireNotNull(row.enrollmentStatus))))
        }
        snapshot
    }

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
        onPulled()
    }
    private suspend fun eligible(row: PendingUpload, hubId: String): Boolean {
        val bound = db.syncStateDao().get("queued_hub:${row.table}:${row.id}")?.value
        if (bound != null && bound != hubId) return false
        return true
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
                PendingUpload("announcement_comments", it.id, comment = it)
            }
            if (result.size < 50) collect({ dao.queuedPrivateComments(userId, it) }) {
                PendingUpload("private_comments", it.id, privateComment = it)
            }
            if (result.size < 50) collect({ dao.queuedAttempts(userId, it) }) {
                PendingUpload("quiz_attempts", it.id, attempt = it)
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
                    if (receipt.status == "SYNCED") {
                        row.comment?.let { db.syncDao().acknowledgeComment(it.id, it.content, it.updatedAt) }
                        row.privateComment?.let { db.syncDao().acknowledgePrivateComment(it.id, it.content, it.updatedAt) }
                        row.attempt?.let { db.syncDao().acknowledgeAttempt(it.id, it.answersJson, requireNotNull(it.submittedAt), it.updatedAt) }
                        acknowledged++
                    } else {
                        row.comment?.let { db.syncDao().rejectComment(it.id, it.content, it.updatedAt, receipt.reason) }
                        row.privateComment?.let { db.syncDao().rejectPrivateComment(it.id, it.content, it.updatedAt, receipt.reason) }
                        row.attempt?.let { db.syncDao().rejectAttempt(it.id, it.answersJson, requireNotNull(it.submittedAt), it.updatedAt, receipt.reason) }
                    }
                }
            }
            // A missing receipt stays queued; let WorkManager retry rather than spin on a partial response.
            if (response.receipts.size < rows.size) throw java.io.IOException("Incomplete sync receipts")
        }
        acknowledged
    }
}
