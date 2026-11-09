package org.lara.app.reading

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.MainActivity
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.SignOutResult
import org.lara.app.domain.enrollment.EnrollmentStatus
import org.lara.app.ui.screens.enrollment.JoinClassDialog
import org.lara.app.ui.screens.enrollment.JoinClassError
import org.lara.app.ui.screens.teacher.classes.CreateClassDialog
import org.lara.app.ui.theme.LaraTheme

/** Opt-in synthetic accounts only; never clears account databases or queued work. */
@RunWith(AndroidJUnit4::class)
class ClassFormsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val phase get() = args.getString("formsPhase") ?: "after"
    private val container get() = (context.applicationContext as LaraApp).container

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

    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label

    private fun waitFor(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { matches(it, label) }) return
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Form control missing: $label")
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            for (candidate in nodes().filter { matches(it, label) }) {
                var node = candidate
                while (!node.isClickable && node.parent != null) node = node.parent
                if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(250)
                    return
                }
            }
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Form action unavailable: $label")
    }

    private fun input(index: Int, value: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val fields = nodes().filter { it.isEditable }
            if (fields.size > index && fields[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) return
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Editable field missing: $index")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (args.getString("formsLandscape") == "true") assertTrue(bitmap.width > bitmap.height)
            for (label in listOf("Join", "Review", "Create", "Cancel")) {
                for (candidate in nodes().filter { matches(it, label) }) {
                    var node = candidate
                    while (!node.isClickable && node.isEnabled && node.parent != null) node = node.parent
                    if (node.isClickable || !node.isEnabled) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        assertTrue("$label must fit the screen: $bounds", bounds.left >= 0 && bounds.right <= bitmap.width && bounds.top >= 0 && bounds.bottom <= bitmap.height)
                        assertTrue("$label must retain the header gutter: $bounds", bounds.right <= bitmap.width - 12 * context.resources.displayMetrics.density)
                        assertTrue("$label minimum touch height", bounds.height() >= (if (label == "Cancel") 52 else 56) * context.resources.displayMetrics.density)
                    }
                }
            }
            File(context.filesDir, "forms266-$phase-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun assertEnabled(label: String, enabled: Boolean) {
        waitFor(label)
        var node = nodes().first { matches(it, label) }
        while (!node.isClickable && node.isEnabled && node.parent != null) node = node.parent
        assertEquals("Enabled state: $label", enabled, node.isEnabled)
    }

    private suspend fun signIn(id: String): String {
        val previous = container.auth.cachedUser()
        check(previous == null || previous.lrnOrId in setOf("T-0001", "123456789012", "123456789013"))
        if (previous != null) assertEquals(SignOutResult.SignedOut, container.forUser(previous.id).settings.signOut(previous.id))
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        container.endpoints.save(endpoint, "Synthetic forms test Hub")
        val user = container.auth.authenticate(endpoint, id, "1234", null)
        container.forUser(user.id).sync.pull(user.id)
        return user.id
    }

    @Test fun createReviewAndPendingJoin(): Unit = runBlocking {
        assumeTrue("Opt in to the local test Hub", args.getString("formsHub") == "127.0.0.1:8080")
        val teacher = signIn("T-0001")
        val title = "Group 2 " + System.nanoTime().toString().takeLast(6)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("My classes")
            click("Create class")
            waitFor("Cancel")
            click("Create class")
            SystemClock.sleep(500)
            if (phase != "before") assertEnabled("Review", false)
            capture("create-empty")
            input(0, title)
            input(1, "Test section")
            capture("create-filled")
            click(if (phase == "before") "Review class details" else "Review")
            waitFor("Edit details")
            capture("create-review")
            if (phase == "before") { click("Cancel"); return@use }
            click("Edit details")
            assertTrue(nodes().any { it.isEditable && it.text?.toString() == title })
            click("Review")
            click("Create")
            waitFor(title)
            capture("created")
        }
        val classroom = if (phase == "before") null else container.forUser(teacher).enrollmentApi.classrooms(teacher).first { it.name == title }
        assertEquals(SignOutResult.SignedOut, container.forUser(teacher).settings.signOut(teacher))
        if (classroom == null) return@runBlocking
        val learner = signIn("123456789013")
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("My classes")
            click("Join a class")
            waitFor("Cancel")
            click("Join a class")
            waitFor("Class code")
            capture("join-empty")
            click("Join")
            waitFor(context.getString(org.lara.app.R.string.enrollment_code_invalid))
            capture("join-invalid")
            input(0, classroom.classCode.lowercase())
            capture("join-filled")
            click("Join")
            waitFor(context.getString(org.lara.app.R.string.enrollment_pending))
            capture("pending")
            val notice = container.forUser(learner).enrollmentNotices.notices(learner).first { rows -> rows.any { it.classroomId == classroom.id } }
            assertEquals(EnrollmentStatus.PENDING, notice.first { it.classroomId == classroom.id }.status)
        }
        assertEquals(SignOutResult.SignedOut, container.forUser(learner).settings.signOut(learner))
    }

    @Test fun formStates() {
        assumeTrue("Explicit presentation-state fixtures", args.getString("formsStates") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (args.getString("formsLandscape") == "true") {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
            }
            fun join(name: String, online: Boolean = true, busy: Boolean = false, error: JoinClassError? = null) {
                scenario.onActivity { it.setContent { LaraTheme {
                    JoinClassDialog(online, busy, error, {}, {}, learnerName = "Sample Learner")
                } } }
                waitFor("Class code")
                assertEnabled("Join", online && !busy)
                assertEnabled("Cancel", !busy)
                capture("fixture-$name")
            }
            join("join-empty")
            input(0, " k7m-4qx ")
            capture("fixture-join-pasted")
            assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(700)
            waitFor("Join")
            repeat(6) {
                if (nodes().any { it.isEditable }) return@repeat
                nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
            assertTrue("The code field must remain reachable with the keyboard", nodes().any { it.isEditable })
            capture("fixture-join-keyboard")
            join("join-offline", online = false)
            join("join-busy", busy = true)
            join("join-error", error = JoinClassError.REQUEST_FAILED)
            for ((name, online, busy) in listOf(Triple("create-empty", true, false), Triple("create-offline", false, false), Triple("create-busy", true, true))) {
                scenario.onActivity { it.setContent { LaraTheme {
                    CreateClassDialog("", "", false, online, busy, {}, {}, {}, {}, {}, {})
                } } }
                waitFor("Class name (required)")
                assertEnabled("Review", false)
                capture("fixture-$name")
            }
        }
    }
}
