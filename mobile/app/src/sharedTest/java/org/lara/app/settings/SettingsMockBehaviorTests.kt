package org.lara.app.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.lara.app.data.local.AccountDatabases
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*
import org.lara.app.data.sync.*

abstract class SettingsMockBehaviorTests {
    protected abstract fun enabled(): Boolean
    @Test fun logoutAndSwitchKeepAccountCaches(): Unit = runBlocking {
        assumeTrue("Requires isolated mock on 38080/38081", enabled())
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 38080, 38081))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefix = "am-${java.util.UUID.randomUUID().toString().take(12)}-"
        val accounts = AccountDatabases(context, prefix)
        var saved: StoredToken? = null
        val tokens = object : SessionTokenStore {
            override fun load() = saved
            override fun save(value: StoredToken) { saved = value }
            override fun clear() { saved = null }
        }
        try {
            val auth = AuthRepository(AuthApi(), tokens) { accounts.prepare(it).authDao() }
            val user = auth.authenticate(hub, "123456789012", "1234", null)
            val db = accounts.prepared(user.id)
            val old = requireNotNull(saved)
            val api = EnrollmentApi(HubClient(), tokens, { hub })
            val sync = SyncRepository(db, HubSyncTransport(api, HubClient()))
            sync.pull(user.id)
            val classroom = db.classroomDao().observeAll().first().single { it.classCode == "K7M4QX" }
            val post = db.streamDao().observeByClassroom(classroom.id).first().first()
            val comment = RoomStreamRepository(db.streamDao()).addComment(post.id, user.id, "Synthetic saved question")
            try { auth.authenticate(hub, "T-0001", "1234", null); fail() }
            catch (failure: AuthFailure) { assertEquals(409, failure.status) }
            val settings = SettingsRepository(db.authDao(), tokens, sync, { hub })
            assertEquals(SignOutResult.NeedsConfirmation(1), settings.signOut(user.id))
            assertEquals(SignOutResult.SignedOut, settings.signOut(user.id, 1))
            assertNull(auth.restore(hub))
            try { HubClient().enrollment(hub, old, "GET"); fail() }
            catch (failure: HubFailure) { assertEquals(401, failure.status) }
            val teacher = auth.authenticate(hub, "T-0001", "1234", null)
            val teacherDb = accounts.prepared(teacher.id)
            assertNotSame(db, teacherDb)
            assertNull(teacherDb.authDao().user(user.id))
            assertNull(teacherDb.syncStateDao().get("cursor"))
            assertEquals(1, db.authDao().queuedCount(user.id))
            val teacherSync = SyncRepository(teacherDb, HubSyncTransport(api, HubClient()))
            assertEquals(SignOutResult.SignedOut, SettingsRepository(teacherDb.authDao(), tokens, teacherSync, { hub }).signOut(teacher.id))
            assertEquals(user.id, auth.authenticate(hub, "123456789012", "1234", null).id)
            assertEquals(user.id, auth.restore(hub)?.id)
            assertEquals(1, db.authDao().queuedCount(user.id))
            assertEquals(comment, db.syncDao().queuedComments(user.id).single().id)
            assertEquals("QUEUED_FOR_SYNC", db.syncDao().queuedComments(user.id).single().syncStatus)
        } finally {
            accounts.close()
            context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
        }
    }
}
