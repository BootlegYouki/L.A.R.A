package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "classrooms",
    primaryKeys = ["id"],
)
data class ClassroomEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "section") val section: String,
    @ColumnInfo(name = "class_code") val classCode: String,
    @ColumnInfo(name = "teacher_id") val teacherId: String,
    @ColumnInfo(name = "co_teacher_ids_json") val coTeacherIdsJson: String? = null,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
