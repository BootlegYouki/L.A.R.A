package org.lara.app.ui.screens.enrollment

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EnrollmentViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var realtime: RealtimeClient
    private val models = mutableListOf<EnrollmentViewModel>()
    private val repository = MemoryRows()
    private val reachable = MutableStateFlow(true)
    private val hints = mutableListOf<EnrollmentRefreshHint>()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        realtime = RealtimeClient()
    }
    @After fun close() {
        models.forEach { it.viewModelScope.cancel() }
        realtime.close()
        server.shutdown()
        Dispatchers.resetMain()
    }
    private fun model(): EnrollmentViewModel {
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
        val tokens = object : SessionTokenStore {
            override fun load() = StoredToken("synthetic", "learner", Long.MAX_VALUE, endpoint.restBase)
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        return EnrollmentViewModel("learner", repository, EnrollmentApi(HubClient(), tokens, { endpoint }),
            realtime, reachable, { hints.add(it) }, {}).also { models.add(it) }
    }
    @Test fun joinShowsPendingAndRequestsRefreshWithoutInventingRoomMembership() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"PENDING","classroom_id":"class"}"""))
        server.enqueue(MockResponse().setBody("""[{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"teacher","enrollment_status":"PENDING"}]"""))
        val model = model()
        model.showJoin()
        model.submit("K7M-4QX")
        val state = withTimeout(10_000) { model.state.first { !it.busy && it.requests.isNotEmpty() } }
        assertFalse(state.showJoinDialog)
        assertEquals(EnrollmentStatus.PENDING, state.requests.single().status)
        assertEquals("Science", state.requests.single().title)
        assertEquals(listOf(EnrollmentRefreshHint.MembershipChanged("class")), hints)
        assertTrue((repository.rows.value as Cached.Content).value.isEmpty())
    }
    @Test fun offlineJoinNeverCallsHubAndSavedActiveClassRemainsReadable() = runBlocking {
        reachable.value = false
        val saved = LearnerClass(Classroom("class", "Science", "A", "K7M4QX", "teacher", null, null, 1, 1),
            Enrollment("server-id", "class", "learner", "ACTIVE", 1, 1))
        repository.rows.value = Cached.Content(listOf(saved))
        val model = model()
        model.submit("K7M-4QX")
        assertEquals(JoinClassError.HUB_UNREACHABLE, model.state.value.error)
        assertTrue((model.state.value.classes as Cached.Content).value.single().canOpen)
        assertEquals(0, server.requestCount)
    }
    private class MemoryRows : EnrollmentRepository {
        val rows = MutableStateFlow<Cached<List<LearnerClass>>>(Cached.Content(emptyList()))
        override fun learnerClasses(studentId: String, scope: CoroutineScope): StateFlow<Cached<List<LearnerClass>>> = rows
        override fun roster(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<RosterMember>>> =
            MutableStateFlow(Cached.Content(emptyList()))
    }
}
