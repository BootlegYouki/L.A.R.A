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
    @Test fun seededRolesAndLearnerRegistration(): Unit = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("mockHub")
        assumeTrue("Supply the USB-reversed local mock endpoint", address == "127.0.0.1:8080")
        val endpoint = requireNotNull(HubEndpoint.manual(requireNotNull(address)))
        val api = AuthApi()
        assertEquals("TEACHER", api.authenticate(endpoint, "T-0001", "1234", null).user.role)
        assertEquals("STUDENT", api.authenticate(endpoint, "123456789012", "1234", null).user.role)
        val syntheticId = "9" + System.nanoTime().toString().takeLast(11).padStart(11, '0')
        assertEquals("STUDENT", api.authenticate(endpoint, syntheticId, "1234", "Synthetic device-test learner").user.role)
    }
}
