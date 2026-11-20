package org.lara.app.teacher

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.*
import org.lara.app.data.local.AccountDatabases
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.toDomain
import org.lara.app.domain.*
import org.lara.app.ui.screens.teacher.classwork.TeacherClasswork
import org.lara.app.ui.theme.LaraTheme

/** Uses synthetic Mock Hub accounts, restores saved session/preferences, never clears app data. */
@RunWith(AndroidJUnit4::class)
class ClassworkAuthoringDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { list.add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return list
    }
    private fun waitFor(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { it.text?.toString() == text }) return
            SystemClock.sleep(100)
        }
        error("Missing control: $text")
    }
    private fun click(text: String) {
        waitFor(text)
        val node = nodes().first { it.text?.toString() == text }
        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        SystemClock.sleep(500)
    }
    private fun enterTitle(value: String) {
        val field = nodes().first { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private fun scrollTo(text: String) {
        repeat(12) {
            if (nodes().any { it.text?.toString() == text }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(300)
        }
        waitFor(text)
    }
    private fun menuFor(title: String) {
        waitFor(title)
        val node = nodes().first { it.text?.toString() == title }
        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
        val x = context.resources.displayMetrics.widthPixels - 42 * context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        SystemClock.sleep(500)
    }
    private fun closeEditor() {
        repeat(10) {
            if (nodes().any { it.text?.toString() == "Discard" }) { click("Discard"); waitFor("Create"); return }
            if (nodes().any { it.text?.toString() == "Create" }) return
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(600)
        }
        waitFor("Create")
    }
    private fun shot(name: String) {
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, "authoring-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun login(hub: HubEndpoint): AuthSession = runBlocking {
        val response = HubClient().postAuth(hub, "/api/auth/login", buildJsonObject {
            put("lrn_or_id", "T-0001"); put("pin", "123456")
        }.toString())
        Json { ignoreUnknownKeys = true }.decodeFromString(AuthSession.serializer(), response)
    }
    @Test fun teacherFormsAndLargeUpload(): Unit = runBlocking {
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 38082, 38081))
        val root = (context.applicationContext as LaraApp).container
        val tokens = TokenStore(context)
        val previousToken = tokens.load()
        val endpointPrefs = context.getSharedPreferences("hub_endpoint", 0)
        val previousPrefs = endpointPrefs.all.toMap()
        val session = login(hub)
        val accounts = AccountDatabases(context)
        val database = accounts.prepare(session.user.id)
        database.authDao().saveProfile(UserEntity(session.user.id, session.user.lrnOrId, "Synthetic teacher", "TEACHER", 1, 1))
        tokens.save(StoredToken(session.token, session.user.id, session.expiresAt, hub.restBase))
        root.endpoints.save(hub, "Authoring test Hub")
        var uri: android.net.Uri? = null
        try {
            assertEquals(session.user.id, root.syncUserId())
            val account = root.forUser(session.user.id)
            account.sync.pull(session.user.id)
            val classroom = database.classroomDao().observeAll().first().first().toDomain()
            val modelWork = database.classworkDao()
            var online by mutableStateOf(true)
            var font by mutableFloatStateOf(1f)
            var empty by mutableStateOf(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val scope = rememberCoroutineScope()
                    val work by remember { account.classwork.classwork(classroom.id, scope) }.collectAsState()
                    val posts by remember { account.stream.stream(classroom.id, scope) }.collectAsState()
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) { LaraTheme {
                        androidx.compose.material3.Scaffold { insets ->
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(insets)) {
                                (work as? Cached.Content)?.value?.let { TeacherClasswork(account.classworkAuthoring, session.user.id, classroom,
                                    if (empty) Classwork(emptyList(), emptyList(), emptyList(), emptyList()) else it,
                                    (posts as? Cached.Content)?.value.orEmpty(), online) {} }
                            }
                        }
                    } }
                } }
                if (InstrumentationRegistry.getArguments().getString("budgetOnly") != "true") {
                waitFor("Create"); shot("classwork")
                scenario.onActivity { empty = true }; SystemClock.sleep(600); shot("empty")
                scenario.onActivity { empty = false }; SystemClock.sleep(600)
                click("Create"); shot("create-menu"); click("Assignment"); waitFor("Title (required)"); shot("assignment-empty")
                // Labelled native text fields support accessibility ACTION_SET_TEXT.
                val field = nodes().first { it.isEditable }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Phone-created assignment")
                }))
                shot("assignment-filled"); click("Save")
                waitFor("Phone-created assignment"); shot("assignment-created")
                click("Create"); click("Topic"); waitFor("Title (required)"); shot("topic")
                enterTitle("Phone topic"); click("Save"); waitFor("Phone topic"); shot("topic-created")
                click("Phone-created assignment"); waitFor("Title (required)")
                enterTitle("Phone assignment edited")
                scrollTo("No topic"); click("No topic"); click("Phone topic"); click("Save")
                waitFor("Phone assignment edited"); shot("assignment-edited")
                menuFor("Phone topic"); click("Edit"); waitFor("Title (required)")
                enterTitle("Renamed phone topic"); click("Save"); waitFor("Renamed phone topic"); shot("topic-renamed")
                menuFor("Renamed phone topic"); click("Delete"); waitFor("Delete this item?"); shot("delete-topic"); click("Delete")
                waitFor("Phone assignment edited")
                assertNull(modelWork.observeAssignments(classroom.id).first().first { it.title == "Phone assignment edited" }.topicId)
                shot("topic-deleted")
                menuFor("Phone assignment edited"); click("Delete"); waitFor("Delete this item?"); shot("delete-assignment"); click("Delete")
                SystemClock.sleep(1000)
                assertFalse(modelWork.observeAssignments(classroom.id).first().any { it.title == "Phone assignment edited" })
                shot("assignment-deleted")
                click("Create"); click("Material"); shot("material")
                click("Add attachment"); shot("file-picker")
                closeEditor()
                scenario.onActivity { online = false }; SystemClock.sleep(600); shot("offline")
                scenario.onActivity { font = 2f }; SystemClock.sleep(600); shot("font200")
                scenario.onActivity { font = 1f; online = true }
                }
                if (Build.VERSION.SDK_INT >= 29 && InstrumentationRegistry.getArguments().getString("screensOnly") != "true") {
                    uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "lara-synthetic-large.mp4")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })
                    val selected = requireNotNull(uri)
                    val payloadMiB = InstrumentationRegistry.getArguments().getString("payloadMiB")?.toIntOrNull()?.coerceIn(1, 220) ?: 220
                    context.contentResolver.openOutputStream(selected)!!.use { output ->
                        val buffer = ByteArray(32 * 1024)
                        repeat(payloadMiB * 32) { output.write(buffer) }
                    }
                    context.contentResolver.update(selected, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    val previousUploads = account.classworkAuthoring.uploads(classroom.id).first().map { it.id }.toSet()
                    account.classworkAuthoring.upload(classroom.id, selected.toString(), MaterialDraft("Synthetic $payloadMiB MB video", "VIDEO"))
                    val samples = File(context.filesDir, "authoring-memory.csv")
                    samples.writeText("elapsed_ms,java_used_bytes,java_committed_bytes,native_allocated_bytes\n")
                    val started = SystemClock.elapsedRealtime()
                    var succeeded = false
                    var captured = false
                    while (SystemClock.elapsedRealtime() - started < 360_000) {
                        val runtime = Runtime.getRuntime()
                        samples.appendText("${SystemClock.elapsedRealtime()-started},${runtime.totalMemory()-runtime.freeMemory()},${runtime.totalMemory()},${android.os.Debug.getNativeHeapAllocatedSize()}\n")
                        val uploads = account.classworkAuthoring.uploads(classroom.id).first().filterNot { it.id in previousUploads }
                        if (!captured && uploads.any { it.state == "RUNNING" && it.percent > 0 }) { shot("upload-progress"); captured = true }
                        if (uploads.any { it.state == "SUCCEEDED" }) { succeeded = true; break }
                        assertFalse("Worker failed", uploads.any { it.state == "FAILED" })
                        SystemClock.sleep(500)
                    }
                    assertTrue("Large upload timed out", succeeded)
                    shot("upload-complete")
                    account.sync.pull(session.user.id)
                    assertTrue(modelWork.observeMaterials(classroom.id).first().any { it.title == "Synthetic $payloadMiB MB video" })
                    val max = samples.readLines().drop(1).maxOf { it.split(',')[1].toLong() }
                    assertTrue("Java heap exceeds 250 MB: $max", max < 250L * 1024 * 1024)
                    println("OPPO CPH2179 Android 10: ${payloadMiB} MiB synthetic payload uploaded; max sampled Java used heap=$max bytes; USB reverse transport")
                    context.contentResolver.openFileDescriptor(selected, "rw")!!.use { descriptor ->
                        java.io.FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                            channel.position(251L * 1024 * 1024 - 1)
                            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
                        }
                    }
                    val count = account.classworkAuthoring.uploads(classroom.id).first().size
                    try {
                        account.classworkAuthoring.upload(classroom.id, selected.toString(), MaterialDraft("Oversize rejected fixture", "VIDEO"))
                        fail("251 MiB accepted")
                    } catch (error: IllegalArgumentException) { assertEquals("FILE_TOO_LARGE", error.message) }
                    assertEquals(count, account.classworkAuthoring.uploads(classroom.id).first().size)
                    println("251 MiB content URI rejected before network enqueue")
                }
            }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            if (previousToken == null) tokens.clear() else tokens.save(previousToken)
            val editor = endpointPrefs.edit().clear()
            previousPrefs.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value); is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value); is Boolean -> editor.putBoolean(key, value)
            } }
            editor.commit()
            accounts.close()
        }
    }
}
