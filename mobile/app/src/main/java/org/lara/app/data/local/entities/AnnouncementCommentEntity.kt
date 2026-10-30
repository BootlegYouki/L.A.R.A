package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "announcement_comments",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = AnnouncementEntity::class, parentColumns = ["id"], childColumns = ["announcement_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["announcement_id", "created_at"], name = "idx_client_comments_order", unique = false, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class AnnouncementCommentEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "announcement_id") val announcementId: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status", defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)
 {
    init {
        require(syncStatus in setOf("SYNCED", "QUEUED_FOR_SYNC"))
    }
}
