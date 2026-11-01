package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.*
import org.lara.app.data.repository.RoomEnrollmentNoticeRepository
import org.lara.app.domain.enrollment.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class EnrollmentNoticePersistenceTest {
    private lateinit var context: Application
    // Windows native SQLite also sees Robolectric's long per-test directory path.
    private val name = "en-${UUID.randomUUID().toString().take(8)}.db"
    private lateinit var db: LaraDatabase
    private fun open() = Room.databaseBuilder(context, LaraDatabase::class.java, name).build()
    @Before fun setup(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(name).parentFile?.mkdirs()
        db = open()
        db.authDao().saveProfile(UserEntity("learner", "synthetic", "Test", "STUDENT", 1, 1))
        db.syncStateDao().put(SyncStateEntity("hub_id", "hub", 1))
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    @Test fun pendingSurvivesReopenWithoutInventingAnEnrollment(): Unit = runBlocking {
        RoomEnrollmentNoticeRepository(db).save("learner", EnrollmentNotice("class", "Science", EnrollmentStatus.PENDING))
        db.close(); db = open()
        val saved = RoomEnrollmentNoticeRepository(db).notices("learner").first().single()
        assertEquals("Science", saved.title)
        assertEquals(EnrollmentStatus.PENDING, saved.status)
        assertTrue(db.rosterDao().observeByClassroom("class").first().isEmpty())
    }
    @Test fun restrictiveStatusSurvivesReopenAndPreservesClassTitle(): Unit = runBlocking {
        val repo = RoomEnrollmentNoticeRepository(db)
        repo.save("learner", EnrollmentNotice("class", "Science", EnrollmentStatus.ACTIVE))
        repo.save("learner", EnrollmentNotice("class", null, EnrollmentStatus.REMOVED))
        db.close(); db = open()
        val saved = RoomEnrollmentNoticeRepository(db).notices("learner").first().single()
        assertEquals(EnrollmentStatus.REMOVED, saved.status)
        assertEquals("Science", saved.title)
    }
    @Test fun noticesAreScopedByAccountAndHubAndMalformedEntriesAreIgnored(): Unit = runBlocking {
        val repo = RoomEnrollmentNoticeRepository(db)
        repo.save("learner", EnrollmentNotice("class", "Science", EnrollmentStatus.PENDING))
        db.syncStateDao().put(SyncStateEntity("enrollment_notice:learner:bad", "invalid-json", 1))
        assertEquals(1, repo.notices("learner").first().size)
        assertTrue(repo.notices("another").first().isEmpty())
        db.syncStateDao().put(SyncStateEntity("hub_id", "different-hub", 2))
        assertTrue(repo.notices("learner").first().isEmpty())
        try { repo.save("another", EnrollmentNotice("class", null, EnrollmentStatus.ACTIVE)); fail() }
        catch (_: IllegalStateException) { }
    }
}
