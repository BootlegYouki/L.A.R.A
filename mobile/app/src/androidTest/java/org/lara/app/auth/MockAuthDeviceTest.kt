package org.lara.app.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.remote.AuthApi
import org.lara.app.data.remote.discovery.HubEndpoint

/** Explicitly opted in against the local mock only; never changes the installed learner session. */
@RunWith(AndroidJUnit4::class)
class MockAuthDeviceTest {
    @Test fun seededLearnerAndTeacherSignIn(): Unit = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("mockHub")
        assumeTrue("Supply the USB-reversed local mock endpoint", address == "127.0.0.1:8080")
        val endpoint = requireNotNull(HubEndpoint.manual(requireNotNull(address)))
        val api = AuthApi()
        assertEquals("TEACHER", api.authenticate(endpoint, "T-0001", "123456").user.role)
        assertEquals("STUDENT", api.authenticate(endpoint, "123456789012", "123456").user.role)
    }
}
