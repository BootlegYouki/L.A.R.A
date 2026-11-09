package org.lara.app.reading

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.MainActivity
import org.lara.app.domain.Cached
import org.lara.app.domain.RosterMember
import org.lara.app.domain.discovery.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.model.*
import org.lara.app.ui.navigation.*
import org.lara.app.ui.screens.auth.*
import org.lara.app.ui.screens.discovery.*
import org.lara.app.ui.screens.enrollment.*
import org.lara.app.ui.screens.settings.*
import org.lara.app.ui.screens.teacher.*
import org.lara.app.ui.screens.teacher.classes.*
import org.lara.app.ui.theme.*

/** Same production components and synthetic state on both sides of the layout change. */
@RunWith(AndroidJUnit4::class)
class ClassroomUiEvidenceTest {
    @Test fun captureExistingScreenStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.lara.app.sprint31reading")
        val classroom = Classroom("ui-class", "Science 4", "Section Mabini", "K7M4QX", "ui-teacher", null, null, 1, 1)
        val enrollment = Enrollment("ui-enrollment", classroom.id, "ui-learner", "ACTIVE", 1, 1)
        val member = RosterMember(enrollment.copy(status = "PENDING"), User("ui-learner", "SYNTHETIC", "Sample Learner", "STUDENT", 1, 1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(name: String, content: @Composable () -> Unit) {
                scenario.onActivity { activity -> activity.setContent { LaraTheme {
                    Surface(Modifier.fillMaxSize(), color = LaraColors.Canvas, contentColor = LaraColors.TextPrimary) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
                    }
                } } }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(context.filesDir, "ui244-$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                } } finally { bitmap.recycle() }
            }
            show("signin") { AuthScreen(AuthState(restoring = false), { _, _, _ -> }, {}) }
            show("signin-hub-step") { AuthScreen(AuthState(restoring = false, error = org.lara.app.R.string.auth_hub_required), { _, _, _ -> }, {}) }
            val discovery = DiscoveryViewModel(object : HubDiscovery {
                override fun observe() = flowOf(DiscoverySnapshot(wifiAvailable = true,
                    hubs = listOf(DiscoveredHub("Synthetic classroom Hub", "192.168.1.50", 8080, 8081))))
                override suspend fun connect(input: String) = ConnectionResult.RESPONDING
                override suspend fun connect(hub: DiscoveredHub) = ConnectionResult.RESPONDING
            })
            show("discovery") { DiscoveryScreen(discovery) {} }
            val home = EnrollmentScreenState(classes = Cached.Content(listOf(LearnerClass(classroom, enrollment))),
                noticesLoaded = true, offline = false)
            show("home") { EnrollmentScreen(home, {}, {}, {}, {}) }
            show("home-offline") { EnrollmentScreen(home.copy(offline = true), {}, {}, {}, {}) }
            show("home-loading") { EnrollmentScreen(home.copy(classes = Cached.Loading), {}, {}, {}, {}) }
            show("home-empty") { EnrollmentScreen(home.copy(classes = Cached.Content(emptyList())), {}, {}, {}, {}) }
            show("waiting") { EnrollmentScreen(home.copy(classes = Cached.Content(listOf(LearnerClass(classroom,
                enrollment.copy(status = "PENDING"))))), {}, {}, {}, {}) }
            show("join") { JoinClassDialog(true, false, null, {}, {}) }
            show("pending") { ApprovalSheet(ApprovalState(roster = Cached.Content(listOf(member)), offline = false, sheetOpen = true), {}, { _, _ -> }) }
            show("people") { Column(Modifier.padding(LaraSpacing.S3), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                ClassPersonCard("Sample Teacher", "Teacher")
                ClassPersonCard("Sample Learner", "Active")
            } }
            show("manage-class") { Column(Modifier.padding(LaraSpacing.S3)) { ManagedClassCard(classroom, true, true) {} } }
            show("settings") { SettingsScreen(SettingsState(loading = false, hubName = "Synthetic classroom Hub", address = "http://192.168.1.50:8080"),
                true, "0.1.0", {}, {}, {}, {}, {}) }
            show("settings-offline") { SettingsScreen(SettingsState(loading = false, address = "http://192.168.1.50:8080"),
                false, "0.1.0", {}, {}, {}, {}, {}) }
            show("class-shell") {
                androidx.compose.runtime.CompositionLocalProvider(org.lara.app.ui.components.LocalClassChrome provides
                    org.lara.app.ui.components.ClassChrome(classroom.name, classroom.section) {}) {
                    StudentNavGraph(ShellState(ShellRole.STUDENT, isHubReachable = true))
                }
            }
        }
    }
}
