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
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.repository.*
import org.lara.app.domain.enrollment.EnrollmentStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SavedRosterRefresherTest {
    private lateinit var db: LaraDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    private suspend fun seed() {
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
            users = listOf(UserEntity("learner", "synthetic", "Old name", "STUDENT", 1, 1)),
            enrollments = listOf(EnrollmentEntity("real-server-id", "class", "learner", "PENDING", 1, 1))))
    }
    private fun remote(rows: List<TeacherRosterEntry>, fail: Boolean = false) = object : ClassManagementRepository {
        override suspend fun roster(classroomId: String): List<TeacherRosterEntry> {
            if (fail) throw java.io.IOException("Hub unavailable")
            return rows
        }
        override suspend fun decide(classroomId: String, studentId: String, accept: Boolean) = Unit
    }
    @Test fun savedStatusAndTeacherVisibleIdentityPersistWithoutInventingEnrollmentIds() = runBlocking {
        seed()
        SavedRosterRefresher(db, remote(listOf(TeacherRosterEntry("learner", "New name", "synthetic", EnrollmentStatus.ACTIVE)))).refresh("class")
        val saved = db.rosterDao().observeByClassroom("class").first().single()
        assertEquals("real-server-id", saved.enrollment.id)
        assertEquals("ACTIVE", saved.enrollment.status)
        assertEquals("New name", saved.user?.fullName)
        assertEquals(1L, saved.enrollment.joinedAt)
        assertEquals(1L, saved.enrollment.updatedAt)
    }
    @Test fun unknownEnrollmentIsReportedAndNeverInsertedWhileKnownRowsRefresh() = runBlocking {
        seed()
        val rows = listOf(TeacherRosterEntry("learner", "Test", "synthetic", EnrollmentStatus.REJECTED),
            TeacherRosterEntry("new-learner", "New", "synthetic", EnrollmentStatus.PENDING))
        try { SavedRosterRefresher(db, remote(rows)).refresh("class"); fail() } catch (_: UnsavedRosterEntries) { }
        val saved = db.rosterDao().observeByClassroom("class").first()
        assertEquals(listOf("real-server-id"), saved.map { it.enrollment.id })
        assertEquals("REJECTED", saved.single().enrollment.status)
    }
    @Test fun failedRequestLeavesCachedPendingRosterReadable() = runBlocking {
        seed()
        try { SavedRosterRefresher(db, remote(emptyList(), fail = true)).refresh("class"); fail() }
        catch (_: java.io.IOException) { }
        assertEquals("PENDING", db.rosterDao().observeByClassroom("class").first().single().enrollment.status)
    }
}
