package com.cuentivo.app.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Android, copiado de donde ya vivía cada formato: `CustomerDetailSheet` para la fecha de negocio y
 * la hora del abono, `DashboardScreen` para la hora corta del tablero. Los `Locale` son los mismos
 * que usaba cada pantalla; nada se normaliza de paso.
 */

actual fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

actual fun newUuid(): String = UUID.randomUUID().toString()

actual fun isoDateToEpochDay(iso: String): Long = LocalDate.parse(iso).toEpochDay()

@Composable
actual fun formatDayMonthYear(epochDay: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { dayMonthYearFormatter(locale) }
    return dayMonthYearText(epochDay, formatter)
}

@Composable
actual fun formatHourMinute(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { hourMinuteFormatter(locale) }
    return hourMinuteText(epochMillis, formatter, ZoneId.systemDefault())
}

actual fun formatShortTimeEsMx(epochMillis: Long): String =
    shortTimeText(epochMillis, Locale.forLanguageTag("es-MX"), ZoneId.systemDefault())

/**
 * El `BackHandler` de siempre. iOS no tiene equivalente y no lo necesita: su `actual` no hace nada.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

// Las partes puras, sin composición, para poder afirmarlas desde `androidHostTest`.

internal fun dayMonthYearFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM uuuu", locale)

internal fun dayMonthYearText(epochDay: Long, formatter: DateTimeFormatter): String =
    LocalDate.ofEpochDay(epochDay).format(formatter)

internal fun hourMinuteFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", locale)

internal fun hourMinuteText(epochMillis: Long, formatter: DateTimeFormatter, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(formatter)

internal fun shortTimeText(epochMillis: Long, locale: Locale, zone: ZoneId): String {
    val format = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    format.timeZone = TimeZone.getTimeZone(zone)
    return format.format(Date(epochMillis))
}
