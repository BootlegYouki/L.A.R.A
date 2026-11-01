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
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.remote.dto.ClassroomDto
import org.lara.app.data.repository.RoomLearnerClassAccess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RoomLearnerClassAccessTest {
    private lateinit var db: LaraDatabase
    private var hub = requireNotNull(HubEndpoint.lan("127.0.0.1"))
    private val classroom = ClassroomDto("class", "Science", "A", "K7M4QX", "teacher", enrollmentStatus = "PENDING")
    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        db.authDao().saveProfile(UserEntity("learner", "synthetic", "Learner", "STUDENT", 1, 1))
    }
    @After fun close() { db.close() }
    @Test fun ownRestApprovalUnlocksWithoutInventingAnEnrollmentRow() = runBlocking {
        val access = RoomLearnerClassAccess(db) { hub }
        access.save("learner", listOf(classroom))
        assertFalse(requireNotNull(access.classes("learner").first()).single().canOpen)
        access.save("learner", listOf(classroom.copy(enrollmentStatus = "ACTIVE")))
        val saved = requireNotNull(access.classes("learner").first()).single()
        assertTrue(saved.canOpen)
        assertNull(saved.enrollment)
        assertTrue(db.rosterDao().observeForStudent("learner").first().isEmpty())
        assertNull(db.syncStateDao().get("cursor"))
    }
    @Test fun authoritativeEmptyListClosesRemovedClassAccess() = runBlocking {
        val access = RoomLearnerClassAccess(db) { hub }
        access.save("learner", listOf(classroom.copy(enrollmentStatus = "ACTIVE")))
        access.save("learner", emptyList())
        assertTrue(requireNotNull(access.classes("learner").first()).isEmpty())
        assertEquals("Science", db.classroomDao().observeAll().first().single().name)
    }
    @Test fun wrongHubOrAccountDoesNotReuseSavedAccess() = runBlocking {
        val access = RoomLearnerClassAccess(db) { hub }
        access.save("learner", listOf(classroom.copy(enrollmentStatus = "ACTIVE")))
        hub = requireNotNull(HubEndpoint.lan("127.0.0.2"))
        assertNull(access.classes("learner").first())
        assertNull(access.classes("other").first())
        try { access.save("learner", listOf(classroom), "http://127.0.0.1:8080"); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun unknownStatusRollsBackTheWholeSnapshot() = runBlocking {
        val access = RoomLearnerClassAccess(db) { hub }
        access.save("learner", listOf(classroom.copy(enrollmentStatus = "ACTIVE")))
        try { access.save("learner", listOf(classroom.copy(name = "Wrong", enrollmentStatus = "UNKNOWN"))); fail() }
        catch (_: IllegalArgumentException) { }
        val saved = requireNotNull(access.classes("learner").first()).single()
        assertTrue(saved.canOpen)
        assertEquals("Science", saved.classroom.name)
    }
}
