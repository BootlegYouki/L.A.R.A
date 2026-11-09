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
    val user: User? = null, val error: Int? = null, val retryAt: Long = 0, val sessionVersion: Long = 0,
    val previousUser: User? = null)

class AuthViewModel(private val repository: AuthRepository, private val endpoints: HubEndpointStore) : ViewModel() {
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var nextSession = 0L
    init {
        viewModelScope.launch {
            try { mutable.value = AuthState(restoring = false, user = repository.restore(endpoints.load()),
                previousUser = repository.cachedUser(), sessionVersion = ++nextSession) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = AuthState(restoring = false, error = R.string.auth_storage_error) }
        }
    }
    fun requireSignIn(expectedSession: Long? = null) {
        if (expectedSession != null && mutable.value.sessionVersion != expectedSession) return
        mutable.value = AuthState(restoring = false, error = R.string.enrollment_session_expired,
            previousUser = mutable.value.user ?: mutable.value.previousUser)
    }
    fun signedOut(expectedSession: Long? = null) {
        if (expectedSession == null || mutable.value.sessionVersion == expectedSession) mutable.value = AuthState(restoring = false)
    }
    fun submit(id: String, pin: String, name: String?) {
        if (mutable.value.busy || SystemClock.elapsedRealtime() < mutable.value.retryAt) return
        val normalizedId = id.trim()
        val normalizedName = name?.trim()
        if (normalizedId.isBlank() || normalizedId.length > 32 || !pin.matches(Regex("[0-9]{4}")) ||
            (normalizedName != null && (normalizedName.isBlank() || normalizedName.length > 100 || !normalizedId.matches(Regex("[0-9]{12}"))))) {
            mutable.value = mutable.value.copy(error = R.string.auth_invalid)
            return
        }
        val endpoint = endpoints.load()
        if (endpoint == null) { mutable.value = mutable.value.copy(error = R.string.auth_hub_required); return }
        mutable.value = mutable.value.copy(busy = true, error = null)
        val previousUser = mutable.value.previousUser
        viewModelScope.launch {
            try { mutable.value = AuthState(restoring = false, user = repository.authenticate(endpoint, normalizedId, pin, normalizedName), sessionVersion = ++nextSession) }
            catch (e: CancellationException) { throw e }
            catch (e: AuthFailure) {
                val error = when(e.status) { 401 -> R.string.auth_wrong_pin; 403 -> R.string.auth_admin; 409 -> R.string.auth_exists; 429 -> R.string.auth_wait; else -> R.string.auth_failed }
                mutable.value = AuthState(restoring = false, error = error,
                    retryAt = if(e.status == 429) SystemClock.elapsedRealtime() + e.retryAfterSeconds * 1000 else 0,
                    previousUser = previousUser)
            }
            catch (_: java.io.IOException) { mutable.value = AuthState(restoring = false, error = R.string.auth_unreachable, previousUser = previousUser) }
            catch (_: Exception) { mutable.value = AuthState(restoring = false, error = R.string.auth_failed, previousUser = previousUser) }
        }
    }
}
