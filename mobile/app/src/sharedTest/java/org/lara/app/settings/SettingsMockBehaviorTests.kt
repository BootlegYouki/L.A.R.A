package org.lara.app.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*
import org.lara.app.data.sync.*

abstract class SettingsMockBehaviorTests {
    protected abstract fun enabled(): Boolean
    @Test fun actualLogoutRevokesTokenAndSameUserSignInKeepsQueuedComment(): Unit = runBlocking {
        assumeTrue("Requires isolated mock on 38080/38081", enabled())
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 38080, 38081))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        var saved: StoredToken? = null
        val tokens = object : SessionTokenStore {
            override fun load() = saved
            override fun save(value: StoredToken) { saved = value }
            override fun clear() { saved = null }
        }
        try {
            val auth = AuthRepository(AuthApi(), tokens, db.authDao())
            val user = auth.authenticate(hub, "123456789012", "1234", null)
            val old = requireNotNull(saved)
            val api = EnrollmentApi(HubClient(), tokens, { hub })
            val sync = SyncRepository(db, HubSyncTransport(api, HubClient()))
            sync.pull(user.id)
            val classroom = db.classroomDao().observeAll().first().single { it.classCode == "K7M4QX" }
            val post = db.streamDao().observeByClassroom(classroom.id).first().first()
            val comment = RoomStreamRepository(db.streamDao()).addComment(post.id, user.id, "Synthetic saved question")
            val settings = SettingsRepository(db.authDao(), tokens, sync, { hub })
            assertEquals(SignOutResult.NeedsConfirmation(1), settings.signOut(user.id))
            assertEquals(SignOutResult.SignedOut, settings.signOut(user.id, 1))
            assertNull(auth.restore(hub))
            try { HubClient().enrollment(hub, old, "GET"); fail() }
            catch (failure: HubFailure) { assertEquals(401, failure.status) }
            assertEquals(user.id, auth.authenticate(hub, "123456789012", "1234", null).id)
            assertEquals(user.id, auth.restore(hub)?.id)
            assertEquals(1, db.authDao().queuedCount(user.id))
            assertEquals(comment, db.syncDao().queuedComments(user.id).single().id)
            assertEquals("QUEUED_FOR_SYNC", db.syncDao().queuedComments(user.id).single().syncStatus)
        } finally { db.close() }
    }
}
