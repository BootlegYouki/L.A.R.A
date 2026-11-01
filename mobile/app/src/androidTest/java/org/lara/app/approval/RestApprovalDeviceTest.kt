package org.lara.app.approval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestApprovalDeviceTest : RestApprovalFlowTests() {
    override fun mockEnabled() = InstrumentationRegistry.getArguments().getString("mockHub") == "127.0.0.1:28080"
}
