package org.lara.app.teacher

import android.content.res.Configuration
import android.os.LocaleList
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.MainActivity
import org.lara.app.R
import org.lara.app.data.remote.*
import org.lara.app.domain.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.model.*
import org.lara.app.ui.screens.teacher.*
import org.lara.app.ui.theme.LaraTheme
import org.lara.app.ui.theme.LaraSpacing
import java.io.File

/** Exercises production UI with synthetic saved rows; it does not implement the sync producer. */
@RunWith(AndroidJUnit4::class)
class TeacherApprovalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun englishApprovalRollbackOfflineAndSuccessfulDecision() = exercise("en", 1f)
    @Test fun filipinoApprovalRollbackOfflineAndSuccessfulDecisionAtLargeFont() = exercise("fil", 2f)

    @Suppress("DEPRECATION")
    private fun exercise(language: String, fontScale: Float) {
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
            this.fontScale = fontScale
        }
        val localized = context.createConfigurationContext(configuration)
        fun text(id: Int) = localized.getString(id)
        val row = RosterMember(Enrollment("fixture-enrollment", "fixture-class", "fixture-learner", "PENDING", 1, 1),
            User("fixture-learner", "SYNTHETIC", "Synthetic Learner", "STUDENT", 1, 1))
        val rows = MutableStateFlow<Cached<List<RosterMember>>>(Cached.Loading)
        val reachable = MutableStateFlow(true)
        val events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 4)
        val cache = object : EnrollmentRepository {
            override fun roster(classroomId: String, scope: CoroutineScope) = rows
            override fun learnerClasses(studentId: String, scope: CoroutineScope) =
                MutableStateFlow<Cached<List<LearnerClass>>>(Cached.Content(emptyList()))
        }
        var shouldFail = true
        var decisionCalls = 0
        val remote = object : ClassManagementRepository {
            override suspend fun roster(classroomId: String) = emptyList<TeacherRosterEntry>()
            override suspend fun decide(classroomId: String, studentId: String, accept: Boolean) {
                decisionCalls++
                delay(200)
                if (shouldFail) throw HubFailure(503, "UNAVAILABLE")
                rows.value = Cached.Content(listOf(row.copy(enrollment = row.enrollment.copy(
                    status = if (accept) "ACTIVE" else "REJECTED"))))
            }
        }
        lateinit var model: RosterViewModel
        lateinit var originalConfiguration: Configuration
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                originalConfiguration = Configuration(activity.resources.configuration)
                // Dialog windows inherit activity resources rather than composition-local resources.
                activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                model = RosterViewModel("fixture-class", "K7M4QX", cache, remote, reachable, events, {}, {})
                model.show()
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                        LocalDensity provides Density(density.density, fontScale)) {
                        LaraTheme {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(LaraSpacing.S4)) {
                                TeacherApprovalHost(model)
                            }
                        }
                    }
                }
            }
            waitFor { find(text(R.string.approval_loading)) != null }
            screenshot("$language-loading")
            rows.value = Cached.Content(listOf(row))
            waitFor { find(text(R.string.approval_accept)) != null }
            screenshot("$language-pending")
            click(text(R.string.approval_accept))
            waitFor { model.state.value.error == ApprovalError.REQUEST_FAILED && model.state.value.busy.isEmpty() }
            assertEquals(1, model.state.value.pending.size)
            scrollTo(text(R.string.approval_failed))
            screenshot("$language-rollback")
            reachable.value = false
            waitFor { model.state.value.offline }
            screenshot("$language-offline-banner")
            scrollTo(text(R.string.approval_accept))
            waitFor { find(text(R.string.approval_accept)) != null && !enabled(text(R.string.approval_accept)) }
            scrollTo(text(R.string.approval_decline))
            waitFor { find(text(R.string.approval_decline)) != null && !enabled(text(R.string.approval_decline)) }
            assertNotNull(find(text(R.string.enrollment_cancel_action)))
            assertEquals(1, decisionCalls)
            screenshot("$language-offline")
            reachable.value = true
            shouldFail = false
            waitFor { enabled(text(R.string.approval_decline)) }
            click(text(R.string.approval_decline))
            waitFor { model.state.value.pending.isEmpty() && model.state.value.busy.isEmpty() }
            assertEquals("REJECTED", (rows.value as Cached.Content).value.single().enrollment.status)
            scenario.onActivity { model.dismiss() }
            waitFor { find(text(R.string.approval_title)) == null }
            rows.value = Cached.Content(listOf(row))
            assertTrue(events.tryEmit(HubEvent.JoinRequest("K7M-4QX", "fixture-learner", "Synthetic Learner", "SYNTHETIC", 1)))
            waitFor { model.state.value.sheetOpen && find(text(R.string.approval_accept)) != null }
            click(text(R.string.approval_accept))
            waitFor { model.state.value.pending.isEmpty() && model.state.value.busy.isEmpty() }
            assertEquals("ACTIVE", (rows.value as Cached.Content).value.single().enrollment.status)
            screenshot("$language-approved")
        } catch (error: Throwable) {
            screenshot("$language-failure")
            throw error
        } finally {
            scenario.onActivity {
                model.viewModelScope.cancel()
                it.resources.updateConfiguration(originalConfiguration, it.resources.displayMetrics)
            }
            scenario.close()
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it)?.let { found -> return found } } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun enabled(label: String): Boolean {
        var node = find(label) ?: return false
        while (!node.isClickable && node.parent != null) {
            if (!node.isEnabled) return false
            node = node.parent
        }
        return node.isEnabled
    }

    private fun click(label: String) {
        scrollTo(label)
        var node = requireNotNull(find(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.isEnabled)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun scrollTo(label: String) {
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { scrollable(it)?.let { found -> return found } }
            }
            return null
        }
        if (find(label) != null) return
        repeat(6) {
            val root = requireNotNull(automation.rootInActiveWindow)
            scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(100)
        }
        repeat(12) {
            if (find(label) != null) return
            val root = requireNotNull(automation.rootInActiveWindow)
            requireNotNull(scrollable(root)).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        error("Approval action is not reachable by scrolling")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Approval UI condition timed out" }
            SystemClock.sleep(100)
        }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "approval-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
