package com.cuentivo.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

/**
 * Los formatos de iOS, contra el día y la hora que se escribieron.
 *
 * El caso que importa es el de la zona: `20089` es `2025-01-01 00:00 UTC`, y en México eso todavía es
 * `2024-12-31 18:00`. La prueba reproduce la trampa con un formateador en zona de México y comprueba
 * que la función de producción, que fija UTC, no se mueve de día.
 *
 * Pendiente de CI: esta prueba sólo corre en `:shared:iosSimulatorArm64Test`.
 */
class PlatformDatesIosTest {

    @Test
    fun the_spanish_date_is_january_first_2025() {
        val text = dayMonthYearText(EPOCH_DAY_2025, "es_MX")

        assertTrue(text.startsWith("1 "), "el día se movió: $text")
        assertTrue(text.contains("2025"), "el año se movió: $text")
    }

    @Test
    fun the_local_zone_would_move_the_day_but_the_function_does_not() {
        // What a naive implementation (the device's zone) would print for the same epoch day.
        val naive = NSDateFormatter()
        naive.setDateFormat("d MMM uuuu")
        naive.setLocale(NSLocale(localeIdentifier = "es_MX"))
        naive.setTimeZone(NSTimeZone.timeZoneForSecondsFromGMT(-21_600L))
        val naiveText = naive.stringFromDate(NSDate.dateWithTimeIntervalSince1970(EPOCH_DAY_2025 * 86_400.0))

        assertTrue(naiveText.startsWith("31 "), "la trampa no se reproduce en este entorno: $naiveText")
        assertTrue(dayMonthYearText(EPOCH_DAY_2025, "es_MX").startsWith("1 "))
    }

    @Test
    fun the_english_date_contains_january() {
        assertTrue(dayMonthYearText(EPOCH_DAY_2025, "en_US").contains("Jan"))
    }

    @Test
    fun hour_and_minute_in_a_fixed_zone() {
        val gmt = NSTimeZone.timeZoneForSecondsFromGMT(0)

        assertEquals("14:05", hourMinuteText(STAMP_14_05_UTC, "es_MX", gmt))
    }

    @Test
    fun the_short_time_is_never_empty() {
        assertTrue(formatShortTimeEsMx(STAMP_14_05_UTC).isNotEmpty())
    }

    @Test
    fun local_day_iso_uses_the_zone_it_is_given() {
        // 2026-08-23T02:00Z is still 2026-08-22 in Mexico City (UTC-6) and 2026-08-23 in UTC.
        assertEquals("2026-08-22", localDayIsoIn(1787450400000L, TimeZone.of("America/Mexico_City")))
        assertEquals("2026-08-23", localDayIsoIn(1787450400000L, TimeZone.UTC))
    }

    @Test
    fun now_is_past_a_known_instant() {
        assertTrue(nowEpochMillis() > 1787450400000L)
    }

    private companion object {
        const val EPOCH_DAY_2025 = 20089L
        const val STAMP_14_05_UTC = 1_735_740_300_000L
    }
}
