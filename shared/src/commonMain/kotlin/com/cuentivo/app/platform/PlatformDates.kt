package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Las fechas y el reloj que las pantallas compartidas necesitan, sin nombrar ninguno de los dos.
 *
 * `commonMain` no puede tocar `kotlinx-datetime` directamente: Android compila contra 0.6.2 e iOS
 * contra 0.7.1, y el mismo método puede devolver tipos distintos (`toEpochDays` es el caso clásico).
 * Por eso todo habla en `Long` y `String`, y cada plataforma conserva **exactamente** su formato de
 * hoy: Android con `DateTimeFormatter`/`DateFormat`, iOS con `NSDateFormatter`.
 */

/** Hoy, en días desde 1970, en la zona del dispositivo. */
expect fun todayEpochDay(): Long

/** Milisegundos de época del teléfono. */
expect fun nowEpochMillis(): Long

/** El día calendario local del teléfono en [epochMillis], en ISO. Nunca UTC. */
expect fun localDayIso(epochMillis: Long): String

/** Un UUID v4 en minúsculas, con guiones. */
expect fun newUuid(): String

/** `yyyy-MM-dd` a días desde 1970. Lanza si no es una fecha válida. */
expect fun isoDateToEpochDay(iso: String): Long

/** "1 ene 2025", con el idioma de la composición. Una fecha de negocio: no depende de la zona. */
@Composable
expect fun formatDayMonthYear(epochDay: Long): String

/** "14:05" en la zona del dispositivo, con el idioma de la composición. */
@Composable
expect fun formatHourMinute(epochMillis: Long): String

/** El mismo día `months` meses antes; un día que no existe se ajusta al último del mes. */
fun epochDayMonthsBefore(epochDay: Long, months: Int): Long =
    LocalDate.fromEpochDays(epochDay.toInt()).minus(months, DateTimeUnit.MONTH).toEpochDays().toLong()

/** Hora corta en es-MX, fija: es lo que hace hoy el tablero. */
expect fun formatShortTimeEsMx(epochMillis: Long): String

/**
 * El "atrás" del sistema, donde exista.
 *
 * En Android es `BackHandler`; en iOS no hay botón ni gesto que interceptar, así que no hace nada a
 * propósito (ver el `actual`).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
