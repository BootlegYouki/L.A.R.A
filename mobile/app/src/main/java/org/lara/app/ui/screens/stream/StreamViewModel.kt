package org.lara.app.ui.screens.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lara.app.domain.StreamRepository

data class CommentAction(val sending: Set<String> = emptySet(), val failed: Set<String> = emptySet())

class StreamViewModel(private val repository: StreamRepository, private val userId: String) : ViewModel() {
    private val mutable = MutableStateFlow(CommentAction())
    val action = mutable.asStateFlow()
    fun send(id: String, text: String, onSaved: () -> Unit) {
        if (text.isBlank() || id in mutable.value.sending) return
        mutable.value = mutable.value.copy(sending = mutable.value.sending + id, failed = mutable.value.failed - id)
        viewModelScope.launch {
            try { repository.addComment(id, userId, text.trim()); onSaved() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(failed = mutable.value.failed + id) }
            finally { mutable.value = mutable.value.copy(sending = mutable.value.sending - id) }
        }
    }
}
