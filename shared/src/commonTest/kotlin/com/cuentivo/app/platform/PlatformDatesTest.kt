package com.cuentivo.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Lo que se puede afirmar de las fechas sin depender de la zona ni del idioma del teléfono.
 *
 * Corre en los dos lados: Android en la JVM del host y iOS en el runner.
 */
class PlatformDatesTest {

    @Test
    fun newUuid_is_lowercase_and_version_four() {
        val uuid = newUuid()

        assertTrue(UUID_V4.matches(uuid), "no es un UUID v4 en minúsculas: $uuid")
    }

    @Test
    fun two_new_uuids_are_different() {
        assertTrue(newUuid() != newUuid(), "dos llamadas devolvieron el mismo UUID")
    }

    @Test
    fun iso_date_of_epoch_day_zero_is_zero() {
        assertEquals(0L, isoDateToEpochDay("1970-01-01"))
    }

    @Test
    fun iso_date_of_a_known_day_matches() {
        assertEquals(20089L, isoDateToEpochDay("2025-01-01"))
    }

    @Test
    fun an_invalid_iso_date_throws() {
        assertFails { isoDateToEpochDay("2025-13-45") }
    }

    @Test
    fun today_is_past_january_first_2025() {
        assertTrue(todayEpochDay() > 20089L, "todayEpochDay() devolvió ${todayEpochDay()}")
    }

    private companion object {
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
