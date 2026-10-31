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
    private val events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 8)
    private val notices = MemoryNotices()
    private val models = mutableListOf<EnrollmentViewModel>()
    private val repository = MemoryRows()
    private val reachable = MutableStateFlow(true)
    private val hints = mutableListOf<EnrollmentRefreshHint>()
    private var afterRefresh: suspend (EnrollmentRefreshHint) -> Unit = {}
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }
    @After fun close() {
        models.forEach { it.viewModelScope.cancel() }
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
            events, reachable, notices, { hints.add(it); afterRefresh(it) }, {}).also { models.add(it) }
    }
    @Test fun joinShowsPendingAndRequestsRefreshWithoutInventingRoomMembership() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"PENDING","classroom_id":"class"}"""))
        server.enqueue(MockResponse().setBody("""[{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"teacher","enrollment_status":"PENDING"}]"""))
        val model = model()
        model.showJoin()
        model.submit("K7M-4QX")
        val state = withTimeout(10_000) { model.state.first { !it.busy && it.requests.singleOrNull()?.title == "Science" } }
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
    @Test fun approvalNoticeAloneCannotOpenClassAndPendingBlocksAnOldActiveRow() {
        val saved = LearnerClass(Classroom("class", "Science", "A", "K7M4QX", "teacher", null, null, 1, 1),
            Enrollment("server-id", "class", "learner", "ACTIVE", 1, 1))
        val loaded = EnrollmentScreenState(classes = Cached.Content(listOf(saved)), noticesLoaded = true)
        assertNotNull(loaded.openableClass("class"))
        assertNull(loaded.copy(noticesLoaded = false).openableClass("class"))
        assertNull(loaded.copy(requests = listOf(EnrollmentNotice("class", null, EnrollmentStatus.PENDING))).openableClass("class"))
        assertNull(loaded.copy(classes = Cached.Content(emptyList()), requests = listOf(
            EnrollmentNotice("class", null, EnrollmentStatus.ACTIVE))).openableClass("class"))
    }
    @Test fun wrongCodeKeepsDialogOpenWithFriendlyError(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"CLASS_CODE_INVALID"}}"""))
        val model = model(); model.showJoin(); model.submit("ZZZ-ZZZ")
        val state = withTimeout(10_000) { model.state.first { !it.busy && it.error != null } }
        assertTrue(state.showJoinDialog)
        assertEquals(JoinClassError.INVALID_CODE, state.error)
        assertTrue(notices.rows.value.isEmpty())
    }
    @Test fun approvalRefreshUnlocksOnlyAfterCanonicalMembershipArrives(): Unit = runBlocking {
        notices.rows.value = listOf(EnrollmentNotice("class", "Science", EnrollmentStatus.PENDING))
        server.enqueue(MockResponse().setBody("""[{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"teacher","enrollment_status":"PENDING"}]"""))
        val model = model(); model.start()
        withTimeout(10_000) { while(hints.isEmpty()) delay(10) }
        events.emit(HubEvent.JoinApproval("class", "another-learner", EnrollmentStatus.ACTIVE, 1))
        assertNull(model.state.value.openableClass("class"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("""[{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"teacher","enrollment_status":"ACTIVE"}]"""))
        afterRefresh = {
            repository.rows.value = Cached.Content(listOf(LearnerClass(
                Classroom("class", "Science", "A", "K7M4QX", "teacher", null, null, 1, 1),
                Enrollment("canonical-server-id", "class", "learner", "ACTIVE", 1, 2))))
        }
        events.emit(HubEvent.JoinApproval("class", "learner", EnrollmentStatus.ACTIVE, 2))
        val state = withTimeout(10_000) { model.state.first { it.openableClass("class") != null } }
        assertEquals("canonical-server-id", state.openableClass("class")?.enrollment?.id)
        model.stop()
        events.emit(HubEvent.JoinApproval("class", "learner", EnrollmentStatus.REMOVED, 3))
        assertNotNull(model.state.value.openableClass("class"))
    }
    @Test fun removalBlocksOldActiveCacheAndRemainsBlockedAfterModelRecreation(): Unit = runBlocking {
        reachable.value = false
        notices.rows.value = listOf(EnrollmentNotice("class", "Science", EnrollmentStatus.ACTIVE))
        repository.rows.value = Cached.Content(listOf(LearnerClass(
            Classroom("class", "Science", "A", "K7M4QX", "teacher", null, null, 1, 1),
            Enrollment("server-id", "class", "learner", "ACTIVE", 1, 1))))
        val first = model(); first.start()
        assertNotNull(first.state.value.openableClass("class"))
        events.emit(HubEvent.JoinApproval("class", "learner", EnrollmentStatus.REMOVED, 2))
        withTimeout(10_000) { first.state.first { it.openableClass("class") == null } }
        first.stop()
        val restored = model()
        assertNull(restored.state.value.openableClass("class"))
        assertEquals(EnrollmentStatus.REMOVED, restored.state.value.requests.single().status)
        assertEquals(0, server.requestCount)
    }

    private class MemoryNotices : EnrollmentNoticeRepository {
        val rows = MutableStateFlow<List<EnrollmentNotice>>(emptyList())
        override fun notices(userId: String): Flow<List<EnrollmentNotice>> = rows
        override suspend fun save(userId: String, notice: EnrollmentNotice) {
            val old = rows.value.firstOrNull { it.classroomId == notice.classroomId }
            rows.value = rows.value.filterNot { it.classroomId == notice.classroomId } + notice.copy(title = notice.title ?: old?.title)
        }
    }

    private class MemoryRows : EnrollmentRepository {
        val rows = MutableStateFlow<Cached<List<LearnerClass>>>(Cached.Content(emptyList()))
        override fun learnerClasses(studentId: String, scope: CoroutineScope): StateFlow<Cached<List<LearnerClass>>> = rows
        override fun roster(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<RosterMember>>> =
            MutableStateFlow(Cached.Content(emptyList()))
    }
}
