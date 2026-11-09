package org.lara.app.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.dto.*
import org.lara.app.data.remote.discovery.*
import org.lara.app.data.repository.*
import org.lara.app.data.sync.*
import org.lara.app.ui.screens.settings.SettingsViewModel

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class SettingsViewModelTest {
    private lateinit var db: LaraDatabase
    private lateinit var model: SettingsViewModel
    private var token: StoredToken? = null
    private var signOuts = 0
    private var hubChanges = 0
    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1"))
        db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        db.authDao().saveProfile(UserEntity("learner", "synthetic", "Learner", "STUDENT", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms=listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)),
            announcements=listOf(AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1))))
        queue("first")
        token = StoredToken("synthetic", "learner", Long.MAX_VALUE, hub.restBase)
        val tokens = object : SessionTokenStore {
            override fun load() = token
            override fun save(value: StoredToken) { token = value }
            override fun clear() { token = null }
        }
        val transport = object : SyncTransport {
            override suspend fun pull(userId: String, request: SyncPullRequestDto): SyncPullResponseDto = error("Unexpected sync")
            override suspend fun push(userId: String, request: SyncPushRequestDto): SyncPushResponseDto = error("Unexpected sync")
        }
        val repository = SettingsRepository(db.authDao(), tokens, SyncRepository(db, transport), { hub }) { _, _ -> }
        model = SettingsViewModel("learner", repository, HubEndpointStore(context),
            onChangeHub={ hubChanges++ }) { signOuts++ }
        model.refresh()
    }
    @After fun close() { model.viewModelScope.cancel(); db.close(); Dispatchers.resetMain() }
    private suspend fun queue(id: String) = db.cacheDao().upsertBatch(CacheBatch(announcementComments=listOf(
        AnnouncementCommentEntity(id, "post", "learner", "Saved", 1, 1, "QUEUED_FOR_SYNC"))))
    private suspend fun warning(count: Int) = withTimeout(5_000) { model.state.first { !it.busy && it.queued == count } }
    @Test fun changingHubKeepsItsIntentWhenNewWorkRequiresConfirmationAgain(): Unit = runBlocking {
        model.signOut(changeHub=true); warning(1)
        queue("second")
        model.signOut(true); warning(2)
        assertNotNull(token); assertEquals(0, hubChanges)
        model.signOut(true)
        withTimeout(5_000) { model.state.first { !it.busy && it.queued == null } }
        assertNull(token); assertEquals(1, hubChanges); assertEquals(0, signOuts)
        assertEquals(2, db.authDao().queuedCount("learner"))
    }
    @Test fun cancelingHubChangeCannotRedirectALaterNormalSignOut(): Unit = runBlocking {
        model.signOut(changeHub=true); warning(1)
        model.cancelSignOut()
        assertNotNull(token)
        model.signOut(); warning(1)
        model.signOut(true)
        withTimeout(5_000) { model.state.first { !it.busy && it.queued == null } }
        assertEquals(1, signOuts); assertEquals(0, hubChanges)
    }
}
