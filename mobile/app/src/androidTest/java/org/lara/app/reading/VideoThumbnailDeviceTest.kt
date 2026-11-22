package org.lara.app.reading

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.ui.screens.classwork.readVideoThumbnail

@RunWith(AndroidJUnit4::class)
class VideoThumbnailDeviceTest {
    @Test fun savedVideoUsesBoundedThumbnailOrApi26Fallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("thumbnail", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("video-lesson.mp4").use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            val thumbnail = readVideoThumbnail(file.path)
            if (Build.VERSION.SDK_INT < 27) assertNull(thumbnail)
            else requireNotNull(thumbnail).let {
                try { assertTrue(it.width <= 512 && it.height <= 512) }
                finally { it.recycle() }
            }
        } finally {
            file.delete()
        }
    }
}
