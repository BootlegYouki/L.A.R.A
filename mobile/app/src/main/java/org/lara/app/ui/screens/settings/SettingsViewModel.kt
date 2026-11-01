package org.lara.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lara.app.data.repository.*
import org.lara.app.data.remote.discovery.HubEndpointStore

data class SettingsState(val loading: Boolean = true, val busy: Boolean = false,
    val hubName: String? = null, val address: String? = null, val queued: Int? = null, val error: Boolean = false)

class SettingsViewModel(private val userId: String, private val repository: SettingsRepository,
    private val languages: AppLanguageStore, private val endpoints: HubEndpointStore,
    private val onChangeHub: (() -> Unit)? = null,
    private val onSignedOut: () -> Unit) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsState())
    val state = mutable.asStateFlow()
    val language = languages.language
    private var pendingHubChange = false
    fun refresh() { viewModelScope.launch {
        try {
            val info = withContext(Dispatchers.IO) { endpoints.name() to endpoints.load()?.restBase }
            mutable.update { it.copy(loading = false, hubName = info.first, address = info.second) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(loading = false, error = true) } }
    } }
    fun language(value: String) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            try { languages.select(value); mutable.update { it.copy(error = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = true) } }
        }
    }
    fun cancelSignOut() { if (!mutable.value.busy) { pendingHubChange = false; mutable.update { it.copy(queued = null) } } }
    fun signOut(confirmed: Boolean = false, changeHub: Boolean = false) {
        if (mutable.value.busy) return
        val approved = if (confirmed) mutable.value.queued ?: return else null
        val nextHub = if (confirmed) pendingHubChange else changeHub
        mutable.update { it.copy(busy = true, error = false) }
        viewModelScope.launch {
            try {
                when (val result = repository.signOut(userId, approved)) {
                    is SignOutResult.NeedsConfirmation -> { pendingHubChange = nextHub; mutable.update { it.copy(queued = result.count) } }
                    SignOutResult.SignedOut -> {
                        mutable.update { it.copy(queued = null) }
                        if (nextHub && onChangeHub != null) onChangeHub.invoke() else onSignedOut()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = true) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
