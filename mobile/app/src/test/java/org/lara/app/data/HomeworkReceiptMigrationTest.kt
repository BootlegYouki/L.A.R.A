package org.lara.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.HomeworkReceiptMigration
import org.lara.app.data.local.LaraDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeworkReceiptMigrationTest {
    @Test fun versionTwoKeepsQueuedPhotosAndSession(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "receipt-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File(System.getProperty("lara.schemaDir"),
            "org.lara.app.data.local.LaraDatabase/2.json").readText()).jsonObject.getValue("database").jsonObject
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
            old.execSQL("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES('class','Science','A','K7M4QX','teacher',1,1)")
            old.execSQL("INSERT INTO assignments(id,classroom_id,title,due_date,created_at,updated_at) VALUES('assignment','class','Work',10,1,1)")
            old.execSQL("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,submitted_at,updated_at) VALUES('photo','assignment','learner','/private/queued.jpg',5,5)")
            old.execSQL("INSERT INTO sync_state(`key`,value,updated_at) VALUES('current_user_id','learner',1)")
            old.version = 2
        }
        val migrated = Room.databaseBuilder(context, LaraDatabase::class.java, name).addMigrations(HomeworkReceiptMigration).build()
        try {
            val photo = requireNotNull(migrated.homeworkDao().find("assignment", "learner"))
            assertEquals("QUEUED_FOR_SYNC", photo.syncStatus)
            assertEquals("/private/queued.jpg", photo.filePath)
            assertEquals(5L, photo.submittedAt)
            assertNull(photo.receivedAt)
            assertEquals("learner", migrated.syncStateDao().get("current_user_id")?.value)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
