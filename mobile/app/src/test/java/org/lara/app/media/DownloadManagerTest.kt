package org.lara.app.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lara.app.data.media.*
import org.lara.app.domain.*

class DownloadManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
    private class Reply(override val status: Int, private val headers: Map<String, String>, override val input: InputStream) : MediaResponse {
        var closed = false
        override fun header(name: String) = headers[name]
        override fun close() { closed = true; input.close() }
    }
    private fun reply(start: Long, end: Long, data: ByteArray = bytes, tag: String = "\"v1\"") = Reply(206,
        mapOf("Content-Range" to "bytes $start-$end/${data.size}", "Content-Length" to "${end - start + 1}", "ETag" to tag),
        ByteArrayInputStream(data.copyOfRange(start.toInt(), end.toInt() + 1)))
    private fun root() = folder.newFolder()

    @Test fun boundedRangesProgressAndAtomicCompletion(): Unit = runBlocking {
        val root = root(); val ranges = mutableListOf<Pair<Long, Long>>(); val replies = mutableListOf<Reply>()
        val manager = DownloadManager(root, { _, start, end, _ ->
            assertFalse(File(root, "lesson.video").exists())
            ranges += start to end; reply(start, end).also { replies += it }
        })
        val progress = mutableListOf<VideoDownload>()
        val file = manager.download("material", "lesson", bytes.size.toLong(), progress::add)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(3, ranges.size)
        assertTrue(ranges.all { it.second - it.first < DownloadManager.CHUNK })
        assertEquals(bytes.size.toLong(), progress.last().bytes)
        assertTrue(replies.all { it.closed })
        assertFalse(File(root, "lesson.part").exists())
        manager.download("material", "lesson", bytes.size.toLong()) {}
        assertEquals(3, ranges.size)
    }
    @Test fun interruptionSurvivesManagerRecreationAndUsesIfRange(): Unit = runBlocking {
        val root = root(); var calls = 0
        val first = DownloadManager(root, { _, start, end, _ ->
            calls++
            if (calls == 2) throw IOException("Disconnected")
            reply(start, end)
        })
        try { first.download("material", "lesson", bytes.size.toLong()) {}; fail() } catch (_: IOException) { }
        assertEquals(DownloadManager.CHUNK, first.progress("lesson", bytes.size.toLong()).bytes)
        val resumed = mutableListOf<Long>()
        val second = DownloadManager(root, { _, start, end, validator ->
            resumed += start; assertEquals("\"v1\"", validator); reply(start, end)
        })
        assertArrayEquals(bytes, second.download("material", "lesson", bytes.size.toLong()) {}.readBytes())
        assertEquals(DownloadManager.CHUNK, resumed.first())
    }
    @Test fun cancellationKeepsExactByteCountAndClosesResponse(): Unit = runBlocking {
        val root = root(); var response: Reply? = null
        val manager = DownloadManager(root, { _, start, end, _ -> reply(start, end).also { response = it } })
        try {
            manager.download("material", "lesson", bytes.size.toLong()) { throw CancellationException() }; fail()
        } catch (_: CancellationException) { }
        assertTrue(requireNotNull(response).closed)
        assertEquals(32_768, manager.progress("lesson", bytes.size.toLong()).bytes)
        assertFalse(File(root, "lesson.video").exists())
        val next = DownloadManager(root, { _, start, end, _ -> reply(start, end) })
        assertArrayEquals(bytes, next.download("material", "lesson", bytes.size.toLong()) {}.readBytes())
    }
    @Test fun ignoredResumeRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        val root = root(); File(root, "lesson.part").writeBytes(bytes.copyOfRange(0, 100_000))
        val manager = DownloadManager(root, { _, start, _, _ ->
            assertEquals(100_000L, start)
            Reply(200, mapOf("Content-Length" to "${bytes.size}"), ByteArrayInputStream(bytes))
        })
        assertArrayEquals(bytes, manager.download("material", "lesson", bytes.size.toLong()) {}.readBytes())
    }
    @Test fun truncatedResponseRetainsOnlyReceivedBytes(): Unit = runBlocking {
        val root = root()
        val manager = DownloadManager(root, { _, _, _, _ -> Reply(206,
            mapOf("Content-Range" to "bytes 0-262143/${bytes.size}", "Content-Length" to "262144"),
            ByteArrayInputStream(bytes.copyOfRange(0, 12345))) })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() } catch (_: IOException) { }
        assertEquals(12345, manager.progress("lesson", bytes.size.toLong()).bytes)
        assertFalse(File(root, "lesson.video").exists())
    }
    @Test fun changedValidatorNeverAppendsNewRepresentation(): Unit = runBlocking {
        val root = root(); File(root, "lesson.part").writeBytes(bytes.copyOfRange(0, 100))
        File(root, "lesson.validator").writeText("\"v1\"")
        val manager = DownloadManager(root, { _, start, end, _ -> reply(start, end, tag = "\"v2\"") })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.CHANGED, e.problem) }
        assertFalse(File(root, "lesson.part").exists())
    }
    @Test fun unsatisfiableRangeDiscardsIncompatiblePartial(): Unit = runBlocking {
        val root = root(); File(root, "lesson.part").writeBytes(bytes.copyOfRange(0, 100))
        val manager = DownloadManager(root, { _, _, _, _ -> Reply(416, mapOf("Content-Range" to "bytes */50"), ByteArrayInputStream(byteArrayOf())) })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.CHANGED, e.problem) }
        assertFalse(File(root, "lesson.part").exists())
    }
    @Test fun completePartialFinalizesWithoutAnotherRequest(): Unit = runBlocking {
        val root = root(); File(root, "lesson.part").writeBytes(bytes)
        val manager = DownloadManager(root, { _, _, _, _ -> error("No network needed") })
        assertArrayEquals(bytes, manager.download("material", "lesson", bytes.size.toLong()) {}.readBytes())
    }
    @Test fun corruptFileIsNotFinalized(): Unit = runBlocking {
        val root = root(); val invalid = ByteArray(bytes.size)
        val manager = DownloadManager(root, { _, start, end, _ -> reply(start, end, invalid) })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.INVALID_FILE, e.problem) }
        assertFalse(File(root, "lesson.video").exists()); assertFalse(File(root, "lesson.part").exists())
    }
    @Test fun insufficientDiskSpaceStopsBeforeRequest(): Unit = runBlocking {
        val manager = DownloadManager(root(), { _, _, _, _ -> error("No network needed") }, { 1L })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.STORAGE, e.problem) }
    }
    @Test fun mismatchedRangeDoesNotWriteBytes(): Unit = runBlocking {
        val root = root()
        val manager = DownloadManager(root, { _, _, _, _ -> reply(1, 100) })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.INVALID_FILE, e.problem) }
        assertEquals(0, manager.progress("lesson", bytes.size.toLong()).bytes)
    }
    @Test fun cancellationClosesABlockedBodyPromptly(): Unit = runBlocking {
        val reading = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { reading.countDown(); closed.await(5, java.util.concurrent.TimeUnit.SECONDS); return -1 }
            override fun close() { closed.countDown() }
        }
        val manager = DownloadManager(root(), { _, _, _, _ -> Reply(206,
            mapOf("Content-Range" to "bytes 0-262143/${bytes.size}", "Content-Length" to "262144"), input) })
        val job = launch(Dispatchers.IO) { manager.download("material", "lesson", bytes.size.toLong()) {} }
        assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0, closed.count)
    }
    @Test fun unwritableValidatorIsAStorageError(): Unit = runBlocking {
        val root = root(); File(root, "lesson.validator").mkdir()
        val manager = DownloadManager(root, { _, start, end, _ -> reply(start, end) })
        try { manager.download("material", "lesson", bytes.size.toLong()) {}; fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.STORAGE, e.problem) }
        assertFalse(File(root, "lesson.video").exists())
    }
}
