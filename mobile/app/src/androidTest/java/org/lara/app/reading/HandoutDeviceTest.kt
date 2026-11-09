package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.ui.screens.classwork.renderHandout

@RunWith(AndroidJUnit4::class)
class HandoutDeviceTest {
    @Test fun nativePdfPagesAndSampledImageStayWithinThePixelBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-reader-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(directory, "lesson.pdf")
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(1000, 1500, index + 1).create())
                    page.canvas.drawText("Synthetic lesson page ${index + 1}", 50f, 100f,
                        android.graphics.Paint().apply { textSize = 32f })
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            repeat(2) { page ->
                val rendered = renderHandout(file, true, page)
                try {
                    assertEquals(2, rendered.pages)
                    assertTrue(rendered.bitmap.width.toLong() * rendered.bitmap.height <= 2_000_000)
                    assertTrue(rendered.bitmap.allocationByteCount <= 8_000_000)
                } finally { rendered.bitmap.recycle() }
            }
            val image = File(directory, "scan.png")
            val original = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
            try { image.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { original.recycle() }
            val sampled = renderHandout(image, false, 0)
            try {
                assertTrue(sampled.bitmap.width < 2400)
                assertTrue(sampled.bitmap.width.toLong() * sampled.bitmap.height <= 2_000_000)
            } finally { sampled.bitmap.recycle() }
            val broken = File(directory, "broken.pdf").apply { writeText("%PDF-not-a-document") }
            try { renderHandout(broken, true, 0); fail("Invalid PDF accepted") } catch (_: java.io.IOException) { }
        } finally { directory.deleteRecursively() }
    }
}
