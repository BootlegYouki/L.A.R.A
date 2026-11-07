package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.repository.DocumentScanner
import org.lara.app.data.repository.HomeworkPhoto
import org.opencv.core.Core

@RunWith(AndroidJUnit4::class)
class DocumentScannerDeviceTest {
    @Test fun findsPlainPaperInsideLargerDistractingBorder(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        val source = File(context.cacheDir, "plain-paper-test.png")
        val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(65, 65, 65))
            val backgroundPaint = Paint().apply { color = Color.rgb(120, 120, 120) }
            canvas.drawRect(0f, 30f, 1000f, 115f, backgroundPaint)
            backgroundPaint.color = Color.rgb(100, 100, 100)
            canvas.drawRect(800f, 0f, 1000f, 800f, backgroundPaint)
            val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f }
            canvas.drawRect(35f, 35f, 965f, 765f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(200f, 150f, 800f, 650f, paint)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val corners = requireNotNull(DocumentScanner.detect(source))
            val expected = floatArrayOf(.2f, .1875f, .8f, .1875f, .8f, .8125f, .2f, .8125f)
            corners.indices.forEach { assertTrue("Plain paper corner $it", abs(corners[it] - expected[it]) < .03f) }
            canvas.drawColor(Color.rgb(65, 65, 65))
            paint.style = Paint.Style.STROKE
            canvas.drawRect(35f, 35f, 965f, 765f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(200f, 150f, 800f, 650f, paint)
            paint.color = Color.rgb(95, 95, 95)
            canvas.drawRect(200f, 150f, 580f, 650f, paint)
            paint.color = Color.rgb(235, 235, 235)
            canvas.drawRect(10f, 5f, 990f, 100f, paint)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val shadowCorners = requireNotNull(DocumentScanner.detect(source))
            corners.indices.forEach { assertTrue("Shadowed paper corner $it", abs(shadowCorners[it] - expected[it]) < .04f) }
            InstrumentationRegistry.getArguments().getString("scan_fixture")?.let { path ->
                val fixture = File(context.filesDir, path)
                require(fixture.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                val detected = requireNotNull(DocumentScanner.detect(fixture))
                InstrumentationRegistry.getArguments().getString("scan_expected")?.let { value ->
                    val expectedCorners = value.split(',').map { it.toFloat() }
                    require(expectedCorners.size == 8)
                    detected.indices.forEach { assertEquals("Captured paper corner $it", expectedCorners[it], detected[it], .06f) }
                }
                val preview = DocumentScanner.render(fixture, detected, false, context.cacheDir)
                try { preview.copyTo(File(context.cacheDir, "calibration-preview.png"), overwrite = true) }
                finally { preview.delete() }
            }
        } finally { bitmap.recycle(); source.delete() }
    }

    @Test fun detectsStraightensAndEnhancesOffline(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        assertTrue(DocumentScanner.available)
        val directory = File(context.filesDir, "document-scan-evidence").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1400, 1100, Bitmap.Config.ARGB_8888)
        val expected = floatArrayOf(.12f, .08f, .9f, .16f, .84f, .9f, .08f, .85f)
        val source = File(directory, "perspective-source.png")
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(40, 40, 40))
            val page = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
            try {
                val pageCanvas = Canvas(page)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                for (x in 0 until 1000) {
                    val level = 150 + x * 70 / 1000
                    paint.color = Color.rgb(level, level, level)
                    pageCanvas.drawRect(x.toFloat(), 0f, x + 1f, 800f, paint)
                }
                paint.color = Color.rgb(85, 85, 85)
                paint.textSize = 38f
                pageCanvas.drawText("SYNTHETIC DOCUMENT SCAN", 65f, 90f, paint)
                paint.strokeWidth = 4f
                pageCanvas.drawLine(60f, 125f, 940f, 125f, paint)
                for (line in 1..9) pageCanvas.drawText("Notebook line $line. 2 + 3 = ?", 65f, 170f + line * 60f, paint)
                val target = FloatArray(8) { index -> expected[index] * if (index % 2 == 0) 1400 else 1100 }
                val transform = Matrix().apply { check(setPolyToPoly(floatArrayOf(0f, 0f, 999f, 0f, 999f, 799f, 0f, 799f), 0, target, 0, 4)) }
                canvas.drawBitmap(page, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { page.recycle() }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val start = SystemClock.elapsedRealtimeNanos()
        val corners = requireNotNull(DocumentScanner.detect(source))
        assertTrue(DocumentScanner.valid(corners))
        corners.indices.forEach { assertTrue("Suggested corner $it", abs(corners[it] - expected[it]) < .08f) }
        val natural = DocumentScanner.render(source, corners, false, directory)
        natural.copyTo(File(directory, "straightened-color.png"), overwrite = true)
        val straightened = requireNotNull(BitmapFactory.decodeFile(natural.path))
        try {
            val expectedRow = (125f / 800 * straightened.height).toInt()
            fun lineRow(x: Int) = (expectedRow - 8..expectedRow + 8).minBy { Color.red(straightened.getPixel(x, it)) }
            assertTrue(abs(lineRow(90) - lineRow(straightened.width - 90)) <= 5)
        } finally { straightened.recycle() }
        natural.delete()
        val enhanced = DocumentScanner.render(source, corners, true, directory)
        enhanced.copyTo(File(directory, "enhanced.png"), overwrite = true)
        val enhancedPage = requireNotNull(BitmapFactory.decodeFile(enhanced.path))
        try {
            val left = Color.red(enhancedPage.getPixel(90, 40))
            val right = Color.red(enhancedPage.getPixel(enhancedPage.width - 90, 40))
            assertTrue(left > 210 && right > 210)
            assertTrue(abs(left - right) < 35)
        } finally { enhancedPage.recycle() }
        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val jpeg = HomeworkPhoto.compress(enhanced, directory)
        assertTrue(jpeg.length() in 1 until HomeworkPhoto.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.path, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1920)
        assertTrue(minOf(bounds.outWidth, bounds.outHeight) <= 1080)
        jpeg.copyTo(File(directory, "final.jpg"), overwrite = true)
        jpeg.delete()
        File(directory, "report.txt").writeText("OpenCV ${Core.VERSION}\nDetection plus two renders $elapsed ms\nFinal JPEG ${bounds.outWidth}x${bounds.outHeight}, ${File(directory, "final.jpg").length()} bytes\n")
    }

    @Test fun preservesColorAndSupportsManualFallback(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        val directory = File(context.cacheDir, "document-color-test").apply { mkdirs() }
        val source = File(directory, "color.png")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(200, 200, 200))
            val paint = Paint().apply { color = Color.rgb(180, 180, 180) }
            canvas.drawRect(250f, 300f, 450f, 500f, paint)
            paint.color = Color.rgb(200, 40, 40)
            canvas.drawRect(50f, 50f, 200f, 200f, paint)
            paint.color = Color.rgb(40, 180, 40)
            canvas.drawRect(500f, 50f, 650f, 200f, paint)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val enhanced = DocumentScanner.render(source, DocumentScanner.fullPage(), true, directory)
        val rendered = requireNotNull(BitmapFactory.decodeFile(enhanced.path))
        try {
            val red = rendered.getPixel(100, 100)
            val green = rendered.getPixel(550, 100)
            assertTrue(Color.red(red) > Color.green(red) + 40)
            assertTrue(Color.green(green) > Color.red(green) + 40)
            assertTrue(abs(Color.red(rendered.getPixel(300, 100)) - Color.red(rendered.getPixel(300, 400))) >= 10)
        } finally { rendered.recycle() }
        val blank = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(Color.WHITE)
            source.outputStream().use { blank.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { blank.recycle() }
        assertNull(DocumentScanner.detect(source))
        val table = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            table.eraseColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
            Canvas(table).drawRect(100f, 100f, 700f, 500f, paint)
            source.outputStream().use { table.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { table.recycle() }
        assertNull("A table border is not the page edge", DocumentScanner.detect(source))
        try { DocumentScanner.render(source, FloatArray(8), true, directory); fail() }
        catch (_: IllegalArgumentException) { }
        source.delete()
        enhanced.delete()
    }
}
