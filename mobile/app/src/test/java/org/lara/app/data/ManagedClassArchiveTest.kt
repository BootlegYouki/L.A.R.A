package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.ClassroomEntity
import org.lara.app.data.repository.RoomClassroomRepository
import org.lara.app.domain.Cached
import org.lara.app.ui.screens.teacher.classes.canManage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ManagedClassArchiveTest {
    @Test fun archivedClassRemainsReadableInManagementAndNeverBecomesEditable(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            db.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(
                ClassroomEntity("active", "Active", "A", "K7M4QX", "owner", createdAt = 1, updatedAt = 1),
                ClassroomEntity("archive", "Archived", "B", "B7M4QX", "owner", archivedAt = 2, createdAt = 1, updatedAt = 2))))
            val active = RoomClassroomRepository(db.classroomDao()).classrooms(scope).first { it is Cached.Content }
            assertEquals(listOf("active"), (active as Cached.Content).value.map { it.id })
            val managed = RoomClassroomRepository(db.classroomDao(), includeArchived = true).classrooms(scope).first { it is Cached.Content }
            val rows = (managed as Cached.Content).value
            assertEquals(setOf("active", "archive"), rows.map { it.id }.toSet())
            assertFalse(rows.single { it.id == "archive" }.canManage("owner", true))
            assertFalse(rows.single { it.id == "archive" }.canManage("owner", false))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }
}
