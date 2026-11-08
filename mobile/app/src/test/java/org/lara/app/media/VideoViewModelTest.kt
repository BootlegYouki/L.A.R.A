package org.lara.app.media

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.lara.app.domain.*
import org.lara.app.domain.model.Material
import org.lara.app.ui.screens.classwork.VideoViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var calls = 0
    private var fail = false
    private var invalid = false
    private val released = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    private val repository = object : VideoRepository {
        override fun observe(id: String) = MutableStateFlow<Material?>(null)
        override suspend fun source(id: String) = VideoSource.Hub(id, 1000)
        override suspend fun progress(id: String) = VideoDownload(DownloadPhase.PAUSED, 200, 1000)
        override suspend fun save(id: String, progress: (VideoDownload) -> Unit) {
            calls++
            if (fail) throw IOException()
            if (invalid) throw VideoFailure(VideoProblem.INVALID_FILE)
            try {
                progress(VideoDownload(DownloadPhase.DOWNLOADING, 500, 1000))
                gate.await()
                progress(VideoDownload(DownloadPhase.COMPLETE, 1000, 1000))
            } finally { released.complete(Unit) }
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun pauseCancelsTransferAndStopsRetries() = runTest(dispatcher) {
        val model = VideoViewModel(repository, "lesson")
        model.save(); runCurrent()
        model.save(); runCurrent()
        assertEquals(1, calls)
        model.pause(); runCurrent()
        assertTrue(released.isCompleted)
        assertEquals(DownloadPhase.PAUSED, model.download.value.phase)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, calls)
    }
    @Test fun wifiFailureRetriesThenCompletesWithoutDuplicateJobs() = runTest(dispatcher) {
        fail = true
        val model = VideoViewModel(repository, "lesson")
        model.save(); runCurrent()
        assertEquals(DownloadPhase.PAUSED, model.download.value.phase)
        assertEquals(VideoProblem.HUB_UNAVAILABLE, model.download.value.problem)
        fail = false; gate.complete(Unit)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, calls)
        assertEquals(DownloadPhase.COMPLETE, model.download.value.phase)
    }
    @Test fun stopReleasesTransferAndStartResumesOnlyRequestedSave() = runTest(dispatcher) {
        val model = VideoViewModel(repository, "lesson")
        model.save(); runCurrent(); model.stop(); runCurrent()
        assertTrue(released.isCompleted)
        gate.complete(Unit)
        model.start(); runCurrent()
        assertEquals(2, calls)
        assertEquals(DownloadPhase.COMPLETE, model.download.value.phase)
        model.stop()
    }
    @Test fun invalidFileDoesNotEnterAutomaticRetryLoop() = runTest(dispatcher) {
        invalid = true
        val model = VideoViewModel(repository, "lesson")
        model.save(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, calls)
        assertEquals(DownloadPhase.FAILED, model.download.value.phase)
        model.stop()
    }
}
