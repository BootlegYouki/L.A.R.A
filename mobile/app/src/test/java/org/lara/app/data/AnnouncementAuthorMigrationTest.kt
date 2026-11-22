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
class AnnouncementAuthorMigrationTest {
    @Test fun versionFourAddsNullAuthorAndKeepsRowsAndQueuedWork(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "announcement-author-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File(System.getProperty("lara.schemaDir"),
            "org.lara.app.data.local.LaraDatabase/4.json").readText()).jsonObject.getValue("database").jsonObject
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
            old.execSQL("INSERT INTO announcements(id,classroom_id,title,content,created_at,updated_at) VALUES('post','class','Lesson','Read',1,2)")
            old.execSQL("INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('comment','post','learner','Queued',1,2,'QUEUED_FOR_SYNC')")
            old.execSQL("INSERT INTO sync_state(`key`,value,updated_at) VALUES('cursor','77',1)")
            old.version = 4
        }
        val db = LaraDatabase.create(context, name)
        try {
            val post = requireNotNull(db.syncDao().announcement("post"))
            assertEquals("Lesson", post.title)
            assertEquals(2L, post.updatedAt)
            assertNull(post.authorId)
            assertEquals("QUEUED_FOR_SYNC", db.syncDao().comment("comment")?.syncStatus)
            assertEquals("77", db.syncStateDao().get("cursor")?.value)
            assertEquals(5, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
