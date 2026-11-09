package org.lara.app.reading

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import org.lara.app.domain.Cached
import org.lara.app.domain.enrollment.LearnerClass
import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Enrollment
import org.lara.app.ui.components.*
import org.lara.app.ui.screens.enrollment.*
import org.lara.app.ui.theme.*

/** Opt-in synthetic accounts; exercises the actual app, Room and Hub instead of a mock screen. */
@RunWith(AndroidJUnit4::class)
class HomeNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("homePhase") ?: "after"

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
        error("Home control missing: $label")
    }

    private fun click(label: String) {
        scrollTo(label)
        var node = nodes().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
        SystemClock.sleep(500)
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

    private fun assertTarget(label: String, minimumDp: Int) {
        waitFor(label)
        var node = nodes().first { it.contentDescription?.toString() == label }
        while (!node.isClickable && node.parent != null) node = node.parent
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val minimum = minimumDp * context.resources.displayMetrics.density
        assertTrue("$label must have a ${minimumDp}dp touch target", bounds.width() >= minimum && bounds.height() >= minimum)
    }

    @Test fun actualHomeAndExistingDestinations(): Unit = runBlocking {
        assumeTrue("Opt in with homeHub=127.0.0.1:8080", InstrumentationRegistry.getArguments().getString("homeHub") == "127.0.0.1:8080")
        val container = (context.applicationContext as LaraApp).container
        val previous = container.auth.cachedUser()
        check(previous == null || previous.lrnOrId in setOf("T-0001", "123456789012")) { "Refusing to replace a personal session" }
        if (previous != null) assertEquals(SignOutResult.SignedOut, container.forUser(previous.id).settings.signOut(previous.id))
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        container.endpoints.save(endpoint, "Synthetic test Hub")
        for ((role, id) in listOf("learner" to "123456789012", "teacher" to "T-0001")) {
            val user = container.auth.authenticate(endpoint, id, "1234", null)
            container.forUser(user.id).sync.pull(user.id)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("Science 4")
                capture("$role-home")
                if (phase == "before") {
                    click("Settings")
                    scrollTo("About L.A.R.A.")
                    capture("$role-settings")
                } else {
                    assertTarget("Open navigation menu", 52)
                    assertTarget(if (role == "learner") "Join a class" else "Create class", 56)
                    click(if (role == "learner") "Join a class" else "Create class")
                    capture("$role-plus-sheet")
                    click(if (role == "learner") "Join a class" else "Create class")
                    waitFor(if (role == "learner") "Class code" else "Class name")
                    capture("$role-existing-form")
                    click("Cancel")
                    if (role == "teacher") click("Back")
                    click("Open navigation menu")
                    waitFor("Classes")
                    capture("$role-drawer")
                    click("Science 4")
                    waitFor("Stream")
                    waitFor("Classwork")
                    click("Back")
                    waitFor("My classes")
                    click("Open navigation menu")
                    click("Settings")
                    scrollTo("About L.A.R.A.")
                    capture("$role-settings")
                }
            }
            assertEquals(SignOutResult.SignedOut, container.forUser(user.id).settings.signOut(user.id))
        }
    }

    @Test fun homeStatesAndLargeText() {
        assumeTrue("Explicit screenshot fixture opt-in", InstrumentationRegistry.getArguments().getString("homeStates") == "true")
        assumeTrue("State fixtures must use their matching APK", phase == "after")
        val classroom = Classroom("home-fixture", "Science 4", "Section Mabini", "K7M4QX", "fixture-teacher", null, null, 1, 1)
        val membership = Enrollment("fixture-enrollment", classroom.id, "fixture-learner", "ACTIVE", 1, 1)
        val home = EnrollmentScreenState(classes = Cached.Content(listOf(LearnerClass(classroom, membership))),
            noticesLoaded = true, offline = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (InstrumentationRegistry.getArguments().getString("homeLandscape") == "true") {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
            }
            fun show(name: String, state: EnrollmentScreenState, fontScale: Float = 1f) {
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        LaraTheme {
                            ClassHomeScaffold(listOf(HomeClass(classroom.id, classroom.name, classroom.section)),
                                false, !state.offline && !state.busy, {}, {}) { padding ->
                                Box(Modifier.padding(padding)) { EnrollmentScreen(state, {}, {}, {}, {}, showJoinAction = false) }
                            }
                        }
                    }
                } }
                waitFor("My classes")
                capture("learner-$name")
            }
            show("offline", home.copy(offline = true))
            show("empty", home.copy(classes = Cached.Content(emptyList())))
            show("loading", home.copy(classes = Cached.Loading))
            show("error", home.copy(error = JoinClassError.REFRESH_FAILED))
            show("pending", home.copy(classes = Cached.Content(listOf(LearnerClass(classroom, membership.copy(status = "PENDING"))))))
            show("font200", home, 2f)
            run {
                click("Open navigation menu")
                waitFor("Classes")
                capture("learner-drawer-font200")
                click("Classes")
                show("offline-font200", home.copy(offline = true), 2f)
                click("Join a class")
                waitFor("Adding a class needs the Hub. Connect to the Hub and wait for any current request to finish.")
                val button = nodes().first { it.text?.toString() == "Join a class" }
                var parent = button
                while (!parent.isClickable && parent.parent != null) parent = parent.parent
                assertFalse("Offline join must remain disabled", parent.isEnabled)
                capture("learner-offline-plus-font200")
                click("Cancel")
            }
        }
    }

    @Test fun cachedHomeWithoutHub(): Unit = runBlocking {
        assumeTrue("Remove USB forwarding after the home opens", InstrumentationRegistry.getArguments().getString("homeOffline") == "true")
        val teacher = InstrumentationRegistry.getArguments().getString("homeRole") == "teacher"
        val role = if (teacher) "teacher" else "learner"
        val id = if (teacher) "T-0001" else "123456789012"
        val container = (context.applicationContext as LaraApp).container
        val previous = container.auth.cachedUser()
        check(previous == null || previous.lrnOrId == id)
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        container.endpoints.save(endpoint, "Synthetic test Hub")
        val user = container.auth.authenticate(endpoint, id, "1234", null)
        container.forUser(user.id).sync.pull(user.id)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Science 4")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Ready to remove USB Hub forwarding\n") })
            val deadline = SystemClock.uptimeMillis() + 40_000
            val offline = context.getString(if (teacher) org.lara.app.R.string.shell_offline else org.lara.app.R.string.enrollment_join_offline)
            while (SystemClock.uptimeMillis() < deadline && nodes().none { it.text?.toString() == offline }) SystemClock.sleep(200)
            waitFor(offline)
            waitFor("Science 4")
            capture("actual-$role-cached-offline")
            click("Open navigation menu")
            click("Science 4")
            waitFor("Stream")
            click("Back")
            click(if (teacher) "Create class" else "Join a class")
            waitFor(context.getString(org.lara.app.R.string.home_add_unavailable))
            capture("actual-$role-cached-offline-plus")
            click("Cancel")
        }
        assertEquals(SignOutResult.SignedOut, container.forUser(user.id).settings.signOut(user.id))
    }
}
