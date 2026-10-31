package com.cuentivo.app.observability

import com.cuentivo.app.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * The fixed, low-cardinality technical failures worth counting in production.
 *
 * This is the *only* vocabulary the reporter accepts: no arbitrary strings, no exception messages,
 * no values. Nothing that could carry a name, balance, business id, email, phone or token is ever
 * sent to Crashlytics — the app's privacy rule holds even inside a crash report.
 */
enum class CrashEvent {
    AUTH_FAILURE,
    AUTH_ANON_OFFLINE,
    AUTH_ANON_PROVIDER_DISABLED,
    AUTH_ANON_SERVICE_ERROR,
    CLOUD_RESTORE_FAILURE,
    BILLING_FAILURE,
}

/** Privacy-safe crash reporting: [report] takes exactly a [CrashEvent] and nothing else. */
interface CrashReporter {
    fun report(event: CrashEvent)
}

/** Debug and emulator builds: nothing leaves the device. */
object NoOpCrashReporter : CrashReporter {
    override fun report(event: CrashEvent) = Unit
}

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
