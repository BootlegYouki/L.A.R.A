package org.lara.app.data.repository

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.lara.app.data.local.dao.*
import org.lara.app.data.local.entities.AnnouncementCommentEntity
import org.lara.app.domain.*
import org.lara.app.domain.model.*

private fun <T> Flow<T>.cached(scope: CoroutineScope): StateFlow<Cached<T>> =
    map<T, Cached<T>> { Cached.Content(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), Cached.Loading)

class RoomClassroomRepository(private val dao: ClassroomDao) : ClassroomRepository {
    override fun classrooms(scope: CoroutineScope) =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.cached(scope)
}

class RoomStreamRepository(
    private val dao: StreamDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : StreamRepository {
    override fun stream(classroomId: String, scope: CoroutineScope) =
        dao.observeByClassroom(classroomId).map { rows -> rows.map { it.toDomain() } }.cached(scope)

    override fun comments(announcementId: String, scope: CoroutineScope) =
        dao.observeComments(announcementId).map { rows -> rows.map { it.toDomain() } }.cached(scope)

    override suspend fun addComment(announcementId: String, authorId: String, content: String): String {
        require(content.isNotBlank())
        val id = newId()
        val timestamp = now()
        dao.insertComment(AnnouncementCommentEntity(
            id = id, announcementId = announcementId, authorId = authorId, content = content,
            createdAt = timestamp, updatedAt = timestamp, syncStatus = "QUEUED_FOR_SYNC",
        ))
        return id
    }
}

class RoomClassworkRepository(
    private val dao: ClassworkDao,
    private val quizDao: QuizDao,
) : ClassworkRepository {
    override fun classwork(classroomId: String, scope: CoroutineScope) = combine(
        dao.observeTopics(classroomId), dao.observeMaterials(classroomId),
        dao.observeAssignments(classroomId), quizDao.observeByClassroom(classroomId),
    ) { topics, materials, assignments, quizzes ->
        Classwork(topics.map { it.toDomain() }, materials.map { it.toDomain() },
            assignments.map { it.toDomain() }, quizzes.map { it.toDomain() })
    }.cached(scope)

    override fun chunks(materialId: String, scope: CoroutineScope) =
        dao.observeChunks(materialId).map { rows -> rows.map { it.toDomain() } }.cached(scope)
}

class RoomEnrollmentRepository(private val dao: RosterDao,
    private val onRefresh: (suspend (String) -> Unit)? = null) : EnrollmentRepository {
    override suspend fun refresh(userId: String): Boolean {
        val refresh = onRefresh ?: return false
        refresh(userId)
        return true
    }
    override fun learnerClasses(studentId: String, scope: CoroutineScope) =
        dao.observeForStudent(studentId).map { rows -> rows.map {
            org.lara.app.domain.enrollment.LearnerClass(it.classroom.toDomain(), it.enrollment.toDomain())
        } }.cached(scope)

    override fun roster(classroomId: String, scope: CoroutineScope) =
        dao.observeByClassroom(classroomId).map { rows ->
            rows.map { RosterMember(it.enrollment.toDomain(), it.user?.toDomain()) }
        }.cached(scope)
}

class RoomQuizRepository(private val dao: QuizDao) : QuizRepository {
    override fun quizzes(classroomId: String, scope: CoroutineScope) =
        dao.observeByClassroom(classroomId).map { rows -> rows.map { it.toDomain() } }.cached(scope)

    override fun questions(quizId: String, scope: CoroutineScope) =
        dao.observeQuestions(quizId).map { rows -> rows.map { it.toDomain() } }.cached(scope)

    override fun attempt(quizId: String, studentId: String, scope: CoroutineScope) =
        dao.observeAttempt(quizId, studentId).map { it?.toDomain() }.cached(scope)

    override fun hasInProgressAttempt(studentId: String) = dao.hasInProgressAttempt(studentId)
}
