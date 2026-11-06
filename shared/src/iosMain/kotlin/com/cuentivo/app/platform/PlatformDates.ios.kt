package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.Foundation.timeZoneForSecondsFromGMT

/**
 * iOS, con `NSDateFormatter` y sin `kotlinx-datetime` más allá de `LocalDate.parse`.
 *
 * La trampa está en [formatDayMonthYear]: una fecha de negocio (`...EpochDay`) no tiene zona, y si se
 * formatea con la zona local, en México el `1 ene` saldría `31 dic`. Por eso el `NSDate` se construye
 * a medianoche UTC y se formatea con **UTC**: el día que se escribió es el día que se lee.
 */

actual fun todayEpochDay(): Long = iosNowLocalDateTime().date.toEpochDays().toLong()

actual fun newUuid(): String = NSUUID().UUIDString.lowercase()

actual fun isoDateToEpochDay(iso: String): Long = LocalDate.parse(iso).toEpochDays().toLong()

@Composable
actual fun formatDayMonthYear(epochDay: Long): String =
    dayMonthYearText(epochDay, compositionLocale().localeIdentifier)

@Composable
actual fun formatHourMinute(epochMillis: Long): String =
    hourMinuteText(epochMillis, compositionLocale().localeIdentifier, NSTimeZone.localTimeZone)

actual fun formatShortTimeEsMx(epochMillis: Long): String =
    shortTimeText(epochMillis, LOCALE_ES_MX, NSTimeZone.localTimeZone)

/**
 * **No hace nada, a propósito.** iOS no tiene botón atrás ni un gesto que interceptar; la hoja de
 * detalle ya trae su `DetailTopBar` visible para cerrar, y secuestrar el gesto de borde sería peor
 * que no tenerlo. Existe sólo para que las pantallas compartidas puedan pedirlo.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/** El idioma de la composición: el primero preferido del sistema, o el actual si no hay lista. */
internal fun compositionLocale(): NSLocale =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.let { NSLocale(localeIdentifier = it) }
        ?: NSLocale.currentLocale

/**
 * "1 ene 2025". `localeIdentifier` y la zona son parámetros para poder probarla desde `iosTest`; la
 * zona es UTC siempre, y no es un detalle: ver el comentario del archivo.
 */
internal fun dayMonthYearText(epochDay: Long, localeIdentifier: String): String {
    val formatter = NSDateFormatter()
    formatter.setDateFormat("d MMM uuuu")
    formatter.setLocale(NSLocale(localeIdentifier = localeIdentifier))
    formatter.setTimeZone(NSTimeZone.timeZoneForSecondsFromGMT(0))
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochDay * 86_400.0))
}

/** "14:05" en la zona recibida. */
internal fun hourMinuteText(epochMillis: Long, localeIdentifier: String, timeZone: NSTimeZone): String {
    val formatter = NSDateFormatter()
    formatter.setDateFormat("HH:mm")
    formatter.setLocale(NSLocale(localeIdentifier = localeIdentifier))
    formatter.setTimeZone(timeZone)
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))
}

/** Hora corta del sistema en el idioma recibido: lo que hace hoy el tablero con `DateFormat.SHORT`. */
internal fun shortTimeText(epochMillis: Long, localeIdentifier: String, timeZone: NSTimeZone): String {
    val formatter = NSDateFormatter()
    formatter.setTimeStyle(NSDateFormatterShortStyle)
    formatter.setDateStyle(NSDateFormatterNoStyle)
    formatter.setLocale(NSLocale(localeIdentifier = localeIdentifier))
    formatter.setTimeZone(timeZone)
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))
}

private const val LOCALE_ES_MX = "es_MX"
