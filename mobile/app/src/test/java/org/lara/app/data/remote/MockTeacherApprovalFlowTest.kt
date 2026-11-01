package org.lara.app.data.remote

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.approval.RestApprovalFlowTests

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MockTeacherApprovalFlowTest : RestApprovalFlowTests() {
    override fun mockEnabled() = System.getenv("LARA_APPROVAL_MOCK") == "1"
}
