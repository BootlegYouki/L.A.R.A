package org.lara.app.ui.screens.teacher

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.remote.*
import org.lara.app.domain.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RosterViewModelTest {
    private val row = RosterMember(Enrollment("server-id", "class", "learner", "PENDING", 1, 1),
        User("learner", "synthetic-lrn", "Test Learner", "STUDENT", 1, 1))
    private val rows = MutableStateFlow<Cached<List<RosterMember>>>(Cached.Content(listOf(row)))
    private val reachable = MutableStateFlow(true)
    private val events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 8)
    private var calls = 0
    private var failure = false
    private val gate = CompletableDeferred<Unit>()
    private lateinit var model: RosterViewModel
    private var refreshes = 0
    private var refreshGate: CompletableDeferred<Unit>? = null
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val cache = object : EnrollmentRepository {
            override fun roster(classroomId: String, scope: CoroutineScope): StateFlow<Cached<List<RosterMember>>> = rows
            override fun learnerClasses(studentId: String, scope: CoroutineScope): StateFlow<Cached<List<LearnerClass>>> =
                MutableStateFlow(Cached.Content(emptyList()))
        }
        val remote = object : ClassManagementRepository {
            override suspend fun roster(classroomId: String) = emptyList<TeacherRosterEntry>()
            override suspend fun decide(classroomId: String, studentId: String, accept: Boolean) {
                calls++; gate.await(); if (failure) throw HubFailure(403, "FORBIDDEN")
            }
        }
        model = RosterViewModel("class", "K7M4QX", cache, remote, reachable, events, { refreshes++; refreshGate?.await(); Unit }, {})
    }
    @After fun close() { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    @Test fun failedDecisionRollsBackOptimisticCountAndPreventsDuplicateRequests() {
        failure = true
        model.decide("learner", true)
        assertEquals(0, model.state.value.pending.size)
        model.decide("learner", false)
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(1, model.state.value.pending.size)
        assertEquals(ApprovalError.FORBIDDEN, model.state.value.error)
        assertTrue(model.state.value.busy.isEmpty())
    }
    @Test fun offlineActionsDoNotSendOrChangeSavedRoster() {
        reachable.value = false
        model.decide("learner", true)
        assertEquals(0, calls)
        assertEquals(1, model.state.value.pending.size)
    }
    @Test fun matchingJoinEventOpensSheetButForeignClassDoesNot() {
        model.start()
        events.tryEmit(HubEvent.JoinRequest("ZZZ-ZZZ", "other", "Other", "private", 1))
        assertFalse(model.state.value.sheetOpen)
        events.tryEmit(HubEvent.JoinRequest("K7M-4QX", "learner", "Test", "private", 1))
        assertTrue(model.state.value.sheetOpen)
        model.dismiss(); model.stop()
        events.tryEmit(HubEvent.JoinRequest("K7M4QX", "learner", "Test", "private", 2))
        assertFalse(model.state.value.sheetOpen)
    }
    @Test fun successfulDecisionStaysOptimisticUntilRoomConfirmsWithoutWritingFakeRows() {
        model.decide("learner", false)
        gate.complete(Unit)
        assertEquals(0, model.state.value.pending.size)
        assertEquals("PENDING", (rows.value as Cached.Content).value.single().enrollment.status)
        rows.value = Cached.Content(listOf(row.copy(enrollment = row.enrollment.copy(status = "REJECTED"))))
        assertTrue(model.state.value.optimistic.isEmpty())
    }
    @Test fun joinDuringRefreshSchedulesAnotherPullRatherThanLosingTheRequest() {
        refreshGate = CompletableDeferred()
        model.start()
        assertEquals(1, refreshes)
        events.tryEmit(HubEvent.JoinRequest("K7M4QX", "new-learner", "Test", "private", 1))
        assertEquals(1, refreshes)
        refreshGate!!.complete(Unit)
        assertEquals(2, refreshes)
        assertTrue(model.state.value.sheetOpen)
    }
    @Test fun stoppingAndRestartingDoesNotDuplicateEventSubscriptions() {
        model.start(); model.start()
        val initial = refreshes
        events.tryEmit(HubEvent.HelloAck("hub", 1, 1))
        assertEquals(initial + 1, refreshes)
        model.stop()
        events.tryEmit(HubEvent.HelloAck("hub", 1, 2))
        assertEquals(initial + 1, refreshes)
        model.start()
        assertEquals(initial + 2, refreshes)
    }
}
