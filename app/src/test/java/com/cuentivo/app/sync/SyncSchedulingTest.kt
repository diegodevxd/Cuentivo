package com.cuentivo.app.sync

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule pinned here is the one that produced the v7/1.3 bug: a customer was written to Room, the
 * scheduling call after it threw, and the exception carried the caller past its own list refresh and
 * success message. The row existed and the screen never showed it.
 *
 * `WorkManager.getInstance()` throws exactly that way when WorkManager never initialised, which is
 * something a minified release build can do and a debug build does not — hence a bug that only ever
 * showed up in the release APK.
 */
class SyncSchedulingTest {

    @Test fun a_successful_schedule_reports_no_failure() {
        var ran = false

        val failure = SyncScheduling.attempt { ran = true }

        assertTrue(ran)
        assertNull(failure)
    }

    @Test fun an_uninitialised_work_manager_is_reported_rather_than_thrown() {
        // The real shape of the crash: WorkManager.getInstance() on a device where its startup
        // provider never ran.
        val thrown = IllegalStateException("WorkManager is not initialized properly")

        val failure = SyncScheduling.attempt { throw thrown }

        // Returned, not thrown: whatever the caller already saved stays visible.
        assertSame(thrown, failure)
    }

    @Test fun an_error_still_travels() {
        val thrown = OutOfMemoryError("no room")

        val error = try {
            SyncScheduling.attempt { throw thrown }
            null
        } catch (caught: OutOfMemoryError) {
            caught
        }

        // An Error is not something to note and carry on from.
        assertSame(thrown, error)
    }
}
