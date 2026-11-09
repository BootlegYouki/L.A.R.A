package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "material_chunks",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = MaterialEntity::class, parentColumns = ["id"], childColumns = ["material_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["material_id", "order_index"], name = "index_material_chunks_material_id_order_index", unique = true, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class MaterialChunkEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "material_id") val materialId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Long,
    @ColumnInfo(name = "heading") val heading: String? = null,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "token_estimate") val tokenEstimate: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
