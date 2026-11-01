package org.lara.app.settings

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.MainActivity
import org.lara.app.data.repository.AppLanguageStore
import org.lara.app.ui.screens.settings.*
import org.lara.app.ui.theme.LaraTheme

@RunWith(AndroidJUnit4::class)
class SettingsLanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(text: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.text?.toString() == text }
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing settings text: $text")
    }
    private fun select(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun languageChangesImmediatelyAtLargeFontWithoutActivityRestartAndSurvivesStoreReopen() {
        val context = instrumentation.targetContext
        val store = (context.applicationContext as LaraApp).container.language
        val original = store.language.value
        runBlocking { store.select("en") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var first: MainActivity? = null
                scenario.onActivity { activity ->
                    first = activity
                    activity.setContent {
                        val language by store.language.collectAsState()
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                            AppLanguage(language) { LaraTheme {
                                SettingsScreen(SettingsState(loading=false), language, false, "test",
                                    onLanguage = { code -> activity.lifecycleScope.launch { store.select(code) } },
                                    onSignOut={}, onConfirm={}, onCancel={}, onChangeHub={}, onBack={})
                            } }
                        }
                    }
                }
                waitFor("Settings")
                select("Filipino")
                waitFor("Mga Setting")
                waitFor("Wika ng app")
                scenario.onActivity { assertSame(first, it) }
                assertEquals("tl", AppLanguageStore(context).language.value)
                select("English")
                waitFor("Settings")
                assertEquals("en", AppLanguageStore(context).language.value)
            }
        } finally { runBlocking { store.select(original) } }
    }
}
