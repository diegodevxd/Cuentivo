package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate

/**
 * El año que la hoja debía tener, y las fechas de los renglones con ese año puesto.
 *
 * Nace del recorrido "Doña Mary" (v44): la IA leyó 2023 en los doce renglones de una hoja
 * fotografiada en 2026. La pantalla mostraba la objeción de fecha imposible, pero el botón grande
 * era "Sí, es correcto", así que con prisa se aceptaba el año equivocado en todo. Y corregirlo a
 * mano era renglón por renglón con formato `AAAA-MM-DD`.
 *
 * Este objeto es puro y no conoce Android ni la pantalla: decide el año correcto, arma la fecha con
 * el **mismo mes y día** y el año arreglado, y dice **sólo** qué renglones con objeción hay que
 * tocar. La pantalla se limita a llamarlo y a pasar cada fecha por `LedgerReviewEdits.applyDate`.
 */
object LedgerYearCorrection {

    /**
     * El año de la página si la hay; si no, el de hoy. Es el año al que se corrige un renglón con
     * `IMPLAUSIBLE_DATE`: la hoja misma es la mejor referencia, y hoy es el respaldo cuando la
     * página no trae fecha.
     *
     * La fecha de página que llega aquí ya pasó el filtro de "no futura", así que no se revalida.
     */
    fun targetYear(pageDateEpochDay: Long?, todayEpochDay: Long): Int =
        pageDateEpochDay
            ?.let { runCatching { LocalDate.ofEpochDay(it).year }.getOrNull() }
            ?: LocalDate.ofEpochDay(todayEpochDay).year

    /**
     * La misma fecha con el año corregido.
     *
     * Si el mes/día no existe en ese año —el 29 de febrero es el único caso real— cae al **último
     * día del mes**. Descartar el renglón o dejar la fecha a medias sería peor: el 29 de febrero
     * leído en un año bisiesto es una fecha de verdad que merece sobrevivir a la corrección.
     */
    fun correctedDate(date: SafeDateCandidate, targetYear: Int): SafeDateCandidate {
        val month = date.month.coerceIn(1, 12)
        val day = runCatching {
            LocalDate.of(targetYear, month, date.day)
        }.getOrNull()?.dayOfMonth
            ?: LocalDate.of(targetYear, month, 1).lengthOfMonth()
        return SafeDateCandidate(year = targetYear, month = month, day = day)
    }

    /**
     * El plan de corrección masiva, o `null` si la hoja no lo pide.
     *
     * Sólo hay plan cuando **dos o más** renglones con objeción traen un año distinto al de la hoja:
     * con uno solo, el diálogo del renglón ya ofrece "Cambiar a \<año\>", y una tarjeta para un caso
     * que se resuelve en un toque sería ruido.
     *
     * `objectionLineIndices` son los renglones con `IMPLAUSIBLE_DATE`. La ausencia de un renglón en
     * esa lista es exactamente lo que garantiza que **no se toque un renglón sin objeción**, por muy
     * viejo que parezca su año.
     */
    fun plan(
        operations: List<LedgerOperation>,
        objectionLineIndices: Set<Int>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
    ): LedgerYearBulkCorrection? {
        val targetYear = targetYear(pageDateEpochDay, todayEpochDay)
        val candidates = operations.filter { op ->
            op.lineIndex in objectionLineIndices &&
                op.date != null &&
                op.date.year != targetYear
        }
        if (candidates.size < 2) return null

        // El año que "dicen" los renglones: el más repetido. Con años mezclados gana el dominante;
        // el empate cae al más antiguo, que es el que la IA suele inventar de menos.
        val readYear = candidates
            .mapNotNull { it.date?.year }
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWithOrNull(compareBy({ it.value }, { -it.key }))
            ?.key
            ?: return null

        return LedgerYearBulkCorrection(
            readYear = readYear,
            targetYear = targetYear,
            lineIndices = candidates.map { it.lineIndex },
        )
    }

    /** La fecha corregida de un renglón, o `null` si el renglón no trae fecha que corregir. */
    fun correctedDateFor(op: LedgerOperation, targetYear: Int): SafeDateCandidate? =
        op.date?.let { correctedDate(it, targetYear) }
}

/**
 * Qué renglones corregir de golpe, y de qué año a qué año. Producto del [LedgerYearCorrection.plan].
 */
data class LedgerYearBulkCorrection(
    val readYear: Int,
    val targetYear: Int,
    val lineIndices: List<Int>,
)
