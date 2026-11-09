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
    @Test fun gLandscapePlayerControlsRemainReachable(): Unit = runBlocking {
        guard()
        val owner = ownerFile.readText()
        val container = AppContainer(context)
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil("Activity did not rotate") {
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario.onActivity { activity -> activity.setContent {
                LaraTheme { Surface(color = LaraColors.Canvas) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { VideoPlayerScreen(active, owner, "video-fixture") {} }
                } }
            } }
            fun reachable(label: String): AccessibilityNodeInfo {
                val display = context.resources.displayMetrics
                val minimum = 52 * display.density - 1
                var match: AccessibilityNodeInfo? = null
                waitUntil("Landscape control clipped: $label") {
                    match = nodes().firstOrNull { node ->
                        val bounds = android.graphics.Rect().also(clickTarget(node)::getBoundsInScreen)
                        (node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                            bounds.height() >= minimum && bounds.top >= 0 && bounds.bottom <= display.heightPixels
                    }
                    if (match == null) instrumentation.uiAutomation.executeShellCommand(
                        "input swipe 400 650 400 450 200").close()
                    match != null
                }
                return requireNotNull(match)
            }
            reachable(context.getString(R.string.video_seek))
            assertTrue(clickTarget(reachable(context.getString(R.string.video_play)))
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            shot("landscape-play-seek")
            // This saved fixture has a completion status instead of a download action.
            waitUntil("Saved status unreachable in landscape") {
                val visible = nodes().any { it.text?.toString() == context.getString(R.string.video_local) }
                if (!visible) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                visible
            }
            shot("landscape-saved")
        }
    }
    @Test fun iFullscreenRotationKeepsPlayerAndPosition(): Unit = runBlocking {
        guard()
        val container = AppContainer(context)
        val owner = ownerFile.readText()
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        val fixtureId = "video-fixture"
        assertTrue(active.videos.source(fixtureId) is VideoSource.Local)
        val application = context.applicationContext as android.app.Application
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {
                if (activity is MainActivity) activity.window.decorView.post {
                    activity.setContent { LaraTheme { Surface(color = LaraColors.Canvas) {
                        VideoPlayerScreen(active, owner, fixtureId) {}
                    } } }
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun session(): org.lara.app.ui.screens.classwork.VideoPlaybackSession {
                    var result: org.lara.app.ui.screens.classwork.VideoPlaybackSession? = null
                    scenario.onActivity { result = androidx.lifecycle.ViewModelProvider(it)[
                        "video-player-$owner-$fixtureId", org.lara.app.ui.screens.classwork.VideoPlaybackSession::class.java] }
                    return requireNotNull(result)
                }
                fun tap(label: Int) {
                    waitUntil("Missing control ${context.getString(label)}") {
                        nodes().any { it.contentDescription?.toString() == context.getString(label) }
                    }
                    assertTrue(clickTarget(nodes().first { it.contentDescription?.toString() == context.getString(label) })
                        .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                fun assertFullWindow() {
                    if (Build.VERSION.SDK_INT < 30) return
                    waitUntil("Fullscreen window did not cover the display or hide bars") {
                        var full = false
                        scenario.onActivity { activity ->
                            val bounds = activity.windowManager.currentWindowMetrics.bounds
                            val window = android.view.inspector.WindowInspector.getGlobalWindowViews()
                                .firstOrNull { it.hasWindowFocus() && it !== activity.window.decorView }
                            val insets = window?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
                            full = window?.width == bounds.width() && window.height == bounds.height() &&
                                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) == false
                        }
                        full
                    }
                }
                waitUntil("Player missing") { var ready = false; scenario.onActivity {
                    ready = session().player?.playbackState == Player.STATE_READY }; ready }
                val original = session()
                val player = requireNotNull(original.player)
                scenario.onActivity { player.seekTo(4_000); player.play() }
                tap(R.string.video_fullscreen)
                waitUntil("Fullscreen missing") { nodes().any { it.contentDescription?.toString() == context.getString(R.string.video_exit_fullscreen) } }
                assertFullWindow()
                shot("fullscreen-portrait")
                var before = 0L
                scenario.onActivity { before = player.currentPosition }
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitUntil("Landscape recreation missing") {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                instrumentation.uiAutomation.executeShellCommand("input tap 300 150").close()
                waitUntil("Fullscreen lost on rotation") { nodes().any {
                    it.contentDescription?.toString() == context.getString(R.string.video_exit_fullscreen) } }
                assertFullWindow()
                assertSame(original, session())
                assertSame(player, session().player)
                waitUntil("Playback did not continue across rotation") { var continued = false
                    scenario.onActivity { continued = player.isPlaying && player.currentPosition > before }; continued }
                instrumentation.uiAutomation.executeShellCommand("input tap 300 150").close()
                shot("fullscreen-landscape")
                waitUntil("Playing overlay did not hide") { nodes().none {
                    it.contentDescription?.toString() == context.getString(R.string.video_exit_fullscreen) } }
                instrumentation.uiAutomation.executeShellCommand("input tap 300 150").close()
                tap(R.string.video_pause)
                SystemClock.sleep(3_500)
                assertTrue(nodes().any { it.contentDescription?.toString() == context.getString(R.string.video_exit_fullscreen) })
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitUntil("Portrait fullscreen did not return") {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    portrait && nodes().any { it.contentDescription?.toString() == context.getString(R.string.video_exit_fullscreen) }
                }
                assertSame(player, session().player)
                tap(R.string.video_exit_fullscreen)
                waitUntil("Normal player missing after exit") { nodes().any { it.contentDescription?.toString() == context.getString(R.string.video_fullscreen) } }
                scenario.onActivity {
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    assertTrue(requireNotNull(insets).isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()))
                    assertTrue(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()))
                }
                tap(R.string.video_fullscreen)
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                waitUntil("Back did not exit fullscreen") { nodes().any { it.contentDescription?.toString() == context.getString(R.string.video_fullscreen) } }
                assertSame(player, session().player)
            }
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    @Test fun jRemoveConfirmationCancelOfflineAndRedownload(): Unit = runBlocking {
        guard()
        val bytes = fixture()
        val mediaRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().matches(Regex("/api/materials/.+/(stream|download)"))) mediaRequests.incrementAndGet()
                if (!request.path.orEmpty().endsWith("/download")) return MockResponse().setResponseCode(404)
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val start = range[0].toInt(); val end = range[1].toInt().coerceAtMost(bytes.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setHeader("ETag", "video-remove").setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        try {
            val owner = ownerFile.readText()
            val container = AppContainer(context)
            val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port, server.port))
            container.endpoints.save(endpoint)
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, Long.MAX_VALUE, endpoint.restBase))
            requireNotNull(container.auth.cachedUser())
            val active = container.forUser(owner)
            val db = AccountDatabases(context).prepare(owner)
            val file = File((active.videos.source("video-fixture") as VideoSource.Local).path)
            val unrelated = File(file.parentFile, "remove-test-handout.pdf").apply { writeText("keep") }
            val partial = File(file.parentFile, "${file.nameWithoutExtension}.part").apply { writeText("partial") }
            val validator = File(file.parentFile, "${file.nameWithoutExtension}.validator").apply { writeText("etag") }
            // Expired auth proves that confirming removal never needs a Hub connection.
            TokenStore(context).save(StoredToken("synthetic-video-session", owner, 1, endpoint.restBase))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { LaraTheme { Surface(color = LaraColors.Canvas) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) { VideoPlayerScreen(active, owner, "video-fixture") {} }
                } } } }
                fun tap(label: Int) {
                    val text = context.getString(label)
                    waitUntil("Missing $text") {
                        val found = nodes().any { it.text?.toString() == text }
                        if (!found) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        found
                    }
                    assertTrue(clickTarget(nodes().first { it.text?.toString() == text }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                tap(R.string.video_remove)
                waitUntil("Confirmation missing") { nodes().any { it.text?.toString() == context.getString(R.string.video_remove_confirmation) } }
                shot("remove-confirmation")
                tap(R.string.video_remove_cancel)
                assertTrue(file.isFile); assertTrue(partial.isFile)
                assertEquals(file.canonicalPath, File(requireNotNull(db.classworkDao().material("video-fixture")?.localFilePath)).canonicalPath)
                tap(R.string.video_remove); tap(R.string.video_remove_confirm)
                waitUntil("Offline state missing") { nodes().any { it.text?.toString() == context.getString(R.string.video_sign_in) } }
                assertFalse(file.exists()); assertFalse(partial.exists()); assertFalse(validator.exists())
                assertNull(db.classworkDao().material("video-fixture")?.localFilePath)
                assertTrue(unrelated.isFile)
                assertEquals(0, mediaRequests.get())
                assertFalse(nodes().any { it.text?.toString() == context.getString(R.string.video_remove) })
                // Restore the synthetic Hub session and retry Save.
                TokenStore(context).save(StoredToken("synthetic-video-session", owner, Long.MAX_VALUE, endpoint.restBase))
                tap(R.string.video_save)
                waitUntil("Redownload did not complete") { nodes().any { it.text?.toString() == context.getString(R.string.video_downloaded) } }
                assertTrue(active.videos.source("video-fixture") is VideoSource.Local)
                assertEquals(sha(bytes), sha(file.readBytes()))
                shot("removed-redownloaded")
            }
        } finally { server.shutdown() }
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
            var pausedBytes = 0L
            try {
                active.videos.save("video-fixture") { if (it.bytes >= 32768) {
                    pausedBytes = it.bytes
                    throw CancellationException()
                } }
                fail("Expected cancelled save")
            } catch (_: CancellationException) { }
            assertNull(db.classworkDao().material("video-fixture")?.localFilePath)
            active.videos.save("video-fixture") {}
            val file = File(requireNotNull(db.classworkDao().material("video-fixture")?.localFilePath))
            assertEquals(sha(bytes), sha(file.readBytes()))
            assertTrue(requests.any { it.path.orEmpty().endsWith("/download") && it.getHeader("Range")?.startsWith("bytes=$pausedBytes-") == true &&
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
                    .throttleBody(8192, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
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
                val saveLabel = context.getString(R.string.video_save)
                waitUntil("Save for home action missing") { nodes().any { it.text?.toString() == saveLabel } }
                assertTrue(clickTarget(nodes().first { it.text?.toString() == saveLabel }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil("Download progress missing") { nodes().any { it.text?.toString() == "Downloading lesson" } }
                shot("download-progress")
                val cancelLabel = context.getString(R.string.video_cancel)
                assertTrue(clickTarget(nodes().first { it.text?.toString() == cancelLabel })
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil("Cancel did not leave a paused download") {
                    nodes().any { it.text?.toString() == context.getString(R.string.video_resume) }
                }
                // Allow socket closure and coroutine cleanup to finish before asserting the settled UI.
                SystemClock.sleep(500)
                assertFalse(nodes().any { it.text?.toString() == context.getString(R.string.video_download_failed) })
                assertFalse(nodes().any { it.text?.toString() == context.getString(R.string.video_unavailable) })
                val partial = active.videos.progress("video-ui-download")
                assertTrue(partial.bytes in 1 until bytes.size.toLong())
                shot("download-paused")
                assertTrue(clickTarget(nodes().first { it.text?.toString() == context.getString(R.string.video_resume) })
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
