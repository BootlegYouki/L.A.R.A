package org.lara.app.ui.screens.teacher.classwork

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClassworkAuthoringViewModelTest {
    @Test fun attachmentFailureRetriesSavedAssignmentWithoutDuplicatingEarlierUploads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val targets = mutableListOf<String?>()
            val uploaded = mutableListOf<String>()
            var fail = true
            val repo = object : ClassworkAuthoringRepository {
                override suspend fun saveAssignment(classroomId: String, id: String?, draft: AssignmentDraft): String { targets.add(id); return "saved" }
                override suspend fun upload(classroomId: String, uri: String, draft: MaterialDraft) {
                    if (uri == "second" && fail) { fail = false; throw IOException() }
                    assertEquals("saved", draft.assignmentId)
                    uploaded.add(uri)
                }
                override suspend fun saveTopic(classroomId: String, id: String?, name: String) = Unit
                override suspend fun saveMaterial(id: String, title: String, topicId: String?) = Unit
                override suspend fun delete(kind: String, id: String) = Unit
                override fun uploads(classroomId: String) = flowOf(emptyList<AuthoringUpload>())
                override suspend fun retry(uploadId: String) = Unit
            }
            val model = ClassworkAuthoringViewModel(repo)
            val draft = AssignmentDraft("Title", "Instructions", 100, 1, false, null)
            model.assignment("class", null, draft, listOf("first", "second"))
            model.assignment("class", null, draft, listOf("first", "second"))
            runCurrent()
            assertEquals(listOf<String?>(null), targets)
            assertNotNull(model.state.value.error)
            model.assignment("class", null, draft.copy(title = "Corrected"), listOf("first", "second"))
            runCurrent()
            assertEquals(listOf(null, "saved"), targets)
            assertEquals(listOf("first", "second"), uploaded)
            assertEquals(1L, model.state.value.completed)
        } finally { Dispatchers.resetMain() }
    }
}
