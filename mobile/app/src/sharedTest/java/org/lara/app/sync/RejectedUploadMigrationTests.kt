package org.lara.app.sync

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.RejectedUploadMigration
import java.util.UUID

abstract class RejectedUploadMigrationTests {
    protected abstract fun versionOneSchema(): String

    @Test fun realVersionOneDatabaseMigratesReceiptsWithoutLosingOrRejectingEditedWork(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = Json.parseToJsonElement(versionOneSchema()).jsonObject.getValue("database").jsonObject
        val old = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { value ->
                        val entity = value.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        entity.getValue("indices").jsonArray.forEach {
                            db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Old fixture must stay v1")
            }).build())
        var upgraded: LaraDatabase? = null
        try {
            val db = old.writableDatabase
            db.execSQL("INSERT INTO users(id,full_name,role,created_at,updated_at) VALUES('learner','Synthetic','STUDENT',1,1)")
            db.execSQL("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES('class','Science','A','K7M4QX','teacher',1,1)")
            db.execSQL("INSERT INTO announcements(id,classroom_id,title,content,created_at,updated_at) VALUES('post','class','Lesson','Read',1,1)")
            db.execSQL("INSERT INTO assignments(id,classroom_id,title,due_date,created_at,updated_at) VALUES('assignment','class','Work',10,1,1)")
            db.execSQL("INSERT INTO quizzes(id,classroom_id,title,time_limit_minutes,created_at,updated_at) VALUES('quiz','class','Quiz',5,1,1)")
            db.execSQL("INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('comment','post','learner','Original',1,2,'QUEUED_FOR_SYNC')")
            db.execSQL("INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('edited','post','learner','New edit',1,3,'QUEUED_FOR_SYNC')")
            db.execSQL("INSERT INTO private_comments(id,assignment_id,student_id,author_id,content,created_at,updated_at,sync_status) VALUES('private','assignment','learner','learner','Private original',1,2,'QUEUED_FOR_SYNC')")
            db.execSQL("INSERT INTO quiz_attempts(id,quiz_id,student_id,status,started_at,submitted_at,updated_at) VALUES('attempt','quiz','learner','SUBMITTED',1,2,3)")
            db.execSQL("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,submitted_at,updated_at) VALUES('photo','assignment','learner','/local/keep.jpg',2,2)")
            fun state(key: String, value: String) = db.execSQL("INSERT INTO sync_state(`key`,value,updated_at) VALUES(?,?,1)", arrayOf(key, value))
            state("current_user_id", "learner"); state("hub_id", "old-hub"); state("sync_epoch", "7"); state("cursor", "42")
            state("queued_hub:announcement_comments:comment", "old-hub")
            // Fixed hashes were captured from the v1 wire payload + row version, not the new migration.
            fun receipt(table: String, id: String, hash: String) = state("upload_receipt:$table:$id",
                """{"status":"REJECTED","reason":"COMMENTS_DISABLED","fingerprint":"$hash","hubId":"old-hub"}""")
            receipt("announcement_comments", "comment", "b19167c2575b4288ca31ed0b97c6812b34eafa9650fddc55b91f984fb8686fb4")
            receipt("private_comments", "private", "7f55a2106bd9e26756e8ff2a517fda90375ce07f678a978657370c8439a56daa")
            receipt("quiz_attempts", "attempt", "e7c3602f9e2be9f63ed501530425dedf58a935adc576c24731aaca700255c7b5")
            receipt("announcement_comments", "edited", "37273d43b387471ffebd9c9166f44ecb6ab0f10637ac08f80724601656a7ec8b")
            state("upload_receipt:announcement_comments:malformed", "not json")
            old.close()
            fun open() = Room.databaseBuilder(context, LaraDatabase::class.java, name).addMigrations(RejectedUploadMigration).build()
            var migrated = open(); upgraded = migrated
            assertEquals("REJECTED", migrated.syncDao().comment("comment")?.syncStatus)
            assertEquals("COMMENTS_DISABLED", migrated.syncDao().comment("comment")?.syncRejectReason)
            assertEquals("REJECTED", migrated.syncDao().privateComment("private")?.syncStatus)
            assertEquals("COMMENTS_DISABLED", migrated.syncDao().privateComment("private")?.syncRejectReason)
            assertEquals("REJECTED", migrated.syncDao().attempt("attempt")?.syncStatus)
            assertEquals("COMMENTS_DISABLED", migrated.syncDao().attempt("attempt")?.syncRejectReason)
            assertEquals("New edit", migrated.syncDao().comment("edited")?.content)
            assertEquals("QUEUED_FOR_SYNC", migrated.syncDao().comment("edited")?.syncStatus)
            assertNull(migrated.syncDao().comment("edited")?.syncRejectReason)
            assertEquals("/local/keep.jpg", migrated.syncDao().submission("photo")?.filePath)
            assertEquals("QUEUED_FOR_SYNC", migrated.syncDao().submission("photo")?.syncStatus)
            assertNull(migrated.syncDao().submission("photo")?.syncRejectReason)
            assertEquals("42", migrated.syncStateDao().get("cursor")?.value)
            assertEquals("7", migrated.syncStateDao().get("sync_epoch")?.value)
            assertEquals("old-hub", migrated.syncStateDao().get("queued_hub:announcement_comments:comment")?.value)
            assertNull(migrated.syncStateDao().get("upload_receipt:announcement_comments:comment"))
            migrated.close(); migrated = open(); upgraded = migrated
            assertEquals("REJECTED", migrated.syncDao().comment("comment")?.syncStatus)
            assertEquals("learner", migrated.authDao().currentUserId())
        } finally { old.close(); upgraded?.close(); context.deleteDatabase(name) }
    }
}
