package org.lara.app.reading

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.lara.app.AppContainer
import org.lara.app.MainActivity
import org.lara.app.R
import org.lara.app.data.local.AccountDatabases
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.StoredToken
import org.lara.app.data.remote.TokenStore
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.VideoSource
import org.lara.app.domain.DownloadPhase
import org.lara.app.domain.VideoDownload
import org.lara.app.ui.screens.classwork.DownloadControls
import org.lara.app.ui.screens.classwork.VideoPlayerScreen
import org.lara.app.ui.theme.*

/** Separate test package only. Synthetic media server is NOT the Python mock or real Hub. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VideoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ownerFile get() = File(context.filesDir, "video-test-owner.txt")
    private fun guard() { check(context.packageName == "org.lara.app.sprint33video") }
    @Test fun ePausedDownloadShowsExactlyOneWorkingResumeAction() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (bytes in listOf(0L, 32_768L)) {
                assertDownloadAction(scenario, VideoDownload(DownloadPhase.PAUSED, bytes, 65_536),
                    R.string.video_resume, R.string.video_cancel, expectedSaves = 1, expectedPauses = 0)
            }
        }
    }
    @Test fun fDownloadingShowsOnlyWorkingPauseAction() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertDownloadAction(scenario, VideoDownload(DownloadPhase.DOWNLOADING, 32_768, 65_536),
                R.string.video_cancel, R.string.video_resume, expectedSaves = 0, expectedPauses = 1)
        }
    }
    private fun assertDownloadAction(scenario: ActivityScenario<MainActivity>, state: VideoDownload,
        expectedLabel: Int, absentLabel: Int, expectedSaves: Int, expectedPauses: Int) {
        var saves = 0
        var pauses = 0
        scenario.onActivity { activity -> activity.setContent {
            LaraTheme { Surface(color = LaraColors.Canvas) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(LaraSpacing.S4)) {
                    DownloadControls(state, save = { saves++ }, pause = { pauses++ })
                }
            } }
        } }
        val expected = context.getString(expectedLabel)
        val absent = context.getString(absentLabel)
        waitUntil("Download action missing: $expected") { nodes().any { it.text?.toString() == expected } }
        assertEquals("Expected exactly one $expected control", 1, nodes().count { it.text?.toString() == expected })
        assertFalse("Unexpected $absent control", nodes().any { it.text?.toString() == absent })
        assertEquals("Expected a single actionable download button", 1, nodes().count { it.isClickable })
        val target = clickTarget(nodes().single { it.text?.toString() == expected })
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(expectedSaves, saves)
        assertEquals(expectedPauses, pauses)
    }
    private fun fixture() = instrumentation.context.assets.open("video-lesson.mp4").use { it.readBytes() }
    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        shot("failure")
        File(context.filesDir, "video-ui-failure.txt").writeText(nodes().joinToString("\n") { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            "text=${node.text} description=${node.contentDescription} clickable=${node.isClickable} enabled=${node.isEnabled} bounds=$bounds"
        })
        throw AssertionError(message)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) nodes += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return nodes
    }
    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        return target
    }
    private fun shot(name: String) {
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, "video-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun nativePlayback(active: AppContainer, source: VideoSource, label: String) {
        val frame = AtomicBoolean(false)
        val failure = AtomicReference<PlaybackException?>()
        val player = AtomicReference<Player>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val p = active.createVideoPlayer(source)
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.set(true) }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                })
                player.set(p)
                activity.setContent { AndroidView(factory = { PlayerView(it).apply { this.player = p; useController = false } },
                    modifier = Modifier.fillMaxSize()) }
                p.prepare(); p.play()
            }
            try {
                waitUntil("Video did not render: ${failure.get()?.errorCodeName}") { frame.get() || failure.get() != null }
                assertNull(failure.get())
                var duration = 0L
                scenario.onActivity { duration = player.get().duration; player.get().seekTo(15_000) }
                assertTrue(duration >= 19_000)
                waitUntil("Forward seek did not advance") {
                    var ready = false
                    scenario.onActivity { ready = player.get().playbackState == Player.STATE_READY && player.get().currentPosition >= 15_000 }
                    ready
                }
                scenario.onActivity { player.get().seekTo(3_000) }
                waitUntil("Backward seek did not resume") {
                    var ready = false
                    scenario.onActivity { ready = player.get().playbackState == Player.STATE_READY && player.get().currentPosition in 3_000..8_000 }
                    ready
                }
                assertNull(failure.get())
                shot(label)
            } finally { scenario.onActivity { player.get().release() } }
        }
    }
    @Test fun aStreamSeekInterruptResumeAndSave(): Unit = runBlocking {
        guard()
        val bytes = fixture()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().matches(Regex("/api/materials/video-fixture/(stream|download)")))
                    return MockResponse().setResponseCode(404)
                if (request.getHeader("Authorization") != "Bearer synthetic-video-session") return MockResponse().setResponseCode(401)
                requests += request
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-') ?: return MockResponse().setResponseCode(400)
                val start = range[0].toInt(); val end = range[1].toInt().coerceAtMost(bytes.lastIndex)
                if (start >= bytes.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setHeader("ETag", "\"video-fixture-v1\"")
                    .setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        try {
            val owner = "synthetic-video-${System.nanoTime()}"
            ownerFile.writeText(owner)
            val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port, server.port))
            val container = AppContainer(context)
            container.endpoints.save(endpoint)
            val db = AccountDatabases(context).prepare(owner)
            db.authDao().saveProfile(UserEntity(owner, null, "Synthetic learner", "STUDENT", 1, 1))
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, Long.MAX_VALUE, endpoint.restBase))
            requireNotNull(container.auth.cachedUser())
            val active = container.forUser(owner)
            db.cacheDao().upsertBatch(CacheBatch(
                classrooms = listOf(ClassroomEntity("video-class", "Science", "Synthetic class", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
                materials = listOf(MaterialEntity("video-fixture", "video-class", "Synthetic lesson video", "VIDEO",
                    fileSizeBytes = bytes.size.toLong(), createdAt = 1, updatedAt = 1))))
            nativePlayback(active, active.videos.source("video-fixture"), "stream-seeking")
            assertTrue(requests.any { it.path.orEmpty().endsWith("/stream") })
            assertTrue(requests.all { request ->
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-').map(String::toLong)
                range[1] - range[0] < 256 * 1024
            })
            try {
                active.videos.save("video-fixture") { if (it.bytes >= 32768) throw CancellationException() }
                fail("Expected cancelled save")
            } catch (_: CancellationException) { }
            assertNull(db.classworkDao().material("video-fixture")?.localFilePath)
            active.videos.save("video-fixture") {}
            val file = File(requireNotNull(db.classworkDao().material("video-fixture")?.localFilePath))
            assertEquals(sha(bytes), sha(file.readBytes()))
            assertTrue(requests.any { it.path.orEmpty().endsWith("/download") && it.getHeader("Range")?.startsWith("bytes=32768-") == true &&
                it.getHeader("If-Range") == "\"video-fixture-v1\"" })
            // Expiry must not block offline app restoration or disk playback.
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, 1, endpoint.restBase))
            File(context.filesDir, "video-device-online.txt").writeText(
                "Synthetic HTTP fixture, not mock/real Hub.\nRendered native frame; duration >=19s; seeks 15s then 3s passed.\n" +
                "Requests=${requests.size}; all bounded and authorized.\nCancellation at 32768 bytes; If-Range resume passed.\nRoom path and SHA-256=${sha(bytes)} passed.\n")
        } finally { server.shutdown() }
    }

    @Test fun bSavedVideoRendersAndSeeksWithoutHub(): Unit = runBlocking {
        guard()
        val owner = ownerFile.readText()
        val container = AppContainer(context)
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        val source = active.videos.source("video-fixture")
        assertTrue(source is VideoSource.Local)
        nativePlayback(active, source, "offline-seeking")
        File(context.filesDir, "video-device-offline.txt").writeText(
            "Rendered saved H.264 frame and sought 15s then 3s with stopped fixture server and expired token.\n" +
            "airplane_mode_on=${android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0)}\n")
    }

    @Test fun cEnglishAndLargeFontControls(): Unit = runBlocking {
        guard()
        val owner = ownerFile.readText()
        val container = AppContainer(context)
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((language, scale, label) in listOf(Triple("en", 1f, "Play"), Triple("en", 2f, "Play"))) {
                scenario.onActivity { activity ->
                    val configuration = android.content.res.Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                    val localized = activity.createConfigurationContext(configuration)
                    activity.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalContext provides localized, LocalDensity provides Density(density.density, scale)) {
                            LaraTheme { Surface(color = LaraColors.Canvas) {
                                Box(Modifier.fillMaxSize().safeDrawingPadding()) { VideoPlayerScreen(active, owner, "video-fixture") {} }
                            } }
                        }
                    }
                }
                waitUntil("Playback control missing: $label") {
                    val visible = nodes().any { node ->
                        val bounds = android.graphics.Rect().also(clickTarget(node)::getBoundsInScreen)
                        node.contentDescription?.toString() == label && bounds.height() >= 56 * context.resources.displayMetrics.density - 1
                    }
                    if (!visible) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    visible
                }
                val control = clickTarget(nodes().first { it.contentDescription?.toString() == label })
                val rect = android.graphics.Rect().also(control::getBoundsInScreen)
                val minimum = 56 * context.resources.displayMetrics.density
                assertTrue(rect.height() >= minimum - 1 && rect.width() >= minimum - 1)
                assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(1000)
                val runtime = Runtime.getRuntime()
                File(context.filesDir, "video-memory-samples.txt").appendText(
                    "fontScale=$scale javaAllocated=${runtime.totalMemory() - runtime.freeMemory()} nativeAllocated=${android.os.Debug.getNativeHeapAllocatedSize()}\n")
                shot("ui-$language-${scale.toInt()}")
                scenario.recreate()
            }
        }
    }

    @Test fun dSaveForHomeUiShowsProgressAndPlaysLocal(): Unit = runBlocking {
        guard()
        val bytes = fixture()
        val owner = ownerFile.readText()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().matches(Regex("/api/materials/video-ui-download/(stream|download)")))
                    return MockResponse().setResponseCode(404)
                if (request.getHeader("Authorization") != "Bearer synthetic-video-session") return MockResponse().setResponseCode(401)
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val start = range[0].toInt(); val end = range[1].toInt().coerceAtMost(bytes.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setHeader("ETag", "\"video-ui-v1\"")
                    .setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
                    .throttleBody(32768, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port, server.port))
        try {
            val container = AppContainer(context)
            container.endpoints.save(endpoint)
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, Long.MAX_VALUE, endpoint.restBase))
            requireNotNull(container.auth.cachedUser())
            val active = container.forUser(owner)
            val db = AccountDatabases(context).prepare(owner)
            db.classworkDao().material("video-ui-download")?.localFilePath?.let { db.classworkDao().clearFile("video-ui-download", it) }
            db.cacheDao().upsertBatch(CacheBatch(materials = listOf(MaterialEntity("video-ui-download", "video-class",
                "Save for Home test", "VIDEO", fileSizeBytes = bytes.size.toLong(), createdAt = 1, updatedAt = System.currentTimeMillis()))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    LaraTheme { Surface(color = LaraColors.Canvas) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { VideoPlayerScreen(active, owner, "video-ui-download") {} }
                    } }
                } }
                waitUntil("Save for Home action missing") { nodes().any { it.text?.toString() == "Save for Home" } }
                assertTrue(clickTarget(nodes().first { it.text?.toString() == "Save for Home" }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil("Download progress missing") { nodes().any { it.text?.toString() == "Downloading lesson" } }
                shot("download-progress")
                waitUntil("Download did not finish") { nodes().any { it.text?.toString() == "Download complete" } }
                val source = active.videos.source("video-ui-download")
                assertTrue(source is VideoSource.Local)
                assertEquals(sha(bytes), sha(File((source as VideoSource.Local).path).readBytes()))
                instrumentation.waitForIdleSync()
                waitUntil("Saved video Play action missing") { nodes().any { it.contentDescription?.toString() == "Play" } }
                assertTrue(clickTarget(nodes().first { it.contentDescription?.toString() == "Play" }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil("Saved video did not advance") { nodes().any { it.text?.toString()?.matches(Regex("Elapsed 0:0[1-9] of 0:20")) == true } }
                shot("ui-saved-playing")
                File(context.filesDir, "video-ui-save-report.txt").writeText(
                    "Tapped Save for Home on actual player screen.\nObserved downloading label and progress, then completion.\nRoom local source and SHA-256 match.\nTapped Play after saving; elapsed time advanced.\nSynthetic throttled loopback fixture, not mock/real Hub or classroom LAN.\n")
            }
        } finally {
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, 1, endpoint.restBase))
            server.shutdown()
        }
    }
}
