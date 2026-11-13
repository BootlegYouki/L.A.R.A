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
import kotlinx.coroutines.flow.first
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
class ClassMenuDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("menuPhase") ?: "after"
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
        var node = nodes().last { it.text?.toString() == label || it.contentDescription?.toString() == label }
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
        if (nodes().none { it.text?.toString() == label || it.contentDescription?.toString() == label }) {
            repeat(8) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(80) }
        }
        repeat(16) {
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
            File(context.filesDir, "menu266-$phase-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun supportedClassControls(): Unit = runBlocking {
        check(context.packageName == "org.lara.app.group7")
        val root = (context.applicationContext as LaraApp).container
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        root.endpoints.save(endpoint, "Group 7 mock Hub")
        val user = root.auth.authenticate(endpoint, "T-0001", "1234", null)
        val active = root.forUser(user.id)
        active.sync.pull(user.id)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        val rows = active.managedClasses.classrooms(scope)
        val classId = kotlinx.coroutines.withTimeout(10000) {
            rows.first { it is org.lara.app.domain.Cached.Content }
        }.let { (it as org.lara.app.domain.Cached.Content).value.first { row -> row.name == "Science 4" }.id }
        val reachable = kotlinx.coroutines.flow.MutableStateFlow(true)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(content: @Composable () -> Unit) {
                scenario.onActivity { activity -> activity.setContent { LaraTheme { content() } } }
                SystemClock.sleep(800)
            }
            show { org.lara.app.ui.screens.teacher.classes.ClassManagementPage(active, user.id,
                reachable, {}, initialClassId = classId, close = {}) }
            scrollTo("Teachers"); capture("class-details")
            click("Class settings"); capture("class-controls")
            click(if (phase == "before") "Class settings" else "Edit details"); waitFor("Class name"); capture("rename")
            click("Cancel")
            click("Archive class"); waitFor(context.getString(org.lara.app.R.string.classes_archive_warning)); capture("archive-confirm")
            click("Cancel")
            click("Add co-teacher"); waitFor("Teacher ID"); capture("add-teacher")
            if (phase != "before") {
                val title = nodes().first { it.text?.toString() == "Add co-teacher" }
                val titleBounds = android.graphics.Rect().also(title::getBoundsInScreen)
                assertTrue("Form title must retain readable width", titleBounds.width() >= 100 * context.resources.displayMetrics.density)
            }
            click("Cancel")
            if (phase != "before") {
                reachable.value = false
                scrollTo(context.getString(org.lara.app.R.string.classes_offline))
                scrollTo("Edit details")
                var node = nodes().first { it.text?.toString() == "Edit details" }
                var disabled = !node.isEnabled
                while (node.parent != null) { node = node.parent; disabled = disabled || !node.isEnabled }
                assertTrue("Offline class edits must stay disabled", disabled)
                capture("class-offline-controls")
                reachable.value = true
            }
            show { androidx.compose.runtime.CompositionLocalProvider(LocalClassChrome provides ClassChrome("Science 4", "Aguinaldo") {}) {
                org.lara.app.ui.navigation.StudentNavGraph(org.lara.app.ui.navigation.ShellState(org.lara.app.ui.navigation.ShellRole.STUDENT, isHubReachable=true))
            } }
            click("Class actions"); capture("class-menu")
            for ((name, state, online) in listOf(
                Triple("settings", org.lara.app.ui.screens.settings.SettingsState(loading=false, hubName="Group 7 mock Hub", address="http://127.0.0.1:8080"), true),
                Triple("settings-offline", org.lara.app.ui.screens.settings.SettingsState(loading=false), false),
                Triple("settings-loading", org.lara.app.ui.screens.settings.SettingsState(), true),
                Triple("settings-queued", org.lara.app.ui.screens.settings.SettingsState(loading=false, queued=2), false))) {
                show { org.lara.app.ui.screens.settings.SettingsScreen(state, online, "0.1.0", {}, {}, {}, {}, {}) }
                waitFor(if (state.queued != null) "Stay signed in" else "Settings"); capture(name)
            }
        } } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun renameAndArchiveThroughProductionControls(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("menuMutations") == "true")
        check(context.packageName == "org.lara.app.group7")
        val root = (context.applicationContext as LaraApp).container
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        root.endpoints.save(endpoint, "Group 7 mock Hub")
        val user = root.auth.authenticate(endpoint, "T-0001", "1234", null)
        val active = root.forUser(user.id)
        val classroom = active.teacherClasses(user.id).create("Group 7 control check", "Synthetic")
        active.sync.pull(user.id)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { LaraTheme {
                org.lara.app.ui.screens.teacher.classes.ClassManagementPage(active, user.id,
                    kotlinx.coroutines.flow.MutableStateFlow(true), {}, initialClassId=classroom.id, close={})
            } } }
            click("Class settings"); click("Edit details"); waitFor("Class name")
            val field = nodes().first { it.isEditable && it.contentDescription?.toString() == "Class name" }
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Group 7 renamed")
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            click("Save"); scrollTo("Group 7 renamed")
            click("Archive class")
            waitFor(context.getString(org.lara.app.R.string.classes_archive_warning))
            click("Archive class")
            scrollTo(context.getString(org.lara.app.R.string.classes_archived))
            assertFalse(nodes().any { it.text?.toString() == "Edit details" && it.isEnabled })
            capture("archived-read-only")
        }
    }

}
