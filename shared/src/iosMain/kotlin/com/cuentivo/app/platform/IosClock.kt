package com.cuentivo.app.platform

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * El reloj de iOS.
 *
 * Vive en `iosMain` a propósito: `commonMain` no puede nombrar ningún reloj porque Android compila
 * contra `kotlinx-datetime` 0.6.2 e iOS contra 0.7.1, y el `Instant` que devuelve cada uno es otro
 * tipo. Aquí no hay conflicto: la 0.7.1 ya expone `kotlin.time.Instant.toLocalDateTime`.
 */
@OptIn(ExperimentalTime::class)
internal fun iosNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** La hora local del teléfono, para quien construya el `PlannedReminderScheduler` en iOS. */
@OptIn(ExperimentalTime::class)
fun iosNowLocalDateTime(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
