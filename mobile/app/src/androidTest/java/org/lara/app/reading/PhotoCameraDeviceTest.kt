package org.lara.app.reading

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.MainActivity
import org.lara.app.R
import org.lara.app.data.repository.HomeworkPhoto
import org.lara.app.ui.screens.homework.CameraCaptureScreen
import org.lara.app.ui.theme.LaraTheme

@RunWith(AndroidJUnit4::class)
class PhotoCameraDeviceTest {
    @Test fun photoHasNoGuideAndDocumentModeShowsIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val captures = ArrayBlockingQueue<File>(1)
        val directory = File(context.cacheDir, "camera-test-${System.nanoTime()}")
        val guide = context.getString(R.string.homework_frame)

        fun find(label: String): List<AccessibilityNodeInfo> = buildList {
            fun visit(node: AccessibilityNodeInfo) {
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }

        fun waitFor(label: String, clickable: Boolean = false): AccessibilityNodeInfo {
            val until = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < until) {
                for (node in find(label)) {
                    if (!clickable) return node
                    generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }?.let { return it }
                }
                SystemClock.sleep(100)
            }
            error("Camera control unavailable: $label")
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun show(document: Boolean) {
                    scenario.onActivity { activity ->
                        activity.setContent {
                            LaraTheme {
                                CameraCaptureScreen(
                                    onCaptured = { if (!captures.offer(it)) it.delete() },
                                    close = {},
                                    document = document,
                                )
                            }
                        }
                    }
                }
                show(document = false)
                waitFor(context.getString(R.string.homework_camera_title))
                assertFalse(find(guide).isNotEmpty())
                assertTrue(waitFor(context.getString(R.string.homework_take_photo), clickable = true)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val photo = requireNotNull(captures.poll(20, TimeUnit.SECONDS))
                try {
                    val jpeg = HomeworkPhoto.compress(photo, directory)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(jpeg.path, bounds)
                    assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
                    assertTrue(jpeg.length() in 1 until HomeworkPhoto.MAX_BYTES)
                } finally { photo.delete() }

                show(document = true)
                waitFor(context.getString(R.string.homework_scan_document))
                waitFor(guide)
            }
        } finally {
            captures.forEach { it.delete() }
            directory.deleteRecursively()
        }
        Unit
    }
}
