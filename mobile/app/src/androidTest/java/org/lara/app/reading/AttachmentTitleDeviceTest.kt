package org.lara.app.reading

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.MainActivity
import org.lara.app.R
import org.lara.app.ui.screens.classwork.AttachmentTitleRow
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraTheme

@RunWith(AndroidJUnit4::class)
class AttachmentTitleDeviceTest {
    @Test fun attachmentTitleHasATouchTargetAtLeast52DpHigh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = "Attachment title"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    LaraTheme { AttachmentTitleRow(label, R.drawable.ic_play, LaraColors.PrimaryDark) {} }
                }
            }
            instrumentation.waitForIdleSync()
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val title = root.findAccessibilityNodeInfosByText(label).first()
            val target = generateSequence(title) { it.parent }.first(AccessibilityNodeInfo::isClickable)
            val bounds = Rect().also(target::getBoundsInScreen)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue(bounds.height() >= (52 * density).toInt())
        }
    }
}
