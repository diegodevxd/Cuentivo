package com.cuentivo.app.domain

/**
 * Los renglones cuya fecha es simplemente vieja, para confirmarlos de una vez (v46).
 *
 * El hueco que cierra: con la hoja real, "Cambiar todos" corrigió el año a 2026, pero las fechas
 * quedaron en marzo de 2026 —más de 180 días atrás— y la objeción `IMPLAUSIBLE_DATE` siguió en los
 * doce renglones con el mismo texto "revisa el año", que ya estaba bien. Para una libreta con deudas
 * de hace meses eso es normal, y confirmarlas una por una es doce diálogos por una razón que no es un
 * error.
 *
 * Igual que `LedgerYearCorrection`, el objeto es puro: decide **qué** renglones y no escribe nada. La
 * pantalla llama a `onConfirmObjection` por cada índice, y la confirmación en sí —con su huella de
 * fecha y tipo, que caduca si algo cambia— vive en [LedgerObjectionConfirmation].
 */
object LedgerOldDatesBulkCorrection {

    /** Con una sola fecha vieja, el diálogo del renglón ya la confirma; una tarjeta sería ruido. */
    const val MIN_ROWS = 2

    /**
     * Los índices de renglón cuya única razón de fecha es [LedgerImplausibleDateReason.TOO_OLD], o
     * vacío si hay menos de [MIN_ROWS].
     *
     * Filtra por motivo y no sólo por tipo: los renglones de año distinto tienen su propia tarjeta
     * ("Cambiar a \<año\>") y confirmarlos aquí los dejaría pasar sin corregir el año, que es lo
     * único que estaba mal.
     */
    fun lineIndices(objections: List<LedgerDeterministicObjection>): List<Int> {
        val old = objections
            .filter {
                it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE &&
                    it.dateReason == LedgerImplausibleDateReason.TOO_OLD
            }
            .map { it.lineIndex }
            .distinct()
            .sorted()
        return if (old.size >= MIN_ROWS) old else emptyList()
    }
}
