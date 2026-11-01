package org.lara.app.ui.screens.auth

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.R
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.*
import org.lara.app.data.repository.AuthRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class AuthViewModelTest {
    private lateinit var db: LaraDatabase
    private lateinit var server: MockWebServer
    private lateinit var model: AuthViewModel
    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        server = MockWebServer().apply { start() }
        var token: StoredToken? = null
        val tokens = object : SessionTokenStore {
            override fun load() = token
            override fun save(value: StoredToken) { token = value }
            override fun clear() { token = null }
        }
        val endpoints = HubEndpointStore(context)
        endpoints.save(requireNotNull(HubEndpoint.lan("127.0.0.1", server.port)))
        model = AuthViewModel(AuthRepository(AuthApi(), tokens, db.authDao()), endpoints)
    }
    @After fun close() { model.viewModelScope.cancel(); server.shutdown(); db.close(); Dispatchers.resetMain() }
    @Test fun registrationTrimsBeforeValidationAndRejectsContractLengthViolations(): Unit = runBlocking {
        withTimeout(5_000) { model.state.first { !it.restoring } }
        model.submit("123456789012", "1234", " ")
        assertEquals(R.string.auth_invalid, model.state.value.error)
        model.submit("123456789012", "1234", "x".repeat(101))
        assertEquals(R.string.auth_invalid, model.state.value.error)
        model.submit("x".repeat(33), "1234", null)
        assertEquals(R.string.auth_invalid, model.state.value.error)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"token":"synthetic","expires_at":9999999999999,"user":{"id":"learner","lrn_or_id":"123456789012","full_name":"Learner","role":"STUDENT"}}"""))
        model.submit(" 123456789012 ", "1234", " Learner ")
        withTimeout(5_000) { model.state.first { it.user != null } }
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("123456789012", body.getValue("lrn_or_id").jsonPrimitive.content)
        assertEquals("Learner", body.getValue("full_name").jsonPrimitive.content)
        val session = model.state.value.sessionVersion
        model.requireSignIn(session - 1)
        model.signedOut(session - 1)
        assertNotNull(model.state.value.user)
        model.requireSignIn(session)
        assertNull(model.state.value.user)
        assertEquals("learner", model.state.value.previousUser?.id)
        model.signedOut(0)
        assertNull(model.state.value.previousUser)
    }
    @Test fun retryAfterBlocksManualAndAutomaticRetriesUntilTheWaitEnds(): Unit = runBlocking {
        withTimeout(5_000) { model.state.first { !it.restoring } }
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "37").setBody("{}"))
        model.submit("123456789012", "1234", null)
        val state = withTimeout(5_000) { model.state.first { !it.busy && it.error == R.string.auth_wait } }
        assertTrue(state.retryAt - SystemClock.elapsedRealtime() in 36_000L..37_000L)
        model.submit("123456789012", "1234", null)
        delay(150)
        assertEquals(1, server.requestCount)
    }
}
