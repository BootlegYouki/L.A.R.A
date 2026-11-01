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
        db.authDao().saveProfile(UserEntity("teacher", "synthetic-teacher", "Teacher", "TEACHER", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
            users = listOf(UserEntity("learner", "synthetic", "Old name", "STUDENT", 1, 1)),
            enrollments = listOf(EnrollmentEntity("real-server-id", "class", "learner", "PENDING", 1, 1))))
    }
    private fun remote(rows: List<TeacherRosterEntry>, fail: Boolean = false,
        classes: List<org.lara.app.data.remote.dto.ClassroomDto> = emptyList()) = object : ClassManagementRepository {
        override suspend fun classrooms() = classes
        override suspend fun roster(classroomId: String): List<TeacherRosterEntry> {
            if (fail) throw java.io.IOException("Hub unavailable")
            return rows
        }
        override suspend fun decide(classroomId: String, studentId: String, accept: Boolean) = Unit
    }
    private fun row(id: String = "real-server-id", student: String = "learner", status: EnrollmentStatus = EnrollmentStatus.ACTIVE) =
        TeacherRosterEntry(student, "New name", "synthetic", status, enrollmentId = id)
    @Test fun savedStatusAndIdentityUseTheRosterEnrollmentId() = runBlocking {
        seed()
        SavedRosterRefresher(db, remote(listOf(row())), "teacher").refresh("class")
        val saved = db.rosterDao().observeByClassroom("class").first().single()
        assertEquals("real-server-id", saved.enrollment.id)
        assertEquals("ACTIVE", saved.enrollment.status)
        assertEquals("New name", saved.user?.fullName)
        assertEquals(1L, saved.enrollment.joinedAt)
        assertTrue(saved.enrollment.updatedAt > 1)
    }
    @Test fun newPendingRowAndProfileAreInsertedWithoutSync() = runBlocking {
        seed()
        SavedRosterRefresher(db, remote(listOf(row("new-server-id", "new-learner", EnrollmentStatus.PENDING))), "teacher").refresh("class")
        val saved = db.rosterDao().observeByClassroom("class").first().single { it.enrollment.studentId == "new-learner" }
        assertEquals("new-server-id", saved.enrollment.id)
        assertEquals("synthetic", saved.user?.lrnOrId)
        assertNull(db.syncStateDao().get("cursor"))
    }
    @Test fun freshTeacherClassComesFromRestBeforeAnyRoster() = runBlocking {
        db.authDao().saveProfile(UserEntity("teacher", "synthetic-teacher", "Teacher", "TEACHER", 1, 1))
        val classes = listOf(org.lara.app.data.remote.dto.ClassroomDto("class", "Science", "A", "K7M4QX", "teacher"))
        val refresher = SavedRosterRefresher(db, remote(listOf(row(status = EnrollmentStatus.PENDING)), classes = classes), "teacher")
        refresher.refreshClasses(); refresher.refresh("class")
        assertEquals("Science", db.classroomDao().observeAll().first().single().name)
        assertEquals("real-server-id", db.rosterDao().observeByClassroom("class").first().single().enrollment.id)
        assertNull(db.syncStateDao().get("cursor"))
    }
    @Test fun conflictingEnrollmentIdentityRollsBackNamesAndStatuses() = runBlocking {
        seed()
        try { SavedRosterRefresher(db, remote(listOf(row("wrong-id"))), "teacher").refresh("class"); fail() }
        catch (_: IllegalArgumentException) { }
        val saved = db.rosterDao().observeByClassroom("class").first().single()
        assertEquals("real-server-id", saved.enrollment.id)
        assertEquals("PENDING", saved.enrollment.status)
        assertEquals("Old name", saved.user?.fullName)
    }
    @Test fun staleActorCannotSaveARosterAfterAccountChanges() = runBlocking {
        seed()
        db.authDao().saveProfile(UserEntity("other", "synthetic-teacher-2", "Other", "TEACHER", 1, 1))
        try { SavedRosterRefresher(db, remote(listOf(row())), "teacher").refresh("class"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals("PENDING", db.rosterDao().observeByClassroom("class").first().single().enrollment.status)
    }
    @Test fun failedRequestLeavesCachedPendingRosterReadable() = runBlocking {
        seed()
        try { SavedRosterRefresher(db, remote(emptyList(), fail = true), "teacher").refresh("class"); fail() }
        catch (_: java.io.IOException) { }
        assertEquals("PENDING", db.rosterDao().observeByClassroom("class").first().single().enrollment.status)
    }
}
