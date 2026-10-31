package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomerStatementCsvTest {

    @Test
    fun `empty statement is just the header`() {
        assertEquals("Fecha,Tipo,Cargo,Abono,Saldo,Notas", CustomerStatementCsv.build(emptyList()))
    }

    @Test
    fun `running balance follows debit minus credit in cents`() {
        val csv = CustomerStatementCsv.build(
            listOf(
                StatementEntry("2026-07-01", "CHARGE", "DEBIT", 15050, null),
                StatementEntry("2026-07-10", "PAYMENT", "CREDIT", 5000, null),
            ),
        )
        val lines = csv.split('\n')
        assertEquals("2026-07-01,Cargo,150.50,,150.50,", lines[1])
        assertEquals("2026-07-10,Abono,,50.00,100.50,", lines[2])
    }

    @Test
    fun `a refund can leave the balance negative and keeps the sign`() {
        val csv = CustomerStatementCsv.build(
            listOf(StatementEntry("2026-07-01", "PAYMENT", "CREDIT", 2500, null)),
        )
        assertEquals("2026-07-01,Abono,,25.00,-25.00,", csv.split('\n')[1])
    }

    @Test
    fun `notes with commas and quotes are escaped, not corrupted`() {
        val csv = CustomerStatementCsv.build(
            listOf(StatementEntry("2026-07-01", "ADJUSTMENT", "DEBIT", 100, """ajuste "julio", acordado""")),
        )
        assertEquals("""2026-07-01,Ajuste,1.00,,1.00,"ajuste ""julio"", acordado"""", csv.split('\n')[1])
    }

    @Test
    fun `unknown type is kept as-is instead of guessed`() {
        val csv = CustomerStatementCsv.build(
            listOf(StatementEntry("2026-07-01", "SOMETHING", "DEBIT", 100, null)),
        )
        assertEquals("2026-07-01,SOMETHING,1.00,,1.00,", csv.split('\n')[1])
    }

    @Test
    fun `non-positive amounts and unknown directions are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            CustomerStatementCsv.build(listOf(StatementEntry("2026-07-01", "CHARGE", "DEBIT", 0, null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomerStatementCsv.build(listOf(StatementEntry("2026-07-01", "CHARGE", "SIDEWAYS", 100, null)))
        }
    }
}
