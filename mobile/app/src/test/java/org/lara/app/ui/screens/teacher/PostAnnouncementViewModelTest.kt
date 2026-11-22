package org.lara.app.ui.screens.teacher

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostAnnouncementViewModelTest {
    @Test fun retriesOnlyAttachmentsThatHaveNotBeenQueued() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var posts = 0
            val attempts = mutableListOf<String>()
            val model = PostAnnouncementViewModel(
                post = { _, _, _ -> posts++; "announcement" },
                refreshStream = {},
                queueAttachment = { id, uri, _ ->
                    assertEquals("announcement", id)
                    attempts.add(uri)
                    if (attempts == listOf("one", "two")) throw IOException()
                },
            )
            val attachments = mapOf("one" to "One", "two" to "Two")
            model.send("Title", "Content", false, true, attachments)
            runCurrent()
            assertEquals(AnnouncementStatus.Failed, model.status.value)
            model.send("Title", "Content", false, true, attachments)
            runCurrent()
            assertEquals(AnnouncementStatus.Posted, model.status.value)
            assertEquals(1, posts)
            assertEquals(listOf("one", "two", "two"), attempts)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun offlineAndRepeatedTapsDoNotPostAndOnlySuccessRefreshesStream() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var refreshes = 0
            val reply = CompletableDeferred<Unit>()
            val model = PostAnnouncementViewModel(post = { _, _, comments ->
                assertEquals(false, comments)
                if (++requests == 1) throw IOException()
                reply.await()
                "announcement"
            }, refreshStream = { refreshes++ })

            model.send("Reminder", "Bring your notes", false, reachable = false)
            runCurrent()
            assertEquals(0, requests)

            model.send("Reminder", "Bring your notes", false, reachable = true)
            runCurrent()
            assertEquals(AnnouncementStatus.Failed, model.status.value)
            assertEquals(0, refreshes)

            repeat(2) { model.send("Reminder", "Bring your notes", false, reachable = true) }
            runCurrent()
            assertEquals(2, requests)
            assertEquals(AnnouncementStatus.Posting, model.status.value)
            reply.complete(Unit)
            runCurrent()
            assertEquals(AnnouncementStatus.Posted, model.status.value)
            assertEquals(1, refreshes)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
