package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión del hallazgo bloqueante de la v45: el detalle de la clienta mostraba el saldo viejo
 * después de registrar una venta o un abono.
 *
 * La prueba invoca el mismo método que `MainActivity.loadStatement` usa para fijar
 * `statementCustomer`. Comprueba que se consulta el ledger para el negocio y la clienta correctos,
 * y que el saldo devuelto reemplaza al de la fila capturada al navegar.
 */
class StatementCustomerRefreshTest {

    @Test
    fun relee_el_saldo_del_ledger_para_la_clienta_y_negocio_del_detalle() = runBlocking {
        val stale = row(balanceCents = 15_000)
        var requestedBusinessId: String? = null
        var requestedCustomerId: String? = null

        val refreshed = StatementCustomerRefresh.fromLedger(
            row = stale,
            businessId = "business-1",
            customerBalanceCents = { businessId, customerId ->
                requestedBusinessId = businessId
                requestedCustomerId = customerId
                22_500L
            },
        )

        assertEquals("business-1", requestedBusinessId)
        assertEquals("rosa", requestedCustomerId)
        assertEquals(22_500L, refreshed.balanceCents)
    }

    @Test
    fun el_saldo_se_reemplaza_por_el_del_libro_mayor() {
        // La fila capturada al navegar traía $150; el libro mayor ya dice $225 tras la venta de $75.
        val stale = row(balanceCents = 15_000)

        val refreshed = refresh(stale, ledgerBalanceCents = 22_500)

        assertEquals(22_500L, refreshed.balanceCents)
    }

    @Test
    fun el_resto_de_la_fila_se_conserva_intacto() {
        val stale = row(balanceCents = 15_000)

        val refreshed = refresh(stale, ledgerBalanceCents = 22_500)

        // Sólo el monto cambia: nombre, teléfono, límite y banderas siguen siendo los de la fila.
        assertEquals(stale.customer, refreshed.customer)
        assertEquals(stale.customer.name, refreshed.customer.name)
        assertEquals(stale.customer.creditLimitCents, refreshed.customer.creditLimitCents)
    }

    @Test
    fun un_abono_que_baja_el_saldo_tambien_se_refleja() {
        // Abono de $100 sobre $225: el detalle debe mostrar $125, no el $225 de la fila capturada.
        val stale = row(balanceCents = 22_500)

        val refreshed = refresh(stale, ledgerBalanceCents = 12_500)

        assertEquals(12_500L, refreshed.balanceCents)
    }

    @Test
    fun un_saldo_en_cero_o_a_favor_se_refleja_tal_cual() {
        val stale = row(balanceCents = 15_000)

        assertEquals(
            0L,
            refresh(stale, ledgerBalanceCents = 0L).balanceCents,
        )
        assertEquals(
            -20_000L,
            refresh(stale, ledgerBalanceCents = -20_000L).balanceCents,
        )
    }

    private fun row(balanceCents: Long) = CustomerBalanceRow(
        customer = CustomerEntity(
            id = "rosa",
            businessId = "business-1",
            name = "Rosa Martinez",
            normalizedName = "rosa martinez",
            phone = "5512345678",
            email = null,
            address = null,
            notes = null,
            creditLimitCents = 50_000,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        ),
        balanceCents = balanceCents,
    )

    private fun refresh(row: CustomerBalanceRow, ledgerBalanceCents: Long): CustomerBalanceRow = runBlocking {
        StatementCustomerRefresh.fromLedger(
            row = row,
            businessId = row.customer.businessId,
            customerBalanceCents = { _, _ -> ledgerBalanceCents },
        )
    }
}
