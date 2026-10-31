package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerDeterministicObjectionsTest {

    @Test
    fun `renglon_cuya_clienta_exacta_existe_pero_no_aparece_en_su_texto_objeta`() {
        val op = LedgerOperation(
            lineIndex = 0, proposedKind = ProposedLedgerKind.SALE, customerName = "Carmen",
            amountCents = "14900", rawText = "1011 Cuaderno 149 Rosa (Carmen)",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )
        val objections = LedgerDeterministicObjections.evaluate(
            listOf(op), listOf(CustomerCandidate("carmen-id", "Carmen")), emptyList(),
        )
        assertEquals(LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED, objections.single().type)
    }

    @Test
    fun `descripcion_que_coincide_exacto_con_el_producto_compara_el_precio`() {
        val op = LedgerOperation(
            lineIndex = 0, proposedKind = ProposedLedgerKind.SALE, customerName = "Rosa",
            amountCents = "25000", description = "Cuaderno",
        )
        val objections = LedgerDeterministicObjections.evaluate(
            listOf(op), emptyList(), listOf(ProductCandidate("p1", "Cuaderno", 2500L)),
        )
        assertEquals(LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT, objections.single().type)
    }

    @Test
    fun `decimal shift detected when operation amount is 10x or 0_1x of catalog price`() {
        // e.g. Catalog price $44.90 (4490 cents), operation amount $494.90 (49490 cents) -> ratio ~11.02, or $449.00 -> 10x
        val product = ProductCandidate(
            id = "prod-1",
            name = "Sani Asientos WC",
            salePriceCents = 4490L,
            sku = "WC-01",
        )
        val op10x = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Sintetico A",
            amountCents = "44900", // Exactly 10x
            description = "Sani Asientos WC",
            rawText = "Sani Asientos WC 449.00",
        )
        val op49490 = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Sintetico B",
            amountCents = "49490", // 494.90 vs 44.90 (ratio ~11.02)
            description = "Sani Asientos WC",
            rawText = "Sani Asientos WC 494.90",
        )

        val productMatches = mapOf(
            0 to EntityMatchResult(product, 1.0, MatchDecision.AUTOMATIC),
            1 to EntityMatchResult(product, 1.0, MatchDecision.AUTOMATIC),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(op10x, op49490),
            existingCustomerCandidates = emptyList(),
            catalogProducts = listOf(product),
        )

        val op0Obj = objections.filter { it.lineIndex == 0 }
        assertEquals(1, op0Obj.size)
        assertEquals(LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT, op0Obj[0].type)

        val op1Obj = objections.filter { it.lineIndex == 1 }
        assertEquals(1, op1Obj.size)
        // Ratio 49490 / 4490 = 11.022. If slightly over 11.0, it triggers PRICE_MISMATCH_SUSPECT (> 2.0)
        assertTrue(
            op1Obj[0].type == LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT ||
                    op1Obj[0].type == LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT
        )
    }

    @Test
    fun `decimal shift detected when operation amount is one tenth of catalog price`() {
        val product = ProductCandidate(
            id = "prod-2",
            name = "Garantia Especial",
            salePriceCents = 99900L, // $999.00
        )
        val opTenth = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Sintetico C",
            amountCents = "9991", // $99.91 (ratio 0.10)
            description = "Garantia Especial",
            rawText = "Garantia 99.91",
        )

        val productMatches = mapOf(
            0 to EntityMatchResult(product, 1.0, MatchDecision.AUTOMATIC)
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opTenth),
            existingCustomerCandidates = emptyList(),
            catalogProducts = listOf(product),
        )

        assertEquals(1, objections.size)
        assertEquals(LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT, objections[0].type)
    }

    @Test
    fun `price mismatch detected when price differs significantly but not 10x`() {
        val product = ProductCandidate(
            id = "prod-3",
            name = "Aceite Cocina",
            salePriceCents = 5000L, // $50.00
        )
        val opMismatch = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Sintetico D",
            amountCents = "15000", // $150.00 (3x > 2x)
            description = "Aceite Cocina",
            rawText = "Aceite Cocina 150",
        )

        val productMatches = mapOf(
            0 to EntityMatchResult(product, 1.0, MatchDecision.AUTOMATIC)
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opMismatch),
            existingCustomerCandidates = emptyList(),
            catalogProducts = listOf(product),
        )

        assertEquals(1, objections.size)
        assertEquals(LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT, objections[0].type)
    }

    @Test
    fun `customer confirmation required when matching existing customer but name not in rawText`() {
        // e.g. Customer exists: "Charly". Line assigns "Charly" but rawText says "〃 385.90"
        val opWithoutNameInRaw = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Charly",
            amountCents = "38590",
            rawText = "〃 385.90",
            sources = LedgerFieldSources(customer = LedgerFieldSource.INHERITED_PREVIOUS_ROW),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opWithoutNameInRaw),
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = emptyList(),
        )

        val custObjs = objections.filter { it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED }
        assertEquals(1, custObjs.size)
        assertEquals(0, custObjs[0].lineIndex)
    }

    @Test
    fun `customer confirmation NOT required when name is explicitly in rawText and not inherited`() {
        val opExplicit = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Charly",
            amountCents = "38590",
            rawText = "Charly 385.90",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opExplicit),
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = emptyList(),
        )

        val custObjs = objections.filter { it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED }
        assertTrue(custObjs.isEmpty())
    }

    @Test
    fun `customer confirmation required when marginal note in parentheses appears on the line`() {
        // Marginal note "(Charly)" stripped before checking -> Charly is NOT in rawText!
        val opMarginal = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Charly",
            amountCents = "14900",
            rawText = "22562 Bottle 149 Dana TS (Charly)",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opMarginal),
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = emptyList(),
        )

        val custObjs = objections.filter { it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED }
        assertEquals(1, custObjs.size)
        assertEquals(0, custObjs[0].lineIndex)
    }

    @Test
    fun `customer confirmation required when candidate name is substring of different word`() {
        // Candidate: "Dana", rawText: "Mariana 100" -> word token mismatch, objection raised
        val opSubstring = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Dana",
            amountCents = "10000",
            rawText = "Mariana 100",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opSubstring),
            existingCustomerCandidates = listOf(CustomerCandidate("dana-id", "Dana")),
            catalogProducts = emptyList(),
        )

        val custObjs = objections.filter { it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED }
        assertEquals(1, custObjs.size)
    }

    @Test
    fun `comilla inherited customer only objects if customer is EXISTING`() {
        val opNewCustInherited = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Nuevo",
            amountCents = "10000",
            rawText = "〃 100",
            sources = LedgerFieldSources(customer = LedgerFieldSource.INHERITED_PREVIOUS_ROW),
        )

        // "cliente nuevo" is NOT in existingCustomerNormalizedNames
        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opNewCustInherited),
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = emptyList(),
        )

        assertTrue(objections.isEmpty())
    }

    @Test
    fun `quantity adjusts expected price and unknown quantity is not checked`() {
        val product = ProductCandidate(
            id = "prod-4",
            name = "Refresco",
            salePriceCents = 2000L, // $20.00
        )
        // Single item with quantity 5, total $100.00 -> 10000 cents != 2000 cents, but quantity = 5
        val opMultiQty = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente E",
            amountCents = "10000",
            description = "Refresco",
            items = listOf(com.cuentivo.app.ai.ledger.LedgerLineItem(description = "Refresco", quantity = 5, subtotalCents = "10000")),
        )
        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opMultiQty),
            existingCustomerCandidates = emptyList(), catalogProducts = listOf(product),
        )

        assertTrue(objections.isEmpty())

        val mismatch = opMultiQty.copy(items = listOf(com.cuentivo.app.ai.ledger.LedgerLineItem("Refresco", 5, "100000")))
        assertEquals(LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT,
            LedgerDeterministicObjections.evaluate(listOf(mismatch), emptyList(), listOf(product)).single().type)
        val unknown = opMultiQty.copy(items = listOf(com.cuentivo.app.ai.ledger.LedgerLineItem("Refresco", null, "100000")))
        assertTrue(LedgerDeterministicObjections.evaluate(listOf(unknown), emptyList(), listOf(product)).isEmpty())
    }

    @Test
    fun `isObjectionConfirmed true clears deterministic objections for that operation`() {
        val product = ProductCandidate(
            id = "prod-5",
            name = "Refresco",
            salePriceCents = 2000L,
        )
        val opConfirmed = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Charly",
            amountCents = "20000", // 10x mismatch
            description = "Refresco",
            rawText = "〃 200", // comilla with existing customer
            sources = LedgerFieldSources(customer = LedgerFieldSource.INHERITED_PREVIOUS_ROW),
            isObjectionConfirmed = true,
        )
        val confirmed = LedgerReviewEdits.confirmObjection(listOf(opConfirmed), opConfirmed.lineIndex)

        val objections = LedgerDeterministicObjections.evaluate(
            operations = confirmed,
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = listOf(product),
        )

        assertTrue(objections.isEmpty())
    }

    @Test
    fun `anchored customer with non-blank customerId never triggers confirmation objection`() {
        val opAnchored = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerId = "existing-uuid-1", // Already confirmed by human
            customerName = "Charly",
            amountCents = "38590",
            rawText = "〃 385.90",
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opAnchored),
            existingCustomerCandidates = listOf(CustomerCandidate("charly-id", "Charly")),
            catalogProducts = emptyList(),
        )

        assertTrue(objections.isEmpty())
    }

    @Test
    fun `una_palabra_del_producto_no_prueba_que_el_renglon_nombre_a_la_clienta`() {
        // Clienta existente "Luz". El renglón sólo la nombra al margen, entre paréntesis, y la única
        // aparición de "Luz" en el texto es parte del nombre del PRODUCTO ("Foco Luz"). Antes esa
        // palabra satisfacía la comprobación y el cargo salía sin que nadie lo confirmara: dinero a
        // la persona equivocada.
        val opWithProductWord = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Luz",
            amountCents = "9900",
            description = "Foco Luz",
            rawText = "1020 Foco Luz 99 (Luz)",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opWithProductWord),
            existingCustomerCandidates = listOf(CustomerCandidate("luz-id", "Luz")),
            catalogProducts = emptyList(),
        )

        assertEquals(
            LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
            objections.single().type,
        )
    }

    @Test
    fun `cuando_el_papel_si_dice_el_nombre_no_hay_objecion`() {
        // El mismo nombre corto, pero esta vez el papel lo dice en el propio renglón. Aquí no hay
        // nada que confirmar y no se puede volver a inflar la duda.
        val opWithNameOnTheRow = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Luz",
            amountCents = "9900",
            description = "Foco",
            rawText = "Luz 99",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opWithNameOnTheRow),
            existingCustomerCandidates = listOf(CustomerCandidate("luz-id", "Luz")),
            catalogProducts = emptyList(),
        )

        assertTrue(objections.isEmpty())
    }

    @Test
    fun `maria_en_el_papel_contra_maria_garcia_sigue_objetando`() {
        // No se relaja a propósito. Cualquier regla que exima "María" por "María García" exime
        // también "Rosa" por "Rosa Martínez", que es otra persona. Los apodos no se intentan resolver.
        val opExpandedName = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "María García",
            amountCents = "18500",
            rawText = "María 185",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opExpandedName),
            existingCustomerCandidates = listOf(CustomerCandidate("maria-id", "María García")),
            catalogProducts = emptyList(),
        )

        assertEquals(
            LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
            objections.single().type,
        )
    }

    @Test
    fun `un_renglon_sin_rawText_asignado_a_una_clienta_existente_objeta`() {
        // v4 deja `rawText` opcional: `functions/src/ai/ledgerExtraction.ts` sólo lo manda cuando el
        // modelo lo tiene. Sin texto no hay forma de comprobar que la hoja nombre a esa clienta, así
        // que se objeta —el lado seguro— y por eso la página entera queda sin resolver. Es justo lo
        // que hacían fallar las pruebas de lote que sembraban clientes existentes sin `rawText`.
        val opWithoutRawText = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
            sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        )

        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(opWithoutRawText),
            existingCustomerCandidates = listOf(CustomerCandidate("rosa-id", "Rosa")),
            catalogProducts = emptyList(),
        )

        assertEquals(
            LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
            objections.single().type,
        )
        assertEquals(
            "y con eso la página queda sin resolver, que es lo que rechaza el registro",
            setOf(0),
            LedgerPageResolution.unresolvedLineIndices(listOf(opWithoutRawText), setOf(0)),
        )
    }

    // ---- Subtotales declarados por la hoja (bloque 3, punto 3) ----

    private fun saleRow(lineIndex: Int, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = cents,
        rawText = "Rosa $cents",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun paymentRow(lineIndex: Int, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.PAYMENT,
        customerName = "Rosa",
        amountCents = cents,
        rawText = "Rosa abono $cents",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun priorDebtRow(lineIndex: Int, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.PRIOR_DEBT,
        customerName = "Rosa",
        amountCents = cents,
        rawText = "Rosa debe $cents",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun totalsOf(
        operations: List<LedgerOperation>,
        totals: List<com.cuentivo.app.ai.ledger.LedgerPageTotal>,
    ) = LedgerDeterministicObjections.evaluate(operations, emptyList(), emptyList(), totals)

    @Test
    fun `un_subtotal_que_cuadra_exacto_no_objeta`() {
        // 25000 de venta menos 5000 de abono son 20000: la hoja dice 20000 y lo leído suma 20000.
        val operations = listOf(saleRow(0, "25000"), paymentRow(1, "5000"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 20_000L, afterLineIndex = 1))

        assertTrue(totalsOf(operations, totals).isEmpty())
    }

    @Test
    fun `un_subtotal_con_un_centavo_de_diferencia_objeta`() {
        // Sin tolerancia: un centavo es un desacuerdo, no un redondeo.
        val operations = listOf(saleRow(0, "25000"), paymentRow(1, "5000"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 20_001L, afterLineIndex = 1))

        val objections = totalsOf(operations, totals)

        assertEquals(
            "un centavo de diferencia marca el bloque entero, no un renglón suelto",
            setOf(0, 1),
            objections.map { it.lineIndex }.toSet(),
        )
        assertTrue(objections.all { it.type == LedgerDeterministicObjectionType.PAGE_TOTAL_MISMATCH })
    }

    @Test
    fun `el_saldo_anterior_cuenta_como_positivo`() {
        // La deuda anterior es un cargo: 10000 que ya debía menos 4000 que abonó son 6000.
        val operations = listOf(priorDebtRow(0, "10000"), paymentRow(1, "4000"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 6_000L, afterLineIndex = 1))

        assertTrue(totalsOf(operations, totals).isEmpty())
    }

    @Test
    fun `el_aviso_lleva_los_dos_numeros`() {
        // El aviso dice "la hoja dice X, lo leído suma Y": sin los dos números, confirmar es a ciegas.
        val operations = listOf(saleRow(0, "89500"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 20_000L, afterLineIndex = 0))

        val objection = totalsOf(operations, totals).single()

        assertEquals(20_000L, objection.declaredCents)
        assertEquals(89_500L, objection.expectedCents)
        assertEquals(
            "el nombre del subtotal viaja en el detalle, para saber cuál de todos no cuadró",
            "saldo",
            objection.detail,
        )
    }

    @Test
    fun `los_renglones_despues_del_bloque_no_se_marcan`() {
        // El bloque es hasta `afterLineIndex`: lo de abajo no tiene por qué quedar dudoso.
        val operations = listOf(saleRow(0, "10000"), saleRow(1, "10000"), saleRow(2, "10000"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 999_999L, afterLineIndex = 1))

        val marked = totalsOf(operations, totals).map { it.lineIndex }.distinct().sorted()

        assertEquals(listOf(0, 1), marked)
    }

    @Test
    fun `un_subtotal_que_no_cuadra_solo_anade_duda`() {
        // Lo que importa: no cambia ningún importe y deja el renglón sin resolver (duda), nunca
        // resuelto. El bloqueo es lo que hace que alguien lo mire.
        val operations = listOf(saleRow(0, "89500"))
        val totals = listOf(com.cuentivo.app.ai.ledger.LedgerPageTotal("saldo", 20_000L, afterLineIndex = 0))

        val objections = totalsOf(operations, totals)

        assertEquals("89500", operations.single().amountCents)
        assertEquals(setOf(0), LedgerPageResolution.unresolvedLineIndices(operations, objections.map { it.lineIndex }.toSet()))
    }

    // ---- Fechas imposibles (v44, encargo: años imposibles) ----

    private val hoy = java.time.LocalDate.of(2026, 9, 22)

    private fun candidate(day: java.time.LocalDate) = SafeDateCandidate(day.year, day.monthValue, day.dayOfMonth)

    private fun datedRow(lineIndex: Int, date: SafeDateCandidate?) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "Rosa 250",
        date = date,
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun fechaDe(
        operations: List<LedgerOperation>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long? = hoy.toEpochDay(),
    ) = LedgerDeterministicObjections.evaluate(
        operations = operations,
        existingCustomerCandidates = emptyList(),
        catalogProducts = emptyList(),
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
    ).filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }

    @Test
    fun `fecha_en_el_mismo_anio_que_la_pagina_y_reciente_no_objeta`() {
        val row = datedRow(0, SafeDateCandidate(2026, 9, 15))
        assertTrue(
            fechaDe(listOf(row), pageDateEpochDay = java.time.LocalDate.of(2026, 9, 10).toEpochDay()).isEmpty(),
        )
    }

    @Test
    fun `fecha_en_otro_anio_que_la_pagina_objeta`() {
        // Aislado del criterio de antigüedad: el renglón tiene 15 días, no puede objetarse por
        // edad; sólo el año difiere (2025 vs 2026).
        val hoyEnero = java.time.LocalDate.of(2026, 1, 15)
        val row = datedRow(0, SafeDateCandidate(2025, 12, 31))
        val objections = LedgerDeterministicObjections.evaluate(
            operations = listOf(row),
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = java.time.LocalDate.of(2026, 1, 10).toEpochDay(),
            todayEpochDay = hoyEnero.toEpochDay(),
        ).filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }

        assertEquals("el año del renglón (2025) difiere del año de la página (2026)", 1, objections.size)
        assertEquals(0, objections.single().lineIndex)
    }

    @Test
    fun `el_caso_de_diego_fecha_2023_en_una_hoja_de_2026_objeta`() {
        val row = datedRow(0, SafeDateCandidate(2023, 3, 1))
        val objections = fechaDe(
            listOf(row),
            pageDateEpochDay = java.time.LocalDate.of(2026, 9, 10).toEpochDay(),
        )

        assertEquals(1, objections.size)
        assertEquals(LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE, objections.single().type)
        assertEquals(0, objections.single().lineIndex)
    }

    @Test
    fun `fecha_181_dias_anterior_a_hoy_objeta_y_180_no`() {
        val page = java.time.LocalDate.of(hoy.year, 1, 1).toEpochDay()

        val hace181 = datedRow(0, candidate(hoy.minusDays(181)))
        assertEquals(
            "181 días es 'más de 180': objeta",
            1,
            fechaDe(listOf(hace181), pageDateEpochDay = page).size,
        )

        val hace180 = datedRow(1, candidate(hoy.minusDays(180)))
        assertTrue(
            "180 días exactos NO es 'más de 180': no objeta (límite)",
            fechaDe(listOf(hace180), pageDateEpochDay = page).isEmpty(),
        )
    }

    @Test
    fun `fecha_futura_objeta_y_la_de_hoy_no`() {
        val page = hoy.toEpochDay()

        val manana = datedRow(0, candidate(hoy.plusDays(1)))
        assertEquals(1, fechaDe(listOf(manana), pageDateEpochDay = page).size)

        val hoyMismo = datedRow(1, candidate(hoy))
        assertTrue(
            "la fecha de hoy no es futura (límite: > hoy, no >= hoy)",
            fechaDe(listOf(hoyMismo), pageDateEpochDay = page).isEmpty(),
        )
    }

    @Test
    fun `sin_fecha_de_pagina_el_anio_no_se_compara_pero_la_edad_si`() {
        val vieja = datedRow(0, SafeDateCandidate(2023, 3, 1))
        assertEquals("el criterio de edad no depende de la página", 1, fechaDe(listOf(vieja), pageDateEpochDay = null).size)

        val reciente = datedRow(1, SafeDateCandidate(2026, 9, 15))
        assertTrue(fechaDe(listOf(reciente), pageDateEpochDay = null).isEmpty())
    }

    @Test
    fun `sin_hoy_no_se_juzga_la_edad_pero_el_anio_si`() {
        // todayEpochDay null: sin punto de comparación no hay antigüedad ni futuridad, pero el
        // año del renglón todavía se puede comparar con el de la página.
        val mismaPagina = datedRow(0, SafeDateCandidate(2023, 3, 1))
        assertTrue(
            "sin hoy no se puede medir la edad",
            LedgerDeterministicObjections.evaluate(
                operations = listOf(mismaPagina),
                existingCustomerCandidates = emptyList(),
                catalogProducts = emptyList(),
                pageDateEpochDay = java.time.LocalDate.of(2023, 3, 16).toEpochDay(),
                todayEpochDay = null,
            ).isEmpty(),
        )

        val otroAnio = datedRow(1, SafeDateCandidate(2023, 3, 1))
        assertEquals(
            "el año se compara con la página aunque no se conozca hoy",
            1,
            LedgerDeterministicObjections.evaluate(
                operations = listOf(otroAnio),
                existingCustomerCandidates = emptyList(),
                catalogProducts = emptyList(),
                pageDateEpochDay = java.time.LocalDate.of(2026, 9, 10).toEpochDay(),
                todayEpochDay = null,
            ).count { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE },
        )
    }

    @Test
    fun `renglon_sin_fecha_no_se_juzga_como_fecha_imposible`() {
        val sinFecha = datedRow(0, null)
        assertTrue(fechaDe(listOf(sinFecha), pageDateEpochDay = hoy.toEpochDay()).isEmpty())
    }

    @Test
    fun `renglon_de_fecha_imposible_confirmado_deja_de_objetar`() {
        val row = datedRow(0, SafeDateCandidate(2023, 3, 1))
        assertEquals(1, fechaDe(listOf(row), pageDateEpochDay = java.time.LocalDate.of(2026, 9, 10).toEpochDay()).size)

        val confirmed = LedgerReviewEdits.confirmObjection(listOf(row), 0)
        assertTrue(
            "confirmar la objeción la levanta: la libreta vieja de verdad sigue siendo registrable",
            fechaDe(confirmed, pageDateEpochDay = java.time.LocalDate.of(2026, 9, 10).toEpochDay()).isEmpty(),
        )
    }
}
