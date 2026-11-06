package com.cuentivo.app.platform

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Los formatos de Android contra el texto exacto que produce Java hoy.
 *
 * Los valores esperados salieron de correr estas mismas funciones, no de suponerlos: si cambian, es
 * porque el formato cambió, que es justo lo que no puede pasar de tapadillo al mover una pantalla.
 */
class PlatformDatesAndroidHostTest {

    @Test
    fun day_month_year_in_spanish() {
        val text = dayMonthYearText(EPOCH_DAY_2025, dayMonthYearFormatter(Locale.forLanguageTag("es-MX")))

        assertEquals(DAY_MONTH_YEAR_ES, text)
    }

    @Test
    fun day_month_year_in_english() {
        val text = dayMonthYearText(EPOCH_DAY_2025, dayMonthYearFormatter(Locale.ENGLISH))

        assertEquals(DAY_MONTH_YEAR_EN, text)
    }

    @Test
    fun hour_minute_uses_the_zone_it_is_given() {
        val formatter = hourMinuteFormatter(Locale.forLanguageTag("es-MX"))

        assertEquals("14:05", hourMinuteText(STAMP_14_05_UTC, formatter, ZoneId.of("UTC")))
        // Japan is nine hours ahead, so the same instant is late at night there. A formatter that
        // ignored the zone would print 14:05 twice and this would fail on the second assertion.
        assertEquals("23:05", hourMinuteText(STAMP_14_05_UTC, formatter, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun today_matches_java() {
        assertEquals(LocalDate.now().toEpochDay(), todayEpochDay())
    }

    @Test
    fun short_time_is_never_empty() {
        assertEquals(true, formatShortTimeEsMx(STAMP_14_05_UTC).isNotEmpty())
    }

    private companion object {
        const val EPOCH_DAY_2025 = 20089L
        const val STAMP_14_05_UTC = 1_735_740_300_000L

        // Leídos de la corrida de estas funciones con Java, no escritos a mano.
        const val DAY_MONTH_YEAR_ES = "1 ene 2025"
        const val DAY_MONTH_YEAR_EN = "1 Jan 2025"
    }
}
