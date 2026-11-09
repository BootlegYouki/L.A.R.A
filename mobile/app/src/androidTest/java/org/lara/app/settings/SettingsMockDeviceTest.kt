package org.lara.app.settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class SettingsMockDeviceTest : SettingsMockBehaviorTests() {
    override fun enabled() = InstrumentationRegistry.getArguments().getString("mockHub") == "127.0.0.1:38080"
}
