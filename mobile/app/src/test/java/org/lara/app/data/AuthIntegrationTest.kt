package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.AuthRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuthIntegrationTest {
    private lateinit var server: ServerSocket
    private lateinit var db: LaraDatabase
    private lateinit var endpoint: HubEndpoint
    private val tokens = MemoryTokens()
    @Volatile private var status = 200
    @Volatile private var role = "STUDENT"
    @Volatile private var userId = "test-user"
    private var requestPath = ""
    private var requestBody = ""
    private var redirects = 0
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        server = startServer()
        endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.localPort))
    }
    private fun startServer(): ServerSocket {
        val listening = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while(!listening.isClosed) {
                val socket = try { listening.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    val input = it.getInputStream().buffered()
                    val header = StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if(byte < 0) break
                        header.append(byte.toChar())
                    }
                    requestPath = header.toString().substringBefore("\r\n").split(" ").getOrElse(1) { "" }
                    val length = Regex("Content-Length: ([0-9]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val bodyBytes = ByteArray(length)
                    var offset = 0
                    while(offset < length) {
                        val count = input.read(bodyBytes, offset, length - offset)
                        if(count < 0) break
                        offset += count
                    }
                    requestBody = bodyBytes.toString(Charsets.UTF_8)
                    if(requestPath == "/redirected") redirects++
                    val body = """{"token":"synthetic-test-token","expires_at":1,"user":{"id":"$userId","lrn_or_id":"123456789012","full_name":"Synthetic learner","role":"$role"}}""".toByteArray()
                    val response = "HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nRetry-After: 37\r\nLocation: /redirected\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(response.toByteArray()); write(body); flush() }
                }
            }
        }
        return listening
    }
    @After fun close() { server.close(); db.close() }
    @Test fun loginPersistsProfileAndRestoresAfterHubStopsEvenWhenTokenExpired() = runBlocking {
        val repo = AuthRepository(AuthApi(), tokens, db.authDao())
        assertEquals("STUDENT", repo.authenticate(endpoint, "123456789012", "1234", null).role)
        assertEquals("/api/auth/login", requestPath)
        assertFalse(requestBody.contains("full_name"))
        assertEquals("test-user", db.authDao().currentUserId())
        server.close()
        assertEquals("test-user", AuthRepository(AuthApi(), tokens, db.authDao()).restore(endpoint)?.id)
        assertNull(repo.restore(requireNotNull(HubEndpoint.lan("127.0.0.2"))))
    }
    @Test fun sameUserCanReauthenticateAfterHubAddressChangesButDifferentUserCannot() = runBlocking {
        val repo = AuthRepository(AuthApi(), tokens, db.authDao())
        repo.authenticate(endpoint, "123456789012", "1234", null)
        val second = startServer()
        try {
            val hubB = requireNotNull(HubEndpoint.lan("127.0.0.1", second.localPort))
            assertNotEquals(endpoint.restBase, hubB.restBase)
            assertNull(repo.restore(hubB))
            assertEquals("test-user", repo.authenticate(hubB, "123456789012", "1234", null).id)
            assertEquals(hubB.restBase, tokens.value?.hub)
            assertEquals("test-user", db.authDao().currentUserId())
            assertEquals("test-user", repo.restore(hubB)?.id)
            assertNull(repo.restore(endpoint))

            val saved = tokens.value
            userId = "different-user"
            try { repo.authenticate(hubB, "123456789013", "1234", null); fail("Different user must remain blocked") }
            catch (e: AuthFailure) { assertEquals(409, e.status) }
            assertEquals(saved, tokens.value)
            assertEquals("test-user", db.authDao().currentUserId())
            assertNull(db.authDao().user("different-user"))
            assertEquals("test-user", repo.restore(hubB)?.id)
        } finally { second.close() }
    }
    @Test fun registrationSendsName() = runBlocking {
        val repo = AuthRepository(AuthApi(), tokens, db.authDao())
        assertEquals("STUDENT", repo.authenticate(endpoint, "123456789012", "1234", "Synthetic").role)
        assertEquals("/api/auth/register", requestPath)
        assertTrue(requestBody.contains("full_name"))
    }
    @Test fun teacherLoginUsesReturnedTeacherRole() = runBlocking {
        role = "TEACHER"
        assertEquals("TEACHER", AuthRepository(AuthApi(), tokens, db.authDao()).authenticate(endpoint, "T-0001", "1234", null).role)
    }
    @Test fun rejectedAdminNeverReachesRoomOrTokenStorage() = runBlocking {
        role = "ADMIN"
        try { AuthRepository(AuthApi(), tokens, db.authDao()).authenticate(endpoint, "ADMIN-0001", "1234", null); fail() }
        catch(e: AuthFailure) { assertEquals(403, e.status) }
        assertNull(tokens.value)
        assertNull(db.authDao().currentUserId())
    }
    @Test fun wrongPinAndRateLimitPreserveExistingCache() = runBlocking {
        val repo = AuthRepository(AuthApi(), tokens, db.authDao())
        repo.authenticate(endpoint, "123456789012", "1234", null)
        for(code in listOf(401, 429)) {
            status = code
            try { repo.authenticate(endpoint, "123456789012", "9999", null); fail() }
            catch(e: AuthFailure) { assertEquals(code, e.status); assertEquals(37L, e.retryAfterSeconds) }
            assertEquals("test-user", repo.restore(endpoint)?.id)
        }
    }
    @Test fun redirectsAreNotFollowed() = runBlocking {
        status = 307
        try { AuthApi().authenticate(endpoint, "123456789012", "1234", null); fail() }
        catch(e: AuthFailure) { assertEquals(307, e.status) }
        assertEquals(0, redirects)
    }
    private class MemoryTokens : SessionTokenStore {
        var value: StoredToken? = null
        override fun load() = value
        override fun save(value: StoredToken) { this.value = value }
        override fun clear() { value = null }
    }
}
