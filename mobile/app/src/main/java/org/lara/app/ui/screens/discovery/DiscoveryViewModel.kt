package org.lara.app.ui.screens.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.domain.discovery.DiscoveredHub
import org.lara.app.domain.discovery.DiscoverySnapshot
import org.lara.app.domain.discovery.HubDiscovery

class DiscoveryViewModel(private val discovery: HubDiscovery) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverySnapshot())
    val state = _state.asStateFlow()
    private val _hubs = MutableStateFlow<List<DiscoveredHub>>(emptyList())
    val hubs: StateFlow<List<DiscoveredHub>> = _hubs.asStateFlow()
    private val _result = MutableStateFlow<ConnectionResult?>(null)
    val result = _result.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting = _connecting.asStateFlow()
    private var scan: Job? = null
    private var stoppingScan: Job? = null
    private var connection: Job? = null
    private var connectionGeneration = 0

    fun start() {
        if (scan?.isActive == true) return
        val previous = stoppingScan
        scan = viewModelScope.launch {
            // Wait for the old UDP socket/listeners to close before a rescan binds again.
            previous?.join()
            discovery.observe().collect { snapshot ->
                if (snapshot.wifiAvailable != _state.value.wifiAvailable || snapshot.session != _state.value.session) {
                    connectionGeneration++
                    connection?.cancel()
                    _connecting.value = false
                    _result.value = null
                }
                _state.value = snapshot
                _hubs.value = snapshot.hubs
            }
        }
    }
    fun stop() {
        connectionGeneration++
        stoppingScan = scan ?: stoppingScan
        scan?.cancel(); scan = null
        connection?.cancel(); connection = null
        _connecting.value = false
        _result.value = null
        _state.value = DiscoverySnapshot()
        _hubs.value = emptyList()
    }
    fun retry() { stop(); start() }
    fun connect(input: String) = connect { discovery.connect(input) }
    fun connect(hub: DiscoveredHub) = connect { discovery.connect(hub) }
    fun clearResult() { if (!_connecting.value) _result.value = null }
    private fun connect(action: suspend () -> ConnectionResult) {
        if (_connecting.value) return
        _result.value = null
        _connecting.value = true
        val generation = ++connectionGeneration
        connection = viewModelScope.launch {
            try { _result.value = action() }
            finally { if (generation == connectionGeneration) _connecting.value = false }
        }
    }
}
