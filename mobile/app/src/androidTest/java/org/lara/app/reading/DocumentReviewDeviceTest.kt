package org.lara.app.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.data.repository.HomeworkPhoto
import org.lara.app.ui.screens.homework.HomeworkViewModel

@RunWith(AndroidJUnit4::class)
class DocumentReviewDeviceTest {
    @Test fun autoPreviewNeedsNoManualAdjustmentAndCancelKeepsSavedWork(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.lara.app.sprint32homework")
        val container = (context.applicationContext as LaraApp).container
        val userId = requireNotNull(container.syncUserId())
        val account = container.forUser(userId)
        val assignmentId = requireNotNull(InstrumentationRegistry.getArguments().getString("assignment"))
        val previous = account.homework.observe(assignmentId).first()
        val store = ViewModelStore()
        lateinit var model: HomeworkViewModel
        instrumentation.runOnMainSync {
            model = HomeworkViewModel(account, assignmentId, SavedStateHandle())
            store.put("review-test", model)
        }
        fun input(blank: Boolean): File {
            val file = File.createTempFile("review-test-", ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (blank) Color.WHITE else Color.DKGRAY)
                if (!blank) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    canvas.drawRect(150f, 80f, 1050f, 820f, paint)
                    paint.color = Color.BLACK
                    paint.textSize = 40f
                    canvas.drawText("SYNTHETIC AUTO PREVIEW", 230f, 200f, paint)
                }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            return file
        }
        suspend fun capture(blank: Boolean) {
            val photo = input(blank)
            instrumentation.runOnMainSync { model.captured(photo) }
            withTimeout(15_000) { model.scanPreview.filterNotNull().first() }
            withTimeout(15_000) { model.busy.filter { !it }.first() }
            assertFalse(photo.exists())
            assertEquals(!blank, model.scanDetected.value)
            assertTrue(File(requireNotNull(model.scanPreview.value)).isFile)
            assertEquals(previous, account.homework.observe(assignmentId).first())
        }
        try {
            capture(blank = false)
            val source = requireNotNull(model.scanSource.value)
            val preview = requireNotNull(model.scanPreview.value)
            instrumentation.runOnMainSync { model.cancelScan() }
            withTimeout(5_000) { model.scanSource.filter { it == null }.first() }
            assertFalse(File(source).exists())
            assertFalse(File(preview).exists())
            assertEquals(previous, account.homework.observe(assignmentId).first())

            capture(blank = true)
            instrumentation.runOnMainSync { model.useScan(false) }
            val accepted = withTimeout(10_000) { model.draft.filterNotNull().first() }
            withTimeout(10_000) { model.busy.filter { !it }.first() }
            assertTrue(File(accepted).length() in 1 until HomeworkPhoto.MAX_BYTES)
            assertNull(model.scanSource.value)
            assertEquals(previous, account.homework.observe(assignmentId).first())
            previous?.let { assertTrue(File(it.filePath).isFile) }
            account.homework.discardDraft(accepted)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
