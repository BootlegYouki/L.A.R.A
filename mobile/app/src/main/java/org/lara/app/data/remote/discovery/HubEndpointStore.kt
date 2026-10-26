package org.lara.app.data.remote.discovery

import android.content.Context

class HubEndpointStore(context: Context) {
    private val prefs = context.getSharedPreferences("hub_endpoint", Context.MODE_PRIVATE)
    fun save(endpoint: HubEndpoint) {
        prefs.edit().putString("ip", endpoint.ip).putInt("http_port", endpoint.httpPort)
            .putInt("ws_port", endpoint.wsPort).apply()
    }
    fun load(): HubEndpoint? = prefs.getString("ip", null)?.let {
        HubEndpoint.lan(it, prefs.getInt("http_port", 8080), prefs.getInt("ws_port", 8081))
    }
}
