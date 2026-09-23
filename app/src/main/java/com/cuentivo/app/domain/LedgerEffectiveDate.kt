package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.SafeDateCandidate

/**
 * La fecha **efectiva** de un renglón, y la ventana de consulta que sale de ella (v44).
 *
 * El defecto que cierra: el detector de duplicados compara contra la fecha efectiva de cada renglón
 * —la del renglón, o la de la página si el renglón no trae—, pero la consulta a Room
 * (`loadRegisteredAround`) usaba **sólo** las fechas de renglón y, si no había ninguna, hoy ±14. Una
 * página de 2023 sin fechas por renglón consultaba 2026, no encontraba nada, y el detector no tenía
 * contra qué comparar: el duplicado pasaba sin frenarse.
 *
 * Aquí vive la única definición de "fecha efectiva" y de "ventana de consulta", y la usan **los dos**
 * (el detector y la consulta), para que no puedan discrepar. Puro: cero Android, cero Room, cero red.
 */
object LedgerEffectiveDate {

    /**
     * La fecha efectiva de [op]: la del renglón si la trae y es utilizable, o la de la página si no.
     *
     * Devuelve null cuando no hay ninguna utilizable. Una fecha futura respecto a [todayEpochDay] no
     * cuenta: no es una fecha de libreta. Mismo criterio que el detector, que es el punto.
     */
    fun of(
        op: LedgerOperation,
        pageDateEpochDay: Long?,
        todayEpochDay: Long?,
    ): Long? {
        val rowEpoch = op.date?.let { candidate ->
            runCatching { java.time.LocalDate.of(candidate.year, candidate.month, candidate.day).toEpochDay() }.getOrNull()
        }
        if (rowEpoch != null && rowEpoch > 0 && (todayEpochDay == null || rowEpoch <= todayEpochDay)) {
            return rowEpoch
        }
        if (pageDateEpochDay != null && pageDateEpochDay > 0 && (todayEpochDay == null || pageDateEpochDay <= todayEpochDay)) {
            return pageDateEpochDay
        }
        return null
    }

    /**
     * La ventana `[min, max]` que hay que consultar para cubrir todos los renglones de la página.
     *
     * Se toma la fecha efectiva de cada renglón (renglón o página) y se ensancha por [windowDays] a
     * cada lado. Si **ningún** renglón tiene fecha efectiva, cae a `today ± windowDays`, que es la
     * única fecha que se conoce en ese caso — pero sólo entonces, no antes de mirar la página.
     */
    fun queryWindow(
        operations: List<LedgerOperation>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
        windowDays: Long,
    ): Pair<Long, Long> {
        val effectiveDays = operations.mapNotNull { of(it, pageDateEpochDay, todayEpochDay) }
        val min = (effectiveDays.minOrNull() ?: todayEpochDay) - windowDays
        val max = (effectiveDays.maxOrNull() ?: todayEpochDay) + windowDays
        return min to max
    }
}
