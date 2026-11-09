package org.lara.app.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.lara.app.data.repository.HomeworkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeworkPhotoTest {
    private lateinit var root: File
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        root = File(context.cacheDir, "compression-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun close() { root.deleteRecursively() }

    @Test fun reviewSourceIsLosslessAndFinalPhotoKeepsTheByteLimit(): Unit = runBlocking {
        val original = File(root, "source.png")
        val bitmap = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val normalized = HomeworkPhoto.normalize(original, root)
        assertFalse(original.exists())
        assertEquals("png", normalized.extension)
        val jpeg = HomeworkPhoto.compress(normalized, root)
        assertFalse(normalized.exists())
        assertTrue(jpeg.length() in 1 until HomeworkPhoto.MAX_BYTES)
    }

    @Test fun transparentGalleryImagesKeepBlackInkOnWhitePaper(): Unit = runBlocking {
        val source = File(root, "transparent.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawRect(40f, 40f, 60f, 60f, Paint().apply { color = Color.BLACK })
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val normalized = HomeworkPhoto.normalize(source, root)
        val result = requireNotNull(BitmapFactory.decodeFile(normalized.path))
        try {
            assertEquals(Color.WHITE, result.getPixel(0, 0))
            assertEquals(Color.BLACK, result.getPixel(50, 50))
        } finally { result.recycle() }
    }

    @Test fun scalesCorrectsOrientationAndDeletesOnlyThePrivateSource(): Unit = runBlocking {
        val source = File(root, "source.jpg")
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        finally { bitmap.recycle() }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val target = HomeworkPhoto.compress(source, File(root, "compressed"))
        assertFalse(source.exists())
        assertTrue(target.length() in 1 until HomeworkPhoto.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        assertTrue(bounds.outHeight > bounds.outWidth)
        assertTrue(bounds.outHeight <= 1920 && bounds.outWidth <= 1080)
    }

    @Test fun malformedImageCreatesNoCompressedFileAndKeepsTheSourceForRecovery(): Unit = runBlocking {
        val source = File(root, "invalid.jpg").apply { writeText("Synthetic invalid photo") }
        val output = File(root, "compressed")
        try { HomeworkPhoto.compress(source, output); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(source.isFile)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
}
