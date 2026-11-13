package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
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
import org.lara.app.domain.*
import org.lara.app.domain.discovery.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.model.*
import org.lara.app.ui.components.ClassroomTopBar
import org.lara.app.ui.screens.auth.*
import org.lara.app.ui.screens.classwork.*
import org.lara.app.ui.screens.discovery.*
import org.lara.app.ui.screens.teacher.*
import org.lara.app.ui.theme.*

/** Production components with explicit synthetic states; never writes to the regular app installation. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Group8UiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val phase get() = args.getString("group8Phase") ?: "after"
    private var render = 0
    private fun guard() { check(context.packageName == "org.lara.app.group8") }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label
    private fun waitFor(label: String) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { matches(it, label) }) return
            SystemClock.sleep(100)
        }
        error("Missing Group 8 control: $label")
    }
    private fun scrollTo(label: String) {
        if (nodes().any { matches(it, label) }) return
        repeat(10) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(60) }
        repeat(18) {
            if (nodes().any { matches(it, label) }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(120)
        }
        waitFor(label)
    }
    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun click(label: String) {
        scrollTo(label)
        var action: AccessibilityNodeInfo? = null
        repeat(18) {
            action = nodes().filter { matches(it, label) }.asReversed().map(::target)
                .firstOrNull { it.isClickable && it.isEnabled }
            if (action != null) {
                assertTrue("Control must respond: $label", requireNotNull(action).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync(); SystemClock.sleep(200)
                return
            }
            // At large text the visible label can precede its field below the viewport.
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("No enabled action: $label")
    }
    private fun enterText(label: String, value: String) {
        click(label)
        // Compose exposes merged field semantics differently across Android versions.
        // Exercise the real focused input connection instead of relying on editable-node labels.
        val input = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        waitUntil("Input connection must be ready: $label") { input.isAcceptingText }
        // Match the production focus-scroll delay and allow the IME resize to settle.
        SystemClock.sleep(450)
        instrumentation.sendStringSync(value)
        instrumentation.waitForIdleSync()
        if (label != context.getString(R.string.auth_pin)) waitUntil("Field must retain entered characters: $label") {
            nodes().any { it.text?.toString() == value }
        }
    }
    private fun show(scenario: ActivityScenario<MainActivity>, classroom: Boolean = false, content: @Composable () -> Unit) {
        val next = ++render
        scenario.onActivity { activity -> activity.setContent { LaraTheme {
            Surface(Modifier.fillMaxSize(), color = LaraColors.Canvas, contentColor = LaraColors.TextPrimary) {
                key(next) {
                    if (classroom) Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ClassroomTopBar("Science 4")
                        Box(Modifier.weight(1f)) { content() }
                    } else Box(if (phase == "before") Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()) { content() }
                }
            }
        } } }
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }
    private fun shot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (args.getString("group8Landscape") == "true") assertTrue(bitmap.width > bitmap.height)
            File(context.filesDir, "group8-$phase-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun authAndDiscoveryPresentation() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario) { AuthScreen(AuthState(restoring=false), { _, _, _ -> }, {}) }
            scrollTo(context.getString(R.string.auth_id)); shot("sign-in")
            scrollTo(context.getString(R.string.auth_pin)); shot("pin-system-field")
            click(context.getString(R.string.auth_register_instead)); scrollTo(context.getString(R.string.auth_name)); shot("registration")
            for ((name, state) in listOf(
                "auth-validation" to AuthState(restoring=false, error=R.string.auth_invalid),
                "auth-unreachable" to AuthState(restoring=false, error=R.string.auth_unreachable),
                "auth-wrong-pin" to AuthState(restoring=false, error=R.string.auth_wrong_pin),
                "auth-busy" to AuthState(restoring=false, busy=true),
                "auth-rate-limit" to AuthState(restoring=false, error=R.string.auth_wait, retryAt=SystemClock.elapsedRealtime()+60_000))) {
                show(scenario) { AuthScreen(state, { _, _, _ -> }, {}) }
                state.error?.let { scrollTo(context.getString(it)) } ?: scrollTo(context.getString(R.string.auth_checking))
                shot(name)
            }
            val snapshot = MutableStateFlow(DiscoverySnapshot(wifiAvailable=true))
            var outcome = ConnectionResult.INVALID_ADDRESS
            val fake = object : HubDiscovery {
                override fun observe() = snapshot
                override suspend fun connect(input: String) = outcome
                override suspend fun connect(hub: DiscoveredHub) = outcome
            }
            val model = DiscoveryViewModel(fake)
            show(scenario) { DiscoveryScreen(model) {} }
            scrollTo(context.getString(R.string.discovery_results)); shot("hub-empty")
            snapshot.value = DiscoverySnapshot(wifiAvailable=true, scanning=true)
            scrollTo(context.getString(R.string.discovery_scanning)); shot("hub-searching")
            snapshot.value = DiscoverySnapshot(wifiAvailable=true, hubs=listOf(DiscoveredHub("Sample classroom Hub", "192.168.1.50",8080,8081)))
            scrollTo("Sample classroom Hub"); shot("hub-found")
            scrollTo(context.getString(R.string.discovery_address)); shot("hub-manual")
            click(context.getString(R.string.discovery_connect)); scrollTo(context.getString(R.string.discovery_invalid)); shot("hub-invalid")
            outcome = ConnectionResult.UNREACHABLE
            click(context.getString(R.string.discovery_connect)); scrollTo(context.getString(R.string.discovery_unreachable)); shot("hub-unreachable")
            snapshot.value = DiscoverySnapshot(wifiAvailable=false)
            model.clearResult(); scrollTo(context.getString(R.string.discovery_offline)); shot("hub-offline")
            model.stop()
        }
    }

    @Test fun approvalAndDownloadPresentation() {
        guard()
        val member = RosterMember(Enrollment("group8-enrollment", "group8-class", "group8-learner", "PENDING", 1, 1),
            User("group8-learner", null, "Sample Learner", "STUDENT",1,1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val pending = ApprovalState(roster=Cached.Content(listOf(member)), offline=false, sheetOpen=true)
            for ((name, state) in listOf("approval-pending" to pending, "approval-offline" to pending.copy(offline=true),
                "approval-busy" to pending.copy(busy=setOf(member.enrollment.studentId)),
                "approval-error" to pending.copy(error=ApprovalError.REQUEST_FAILED),
                "approval-empty" to pending.copy(roster=Cached.Content(emptyList())),
                "approval-loading" to pending.copy(roster=Cached.Loading))) {
                show(scenario) { ApprovalSheet(state, {}, { _, _ -> }) }
                waitFor(context.getString(R.string.approval_title))
                if (phase.contains("200")) {
                    val detail = when (name) {
                        "approval-pending" -> R.string.approval_accept
                        "approval-offline" -> R.string.approval_offline
                        "approval-busy" -> R.string.approval_updating
                        "approval-error" -> R.string.approval_failed
                        "approval-empty" -> R.string.approval_empty
                        else -> null
                    }
                    detail?.let { scrollTo(context.getString(it)) }
                }
                shot(name)
            }
            for ((name, download) in listOf(
                "save-idle" to VideoDownload(), "save-progress" to VideoDownload(DownloadPhase.DOWNLOADING,50,100),
                "save-paused" to VideoDownload(DownloadPhase.PAUSED,50,100,VideoProblem.HUB_UNAVAILABLE),
                "save-complete" to VideoDownload(DownloadPhase.COMPLETE,100,100),
                "save-failed" to VideoDownload(DownloadPhase.FAILED,0,100,VideoProblem.STORAGE))) {
                show(scenario, classroom=true) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4)) { DownloadControls(download, {}, {}) } }
                if (phase.contains("200")) scrollTo(context.getString(when (download.phase) {
                    DownloadPhase.DOWNLOADING -> R.string.video_cancel
                    DownloadPhase.PAUSED -> R.string.video_resume
                    DownloadPhase.COMPLETE -> R.string.video_downloaded
                    DownloadPhase.FAILED -> R.string.video_download_failed
                    else -> R.string.video_save
                }))
                shot(name)
            }
        }
    }

    @Test fun cachedReaderAndPlayerPresentation(): Unit = runBlocking {
        guard()
        val owner = "group8-fixture"
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1",9))
        val container = AppContainer(context)
        container.endpoints.save(hub)
        val db = AccountDatabases(context).prepare(owner)
        db.authDao().saveProfile(UserEntity(owner,null,"Sample Teacher","TEACHER",1,1))
        TokenStore(context).save(StoredToken("synthetic-group8",owner,Long.MAX_VALUE,hub.restBase))
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        val account = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val folder = File(context.filesDir,"handouts/$account").apply { mkdirs() }
        val pdf = File(folder,"group8.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600,800,index+1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText("Sample lesson ${index+1}",40f,80f,android.graphics.Paint().apply { textSize=28f })
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val image = File(folder,"group8.png")
        val bitmap = Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(bitmap).drawText("Sample handout",40f,80f,android.graphics.Paint().apply { textSize=28f })
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
        val text = File(folder,"group8.docx").apply { writeText("Synthetic cached text fallback") }
        val videoFolder = File(context.filesDir,"videos/$account").apply { mkdirs() }
        val video = File(videoFolder,"${"a".repeat(64)}.video")
        instrumentation.context.assets.open("video-lesson.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val files = listOf(
            MaterialEntity("group8-pdf","group8-class","Sample PDF handout","DOCUMENT","application/pdf",pdf.length(),localFilePath=pdf.path,createdAt=1,updatedAt=1),
            MaterialEntity("group8-image","group8-class","Sample image handout","DOCUMENT","image/png",image.length(),localFilePath=image.path,createdAt=1,updatedAt=1),
            MaterialEntity("group8-text","group8-class","Sample lesson text","DOCUMENT","application/octet-stream",text.length(),localFilePath=text.path,createdAt=1,updatedAt=1),
            MaterialEntity("group8-missing","group8-class","Unsaved handout","DOCUMENT","application/pdf",100,createdAt=1,updatedAt=1),
            MaterialEntity("group8-video","group8-class","Sample lesson video","VIDEO","video/mp4",video.length(),localFilePath=video.path,createdAt=1,updatedAt=1))
        db.cacheDao().upsertBatch(CacheBatch(classrooms=listOf(ClassroomEntity("group8-class","Science 4","Sample class","K7M4QX",owner,createdAt=1,updatedAt=1)),
            materials=files, materialChunks=listOf(MaterialChunkEntity("group8-chunk","group8-text",0,"Plants","Read the lesson and notice the parts of a leaf.",updatedAt=1))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((id,title) in listOf("group8-pdf" to "Sample PDF handout", "group8-image" to "Sample image handout", "group8-text" to "Sample lesson text", "group8-missing" to "Unsaved handout")) {
                val row = files.first { it.id == id }
                val material = Material(row.id,row.classroomId,row.title,row.fileType,row.mimeType,row.fileSizeBytes,null,row.localFilePath,null,null,null,1,1)
                show(scenario,classroom=true) { MaterialReader(active,material) {} }
                waitFor(title)
                if (id == "group8-pdf") {
                    scrollTo(context.getString(R.string.reading_page,1,2)); shot("reader-pdf")
                    click(context.getString(R.string.reading_next)); scrollTo(context.getString(R.string.reading_page,2,2)); shot("reader-page-two")
                    click(context.getString(R.string.reading_zoom_in)); shot("reader-zoom")
                } else {
                    if (id == "group8-image") SystemClock.sleep(500)
                    if (id == "group8-text") scrollTo("Plants")
                    if (id == "group8-missing") scrollTo(context.getString(R.string.reading_file_unavailable))
                    shot(id)
                }
            }
            show(scenario,classroom=true) { VideoPlayerScreen(active,owner,"group8-video") {} }
            waitFor("Sample lesson video"); scrollTo(context.getString(R.string.video_local)); shot("video-saved")
            scrollTo(context.getString(R.string.video_play)); click(context.getString(R.string.video_play)); SystemClock.sleep(1200); shot("video-playing")
            click(context.getString(R.string.video_pause))
            click(context.getString(R.string.video_fullscreen)); waitFor(context.getString(R.string.video_exit_fullscreen)); shot("video-fullscreen")
            click(context.getString(R.string.video_exit_fullscreen))
            show(scenario,classroom=true) { VideoPlayerScreen(active,owner,"group8-no-video") {} }
            waitFor(context.getString(R.string.video_empty_title)); shot("video-unavailable")
        }
    }
    private fun waitUntil(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error(message)
    }
    private fun keyboardShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity -> visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        return visible
    }

    @Test fun inputCallbacksAndKeyboardRemainUsable() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val submitted = java.util.concurrent.atomic.AtomicReference<Triple<String,String,String?>?>()
            val authState = mutableStateOf(AuthState(restoring=false))
            show(scenario) { AuthScreen(authState.value, { id, pin, name -> submitted.set(Triple(id,pin,name)) }, {}) }
            click(context.getString(R.string.auth_id))
            waitUntil("ID keyboard must open") { keyboardShown(scenario) }
            shot("keyboard-blank-id")
            click(context.getString(R.string.auth_pin))
            waitUntil("PIN numeric keyboard must open") { keyboardShown(scenario) }
            shot("keyboard-blank-pin")
            enterText(context.getString(R.string.auth_id), "SAMPLE-TEACHER")
            enterText(context.getString(R.string.auth_pin), "12a456")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitUntil("Back must dismiss the keyboard") { !keyboardShown(scenario) }
            click(context.getString(R.string.auth_login))
            assertTrue("Sign-in callback must receive the unchanged values", submitted.get()?.let {
                it.first == "SAMPLE-TEACHER" && it.second == "1245" && it.third == null } == true)
            click(context.getString(R.string.auth_pin))
            val passwordFields = nodes().filter { it.isPassword }
            assertTrue("PIN field must retain password semantics", passwordFields.isNotEmpty())
            assertTrue("PIN must clear after submission", passwordFields.all { it.text.isNullOrEmpty() })
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitUntil("Submission must disable until a new PIN is entered") {
                nodes().filter { matches(it, context.getString(R.string.auth_login)) }.map(::target)
                    .any { it.isClickable && !it.isEnabled }
            }
            authState.value = authState.value.copy(error=R.string.auth_wrong_pin)
            waitFor(context.getString(R.string.auth_wrong_pin))
            show(scenario) { AuthScreen(AuthState(restoring=false), { id, pin, name -> submitted.set(Triple(id,pin,name)) }, {}) }
            click(context.getString(R.string.auth_register_instead))
            click(context.getString(R.string.auth_lrn))
            waitUntil("Registration numeric keyboard must open") { keyboardShown(scenario) }
            shot("keyboard-blank-registration")
            click(context.getString(R.string.auth_pin))
            waitUntil("Registration PIN numeric keyboard must open") { keyboardShown(scenario) }
            shot("keyboard-blank-registration-pin")
            enterText(context.getString(R.string.auth_lrn), "999999999999")
            enterText(context.getString(R.string.auth_name), "Sample Learner")
            enterText(context.getString(R.string.auth_pin), "12a456")
            assertTrue("Registration PIN must retain password semantics", nodes().any { it.isPassword })
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitUntil("Back must dismiss registration keyboard") { !keyboardShown(scenario) }
            click(context.getString(R.string.auth_register))
            assertTrue("Existing registration callback must receive name and LRN", submitted.get()?.let {
                it.first == "999999999999" && it.second == "1245" && it.third == "Sample Learner" } == true)
            val choices = mutableListOf<Boolean>()
            val member = RosterMember(Enrollment("interaction-request","interaction-class","interaction-learner","PENDING",1,1),
                User("interaction-learner",null,"Sample Learner","STUDENT",1,1))
            val pending = ApprovalState(roster=Cached.Content(listOf(member)),offline=false,sheetOpen=true)
            for (accept in listOf(true,false)) {
                show(scenario) { ApprovalSheet(pending, {}, { _, decision -> choices += decision }) }
                click(context.getString(if (accept) R.string.approval_accept else R.string.approval_decline))
            }
            assertEquals(listOf(true,false), choices)
            var saves = 0; var pauses = 0
            for (phase in listOf(DownloadPhase.DOWNLOADING,DownloadPhase.PAUSED)) {
                show(scenario,classroom=true) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4)) {
                    DownloadControls(VideoDownload(phase,50,100), { saves++ }, { pauses++ })
                } }
                click(context.getString(if (phase == DownloadPhase.DOWNLOADING) R.string.video_cancel else R.string.video_resume))
            }
            assertEquals(1,saves); assertEquals(1,pauses)
        }
    }

    @Test fun pinKeyboardPresentation() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (register in listOf(false, true)) {
                show(scenario) { AuthScreen(AuthState(restoring=false), { _, _, _ -> }, {}) }
                if (register) click(context.getString(R.string.auth_register_instead))
                click(context.getString(R.string.auth_pin))
                waitUntil("PIN keyboard must open for capture") { keyboardShown(scenario) }
                SystemClock.sleep(700)
                assertTrue("PIN keyboard must remain visible after focus scrolling settles", keyboardShown(scenario))
                shot(if (register) "registration-pin-system-keyboard" else "sign-in-pin-system-keyboard")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitUntil("Back must dismiss the captured keyboard") { !keyboardShown(scenario) }
            }
        }
    }

    @Test fun hardwareKeyboardFocusAndSubmission() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val submitted = java.util.concurrent.atomic.AtomicReference<Triple<String,String,String?>?>()
            show(scenario) { AuthScreen(AuthState(restoring=false), { id, pin, name -> submitted.set(Triple(id,pin,name)) }, {}) }
            enterText(context.getString(R.string.auth_id), "SAMPLE-TEACHER")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            waitUntil("Tab must move from ID to PIN") { nodes().any { it.isFocused && it.isPassword } }
            instrumentation.sendStringSync("1234")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            waitUntil("Hardware Enter must submit sign-in") { submitted.get() == Triple("SAMPLE-TEACHER", "1234", null) }
            submitted.set(null)
            show(scenario) { AuthScreen(AuthState(restoring=false), { id, pin, name -> submitted.set(Triple(id,pin,name)) }, {}) }
            click(context.getString(R.string.auth_register_instead))
            enterText(context.getString(R.string.auth_lrn), "999999999999")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            waitUntil("Tab must focus the name input") { nodes().any { it.isFocused && !it.isPassword } }
            instrumentation.sendStringSync("Sample Learner")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            waitUntil("Tab must move from name to PIN") { nodes().any { it.isFocused && it.isPassword } }
            instrumentation.sendStringSync("123")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            assertNull("Incomplete PIN must never submit", submitted.get())
            enterText(context.getString(R.string.auth_pin), "4")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            waitUntil("Hardware Enter must submit registration") {
                submitted.get() == Triple("999999999999", "1234", "Sample Learner")
            }
        }
    }

    @Test fun liveMockAuthenticationApprovalAndMedia(): Unit = runBlocking {
        assumeTrue(args.getString("group8Mock") == "true")
        guard()
        val root = AppContainer(context)
        root.auth.cachedUser()?.let { previous -> root.forUser(previous.id).settings.signOut(previous.id) }
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        root.endpoints.save(endpoint,"Group 8 mock Hub")
        val scan = DiscoveryViewModel(root.discovery)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario) { DiscoveryScreen(scan) {} }
            enterText(context.getString(R.string.discovery_address),"127.0.0.1:8080")
            click(context.getString(R.string.discovery_connect))
            waitFor(context.getString(R.string.discovery_responding))
            shot("mock-hub-connected")
        }
        var teacher: User? = null
        var learner: User? = null
        for (register in listOf(false,true)) {
            val model = AuthViewModel(root.auth,root.endpoints)
            try {
                waitUntil("Authentication restoration must finish") { !model.state.value.restoring }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    show(scenario) { val state by model.state.collectAsState(); AuthScreen(state,model::submit,{}) }
                    val label = context.getString(if (register) R.string.auth_lrn else R.string.auth_id)
                    if (register) click(context.getString(R.string.auth_register_instead))
                    val id = if (register) "9" + System.nanoTime().toString().takeLast(11).padStart(11,'0') else "T-0001"
                    enterText(label,id)
                    if (register) enterText(context.getString(R.string.auth_name),"Group 8 Sample Learner")
                    enterText(context.getString(R.string.auth_pin), "1234")
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    waitUntil("Back must dismiss authentication keyboard") { !keyboardShown(scenario) }
                    click(context.getString(if (register) R.string.auth_register else R.string.auth_login))
                    waitUntil("Mock authentication must complete successfully") { model.state.value.user != null }
                    val user = requireNotNull(model.state.value.user)
                    assertEquals(if (register) "STUDENT" else "TEACHER",user.role)
                    if (register) learner = user else teacher = user
                }
            } finally { instrumentation.runOnMainSync { model.viewModelScope.cancel() } }
            if (!register) root.forUser(requireNotNull(teacher).id).settings.signOut(requireNotNull(teacher).id)
        }
        val student = requireNotNull(learner)
        val learnerContainer = root.forUser(student.id)
        assertEquals("PENDING",learnerContainer.enrollmentApi.join(student.id,"K7M4QX").status.name)
        learnerContainer.settings.signOut(student.id)
        val teacherUser = root.auth.authenticate(endpoint,"T-0001","1234",null)
        val active = root.forUser(teacherUser.id)
        active.sync.pull(teacherUser.id)
        val classroom = active.enrollmentApi.classrooms(teacherUser.id).first { it.classCode == "K7M4QX" }
        val remote = active.classManagement(teacherUser.id)
        val refresher = active.savedRosterRefresher(remote,teacherUser.id)
        refresher.refreshIfActive(classroom.id)
        val roster = RosterViewModel(classroom.id,classroom.classCode,active.enrollments,remote,
            MutableStateFlow(true),kotlinx.coroutines.flow.emptyFlow(),
            { id -> active.sync.refreshRoster(teacherUser.id) { refresher.refreshIfActive(id) } }, {})
        try {
            waitUntil("Mock join request must reach the cached roster") { roster.state.value.pending.any { it.enrollment.studentId == student.id } }
            roster.show()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                show(scenario) { TeacherApprovalHost(roster) }
                val pendingCount = roster.state.value.pending.size
                repeat(pendingCount) {
                    val previous = roster.state.value.pending.size
                    click(context.getString(R.string.approval_accept))
                    waitUntil("Sequential approval must finish") {
                        roster.state.value.busy.isEmpty() && roster.state.value.pending.size < previous
                    }
                }
                assertTrue(remote.roster(classroom.id).any { it.studentId == student.id && it.status == EnrollmentStatus.ACTIVE })
                waitFor(context.getString(R.string.approval_empty)); shot("mock-approval-complete")
            }
        } finally { instrumentation.runOnMainSync { roster.viewModelScope.cancel() } }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        try {
            val cached = active.classwork.classwork(classroom.id,scope).first { it is Cached.Content }
            val work = (cached as Cached.Content).value
            val handout = work.materials.first { it.fileType == "DOCUMENT" }
            val video = work.materials.first { it.fileType == "VIDEO" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                show(scenario,classroom=true) { MaterialReader(active,handout) {} }
                waitFor("Ang Ecosystem"); shot("mock-handout-text")
                show(scenario,classroom=true) { VideoPlayerScreen(active,teacherUser.id,video.id) {} }
                click(context.getString(R.string.video_play))
                waitUntil("Mock video playback must advance") {
                    var playing = false
                    scenario.onActivity { activity ->
                        val session = androidx.lifecycle.ViewModelProvider(activity)["video-player-${teacherUser.id}-${video.id}",VideoPlaybackSession::class.java]
                        playing = (session.player?.currentPosition ?: 0) > 500
                    }; playing
                }
                click(context.getString(R.string.video_pause)); shot("mock-video-stream")
                click(context.getString(R.string.video_save))
                scrollTo(context.getString(R.string.video_local))
                assertTrue(active.videos.source(video.id) is VideoSource.Local)
                shot("mock-video-saved")
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

}
