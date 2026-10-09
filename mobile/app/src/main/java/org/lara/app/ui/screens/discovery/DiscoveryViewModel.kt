package org.lara.app.ui.screens.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lara.app.data.remote.discovery.DiscoverySource
import org.lara.app.data.remote.discovery.DiscoveredHub
import org.lara.app.data.remote.discovery.HubDiscoveryService
import org.lara.app.data.remote.discovery.NetworkMonitor

/**
 * Immutable screen state for the discovery screen (MVI, `mobile/docs/TECH_SPEC.md` §3). One state
 * object drives the whole UI so it survives configuration change and is trivially testable.
 */
data class DiscoveryUiState(
    val hubs: List<DiscoveredHub> = emptyList(),
    val isScanning: Boolean = false,
    val wifiAvailable: Boolean = true,
    val manualInput: String = "",
    val manualError: ManualInputError? = null,
    val showManualDialog: Boolean = false,
) {
    /** True once at least one Hub is known, so the UI can swap the "searching" skeleton for the list. */
    val hasHubs: Boolean get() = hubs.isNotEmpty()
}

/** Why a manual IP entry was rejected; mapped to a localized string in the UI. */
enum class ManualInputError { EMPTY, INVALID_FORMAT }

/**
 * Owns LAN discovery state for the connection screen. It merges the passive mDNS + UDP sources from
 * [HubDiscoveryService], de-duplicates them by [DiscoveredHub.key], and restarts the scan whenever
 * Wi-Fi becomes available again ([NetworkMonitor]). Manual IP entries are validated with the issue's
 * regex and merged into the same list so the UI renders one uniform set of Hub cards.
 *
 * [AndroidViewModel] is used because discovery needs the application [Context] for the Android
 * system services; the context is the application's, never an Activity's, so nothing leaks.
 */
class DiscoveryViewModel(
    application: Application,
    private val discoveryService: HubDiscoveryService = HubDiscoveryService(application),
    private val networkMonitor: NetworkMonitor = NetworkMonitor(application),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    /**
     * The discovered Hubs as a standalone [StateFlow], the primary artifact this issue requires
     * ("Expose discovered hubs via StateFlow<List<DiscoveredHub>>"). It is derived from [uiState] so
     * there is a single source of truth.
     */
    private val _hubs = MutableStateFlow<List<DiscoveredHub>>(emptyList())
    val hubs: StateFlow<List<DiscoveredHub>> = _hubs.asStateFlow()

    private var scanJob: Job? = null

    init {
        observeNetwork()
        startDiscovery()
    }

    /** Restart discovery each time Wi-Fi (re)appears; stop scanning spinner when it is lost. */
    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.wifiAvailability().collect { available ->
                _uiState.update { it.copy(wifiAvailable = available) }
                if (available) {
                    startDiscovery()
                }
            }
        }
    }

    /**
     * (Re)start collecting the passive discovery sources. Cancels any prior scan first so a network
     * flap cannot leave two overlapping collectors (and two multicast-lock holders) running.
     */
    fun startDiscovery() {
        scanJob?.cancel()
        _uiState.update { it.copy(isScanning = true) }
        scanJob = viewModelScope.launch {
            discoveryService.discover().collect { hub ->
                mergeHub(hub)
            }
        }
    }

    /** Fold a newly seen Hub into the list, replacing any earlier entry with the same [DiscoveredHub.key]. */
    private fun mergeHub(hub: DiscoveredHub) {
        _uiState.update { state ->
            val merged = buildList {
                var replaced = false
                for (existing in state.hubs) {
                    if (existing.key == hub.key) {
                        // Prefer a manual entry's identity but keep whichever has a real name/version.
                        add(preferRicher(existing, hub))
                        replaced = true
                    } else {
                        add(existing)
                    }
                }
                if (!replaced) add(hub)
            }.sortedWith(hubOrder)
            state.copy(hubs = merged, isScanning = true)
        }
        _hubs.value = _uiState.value.hubs
    }

    /** When the same endpoint arrives twice, keep the entry that carries more information. */
    private fun preferRicher(a: DiscoveredHub, b: DiscoveredHub): DiscoveredHub {
        val aScore = (if (a.name != a.ip) 1 else 0) + (if (a.version != null) 1 else 0)
        val bScore = (if (b.name != b.ip) 1 else 0) + (if (b.version != null) 1 else 0)
        return if (bScore >= aScore) b else a
    }

    // -------- Manual IP fallback (router AP isolation) --------

    fun onManualInputChange(value: String) {
        _uiState.update { it.copy(manualInput = value, manualError = null) }
    }

    fun openManualDialog() {
        _uiState.update { it.copy(showManualDialog = true, manualInput = "", manualError = null) }
    }

    fun dismissManualDialog() {
        _uiState.update { it.copy(showManualDialog = false, manualError = null) }
    }

    /**
     * Validate and accept a manual IP/port. On success the Hub is merged into the list and [on
     * accepted] is invoked (the screen navigates on). On failure the state carries a
     * [ManualInputError] so the dialog shows inline, elementary-friendly feedback.
     */
    fun submitManualEntry(onAccepted: (DiscoveredHub) -> Unit) {
        val raw = _uiState.value.manualInput.trim()
        if (raw.isEmpty()) {
            _uiState.update { it.copy(manualError = ManualInputError.EMPTY) }
            return
        }
        val hub = discoveryService.manualHub(raw)
        if (hub == null) {
            _uiState.update { it.copy(manualError = ManualInputError.INVALID_FORMAT) }
            return
        }
        mergeHub(hub)
        _uiState.update { it.copy(showManualDialog = false, manualError = null) }
        onAccepted(hub)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    private companion object {
        // Manual entries first (the pupil typed them on purpose), then by display name.
        val hubOrder = compareByDescending<DiscoveredHub> { it.source == DiscoverySource.MANUAL }
            .thenBy { it.name.lowercase() }
    }
}
