package com.securekeyboard.app

import android.app.Application

/**
 * Initializes the local runtime security monitor. No secrets or user input
 * are logged. The posture is exposed only through SecurityRuntime for
 * sensitive screens to consult before cryptographic actions.
 */
class SecurityApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Do not emit the posture to logcat: a hardened build should minimize
        // useful security telemetry available to local attackers.
        SecurityRuntime.assess(this)
    }
}
