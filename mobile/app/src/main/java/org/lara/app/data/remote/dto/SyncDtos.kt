package org.lara.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicUserDto(
    @SerialName("id") val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("role") val role: String,
    @SerialName("lrn_or_id") val lrnOrId: String? = null,
)

@Serializable
data class ClassroomDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("section") val section: String,
    @SerialName("class_code") val classCode: String,
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("archived_at") val archivedAt: Long? = null,
    @SerialName("co_teacher_ids") val coTeacherIds: List<String>? = null,
    @SerialName("enrollment_status") val enrollmentStatus: String? = null,
)

@Serializable
data class EnrollmentDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("joined_at") val joinedAt: Long? = null,
)

@Serializable
data class AnnouncementDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("allow_comments") val allowComments: Boolean? = null,
)

@Serializable
data class CommentDto(
    @SerialName("id") val id: String,
    @SerialName("announcement_id") val announcementId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class MaterialDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("title") val title: String,
    @SerialName("file_type") val fileType: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("assignment_id") val assignmentId: String? = null,
    @SerialName("announcement_id") val announcementId: String? = null,
)

@Serializable
data class MaterialChunkDto(
    @SerialName("id") val id: String,
    @SerialName("material_id") val materialId: String,
    @SerialName("order_index") val orderIndex: Long,
    @SerialName("text") val text: String,
    @SerialName("heading") val heading: String? = null,
)

@Serializable
data class AssignmentDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("title") val title: String,
    @SerialName("max_points") val maxPoints: Long,
    @SerialName("due_date") val dueDate: Long,
    @SerialName("instructions") val instructions: String? = null,
    @SerialName("allow_late") val allowLate: Boolean? = null,
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class TopicDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("name") val name: String,
    @SerialName("order_index") val orderIndex: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class PrivateCommentDto(
    @SerialName("id") val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class SubmissionDto(
    @SerialName("id") val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("file_type") val fileType: String,
    @SerialName("submitted_at") val submittedAt: Long,
    @SerialName("score") val score: Long? = null,
    @SerialName("teacher_feedback") val teacherFeedback: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class QuizMetadataDto(
    @SerialName("id") val id: String,
    @SerialName("classroom_id") val classroomId: String,
    @SerialName("title") val title: String,
    @SerialName("status") val status: String,
    @SerialName("time_limit_minutes") val timeLimitMinutes: Long,
    @SerialName("started_at") val startedAt: Long? = null,
)

@Serializable
data class GradeReceiptDto(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("score") val score: Long?,
    @SerialName("total_points") val totalPoints: Long,
    @SerialName("submitted_at") val submittedAt: Long,
)

@Serializable
data class DeletedRecordDto(
    @SerialName("entity_table") val entityTable: String,
    @SerialName("entity_id") val entityId: String,
)

@Serializable
data class SyncPullRequestDto(
    @SerialName("cursor") val cursor: Long,
    @SerialName("hub_id") val hubId: String? = null,
    @SerialName("sync_epoch") val syncEpoch: Long? = null,
)

@Serializable
data class SyncPullResponseDto(
    @SerialName("server_time") val serverTime: Long,
    @SerialName("hub_id") val hubId: String,
    @SerialName("sync_epoch") val syncEpoch: Long,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("reset") val reset: Boolean,
    @SerialName("users") val users: List<PublicUserDto> = emptyList(),
    @SerialName("classrooms") val classrooms: List<ClassroomDto> = emptyList(),
    @SerialName("enrollments") val enrollments: List<EnrollmentDto> = emptyList(),
    @SerialName("topics") val topics: List<TopicDto> = emptyList(),
    @SerialName("private_comments") val privateComments: List<PrivateCommentDto> = emptyList(),
    @SerialName("announcements") val announcements: List<AnnouncementDto> = emptyList(),
    @SerialName("comments") val comments: List<CommentDto> = emptyList(),
    @SerialName("materials") val materials: List<MaterialDto> = emptyList(),
    @SerialName("material_chunks") val materialChunks: List<MaterialChunkDto> = emptyList(),
    @SerialName("assignments") val assignments: List<AssignmentDto> = emptyList(),
    @SerialName("submissions") val submissions: List<SubmissionDto> = emptyList(),
    @SerialName("quizzes") val quizzes: List<QuizMetadataDto> = emptyList(),
    @SerialName("quiz_attempts") val quizAttempts: List<GradeReceiptDto> = emptyList(),
    @SerialName("deleted") val deleted: List<DeletedRecordDto> = emptyList(),
)

@Serializable
data class AnswerDto(
    @SerialName("question_id") val questionId: String,
    @SerialName("selected_option") val selectedOption: String,
)

@Serializable
data class QuizAttemptPushDto(
    @SerialName("id") val id: String,
    @SerialName("quiz_id") val quizId: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("submitted_at") val submittedAt: Long,
    @SerialName("answers") val answers: List<AnswerDto>,
)

@Serializable
data class CommentPushDto(
    @SerialName("id") val id: String,
    @SerialName("announcement_id") val announcementId: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class PrivateCommentPushDto(
    @SerialName("id") val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class SyncPushRequestDto(
    @SerialName("quiz_attempts") val quizAttempts: List<QuizAttemptPushDto> = emptyList(),
    @SerialName("comments") val comments: List<CommentPushDto> = emptyList(),
    @SerialName("private_comments") val privateComments: List<PrivateCommentPushDto> = emptyList(),
)

@Serializable
data class SyncReceiptDto(
    @SerialName("id") val id: String,
    @SerialName("status") val status: String,
    @SerialName("reason") val reason: String? = null,
)

@Serializable
data class SyncPushResponseDto(
    @SerialName("acknowledged") val acknowledged: Boolean,
    @SerialName("receipts") val receipts: List<SyncReceiptDto>,
)
