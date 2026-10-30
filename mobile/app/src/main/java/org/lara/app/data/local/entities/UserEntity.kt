package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "users",
    primaryKeys = ["id"],
)
data class UserEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "lrn_or_id") val lrnOrId: String? = null,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
 {
    init {
        require(role in setOf("TEACHER", "STUDENT"))
    }
}
