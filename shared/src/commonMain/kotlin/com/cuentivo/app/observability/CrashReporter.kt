package com.cuentivo.app.observability

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
