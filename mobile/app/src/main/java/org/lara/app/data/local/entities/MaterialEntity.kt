package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "materials",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["classroom_id", "file_type"], name = "idx_client_materials_class_type", unique = false, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class MaterialEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "file_type") val fileType: String,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "download_url") val downloadUrl: String? = null,
    @ColumnInfo(name = "local_file_path") val localFilePath: String? = null,
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    @ColumnInfo(name = "assignment_id") val assignmentId: String? = null,
    @ColumnInfo(name = "announcement_id") val announcementId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
 {
    init {
        require(fileType in setOf("DOCUMENT", "VIDEO", "WORKSHEET"))
    }
}
