package org.lara.app

import android.app.Application

class LaraApp : Application() {
    val container: AppContainer by lazy { AppContainer() }
}

// Services are added by their owning issues; the scaffold opens no database or connection.
class AppContainer
