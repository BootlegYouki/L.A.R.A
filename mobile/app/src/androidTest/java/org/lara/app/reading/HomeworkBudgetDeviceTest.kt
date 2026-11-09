package org.lara.app.reading

import android.Manifest
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.MainActivity
import org.lara.app.R
import org.lara.app.data.repository.DocumentScanner
import org.lara.app.data.repository.HomeworkPhoto
import org.lara.app.ui.screens.homework.CameraCaptureScreen
import org.lara.app.ui.theme.LaraTheme

@RunWith(AndroidJUnit4::class)
class HomeworkBudgetDeviceTest {
    @Test fun captureScanAndCompressWithinBudget(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        require(memory.totalMem in (2500L * 1024 * 1024)..(4L * 1024 * 1024 * 1024))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val directory = File(context.cacheDir, "budget-${UUID.randomUUID()}").apply { mkdirs() }
        val running = AtomicBoolean(true)
        val peak = AtomicLong()
        // Bitmap and OpenCV allocations are native, so Java heap alone would under-report the peak.
        val sampler = thread(name = "homework-heap-sampler", isDaemon = true) {
            val runtime = Runtime.getRuntime()
            while (running.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory() + Debug.getNativeHeapAllocatedSize()
                peak.accumulateAndGet(used) { previous, current -> maxOf(previous, current) }
                Thread.sleep(5)
            }
        }
        val report = mutableListOf("Device ${android.os.Build.MODEL}", "Physical memory ${memory.totalMem} bytes")
        val compressionTimes = mutableListOf<Double>()
        var capture: File? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val captured = CompletableDeferred<File>()
                val cameraVisible = mutableStateOf(true)
                scenario.onActivity { activity ->
                    activity.setContent {
                        LaraTheme {
                            if (cameraVisible.value) CameraCaptureScreen({ file ->
                                cameraVisible.value = false
                                captured.complete(file)
                            }, {}) else Text(stringResource(R.string.homework_preparing))
                        }
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var clicked = false
                while (!clicked && SystemClock.elapsedRealtime() < deadline) {
                    var button = findShutter(instrumentation.uiAutomation.rootInActiveWindow,
                        context.getString(R.string.homework_take_photo))
                    while (button != null && !button.isClickable) button = button.parent
                    if (button?.isEnabled == true) clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) SystemClock.sleep(250)
                }
                assertTrue("Camera shutter must become available", clicked)
                capture = withTimeout(120_000) { captured.await() }
                val start = SystemClock.elapsedRealtimeNanos()
                val source = HomeworkPhoto.normalize(requireNotNull(capture), directory)
                val corners = DocumentScanner.detect(source) ?: DocumentScanner.fullPage()
                val preview = DocumentScanner.render(source, corners, true, directory)
                report.add("Normalize, detect and render ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0} ms")
                val compressionStart = SystemClock.elapsedRealtimeNanos()
                val output = HomeworkPhoto.compress(preview, directory)
                val elapsed = (SystemClock.elapsedRealtimeNanos() - compressionStart) / 1_000_000.0
                report.add("Camera JPEG compression $elapsed ms, ${output.length()} bytes")
                compressionTimes.add(elapsed)
                assertTrue(output.length() in 1 until HomeworkPhoto.MAX_BYTES)
                output.copyTo(File(context.filesDir, "budget-camera.jpg"), overwrite = true)
            }
            val source = File(directory, "synthetic-12mp.jpg")
            val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 100f }
                val canvas = Canvas(bitmap)
                for (line in 1..20) canvas.drawText("Synthetic notebook line $line. 1 + 1 = ?", 150f, line * 130f, paint)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { bitmap.recycle() }
            val documentInput = File(directory, "scan-12mp.jpg")
            source.copyTo(documentInput)
            val processingStart = SystemClock.elapsedRealtimeNanos()
            val document = HomeworkPhoto.normalize(documentInput, directory)
            val documentCorners = DocumentScanner.detect(document) ?: DocumentScanner.fullPage()
            val documentPreview = DocumentScanner.render(document, documentCorners, true, directory)
            report.add("12MP normalize, detect and render ${(SystemClock.elapsedRealtimeNanos() - processingStart) / 1_000_000.0} ms")
            val documentStart = SystemClock.elapsedRealtimeNanos()
            val documentJpeg = HomeworkPhoto.compress(documentPreview, directory)
            val documentTime = (SystemClock.elapsedRealtimeNanos() - documentStart) / 1_000_000.0
            compressionTimes.add(documentTime)
            report.add("12MP scanned JPEG compression $documentTime ms, ${documentJpeg.length()} bytes")
            assertTrue(documentJpeg.length() in 1 until HomeworkPhoto.MAX_BYTES)
            documentJpeg.copyTo(File(context.filesDir, "budget-document.jpg"), overwrite = true)
            repeat(3) { index ->
                val input = File(directory, "input-$index.jpg")
                source.copyTo(input)
                val start = SystemClock.elapsedRealtimeNanos()
                val output = HomeworkPhoto.compress(input, directory)
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                compressionTimes.add(elapsed)
                report.add("12MP JPEG compression ${index + 1} $elapsed ms, ${output.length()} bytes")
                assertTrue(output.length() in 1 until HomeworkPhoto.MAX_BYTES)
                assertFalse(input.exists())
                output.delete()
            }
        } finally {
            running.set(false)
            sampler.join()
            report.add("Peak combined managed/native allocated heap ${peak.get()} bytes, sampled every 5 ms")
            File(context.filesDir, "budget-capture-report.txt").writeText(report.joinToString("\n"))
            capture?.delete()
            directory.deleteRecursively()
        }
        assertTrue("Peak allocated heap must stay under 250 MiB", peak.get() < 250L * 1024 * 1024)
        assertTrue("JPEG compression must finish under 500 ms", compressionTimes.all { it < 500 })
    }

    private fun findShutter(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findShutter(node.getChild(index), label)?.let { return it }
        }
        return null
    }
}
