package org.lara.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.local.LaraDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuditTimestampMigrationTest {
    @Test fun versionThreeBackfillsAuditFieldsAndPreservesWork(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "audit-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val photo = File(context.cacheDir, "synthetic-queued.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val schema = Json.parseToJsonElement(File(System.getProperty("lara.schemaDir"),
            "org.lara.app.data.local.LaraDatabase/3.json").readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val entity = value.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.getValue("indices").jsonArray.forEach {
                    old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO users(id,full_name,role,created_at,updated_at) VALUES('learner','Synthetic','STUDENT',1,1)")
            old.execSQL("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES('class','Science','A','K7M4QX','teacher',1,1)")
            old.execSQL("INSERT INTO enrollments(id,classroom_id,student_id,joined_at,updated_at) VALUES('enrollment','class','learner',11,99)")
            old.execSQL("INSERT INTO materials(id,classroom_id,title,file_type,file_size_bytes,created_at,updated_at) VALUES('material','class','Lesson','DOCUMENT',5,1,1)")
            old.execSQL("INSERT INTO material_chunks(id,material_id,order_index,text,updated_at) VALUES('chunk','material',0,'Synthetic lesson',22)")
            for (status in listOf("QUEUED_FOR_SYNC", "REJECTED", "SYNCED")) {
                old.execSQL("INSERT INTO assignments(id,classroom_id,title,due_date,created_at,updated_at) VALUES(?,'class','Work',100,1,1)", arrayOf("assignment-$status"))
                old.execSQL("INSERT INTO quizzes(id,classroom_id,title,time_limit_minutes,created_at,updated_at) VALUES(?,'class','Quiz',5,1,1)", arrayOf("quiz-$status"))
                val reason = if (status == "REJECTED") "FORBIDDEN" else null
                old.execSQL("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,submitted_at,updated_at,sync_status,sync_reject_reason,received_at,score,teacher_feedback) VALUES(?,?,'learner',?,33,99,?,?,55,10,'Retained')",
                    arrayOf("photo-$status", "assignment-$status", photo.path, status, reason))
                old.execSQL("INSERT INTO quiz_attempts(id,quiz_id,student_id,status,started_at,submitted_at,answers_json,updated_at,sync_status,sync_reject_reason) VALUES(?,?,'learner','SUBMITTED',44,45,'[]',99,?,?)",
                    arrayOf("attempt-$status", "quiz-$status", status, reason))
            }
            old.execSQL("INSERT INTO sync_state(`key`,value,updated_at) VALUES('current_user_id','learner',1),('cursor','123',1),('queued_hub:assignment_submissions:photo-QUEUED_FOR_SYNC','synthetic-hub',1)")
            old.version = 3
        }
        var db = LaraDatabase.create(context, name)
        try {
            assertEquals(11L, db.syncDao().enrollment("class", "learner")?.createdAt)
            assertEquals(11L, db.syncDao().enrollment("class", "learner")?.joinedAt)
            assertEquals(99L, db.syncDao().enrollment("class", "learner")?.updatedAt)
            val chunk = requireNotNull(db.syncDao().materialChunk("chunk"))
            assertEquals(22L, chunk.createdAt)
            assertEquals(22L, chunk.updatedAt)
            assertEquals("Synthetic lesson", chunk.text)
            for (status in listOf("QUEUED_FOR_SYNC", "REJECTED", "SYNCED")) {
                val saved = requireNotNull(db.syncDao().submission("photo-$status"))
                assertEquals(33L, saved.createdAt)
                assertEquals(33L, saved.submittedAt)
                assertEquals(99L, saved.updatedAt)
                assertEquals(status, saved.syncStatus)
                assertEquals(photo.path, saved.filePath)
                assertEquals(55L, saved.receivedAt)
                assertEquals(10L, saved.score)
                assertEquals("Retained", saved.teacherFeedback)
                assertEquals(if (status == "REJECTED") "FORBIDDEN" else null, saved.syncRejectReason)
                val attempt = requireNotNull(db.syncDao().attempt("attempt-$status"))
                assertEquals(44L, attempt.createdAt)
                assertEquals(44L, attempt.startedAt)
                assertEquals(45L, attempt.submittedAt)
                assertEquals(99L, attempt.updatedAt)
                assertEquals("SUBMITTED", attempt.status)
                assertEquals(if (status == "REJECTED") "FORBIDDEN" else null, attempt.syncRejectReason)
                assertEquals(status, attempt.syncStatus)
                assertEquals("[]", attempt.answersJson)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), photo.readBytes())
            assertEquals("123", db.syncStateDao().get("cursor")?.value)
            assertEquals("learner", db.syncStateDao().get("current_user_id")?.value)
            assertEquals("synthetic-hub", db.syncStateDao().get("queued_hub:assignment_submissions:photo-QUEUED_FOR_SYNC")?.value)
            db.close()
            db = LaraDatabase.create(context, name)
            assertEquals(4, db.openHelper.writableDatabase.version)
            assertEquals(33L, db.syncDao().submission("photo-QUEUED_FOR_SYNC")?.createdAt)
            assertEquals(44L, db.syncDao().attempt("attempt-REJECTED")?.createdAt)
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            for (table in listOf("enrollments", "material_chunks", "assignment_submissions", "quiz_attempts")) {
                val failure = runCatching { sql.execSQL("UPDATE $table SET created_at=NULL") }.exceptionOrNull()
                assertTrue(failure is android.database.sqlite.SQLiteConstraintException)
            }
        } finally { db.close(); context.deleteDatabase(name); photo.delete() }
    }
}
