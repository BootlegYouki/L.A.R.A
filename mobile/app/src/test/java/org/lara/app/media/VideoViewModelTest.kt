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
        override suspend fun remove(id: String) {}
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

    @Test fun cancelledTransferCannotReportFailureOrOverwriteResumedProgress() = runTest(dispatcher) {
        val finishingCancelled = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        var attempts = 0
        val controlled = object : VideoRepository {
            override suspend fun remove(id: String) {}
        override fun observe(id: String) = MutableStateFlow<Material?>(null)
            override suspend fun source(id: String) = VideoSource.Hub(id, 1000)
            override suspend fun progress(id: String) = VideoDownload(DownloadPhase.PAUSED, 500, 1000)
            override suspend fun save(id: String, progress: (VideoDownload) -> Unit) {
                if (++attempts == 1) {
                    progress(VideoDownload(DownloadPhase.DOWNLOADING, 500, 1000))
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) { finishingCancelled.await() }
                        // Model a blocking transport's late callback and non-cancellation exception.
                        progress(VideoDownload(DownloadPhase.DOWNLOADING, 600, 1000))
                        throw IllegalStateException("Transport closed during cancellation")
                    }
                } else {
                    progress(VideoDownload(DownloadPhase.DOWNLOADING, 750, 1000))
                    resumed.await()
                    progress(VideoDownload(DownloadPhase.COMPLETE, 1000, 1000))
                }
            }
        }
        val model = VideoViewModel(controlled, "lesson")
        model.save(); runCurrent(); model.pause(); runCurrent()
        assertEquals(VideoDownload(DownloadPhase.PAUSED, 500, 1000), model.download.value)
        assertNull(model.state.value.problem)
        model.save(); runCurrent()
        finishingCancelled.complete(Unit); runCurrent()
        assertEquals(VideoDownload(DownloadPhase.DOWNLOADING, 750, 1000), model.download.value)
        assertNull(model.state.value.problem)
        resumed.complete(Unit); runCurrent()
        assertEquals(DownloadPhase.COMPLETE, model.download.value.phase)
        assertEquals(2, attempts)
        model.stop()
    }

    @Test fun cancelledTransportExceptionLeavesPausedBytesWithoutProblem() = runTest(dispatcher) {
        val controlled = object : VideoRepository {
            override suspend fun remove(id: String) {}
        override fun observe(id: String) = MutableStateFlow<Material?>(null)
            override suspend fun source(id: String) = VideoSource.Hub(id, 1000)
            override suspend fun progress(id: String) = VideoDownload()
            override suspend fun save(id: String, progress: (VideoDownload) -> Unit) {
                progress(VideoDownload(DownloadPhase.DOWNLOADING, 500, 1000))
                try { awaitCancellation() }
                finally { throw IllegalStateException("Transport closed during cancellation") }
            }
        }
        val model = VideoViewModel(controlled, "lesson")
        model.save(); runCurrent(); model.pause(); runCurrent()
        assertEquals(VideoDownload(DownloadPhase.PAUSED, 500, 1000), model.download.value)
        assertNull(model.state.value.problem)
        model.stop()
    }
    @Test fun removeWaitsForCancelledTransferAndBlocksConcurrentSave() = runTest(dispatcher) {
        val cleanup = CompletableDeferred<Unit>()
        var removed = false
        var saves = 0
        val controlled = object : VideoRepository {
            override fun observe(id: String) = MutableStateFlow<Material?>(null)
            override suspend fun source(id: String): VideoSource {
                if (removed) throw IOException()
                return VideoSource.Local("lesson.video")
            }
            override suspend fun progress(id: String) = VideoDownload(DownloadPhase.COMPLETE, 1000, 1000)
            override suspend fun save(id: String, progress: (VideoDownload) -> Unit) {
                saves++
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
            override suspend fun remove(id: String) { removed = true }
        }
        val model = VideoViewModel(controlled, "lesson")
        model.save(); runCurrent()
        model.remove(); runCurrent()
        model.save(); runCurrent()
        assertFalse(removed)
        assertEquals(1, saves)
        cleanup.complete(Unit); runCurrent()
        assertTrue(removed)
        assertNull(model.state.value.source)
        assertEquals(VideoProblem.HUB_UNAVAILABLE, model.state.value.problem)
        assertEquals(DownloadPhase.IDLE, model.download.value.phase)
        model.start(); runCurrent()
        assertEquals(1, saves)
        model.stop()
    }

    @Test fun removalStorageFailureKeepsSavedSourceVisible() = runTest(dispatcher) {
        val controlled = object : VideoRepository {
            override fun observe(id: String) = MutableStateFlow<Material?>(null)
            override suspend fun source(id: String) = VideoSource.Local("lesson.video")
            override suspend fun progress(id: String) = VideoDownload(DownloadPhase.COMPLETE, 1000, 1000)
            override suspend fun save(id: String, progress: (VideoDownload) -> Unit) {}
            override suspend fun remove(id: String) { throw VideoFailure(VideoProblem.STORAGE) }
        }
        val model = VideoViewModel(controlled, "lesson")
        model.remove(); runCurrent()
        assertEquals(VideoSource.Local("lesson.video"), model.state.value.source)
        assertEquals(VideoProblem.STORAGE, model.state.value.problem)
        assertFalse(model.state.value.removing)
        assertEquals(DownloadPhase.COMPLETE, model.download.value.phase)
        model.stop()
    }

}
