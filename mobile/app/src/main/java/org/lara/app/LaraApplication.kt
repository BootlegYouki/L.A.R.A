package org.lara.app

import android.app.Application

/**
 * Process-wide [Application]. Kept intentionally thin in the scaffold: later sprints wire the Room
 * database, OkHttp client, WorkManager and the Hub discovery service here (see TECH_SPEC §2).
 */
class LaraApplication : Application()
