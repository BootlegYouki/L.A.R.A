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
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.cancel
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.dto.*
import org.lara.app.data.remote.discovery.*
import org.lara.app.data.repository.SettingsRepository
import org.lara.app.data.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.MainActivity
import org.lara.app.data.repository.AppLanguageStore
import org.lara.app.ui.screens.settings.*
import org.lara.app.ui.theme.LaraTheme
import org.lara.app.ui.theme.AppLanguage

@RunWith(AndroidJUnit4::class)
class SettingsLanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun matches(text: String): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        val target = instrumentation.targetContext.packageName
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == target && node.text?.toString() == text) found.add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return found
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            matches(text).firstOrNull()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing settings text: $text")
    }
    private fun select(text: String) {
        waitFor(text)
        val nodes = matches(text)
        for (match in nodes.asReversed()) {
            if (match.text?.toString() != text) continue
            var node: AccessibilityNodeInfo? = match
            while (node != null && !node.isClickable) node = node.parent
            if (node != null) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
        }
        throw AssertionError("No settings action: $text")
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
    @Test fun queuedWarningCanCancelAndConfirmOfflineAtLargeFont() {
        val context = instrumentation.targetContext
        val store = (context.applicationContext as LaraApp).container.language
        val original = store.language.value
        val db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 9))
        var token: StoredToken? = StoredToken("synthetic", "learner", Long.MAX_VALUE, hub.restBase)
        val tokens = object : SessionTokenStore {
            override fun load() = token
            override fun save(value: StoredToken) { token = value }
            override fun clear() { token = null }
        }
        val transport = object : SyncTransport {
            override suspend fun pull(userId: String, request: SyncPullRequestDto): SyncPullResponseDto = error("Unexpected sync")
            override suspend fun push(userId: String, request: SyncPushRequestDto): SyncPushResponseDto = error("Unexpected sync")
        }
        val signedOut = AtomicBoolean(false)
        var model: SettingsViewModel? = null
        runBlocking {
            store.select("tl")
            db.authDao().saveProfile(UserEntity("learner", "synthetic", "Learner", "STUDENT", 1, 1))
            db.cacheDao().upsertBatch(CacheBatch(
                classrooms=listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)),
                announcements=listOf(AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1)),
                announcementComments=listOf(AnnouncementCommentEntity("comment", "post", "learner", "Saved question", 1, 1, "QUEUED_FOR_SYNC"))))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = SettingsViewModel("learner", SettingsRepository(db.authDao(), tokens, SyncRepository(db, transport), { hub }) {
                        _, _ -> throw java.io.IOException("Offline")
                    }, store, HubEndpointStore(context)) { signedOut.set(true) }
                    model = vm; vm.refresh()
                    activity.setContent {
                        val state by vm.state.collectAsState()
                        val language by store.language.collectAsState()
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                            AppLanguage(language) { LaraTheme {
                                SettingsScreen(state, language, false, "test", vm::language, { vm.signOut() },
                                    { vm.signOut(true) }, vm::cancelSignOut, {}, {})
                            } }
                        }
                    }
                }
                // The page scrolls at large text size; invoke the same ViewModel action as its button.
                instrumentation.runOnMainSync { requireNotNull(model).signOut() }
                val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("tl")) }
                val resources = context.createConfigurationContext(config).resources
                val warning = resources.getString(org.lara.app.R.string.settings_queued_warning, 1)
                waitFor(warning)
                select(resources.getString(org.lara.app.R.string.settings_cancel))
                assertNotNull(token)
                assertFalse(signedOut.get())
                assertEquals(1, runBlocking { db.authDao().queuedCount("learner") })
                instrumentation.runOnMainSync { requireNotNull(model).signOut() }
                waitFor(warning)
                select(resources.getString(org.lara.app.R.string.settings_sign_out))
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!signedOut.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertTrue(signedOut.get()); assertNull(token)
                assertEquals(1, runBlocking { db.authDao().queuedCount("learner") })
            }
        } finally {
            instrumentation.runOnMainSync { model?.viewModelScope?.cancel() }
            db.close(); runBlocking { store.select(original) }
        }
    }
}
