package org.lara.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** The caller owns the private source copy. A gallery original is never passed here. */
object HomeworkPhoto {
    const val MAX_BYTES = 800_000L

    suspend fun compress(source: File, directory: File): File = convert(source, directory, Bitmap.CompressFormat.JPEG)

    suspend fun normalize(source: File, directory: File): File = convert(source, directory, Bitmap.CompressFormat.PNG)

    private suspend fun convert(source: File, directory: File, format: Bitmap.CompressFormat): File = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val output = if (format == Bitmap.CompressFormat.PNG) File.createTempFile("scan-", ".png", directory)
            else File.createTempFile("homework-", ".jpg", directory)
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (longEdge / options.inSampleSize > 3840 || shortEdge / options.inSampleSize > 2160) {
                options.inSampleSize *= 2
            }
            bitmap = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("Unreadable photo")
            val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val current = requireNotNull(bitmap)
            val scale = minOf(1f, 1920f / maxOf(current.width, current.height), 1080f / minOf(current.width, current.height))
            matrix.postScale(scale, scale)
            var transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (transformed !== current) current.recycle()
            bitmap = transformed
            if (transformed.hasAlpha()) {
                val opaque = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
                try {
                    opaque.eraseColor(Color.WHITE)
                    Canvas(opaque).drawBitmap(transformed, 0f, 0f, null)
                } catch (error: Throwable) { opaque.recycle(); throw error }
                transformed.recycle()
                transformed = opaque
                bitmap = opaque
            }
            var quality = 75
            do {
                currentCoroutineContext().ensureActive()
                output.outputStream().use { stream ->
                    check(transformed.compress(format, quality, stream))
                }
                quality -= 5
            } while (format == Bitmap.CompressFormat.JPEG && output.length() >= MAX_BYTES && quality >= 5)
            require(output.length() > 0)
            if (format == Bitmap.CompressFormat.JPEG) require(output.length() < MAX_BYTES)
            source.delete()
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            bitmap?.recycle()
        }
    }
}
