package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "announcements",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["classroom_id", "created_at"], name = "idx_client_announcements_feed", unique = false, orders = [Index.Order.ASC, Index.Order.DESC])
    ],
)
data class AnnouncementEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "allow_comments", defaultValue = "1") val allowComments: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
