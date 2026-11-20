package org.lara.app.ui.screens.teacher.classwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lara.app.R
import org.lara.app.domain.*

internal data class AuthoringState(val busy: Boolean = false, val error: Int? = null, val completed: Long = 0)
internal class ClassworkAuthoringViewModel(val repository: ClassworkAuthoringRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AuthoringState())
    val state = mutable.asStateFlow()
    private var savedAssignmentId: String? = null
    private val queuedAttachments = mutableSetOf<String>()
    fun reset() { if (!mutable.value.busy) { savedAssignmentId = null; queuedAttachments.clear(); mutable.value = AuthoringState(completed = mutable.value.completed) } }
    fun run(close: Boolean = true, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                action()
                mutable.value = AuthoringState(completed = mutable.value.completed + if (close) 1 else 0)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(busy = false,
                    error = if (error.message == "FILE_TOO_LARGE") R.string.author_too_large else R.string.author_failed)
            }
        }
    }
    fun assignment(classroomId: String, id: String?, draft: AssignmentDraft, attachments: List<String>) = run {
        val saved = repository.saveAssignment(classroomId, savedAssignmentId ?: id, draft).also { savedAssignmentId = it }
        // Each successful enqueue is removed, so retrying a later attachment cannot post it twice.
        attachments.filterNot { it in queuedAttachments }.forEach { uri ->
            repository.upload(classroomId, uri, MaterialDraft(draft.title, "DOCUMENT", assignmentId = saved))
            queuedAttachments.add(uri)
        }
        savedAssignmentId = null
    }
}
