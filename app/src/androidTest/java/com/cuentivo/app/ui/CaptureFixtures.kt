package com.cuentivo.app.ui

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.CustomerCandidate
import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.LedgerDeterministicObjection
import com.cuentivo.app.domain.LedgerDeterministicObjections
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.dashboard.DashboardUiState
import java.time.LocalDate

/**
 * The fictional business every capture is rendered against.
 *
 * Shared by `StitchCaptureTest` (design review) and `PlayStoreCaptureTest` (store listing) so both
 * show the same shop. Two sets of fixtures would drift, and a store screenshot that disagrees with
 * the reviewed design is worse than no screenshot.
 *
 * None of this reaches the app: production activity, balances and customers all come from Room.
 * The names are invented small businesses — never a real person, never a third-party brand
 * (project rule 5). The amounts are in cents, like everywhere else.
 */
internal object CaptureFixtures {

    val CUSTOMERS = listOf(
        balance("ramirez", "Distribuidora Ramírez", 150_000),
        balance("todotexto", "Librería Todo Texto", 98_000),
        balance("martinez", "Minimarket Martínez", 75_000),
        balance("centro", "Farmacia del Centro", 24_000),
    )

    val PRODUCTS = listOf(
        product("p1", "Café molido 500 g", 12_500, "CAF-500", "Abarrotes"),
        product("p2", "Aceite vegetal 1 L", 4_800, "ACE-1L", "Abarrotes"),
    )

    val ALERTS = listOf(
        Alert(
            title = "Distribuidora Ramírez",
            detail = "Debe \$1,500.00 desde hace 15 días.",
            severity = Alert.Severity.OVERDUE,
            customerId = "ramirez",
        ),
        Alert(
            title = "Librería Todo Texto",
            detail = "Le toca pagar hoy: \$980.00.",
            severity = Alert.Severity.DUE_SOON,
            customerId = "todotexto",
        ),
        Alert(
            title = "Te queda 1 cliente",
            detail = "El plan gratis permite 5 clientes.",
            severity = Alert.Severity.INFO,
        ),
    )

    /**
     * The first name is long on purpose: the cart row has to prove that the amount still fits
     * beside it at 320 dp, and a catalog of tidy short names would never show that.
     */
    val SALE_PRODUCTS = listOf(
        product("s1", "Café molido premium de altura 500 g", 12_500, "CAF-500", "Abarrotes"),
        product("s2", "Aceite vegetal 1 L", 4_800, "ACE-1L", "Abarrotes"),
    )

    val STATEMENT = listOf(
        StatementEntry("2026-07-14", "CHARGE", "DEBIT", 150_000, "Pedido semanal"),
        StatementEntry("2026-07-21", "PAYMENT", "CREDIT", 25_000, null),
    )

    val CANCELABLES = listOf(
        CorrectionCandidate.Sale("s1", "Venta del 14 jul", 20_000, 150_000, 0, 150_000),
        CorrectionCandidate.Payment("p1", 25_000, 21_000, 1, "CASH", null, 1, 1, false),
    )

    fun balance(id: String, name: String, cents: Long) = CustomerBalanceRow(
        customer = CustomerEntity(
            id = id,
            businessId = "business-1",
            name = name,
            normalizedName = name.lowercase(),
            phone = "55 0000 0000",
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        ),
        balanceCents = cents,
    )

    fun product(id: String, name: String, cents: Long, sku: String, category: String) = ProductEntity(
        id = id,
        businessId = "business-1",
        name = name,
        description = null,
        sku = sku,
        category = category,
        salePriceCents = cents,
        costPriceCents = null,
        stock = null,
        imageUri = null,
        isActive = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        deletedAtEpochMillis = null,
    )

    fun populatedState(): DashboardUiState.Content {
        val today = LocalDate.now().toEpochDay()
        return DashboardUiState.Content(
            businessId = "business-1",
            businessName = "Mi negocio",
            receivableCents = 4_250_000,
            soldCents = 6_100_000,
            collectedCents = 1_850_000,
            overdueCents = 345_000,
            customersWithDebt = 4,
            dueTodayCents = 128_000,
            dueSales = listOf(
                DueSale("s1", "ramirez", "Distribuidora Ramírez", today - 15, 150_000),
                DueSale("s2", "todotexto", "Librería Todo Texto", today, 98_000),
                DueSale("s3", "martinez", "Minimarket Martínez", today, 75_000),
            ),
            recentActivity = listOf(
                activity("a1", "centro", "Farmacia del Centro", "PAYMENT", "CREDIT", 25_000, 1),
                activity("a2", "alpes", "Bodega Los Alpes", "CHARGE", "DEBIT", 42_000, 26),
                activity("a3", "martinez", "Minimarket Martínez", "PAYMENT", "CREDIT", 18_000, 30),
                activity("a4", "ramirez", "Distribuidora Ramírez", "REFUND", "DEBIT", 6_000, 40),
                activity("a5", "todotexto", "Librería Todo Texto", "CANCELLATION", "CREDIT", 9_000, 50),
            ),
        )
    }

    fun activity(
        id: String,
        customerId: String,
        customerName: String,
        type: String,
        direction: String,
        cents: Long,
        hoursAgo: Long,
    ) = DashboardActivity(
        id = id,
        customerId = customerId,
        customerName = customerName,
        type = type,
        direction = direction,
        amountCents = cents,
        occurredAtEpochMillis = System.currentTimeMillis() - hoursAgo * 3_600_000L,
    )

    // ---------------------------------------------------------------------------------------------
    // La libreta de la función nueva (importación por foto), también inventada.
    //
    // Nombres de persona inventados y montos redondos de tienda: NADA de una libreta real. Una
    // libreta de verdad lleva nombres, montos y teléfonos de clientes, y esto termina en la ficha de
    // Play, a la vista de cualquiera.
    // ---------------------------------------------------------------------------------------------

    /** Un día fijo, para que las capturas salgan iguales hoy y en un mes. */
    private val LEDGER_DAY = LocalDate.of(2026, 9, 1).toEpochDay()

    /**
     * Un renglón de la libreta inventada, **con fecha**.
     *
     * La fecha no es un adorno: sin ella cada renglón dice "Sin fecha en la libreta", que es correcto
     * pero le cuenta a quien mira la tienda justo lo contrario de lo que vendemos. La hoja trae la
     * fecha arriba y la app la aplica; esto es la libreta ya leída.
     */
    private fun ledgerRow(lineIndex: Int, name: String, cents: String, rawText: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        description = "Cuaderno",
        rawText = rawText,
        date = SafeDateCandidate(2026, 9, 1),
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    /**
     * Una página recién leída **sin dudas**: la foto ya es un montón de cuentas.
     *
     * Cuatro clientas a propósito, y no las mismas tres de la página con duda: una página limpia y
     * esa misma página ya resuelta acaban en el **mismo** estado visual (sin aviso, botón activo), así
     * que dos capturas distintas de esa historia salían idénticas byte por byte. Cuatro renglones
     * frente a tres las separa.
     */
    val LEDGER_PAGE_READ = listOf(
        ledgerRow(0, "Rosa", "25000", "1010 Cuaderno 250 Rosa"),
        ledgerRow(1, "Juana", "18000", "1011 Cuaderno 180 Juana"),
        ledgerRow(2, "Carmen", "14900", "1012 Cuaderno 149 Carmen"),
        ledgerRow(3, "Pedro", "32000", "1013 Cuaderno 320 Pedro"),
    )

    /**
     * La misma página, pero el renglón de Carmen sólo lleva su nombre **al margen, entre paréntesis**.
     *
     * Es el caso real que la app existe para atrapar: el modelo asigna a una clienta que ya existe
     * apoyándose en una nota del margen, y cargarle sin que una persona lo confirme es cargarle a
     * quien no era. Los tres montos y el nombre son inventados.
     */
    val LEDGER_PAGE_WITH_DOUBT = listOf(
        ledgerRow(0, "Rosa", "25000", "1010 Cuaderno 250 Rosa"),
        ledgerRow(1, "Juana", "18000", "1011 Cuaderno 180 Juana"),
        ledgerRow(2, "Carmen", "14900", "1012 Cuaderno 149 (Carmen)"),
    )

    /** La clienta que ya existe: el renglón dudoso se parece a ella. */
    val EXISTING_CARMEN = listOf(CustomerCandidate(id = "carmen-id", name = "Carmen"))

    fun ledgerReview(operations: List<LedgerOperation>): LedgerReview = LedgerReviewEngine.build(
        operations = operations,
        pageDateEpochDay = LEDGER_DAY,
        todayEpochDay = LEDGER_DAY,
    )

    fun ledgerObjections(operations: List<LedgerOperation>): List<LedgerDeterministicObjection> =
        LedgerDeterministicObjections.evaluate(operations, EXISTING_CARMEN, emptyList())
}
