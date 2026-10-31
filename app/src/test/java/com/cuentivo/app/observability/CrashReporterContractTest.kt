package com.cuentivo.app.observability

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReporterContractTest {
    @Test
    fun `reporter only accepts fixed technical events`() {
        assertEquals(
            setOf(
                CrashEvent.AUTH_FAILURE,
                CrashEvent.AUTH_ANON_OFFLINE,
                CrashEvent.AUTH_ANON_PROVIDER_DISABLED,
                CrashEvent.AUTH_ANON_SERVICE_ERROR,
                CrashEvent.CLOUD_RESTORE_FAILURE,
                CrashEvent.BILLING_FAILURE,
            ),
            CrashEvent.entries.toSet(),
        )
    }
}
