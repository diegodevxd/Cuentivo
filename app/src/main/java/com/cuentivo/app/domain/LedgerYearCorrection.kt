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
     * El año que un renglón **debería** tener: el de la página si la hay; si no, el de hoy. `null`
     * sólo cuando no se conoce ninguno de los dos.
     *
     * Es la **única** definición de "el año correcto", y la usan las dos partes que tienen que estar
     * de acuerdo: el plan de "Cambiar todos" ([targetYear]) y el motivo de la objeción de fecha.
     * Antes sólo el plan usaba el respaldo de hoy, así que un renglón de 2023 en una hoja sin fecha
     * se comparaba contra 2026 para la tarjeta de año y contra nada para el motivo: salía como "hace
     * más de 6 meses" *y* contaba para "Cambiar todos", y las dos tarjetas aparecían juntas por los
     * mismos doce renglones (v47). "Confirmar todos" aceptaba entonces las fechas de 2023.
     */
    fun expectedYearOrNull(pageDateEpochDay: Long?, todayEpochDay: Long?): Int? =
        pageDateEpochDay
            ?.let { runCatching { LocalDate.ofEpochDay(it).year }.getOrNull() }
            ?: todayEpochDay?.let { runCatching { LocalDate.ofEpochDay(it).year }.getOrNull() }

    /**
     * El año de la página si la hay; si no, el de hoy. Es el año al que se corrige un renglón con
     * `IMPLAUSIBLE_DATE`: la hoja misma es la mejor referencia, y hoy es el respaldo cuando la
     * página no trae fecha.
     *
     * La fecha de página que llega aquí ya pasó el filtro de "no futura", así que no se revalida.
     */
    fun targetYear(pageDateEpochDay: Long?, todayEpochDay: Long): Int =
        expectedYearOrNull(pageDateEpochDay, todayEpochDay)
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
     * Sólo hay plan cuando **dos o más** renglones con la objeción de fecha traen un año distinto al
     * de la hoja: con uno solo, el diálogo del renglón ya ofrece "Cambiar a \<año\>", y una tarjeta
     * para un caso que se resuelve en un toque sería ruido.
     *
     * El tipo de objeción se filtra **aquí**, en el objeto puro, y no en la pantalla: "Cambiar todos"
     * corrige el año mal leído, y la única señal de eso es `IMPLAUSIBLE_DATE`. Un renglón con precio
     * sospechoso cuya fecha es reciente y plausible no tiene nada que corregir, y no puede entrar al
     * plan sólo porque el llamador le pasara su índice. La garantía no puede depender de que la
     * pantalla pre-filtre: el plan recibe las objeciones **tipadas** y decide.
     */
    fun plan(
        operations: List<LedgerOperation>,
        objections: List<LedgerDeterministicObjection>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
    ): LedgerYearBulkCorrection? {
        val targetYear = targetYear(pageDateEpochDay, todayEpochDay)
        val dateObjectionLineIndices = objections
            .filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }
            .map { it.lineIndex }
            .toSet()
        val candidates = operations.filter { op ->
            op.lineIndex in dateObjectionLineIndices &&
                op.date != null &&
                op.date.year != targetYear
        }
        if (candidates.size < 2) return null

        // El año que "dicen" los renglones: el más repetido. Con años mezclados gana el dominante;
        // el empate cae al más antiguo, que es el que la IA suele inventar de menos. Cuando los años
        // no son todos el mismo, el dominante ya no representa a todos los renglones: la tarjeta lo
        // sabe por `hasMixedReadYears` y deja de afirmar que el conteo entero "dice" ese año.
        val candidateYears = candidates.mapNotNull { it.date?.year }
        val readYear = candidateYears
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
            hasMixedReadYears = candidateYears.distinct().size > 1,
        )
    }

    /** La fecha corregida de un renglón, o `null` si el renglón no trae fecha que corregir. */
    fun correctedDateFor(op: LedgerOperation, targetYear: Int): SafeDateCandidate? =
        op.date?.let { correctedDate(it, targetYear) }
}

/**
 * Qué renglones corregir de golpe, y de qué año a qué año. Producto del [LedgerYearCorrection.plan].
 *
 * [hasMixedReadYears] es `true` cuando los renglones del plan no comparten un solo año leído. En ese
 * caso [readYear] es sólo el dominante y la tarjeta no puede decir que *todos* los renglones lo
 * dicen: el conteo incluiría a los minoritarios, que traen otro año.
 */
data class LedgerYearBulkCorrection(
    val readYear: Int,
    val targetYear: Int,
    val lineIndices: List<Int>,
    val hasMixedReadYears: Boolean = false,
)
