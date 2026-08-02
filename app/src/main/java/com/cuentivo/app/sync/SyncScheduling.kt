package com.cuentivo.app.sync

/**
 * Runs the "wake the sync worker" half of an enqueue without letting it undo the half that already
 * succeeded.
 *
 * The outbox row is written to Room first and is the durable part: once committed, the change is
 * safe and drains whenever a worker next runs. Scheduling that worker is only a hint that it could
 * run sooner. `WorkManager.getInstance()` throws when WorkManager is not initialised — which is what
 * a minified release build does if its startup provider is stripped — and that exception used to
 * travel all the way out of the caller's coroutine, skipping the list refresh and the success
 * message that came after it. The row was saved and the screen never showed it.
 *
 * Kept as its own object rather than a `try` inline so the swallowing is unit-testable without
 * Android. The thing that must never regress: an ordinary scheduling failure is reported rather
 * than thrown, and an [Error] still travels.
 */
internal object SyncScheduling {

    /**
     * Invokes [schedule], returning the exception it failed with, or null when it succeeded.
     *
     * Only [Exception] is caught. An [Error] — an OOM, a linkage failure — is not something to note
     * and carry on from, so it keeps propagating.
     */
    fun attempt(schedule: () -> Unit): Exception? = try {
        schedule()
        null
    } catch (error: Exception) {
        error
    }
}
