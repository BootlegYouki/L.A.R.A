package org.lara.app.domain.model

data class User(
    val id: String,
    val lrnOrId: String?,
    val fullName: String,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Classroom(
    val id: String,
    val name: String,
    val section: String,
    val classCode: String,
    val teacherId: String,
    val coTeacherIdsJson: String?,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Enrollment(
    val id: String,
    val classroomId: String,
    val studentId: String,
    val status: String,
    val joinedAt: Long,
    val updatedAt: Long,
)

data class Announcement(
    val id: String,
    val classroomId: String,
    val title: String,
    val content: String,
    val allowComments: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AnnouncementComment(
    val id: String,
    val announcementId: String,
    val authorId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String,
)

data class Material(
    val id: String,
    val classroomId: String,
    val title: String,
    val fileType: String,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val downloadUrl: String?,
    val localFilePath: String?,
    val topicId: String?,
    val assignmentId: String?,
    val announcementId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MaterialChunk(
    val id: String,
    val materialId: String,
    val orderIndex: Long,
    val heading: String?,
    val text: String,
    val tokenEstimate: Long?,
    val updatedAt: Long,
)

data class Assignment(
    val id: String,
    val classroomId: String,
    val title: String,
    val instructions: String,
    val dueDate: Long,
    val allowLate: Boolean,
    val maxPoints: Long,
    val topicId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AssignmentSubmission(
    val id: String,
    val assignmentId: String,
    val studentId: String,
    val filePath: String,
    val fileType: String,
    val submittedAt: Long,
    val score: Long?,
    val teacherFeedback: String?,
    val updatedAt: Long,
    val syncStatus: String,
)

data class Quiz(
    val id: String,
    val classroomId: String,
    val title: String,
    val instructions: String?,
    val topicId: String?,
    val timeLimitMinutes: Long,
    val shuffleQuestions: Boolean,
    val status: String,
    val startedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class QuizQuestion(
    val id: String,
    val quizId: String,
    val orderIndex: Long,
    val questionText: String,
    val questionType: String,
    val optionsJson: String?,
    val points: Long,
    val imagePath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class QuizAttempt(
    val id: String,
    val quizId: String,
    val studentId: String,
    val status: String,
    val startedAt: Long,
    val submittedAt: Long?,
    val score: Long?,
    val totalPoints: Long?,
    val answersJson: String,
    val updatedAt: Long,
    val syncStatus: String,
)

data class AiChatMessage(
    val id: String,
    val classroomId: String,
    val studentId: String,
    val materialId: String?,
    val chunkId: String?,
    val language: String?,
    val role: String,
    val content: String,
    val createdAt: Long,
)

data class Topic(
    val id: String,
    val classroomId: String,
    val name: String,
    val orderIndex: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PrivateComment(
    val id: String,
    val assignmentId: String,
    val studentId: String,
    val authorId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String,
)

data class SyncState(
    val key: String,
    val value: String,
    val updatedAt: Long,
)
