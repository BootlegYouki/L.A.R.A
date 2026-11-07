package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.data.repository.HomeworkPhoto

/** Operates only on the separate phone-test account and synthetic page fixtures. */
@RunWith(AndroidJUnit4::class)
class HomeworkDeviceTest {
    @Test fun savedPhotoAndCompression(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LaraApp).container
        val userId = requireNotNull(container.syncUserId())
        val assignmentId = requireNotNull(arguments.getString("assignment"))
        val row = requireNotNull(container.forUser(userId).homework.observe(assignmentId).first())
        val report = mutableListOf("Submission status ${row.syncStatus}")
        assertEquals(arguments.getString("expected"), row.syncStatus)
        val saved = File(row.filePath)
        assertTrue(saved.length() in 1 until HomeworkPhoto.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(saved.path, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1920)
        assertTrue(minOf(bounds.outWidth, bounds.outHeight) <= 1080)
        report.add("Saved JPEG ${bounds.outWidth}x${bounds.outHeight}, ${saved.length()} bytes")

        if (arguments.getString("benchmark") == "true") {
            val directory = File(context.cacheDir, "homework-device-benchmark").apply { mkdirs() }
            val source = File(directory, "synthetic-source.jpg")
            val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
                canvas.drawText("SYNTHETIC HOMEWORK TEST", 150f, 200f, paint)
                for (line in 1..20) canvas.drawText("Sample notebook line $line. 1 + 1 = ?", 150f, 250f + line * 120f, paint)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { bitmap.recycle() }
            repeat(3) { index ->
                val input = File(directory, "input-$index.jpg")
                source.copyTo(input, overwrite = true)
                val start = SystemClock.elapsedRealtimeNanos()
                val output = HomeworkPhoto.compress(input, directory)
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                assertTrue(output.length() in 1 until HomeworkPhoto.MAX_BYTES)
                assertFalse(input.exists())
                report.add("Synthetic 12MP compression ${index + 1}: $elapsedMs ms, ${output.length()} bytes")
                if (index == 0) output.copyTo(File(context.filesDir, "synthetic-homework-gallery.jpg"), overwrite = true)
                output.delete()
            }
            source.delete()
        }
        File(context.filesDir, "homework-device-report.txt").writeText(report.joinToString("\n"))
    }
}
