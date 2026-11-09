package org.lara.app.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.local.*
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import java.util.UUID

abstract class AccountCacheTests {
    @Test fun legacyCacheStaysPrivateAcrossSwitch(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefix = "ac-${UUID.randomUUID().toString().take(12)}-"
        var accounts = AccountDatabases(context, prefix)
        try {
            val old = LaraDatabase.create(context, "${prefix}lara.db")
            old.authDao().saveProfile(UserEntity("teacher", "synthetic-teacher", "Teacher", "TEACHER", 1, 1))
            old.cacheDao().upsertBatch(CacheBatch(
                users=listOf(UserEntity("roster-learner", "synthetic-private-identifier", "Roster learner", "STUDENT", 1, 1)),
                classrooms=listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)),
                announcements=listOf(AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1)),
                announcementComments=listOf(AnnouncementCommentEntity("comment", "post", "teacher", "Saved question", 1, 1, "QUEUED_FOR_SYNC")),
                syncState=listOf(SyncStateEntity("cursor", "42", 1))))
            old.close()
            val learner = accounts.prepare("learner")
            assertNull(learner.authDao().user("teacher"))
            assertNull(learner.authDao().user("roster-learner"))
            assertNull(learner.syncStateDao().get("cursor"))
            learner.authDao().saveProfile(UserEntity("learner", "synthetic-own", "Learner", "STUDENT", 1, 1))
            learner.syncStateDao().put(SyncStateEntity("cursor", "7", 1))
            val teacher = accounts.prepare("teacher")
            assertNotSame(teacher, learner)
            assertEquals("42", teacher.syncStateDao().get("cursor")?.value)
            assertEquals(1, teacher.authDao().queuedCount("teacher"))
            accounts.close(); accounts = AccountDatabases(context, prefix)
            assertEquals("learner", accounts.prepare("learner").authDao().currentUserId())
            assertEquals("7", accounts.prepare("learner").syncStateDao().get("cursor")?.value)
            assertNull(accounts.prepare("learner").authDao().user("roster-learner"))
            assertEquals(1, accounts.prepare("teacher").authDao().queuedCount("teacher"))
            assertEquals("synthetic-private-identifier", accounts.prepare("teacher").authDao().user("roster-learner")?.lrnOrId)
        } finally {
            accounts.close()
            context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
        }
    }
    @Test fun firstAccountsNeverShareAMirror(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefix = "ac-${UUID.randomUUID().toString().take(12)}-"
        val accounts = AccountDatabases(context, prefix)
        try {
            val first = async(Dispatchers.IO) { accounts.prepare("first") }
            val second = async(Dispatchers.IO) { accounts.prepare("second") }
            assertNotSame(first.await(), second.await())
            assertNotEquals(first.await().openHelper.databaseName, second.await().openHelper.databaseName)
        } finally {
            accounts.close()
            context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
        }
    }
    @Test fun unownedDataIsNotClaimed(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefix = "ac-${UUID.randomUUID().toString().take(12)}-"
        val accounts = AccountDatabases(context, prefix)
        val old = LaraDatabase.create(context, "${prefix}lara.db")
        try {
            old.cacheDao().upsertBatch(CacheBatch(users=listOf(UserEntity("unowned", "synthetic-private", "Teacher", "TEACHER", 1, 1))))
            assertNull(old.authDao().currentUserId())
            assertNull(accounts.prepare("new-learner").authDao().user("unowned"))
            assertNotNull(old.authDao().user("unowned"))
        } finally {
            old.close(); accounts.close()
            context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
        }
    }
}
