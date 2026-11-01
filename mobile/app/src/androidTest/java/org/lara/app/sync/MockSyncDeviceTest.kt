package org.lara.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockSyncDeviceTest : MockSyncBehaviorTests() {
    override fun mockEnabled() = InstrumentationRegistry.getArguments().getString("mockHub") == "127.0.0.1:18080"
}
