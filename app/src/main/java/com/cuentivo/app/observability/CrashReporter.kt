package com.cuentivo.app.observability

import com.cuentivo.app.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Release delegate: records the fixed event name as a non-fatal. The exception carries no message
 * content beyond the enum name itself — deliberately constructed here, never forwarded from a catch.
 */
class CrashlyticsCrashReporter : CrashReporter {
    override fun report(event: CrashEvent) {
        FirebaseCrashlytics.getInstance()
            .recordException(IllegalStateException("event:${event.name}"))
    }
}

object CrashReporterFactory {
    /** The running build's reporter: debug is a no-op, release reports fixed events. */
    fun create(): CrashReporter =
        if (BuildConfig.DEBUG) NoOpCrashReporter else CrashlyticsCrashReporter()

    /**
     * Disables Crashlytics collection entirely for debug/emulator, so even uncaught crashes in a
     * dev session never pollute the production dashboard. Idempotent; call once at startup.
     */
    fun install() {
        if (BuildConfig.DEBUG) {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
        }
    }
}
