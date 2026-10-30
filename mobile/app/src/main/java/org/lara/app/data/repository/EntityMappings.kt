package org.lara.app.data.repository

import org.lara.app.data.local.entities.*
import org.lara.app.domain.model.*

fun UserEntity.toDomain() = User(
    id = id,
    lrnOrId = lrnOrId,
    fullName = fullName,
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ClassroomEntity.toDomain() = Classroom(
    id = id,
    name = name,
    section = section,
    classCode = classCode,
    teacherId = teacherId,
    coTeacherIdsJson = coTeacherIdsJson,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun EnrollmentEntity.toDomain() = Enrollment(
    id = id,
    classroomId = classroomId,
    studentId = studentId,
    status = status,
    joinedAt = joinedAt,
    updatedAt = updatedAt,
)

fun AnnouncementEntity.toDomain() = Announcement(
    id = id,
    classroomId = classroomId,
    title = title,
    content = content,
    allowComments = allowComments,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AnnouncementCommentEntity.toDomain() = AnnouncementComment(
    id = id,
    announcementId = announcementId,
    authorId = authorId,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStatus = syncStatus,
)

fun MaterialEntity.toDomain() = Material(
    id = id,
    classroomId = classroomId,
    title = title,
    fileType = fileType,
    mimeType = mimeType,
    fileSizeBytes = fileSizeBytes,
    downloadUrl = downloadUrl,
    localFilePath = localFilePath,
    topicId = topicId,
    assignmentId = assignmentId,
    announcementId = announcementId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MaterialChunkEntity.toDomain() = MaterialChunk(
    id = id,
    materialId = materialId,
    orderIndex = orderIndex,
    heading = heading,
    text = text,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
)

fun AssignmentEntity.toDomain() = Assignment(
    id = id,
    classroomId = classroomId,
    title = title,
    instructions = instructions,
    dueDate = dueDate,
    allowLate = allowLate,
    maxPoints = maxPoints,
    topicId = topicId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssignmentSubmissionEntity.toDomain() = AssignmentSubmission(
    id = id,
    assignmentId = assignmentId,
    studentId = studentId,
    filePath = filePath,
    fileType = fileType,
    submittedAt = submittedAt,
    score = score,
    teacherFeedback = teacherFeedback,
    updatedAt = updatedAt,
    syncStatus = syncStatus,
)

fun QuizEntity.toDomain() = Quiz(
    id = id,
    classroomId = classroomId,
    title = title,
    instructions = instructions,
    topicId = topicId,
    timeLimitMinutes = timeLimitMinutes,
    shuffleQuestions = shuffleQuestions,
    status = status,
    startedAt = startedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun QuizQuestionEntity.toDomain() = QuizQuestion(
    id = id,
    quizId = quizId,
    orderIndex = orderIndex,
    questionText = questionText,
    questionType = questionType,
    optionsJson = optionsJson,
    points = points,
    imagePath = imagePath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun QuizAttemptEntity.toDomain() = QuizAttempt(
    id = id,
    quizId = quizId,
    studentId = studentId,
    status = status,
    startedAt = startedAt,
    submittedAt = submittedAt,
    score = score,
    totalPoints = totalPoints,
    answersJson = answersJson,
    updatedAt = updatedAt,
    syncStatus = syncStatus,
)

fun AiChatMessageEntity.toDomain() = AiChatMessage(
    id = id,
    classroomId = classroomId,
    studentId = studentId,
    materialId = materialId,
    chunkId = chunkId,
    language = language,
    role = role,
    content = content,
    createdAt = createdAt,
)

fun TopicEntity.toDomain() = Topic(
    id = id,
    classroomId = classroomId,
    name = name,
    orderIndex = orderIndex,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PrivateCommentEntity.toDomain() = PrivateComment(
    id = id,
    assignmentId = assignmentId,
    studentId = studentId,
    authorId = authorId,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStatus = syncStatus,
)

fun SyncStateEntity.toDomain() = SyncState(
    key = key,
    value = value,
    updatedAt = updatedAt,
)
