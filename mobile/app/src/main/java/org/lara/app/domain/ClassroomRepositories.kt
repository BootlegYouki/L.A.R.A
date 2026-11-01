package org.lara.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.lara.app.domain.model.*

sealed interface Cached<out T> {
    data object Loading : Cached<Nothing>
    data class Content<T>(val value: T) : Cached<T>
}

data class Classwork(
    val topics: List<Topic>,
    val materials: List<Material>,
    val assignments: List<Assignment>,
    val quizzes: List<Quiz>,
)

data class RosterMember(val enrollment: Enrollment, val user: User?)

interface ClassroomRepository {
    fun classrooms(scope: CoroutineScope): StateFlow<Cached<List<Classroom>>>
}

interface StreamRepository {
    fun stream(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<Announcement>>>
    fun comments(announcementId: String, scope: CoroutineScope): StateFlow<Cached<List<AnnouncementComment>>>
    suspend fun addComment(announcementId: String, authorId: String, content: String): String
}

interface ClassworkRepository {
    fun classwork(classroomId: String, scope: CoroutineScope): StateFlow<Cached<Classwork>>
    fun chunks(materialId: String, scope: CoroutineScope): StateFlow<Cached<List<MaterialChunk>>>
}

interface EnrollmentRepository {
    /** Cached-only builds return false; MOBILE 2.5 injects the canonical producer. */
    suspend fun refresh(userId: String): Boolean = false
    fun learnerClasses(studentId: String, scope: CoroutineScope): StateFlow<Cached<List<org.lara.app.domain.enrollment.LearnerClass>>>

    fun roster(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<RosterMember>>>
}

interface QuizRepository {
    fun quizzes(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<Quiz>>>
    fun questions(quizId: String, scope: CoroutineScope): StateFlow<Cached<List<QuizQuestion>>>
    fun attempt(quizId: String, studentId: String, scope: CoroutineScope): StateFlow<Cached<QuizAttempt?>>
    fun hasInProgressAttempt(studentId: String): Flow<Boolean>
}
