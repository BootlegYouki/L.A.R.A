package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.repository.RoomEnrollmentRepository
import org.lara.app.domain.Cached

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EnrollmentRoomTest {
    private lateinit var db: LaraDatabase
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun close() { scope.cancel(); db.close() }
    @Test fun authoritativeEnrollmentUnlocksAndRemainsReadableWithoutAnyNetwork() = runBlocking {
        val classroom = ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)
        val enrollment = EnrollmentEntity("server-enrollment-id", "class", "learner", "PENDING", 1, 1)
        db.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), enrollments = listOf(enrollment)))
        val rows = RoomEnrollmentRepository(db.rosterDao()).learnerClasses("learner", scope)
        val pending = withTimeout(10_000) { rows.first { it is Cached.Content && it.value.isNotEmpty() } } as Cached.Content
        assertFalse(pending.value.single().canOpen)
        db.cacheDao().upsertBatch(CacheBatch(enrollments = listOf(enrollment.copy(status = "ACTIVE", updatedAt = 2))))
        val active = withTimeout(10_000) { rows.first { it is Cached.Content && it.value.singleOrNull()?.canOpen == true } } as Cached.Content
        assertEquals("server-enrollment-id", active.value.single().enrollment.id)
        val restored = RoomEnrollmentRepository(db.rosterDao()).learnerClasses("learner", scope)
        val saved = withTimeout(10_000) { restored.first { it is Cached.Content && it.value.isNotEmpty() } } as Cached.Content
        assertTrue(saved.value.single().canOpen)
    }
    @Test fun anotherLearnersEnrollmentIsNeverShown() = runBlocking {
        val classroom = ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)
        db.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), enrollments = listOf(
            EnrollmentEntity("mine", "class", "learner", "PENDING", 1, 1),
            EnrollmentEntity("foreign", "class", "other", "ACTIVE", 1, 1))))
        val rows = RoomEnrollmentRepository(db.rosterDao()).learnerClasses("learner", scope)
        val state = withTimeout(10_000) { rows.first { it is Cached.Content && it.value.isNotEmpty() } } as Cached.Content
        assertEquals(listOf("mine"), state.value.map { it.enrollment.id })
        assertFalse(state.value.single().canOpen)
    }
}
