package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerBalanceRow

/**
 * La fila de clienta que el detalle debe mostrar, con el saldo releído del libro mayor (v45).
 *
 * El defecto que cierra: tras registrar una venta o un abono, el host abre el detalle llamando a
 * `loadStatement` con la fila que tenía capturada **antes** de la escritura. Esa fila trae el
 * `balanceCents` viejo, así que el detalle mostraba "Te debe $150" después de una venta de $75 sobre
 * $150, y "Te debe $225" después de un abono de $100. Salir y volver a entrar sí mostraba el saldo
 * correcto, porque entonces la fila venía de una consulta nueva.
 *
 * La regla de `CLAUDE.md` es explícita: **el saldo se deriva, nunca de un valor viejo**. Aquí se
 * conserva todo lo descriptivo de la fila (nombre, teléfono, límite de crédito) y sólo se sustituye
 * el monto por el que devuelve el libro mayor. La decisión vive en un `object` puro —sin `Context` ni
 * Room— para que una prueba JVM la invoque de verdad y falle si el saldo dejara de releerse.
 */
object StatementCustomerRefresh {

    /**
     * Lee el saldo actual para la clienta de [row] y devuelve la fila apta para el detalle.
     *
     * Mantener esta lectura aquí hace explícito que el detalle no puede reutilizar el monto de la
     * fila de navegación. [customerBalanceCents] es el seam que producción conecta con
     * `FinancialDao.customerBalanceCents` y las pruebas JVM con una lectura controlada.
     */
    suspend fun fromLedger(
        row: CustomerBalanceRow,
        businessId: String,
        customerBalanceCents: suspend (businessId: String, customerId: String) -> Long,
    ): CustomerBalanceRow {
        val currentBalance = customerBalanceCents(businessId, row.customer.id)
        return row.copy(balanceCents = currentBalance)
    }
}
