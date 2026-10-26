package org.lara.app

import android.app.Application
import android.content.Context
import org.lara.app.data.remote.discovery.AndroidHubDiscovery
import org.lara.app.domain.discovery.HubDiscovery

class LaraApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val discovery: HubDiscovery = AndroidHubDiscovery(context)
}
