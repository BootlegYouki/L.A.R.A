package org.lara.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.lara.app.data.remote.dto.*
import java.security.MessageDigest

/** Add nullable columns in place; Room runs the upgrade and legacy-receipt conversion atomically. */
object RejectedUploadMigration : Migration(1, 2) {
    private val tables = setOf("announcement_comments", "private_comments", "quiz_attempts", "assignment_submissions")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override fun migrate(db: SupportSQLiteDatabase) {
        tables.forEach { db.execSQL("ALTER TABLE $it ADD COLUMN sync_reject_reason TEXT") }
        db.query("SELECT `key`, value FROM sync_state WHERE substr(`key`,1,15)='upload_receipt:'").use { rows ->
            while (rows.moveToNext()) {
                val key = rows.getString(0).removePrefix("upload_receipt:")
                val table = key.substringBefore(':')
                val id = key.substringAfter(':', "")
                if (table !in tables || id.isBlank()) continue
                val receipt = runCatching { json.parseToJsonElement(rows.getString(1)).jsonObject }.getOrNull() ?: continue
                fun value(name: String) = (receipt[name] as? JsonPrimitive)?.contentOrNull
                if (value("status") != "REJECTED") continue
                val sent = value("fingerprint") ?: continue
                // An edited row is a different upload; an old refusal must not reject the newer work.
                if (sent != legacyFingerprint(db, table, id)) continue
                val reason = value("reason")
                db.execSQL("UPDATE $table SET sync_status='REJECTED', sync_reject_reason=? WHERE id=? AND sync_status='QUEUED_FOR_SYNC'",
                    arrayOf(reason, id))
            }
        }
        db.execSQL("DELETE FROM sync_state WHERE substr(`key`,1,15)='upload_receipt:'")
    }

    private fun legacyFingerprint(db: SupportSQLiteDatabase, table: String, id: String): String? =
        db.query("SELECT * FROM $table WHERE id=? AND sync_status='QUEUED_FOR_SYNC'", arrayOf(id)).use { row ->
            if (!row.moveToFirst()) return@use null
            fun text(name: String) = row.getString(row.getColumnIndexOrThrow(name))
            fun long(name: String) = row.getLong(row.getColumnIndexOrThrow(name))
            val payload = when (table) {
                "announcement_comments" -> json.encodeToString(CommentPushDto(id, text("announcement_id"), text("content"), long("created_at"))) + long("updated_at")
                "private_comments" -> json.encodeToString(PrivateCommentPushDto(id, text("assignment_id"), text("content"), long("created_at"))) + long("updated_at")
                "quiz_attempts" -> {
                    if (row.isNull(row.getColumnIndexOrThrow("submitted_at"))) return@use null
                    val answers = runCatching { json.decodeFromString<List<AnswerDto>>(text("answers_json")) }.getOrNull() ?: return@use null
                    json.encodeToString(QuizAttemptPushDto(id, text("quiz_id"), long("started_at"), long("submitted_at"), answers)) + "|" + long("updated_at")
                }
                // The previous worker never produced multipart homework receipts.
                else -> return@use null
            }
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
}
