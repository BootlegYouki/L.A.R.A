package org.lara.app.ui.screens.discovery

import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.domain.discovery.DiscoverySnapshot

internal enum class HubBeacon { SEARCHING, CONNECTED, NOT_CONNECTED }

internal fun hubBeacon(state: DiscoverySnapshot, result: ConnectionResult?, checking: Boolean): HubBeacon = when {
    checking -> HubBeacon.SEARCHING
    result == ConnectionResult.RESPONDING -> HubBeacon.CONNECTED
    result != null || !state.wifiAvailable || state.warning -> HubBeacon.NOT_CONNECTED
    state.scanning -> HubBeacon.SEARCHING
    else -> HubBeacon.NOT_CONNECTED
}
