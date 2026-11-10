package org.lara.app.reading

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.MainActivity
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.SignOutResult
import org.lara.app.ui.components.*
import org.lara.app.ui.theme.*

@RunWith(AndroidJUnit4::class)
class ClassTabsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("tabsPhase") ?: "after"
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun waitFor(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return
            SystemClock.sleep(100)
        }
        error("Class control missing: $label")
    }

    private fun click(label: String) {
        scrollTo(label)
        var node = nodes().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun scrollTo(label: String) {
        repeat(8) {
            if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        waitFor(label)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (InstrumentationRegistry.getArguments().getString("homeLandscape") == "true") {
                assertTrue("Landscape evidence must be wider than tall", bitmap.width > bitmap.height)
            }
            File(context.filesDir, "home266-$phase-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun liveClassTabs(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tabsHub") == "true")
        check(context.packageName == "org.lara.app.group3")
        val container = (context.applicationContext as LaraApp).container
        container.auth.cachedUser()?.let { previous ->
            assertEquals(SignOutResult.SignedOut, container.forUser(previous.id).settings.signOut(previous.id))
        }
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        container.endpoints.save(endpoint, "Synthetic Group 3 Hub")
        for ((role, id) in listOf("learner" to "123456789012", "teacher" to "T-0001")) {
            val requestedRole = InstrumentationRegistry.getArguments().getString("tabsRole")
            if (requestedRole != null && requestedRole != role) continue
            container.endpoints.save(endpoint, "Synthetic Group 3 Hub")
            val user = container.auth.authenticate(endpoint, id, "1234", null)
            container.forUser(user.id).sync.pull(user.id)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("Science 4"); click("Science 4"); waitFor("Stream")
                capture("$role-stream")
                click("Classwork"); capture("$role-classwork")
                if (phase != "before") {
                    click("Filter by topic"); waitFor("All topics"); capture("$role-topics")
                    click("All topics"); waitFor("Classwork")
                    click("People"); waitFor("Teachers"); capture("$role-people")
                    if (role == "learner") assertFalse(nodes().any { it.text?.toString() == "Learners" })
                    click("Stream"); waitFor("Classwork")
                } else if (role == "teacher") {
                    click("People"); waitFor("Teachers"); capture("$role-people")
                }
            }
            if (InstrumentationRegistry.getArguments().getString("tabsKeepSession") != role) {
                assertEquals(SignOutResult.SignedOut, container.forUser(user.id).settings.signOut(user.id))
            }
        }
    }

    @Test fun cachedLiveClassWithoutHub(): Unit = runBlocking {
        val role = InstrumentationRegistry.getArguments().getString("tabsCachedRole")
        assumeTrue(role in setOf("learner", "teacher"))
        check(context.packageName == "org.lara.app.group3")
        val container = (context.applicationContext as LaraApp).container
        val user = requireNotNull(container.auth.cachedUser())
        assertEquals(if (role == "teacher") "TEACHER" else "STUDENT", user.role)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Science 4"); click("Science 4")
            waitFor(context.getString(org.lara.app.R.string.shell_offline)); capture("$role-disconnected-stream")
            click("Classwork"); click("Filter by topic"); waitFor("All topics"); capture("$role-disconnected-topics")
            click("All topics"); click("People"); waitFor("Teachers"); capture("$role-disconnected-people")
            assertFalse(nodes().any { it.text?.toString() == context.getString(org.lara.app.R.string.classes_offline) })
            if (role == "learner") assertFalse(nodes().any { it.text?.toString() == "Learners" })
        }
        assertEquals(SignOutResult.SignedOut, container.forUser(user.id).settings.signOut(user.id))
    }

    @Test fun cachedFiltersPrivacyAndLargeText(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tabsStates") == "true")
        check(context.packageName == "org.lara.app.group3")
        val owner = "tabs-fixture-${System.nanoTime()}"
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 9))
        val container = org.lara.app.AppContainer(context)
        container.endpoints.save(hub)
        val db = org.lara.app.data.local.AccountDatabases(context).prepare(owner)
        db.authDao().saveProfile(org.lara.app.data.local.entities.UserEntity(owner, "SYNTHETIC", "Sample Learner", "STUDENT", 1, 1))
        org.lara.app.data.remote.TokenStore(context).save(org.lara.app.data.remote.StoredToken("synthetic", owner, Long.MAX_VALUE, hub.restBase))
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        db.cacheDao().upsertBatch(org.lara.app.data.local.dao.CacheBatch(
            users = listOf(org.lara.app.data.local.entities.UserEntity("tabs-teacher", null, "Sample Teacher", "TEACHER", 1, 1),
                org.lara.app.data.local.entities.UserEntity("tabs-peer", null, "Private Peer", "STUDENT", 1, 1)),
            classrooms = listOf(org.lara.app.data.local.entities.ClassroomEntity("tabs-class", "Science 4", "Section Mabini", "K7M4QX", "tabs-teacher", createdAt = 1, updatedAt = 1),
                org.lara.app.data.local.entities.ClassroomEntity("tabs-empty", "Empty class", "Section Mabini", "ABCDEF", "tabs-teacher", createdAt = 1, updatedAt = 1)),
            topics = listOf(org.lara.app.data.local.entities.TopicEntity("plants", "tabs-class", "Plants", createdAt = 1, updatedAt = 1),
                org.lara.app.data.local.entities.TopicEntity("animals", "tabs-class", "Animals", orderIndex = 1, createdAt = 1, updatedAt = 1)),
            materials = listOf(org.lara.app.data.local.entities.MaterialEntity("plants-file", "tabs-class", "Plant lesson", "DOCUMENT", fileSizeBytes = 20, topicId = "plants", createdAt = 1, updatedAt = 1),
                org.lara.app.data.local.entities.MaterialEntity("animals-file", "tabs-class", "Animal lesson", "DOCUMENT", fileSizeBytes = 20, topicId = "animals", createdAt = 1, updatedAt = 1)),
            assignments = listOf(org.lara.app.data.local.entities.AssignmentEntity("plants-task", "tabs-class", "Draw a leaf", dueDate = Long.MAX_VALUE, topicId = "plants", createdAt = 1, updatedAt = 1))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var render = 0
            fun show(font: Float = 1f, loading: Boolean = false, locked: Boolean = false, empty: Boolean = false) {
                val renderKey = ++render
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                        LocalClassChrome provides ClassChrome("Science 4", "Section Mabini") {}) {
                        LaraTheme {
                            key(renderKey) { org.lara.app.ui.navigation.StudentNavGraph(org.lara.app.ui.navigation.ShellState(
                                org.lara.app.ui.navigation.ShellRole.STUDENT, isLoading = loading, hasInProgressAttempt = locked), content = {
                                org.lara.app.ui.screens.stream.ClassroomLearningHost(active, owner, it, if (empty) "tabs-empty" else "tabs-class")
                            }) }
                        }
                    }
                } }
                SystemClock.sleep(500)
            }
            show(); click("Classwork"); waitFor("Plant lesson"); capture("fixture-offline-classwork")
            click("Filter by topic"); click("Animals"); waitFor("Animal lesson")
            assertFalse(nodes().any { it.text?.toString() == "Plant lesson" || it.text?.toString() == "Draw a leaf" })
            capture("fixture-selected-topic")
            click("Filter by topic"); click("Plants"); waitFor("Draw a leaf")
            assertFalse(nodes().any { it.text?.toString() == "Animal lesson" })
            click("People"); waitFor("Sample Teacher"); assertFalse(nodes().any { it.text?.toString() == "Private Peer" })
            capture("fixture-offline-people")
            click("Classwork"); waitFor("Draw a leaf")
            click("Filter by topic"); waitFor("Plants"); capture("fixture-topic-retained")
            click("All topics"); waitFor("Plant lesson")
            click("Class actions"); click("AI Tutor"); waitFor(context.getString(org.lara.app.R.string.shell_ai_unavailable))
            show(locked = true); click("Class actions"); waitFor("AI Tutor")
            var tutor = nodes().first { it.text?.toString() == "AI Tutor" }
            while (!tutor.isClickable && tutor.parent != null) tutor = tutor.parent
            assertFalse(tutor.isEnabled); capture("fixture-quiz-lockout")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            show(font = 2f); capture("fixture-stream-font200")
            scrollTo(context.getString(org.lara.app.R.string.reading_stream_empty)); capture("fixture-stream-font200-bottom")
            for (label in listOf("Stream", "Classwork", "People")) {
                waitFor(label)
                var node = nodes().first { it.text?.toString() == label }
                while (!node.isClickable && node.parent != null) node = node.parent
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                assertTrue(bounds.height() >= 52 * context.resources.displayMetrics.density)
            }
            click("Classwork"); click("Filter by topic"); capture("fixture-filter-font200")
            click("All topics")
            show(empty = true); click("Stream"); capture("fixture-empty-stream")
            click("Classwork"); capture("fixture-empty-classwork")
            show(loading = true); capture("fixture-loading")
        }
        assertEquals(SignOutResult.SignedOut, active.settings.signOut(owner))
    }
}
