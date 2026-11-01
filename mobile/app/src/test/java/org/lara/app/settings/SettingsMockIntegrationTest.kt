package org.lara.app.settings
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsMockIntegrationTest : SettingsMockBehaviorTests() {
    override fun enabled() = System.getenv("LARA_SETTINGS_MOCK") == "1"
}
