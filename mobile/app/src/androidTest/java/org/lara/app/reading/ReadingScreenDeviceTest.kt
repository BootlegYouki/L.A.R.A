package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.AppContainer
import org.lara.app.MainActivity
import org.lara.app.data.local.AccountDatabases
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.StoredToken
import org.lara.app.data.remote.TokenStore
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.toDomain
import org.lara.app.ui.navigation.ShellDestination
import org.lara.app.ui.screens.stream.ClassroomLearningHost
import org.lara.app.ui.screens.stream.ReadingSkeleton
import org.lara.app.ui.screens.classwork.MaterialReader
import org.lara.app.ui.theme.LaraTheme

/** Synthetic local fixtures exercise real components without modifying the shared mock. */
@RunWith(AndroidJUnit4::class)
class ReadingScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) result.add(node)
            repeat(node.childCount) { i -> node.getChild(i)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun waitFor(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { it.text?.toString() == text }) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Reading control missing: $text")
    }
    private fun click(text: String) {
        waitFor(text)
        var node = nodes().first { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scrollTo(text: String) {
        repeat(12) {
            if (nodes().any { it.text?.toString() == text }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        waitFor(text)
    }
    private fun shot(name: String) {
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, "reading-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun pinch(title: String) {
        val node = nodes().first { it.contentDescription?.toString() == title }
        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> android.view.MotionEvent.PointerProperties().apply {
            id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        } }
        fun event(action: Int, count: Int, distance: Float) {
            val coordinates = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = rect.exactCenterX() + if (index == 0) -distance else distance
                y = rect.exactCenterY(); pressure = 1f; size = 1f
            } }
            val motion = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(motion, true)) }
            finally { motion.recycle() }
        }
        event(android.view.MotionEvent.ACTION_DOWN, 1, 50f)
        event(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 50f)
        repeat(10) { step -> SystemClock.sleep(20); event(android.view.MotionEvent.ACTION_MOVE, 2, 50f + step * 12f) }
        event(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 158f)
        event(android.view.MotionEvent.ACTION_UP, 1, 158f)
    }
    @Test fun streamCommentsNativeReadersAndLargeTextWorkOffline(): Unit = runBlocking {
        // This fixture writes auth/cache data; never run it over the user's production package.
        check(context.packageName == "org.lara.app.sprint31reading")
        val owner = "synthetic-reader-${System.nanoTime()}"
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 9))
        val container = AppContainer(context)
        container.endpoints.save(hub)
        val db = AccountDatabases(context).prepare(owner)
        db.authDao().saveProfile(UserEntity(owner, "SYNTHETIC", "Synthetic Teacher", "TEACHER", 1, 1))
        TokenStore(context).save(StoredToken("synthetic", owner, Long.MAX_VALUE, hub.restBase))
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        val folder = File(context.filesDir, "handouts/" + MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }).apply { mkdirs() }
        val pdf = File(folder, "synthetic.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawText("Lesson page ${index + 1}", 40f, 80f, android.graphics.Paint().apply { textSize=28f })
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val image = File(folder, "scan.png")
        val original = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(original).drawText("Synthetic notebook", 80f, 160f, android.graphics.Paint().apply { textSize=64f })
            image.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { original.recycle() }
        val docx = File(folder, "text.docx").apply { writeText("synthetic binary placeholder; UI reads Room chunks") }
        val materials = listOf(
            MaterialEntity("fixture-pdf", "fixture-class", "Synthetic PDF", "DOCUMENT", "application/pdf", pdf.length(), localFilePath=pdf.path, createdAt=1, updatedAt=1),
            MaterialEntity("fixture-image", "fixture-class", "Synthetic image", "DOCUMENT", "image/png", image.length(), localFilePath=image.path, createdAt=1, updatedAt=1),
            MaterialEntity("fixture-docx", "fixture-class", "Synthetic DOCX", "DOCUMENT", "application/octet-stream", docx.length(), localFilePath=docx.path, createdAt=1, updatedAt=1))
        db.cacheDao().upsertBatch(CacheBatch(
            users=listOf(UserEntity("fixture-peer", null, "Sample SecretSurname", "STUDENT", 1, 1)),
            classrooms=listOf(ClassroomEntity("fixture-class", "Science", "Synthetic class", "K7M4QX", owner, createdAt=1, updatedAt=1),
                ClassroomEntity("fixture-empty", "Empty class", "Synthetic class", "ABCDEF", owner, createdAt=1, updatedAt=1)),
            announcements=listOf(AnnouncementEntity("fixture-post", "fixture-class", "Synthetic lesson", "Read the lesson and share a question.", createdAt=1, updatedAt=1)),
            announcementComments=listOf(AnnouncementCommentEntity("fixture-comment", "fixture-post", "fixture-peer", "A saved class comment", 1, 1)),
            materials=materials,
            materialChunks=listOf(MaterialChunkEntity("fixture-chunk", "fixture-docx", 0, "Lesson heading", "Synthetic DOCX lesson text is available offline.", updatedAt=1))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun show(font: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
                    scenario.onActivity { activity -> activity.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                            LaraTheme { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = org.lara.app.ui.theme.LaraColors.Canvas,
                                contentColor = org.lara.app.ui.theme.LaraColors.TextPrimary) {
                                Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
                            } }
                        }
                    } }
                }
                show { ClassroomLearningHost(active, owner, ShellDestination.STREAM, "fixture-class") }
                waitFor("Synthetic lesson"); shot("stream")
                scrollTo("Class comments (1)"); click("Class comments (1)")
                scrollTo("Sample")
                assertFalse(nodes().any { it.text?.toString()?.contains("SecretSurname") == true })
                scrollTo("Save comment")
                val field = nodes().first { it.className?.toString() == "android.widget.EditText" }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Phone queued question")
                }))
                click("Save comment")
                repeat(50) {
                    if (db.streamDao().observeComments("fixture-post").first().any { it.content == "Phone queued question" }) return@repeat
                    SystemClock.sleep(100)
                }
                assertTrue(db.streamDao().observeComments("fixture-post").first().any { it.syncStatus == "QUEUED_FOR_SYNC" })
                scrollTo("Queued for sync"); shot("queued-comment")
                show { ClassroomLearningHost(active, owner, ShellDestination.CLASSWORK, "fixture-class") }
                waitFor("Synthetic PDF"); shot("classwork")
                show(font=2f) { ClassroomLearningHost(active, owner, ShellDestination.STREAM, "fixture-class") }
                waitFor("Science"); shot("stream-200-top")
                scrollTo("Class comments (2)"); click("Class comments (2)")
                scrollTo("Hide comments (2)"); shot("stream-200-bottom")
                show(font=2f) { ClassroomLearningHost(active, owner, ShellDestination.CLASSWORK, "fixture-class") }
                waitFor("Science"); shot("classwork-200-top")
                scrollTo("Synthetic PDF"); shot("classwork-200-bottom")
                show { MaterialReader(active, materials[0].toDomain()) {} }
                waitFor("Page 1 of 2"); shot("pdf-page-one")
                click("Next"); waitFor("Page 2 of 2"); click("Zoom in"); shot("pdf-page-two-zoom")
                waitFor("Zoom: 150%")
                pinch("Synthetic PDF")
                SystemClock.sleep(300)
                assertTrue(nodes().mapNotNull { it.text?.toString() }.any { text ->
                    text.startsWith("Zoom: ") && (text.filter(Char::isDigit).toIntOrNull() ?: 0) > 150
                })
                shot("pdf-pinch")
                show(font=2f) { MaterialReader(active, materials[0].toDomain()) {} }
                waitFor("Page 1 of 2"); shot("pdf-200")
                show { MaterialReader(active, materials[1].toDomain()) {} }
                waitFor("Zoom in"); click("Zoom in"); shot("image-zoom")
                show { MaterialReader(active, materials[2].toDomain()) {} }
                waitFor("Synthetic DOCX lesson text is available offline."); shot("docx-text")
                show { ClassroomLearningHost(active, owner, ShellDestination.STREAM, "fixture-empty") }
                waitFor("This is where your class announcements will appear."); shot("empty-stream")
                show { ClassroomLearningHost(active, owner, ShellDestination.CLASSWORK, "fixture-empty") }
                waitFor(context.getString(org.lara.app.R.string.reading_handouts_empty_title)); shot("empty-classwork")
                show { ReadingSkeleton() }; waitFor("Loading saved classroom content…"); shot("loading")
            }
        } finally { TokenStore(context).clear(); db.close() }
    }
}
