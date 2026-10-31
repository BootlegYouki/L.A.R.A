package org.lara.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lara.app.R
import org.lara.app.domain.model.User
import org.lara.app.data.remote.AuthFailure
import org.lara.app.data.remote.discovery.HubEndpointStore
import org.lara.app.data.repository.AuthRepository
import android.os.SystemClock

data class AuthState(val restoring: Boolean = true, val busy: Boolean = false,
    val user: User? = null, val error: Int? = null, val retryAt: Long = 0)

class AuthViewModel(private val repository: AuthRepository, private val endpoints: HubEndpointStore) : ViewModel() {
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try { mutable.value = AuthState(restoring = false, user = repository.restore(endpoints.load())) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = AuthState(restoring = false, error = R.string.auth_storage_error) }
        }
    }
    fun submit(id: String, pin: String, name: String?) {
        if (mutable.value.busy || SystemClock.elapsedRealtime() < mutable.value.retryAt) return
        if (id.isBlank() || !pin.matches(Regex("[0-9]{4}")) || (name != null && (name.isBlank() || !id.matches(Regex("[0-9]{12}"))))) {
            mutable.value = mutable.value.copy(error = R.string.auth_invalid)
            return
        }
        val endpoint = endpoints.load()
        if (endpoint == null) { mutable.value = mutable.value.copy(error = R.string.auth_hub_required); return }
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { mutable.value = AuthState(restoring = false, user = repository.authenticate(endpoint, id.trim(), pin, name?.trim())) }
            catch (e: CancellationException) { throw e }
            catch (e: AuthFailure) {
                val error = when(e.status) { 401 -> R.string.auth_wrong_pin; 403 -> R.string.auth_admin; 409 -> R.string.auth_exists; 429 -> R.string.auth_wait; else -> R.string.auth_failed }
                mutable.value = AuthState(restoring = false, error = error,
                    retryAt = if(e.status == 429) SystemClock.elapsedRealtime() + e.retryAfterSeconds * 1000 else 0)
            }
            catch (_: java.io.IOException) { mutable.value = AuthState(restoring = false, error = R.string.auth_unreachable) }
            catch (_: Exception) { mutable.value = AuthState(restoring = false, error = R.string.auth_failed) }
        }
    }
}
