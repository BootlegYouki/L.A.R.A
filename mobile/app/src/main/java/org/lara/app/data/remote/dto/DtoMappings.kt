package org.lara.app.data.remote.dto

import org.lara.app.data.local.entities.AnnouncementEntity
import org.lara.app.data.local.entities.AnnouncementCommentEntity
import org.lara.app.data.local.entities.PrivateCommentEntity
import org.lara.app.data.local.entities.UserEntity

// PublicUser has no wire timestamps. The caller must supply its snapshot timestamp.
fun PublicUserDto.toEntity(createdAt: Long, updatedAt: Long) = UserEntity(
    id = id, lrnOrId = lrnOrId, fullName = fullName, role = role,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun AnnouncementDto.toEntity() = AnnouncementEntity(
    id = id, classroomId = classroomId, title = title, content = content,
    allowComments = allowComments ?: true, createdAt = createdAt, updatedAt = updatedAt,
)

fun AnnouncementCommentEntity.toPushDto(): CommentPushDto {
    require(syncStatus == "QUEUED_FOR_SYNC")
    return CommentPushDto(id, announcementId, content, createdAt)
}

fun PrivateCommentEntity.toPushDto(): PrivateCommentPushDto {
    require(syncStatus == "QUEUED_FOR_SYNC")
    return PrivateCommentPushDto(id, assignmentId, content, createdAt)
}
