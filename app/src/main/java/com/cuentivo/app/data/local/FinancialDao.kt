package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cuentivo.app.domain.AuditAction
import com.cuentivo.app.domain.AuditTrail
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.PaymentAllocator
import com.cuentivo.app.domain.PaymentOperationIds

sealed interface ExpectedBalancePaymentResult {
    data class Recorded(val paymentId: String) : ExpectedBalancePaymentResult
    data object StaleBalance : ExpectedBalancePaymentResult
}

sealed interface ExpectedLedgerBatchResult {
    data class Recorded(val batchIdempotencyKey: String, val executableOperationsCount: Int) : ExpectedLedgerBatchResult
    data class AlreadyRecorded(val batchIdempotencyKey: String, val executableOperationsCount: Int) : ExpectedLedgerBatchResult
    data object StaleData : ExpectedLedgerBatchResult
}

data class StoredLedgerBatchBundle(
    val batchIdempotencyKey: String,
    val businessId: String,
    val createdCustomers: List<CustomerEntity>,
    val sales: List<SaleEntity>,
    val saleItems: List<SaleItemEntity>,
    val chargeMovements: List<AccountMovementEntity>,
    val payments: List<PaymentEntity>,
    val paymentAllocations: List<PaymentAllocationEntity>,
    val paymentMovements: List<AccountMovementEntity>,
)

data class StoredPaymentBundle(
    val payment: PaymentEntity,
    val allocations: List<PaymentAllocationEntity>,
    val movement: AccountMovementEntity,
)

data class StoredSaleBundle(
    val sale: SaleEntity,
    val items: List<SaleItemEntity>,
    val chargeMovement: AccountMovementEntity,
    val paymentBundle: StoredPaymentBundle?,
    val createdCustomer: CustomerEntity? = null,
    val createdProducts: List<ProductEntity> = emptyList(),
)

data class RegisteredOperationEntityRow(
    val id: String,
    val customerId: String,
    val customerName: String,
    val amountCents: Long,
    val effectiveDateEpochDay: Long,
    val kindName: String,
    val description: String?,
)

sealed interface ExpectedSaleResult {
    data class Recorded(val saleId: String, val paymentId: String?) : ExpectedSaleResult
    data object StaleData : ExpectedSaleResult
}

/**
 * A sale with money still owed on it. Mirrors domain.OutstandingSale, which PaymentAllocator takes;
 * kept separate so the domain layer stays free of Room.
 */
data class OutstandingSaleRow(
    val id: String,
    val outstandingCents: Long,
    val dueDateEpochDay: Long?,
    val saleDateEpochDay: Long,
)

/** A dated sale that still owes money, with whose sale it is. Feeds the alerts screen. */
data class DueSaleRow(
    val saleId: String,
    val customerId: String,
    val customerName: String,
    val dueDateEpochDay: Long,
    val outstandingCents: Long,
)

/**
 * One non-canceled sale as the payment score reads it: what it was worth, what has actually been
 * paid against it, and when the newest of those payments landed.
 *
 * Sales with no agreed date are included on purpose even though they cannot be scored — leaving
 * that judgement to [com.cuentivo.app.domain.CustomerScoreCalculator] keeps the rule about what
 * counts as evidence in one place, where it is tested on the JVM.
 */
data class CustomerSaleHistoryRow(
    val customerId: String,
    val dueDateEpochDay: Long?,
    val totalCents: Long,
    val allocatedCents: Long,
    val lastPaymentDateEpochDay: Long?,
)

/** An immutable ledger entry with the local customer's name for the dashboard feed. */
data class DashboardActivityRow(
    val id: String,
    val customerId: String,
    val customerName: String,
    val type: String,
    val direction: String,
    val amountCents: Long,
    val occurredAtEpochMillis: Long,
    /**
     * True when this charge is really the debt the customer already had before the app (v45).
     *
     * Inicio used to call it "Nueva venta" while the customer's own history called the same movement
     * "Deuda anterior": the same ledger row read two different ways on two screens. The signal is the
     * same one `priorDebtSaleIdsForCustomer` uses — the persisted marker, or the uncataloged line
     * named "Saldo anterior" for rows written before the marker existed.
     */
    val isPriorDebt: Boolean = false,
)

/** A sale as offered for correction on screen: what it was worth and what is still unpaid. */
data class CancelableSaleRow(
    val id: String,
    val saleNumber: String,
    val saleDateEpochDay: Long,
    val totalCents: Long,
    val outstandingCents: Long,
)

/** One sold line of one sale, as the statement reads it. */
data class SoldLineRow(
    val saleId: String,
    val productNameSnapshot: String,
    val quantity: Long,
)

/** A payment as offered for correction, including whether its allocation touches a canceled sale. */
data class CancelablePaymentRow(
    val id: String,
    val businessId: String,
    val customerId: String,
    val amountCents: Long,
    val paymentMethod: String,
    val paymentDateEpochDay: Long,
    val reference: String?,
    val createdAtEpochMillis: Long,
    val blockedByCanceledSale: Boolean,
)

@Dao
interface FinancialDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserveOperation(operation: OperationEntity): Long

    @Query("SELECT resultId FROM operations WHERE idempotencyKey = :key")
    suspend fun operationResultId(key: String): String?

    /**
     * IGNORE, not ABORT: the audit id is derived from the idempotency key, so a replayed operation
     * must find its event already there and carry on rather than blowing up the transaction.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuditEvent(event: AuditLogEntity)

    /**
     * Records who did what, inside the caller's transaction.
     *
     * Being in the same transaction as the financial write is the whole point: an audit row that
     * can be lost independently of the movement it describes is not an audit trail.
     */
    suspend fun recordAudit(
        operation: OperationEntity,
        action: AuditAction,
        businessId: String,
        entityType: String,
        entityId: String,
        userId: String,
        timestampEpochMillis: Long,
    ) {
        insertAuditEvent(
            AuditLogEntity(
                id = AuditTrail.eventId(operation.idempotencyKey),
                businessId = businessId,
                entityType = entityType,
                entityId = entityId,
                action = action.code,
                userId = userId,
                timestampEpochMillis = timestampEpochMillis,
                summary = action.summary,
            )
        )
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleItems(items: List<SaleItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: PaymentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllocations(allocations: List<PaymentAllocationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovement(movement: AccountMovementEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProducts(products: List<ProductEntity>)

    // Remote adoption uses IGNORE, not ABORT: a document that already exists locally is the normal
    // reconvergence case, not an error. Nothing here can overwrite an existing financial row, which
    // is what keeps the ledger append-only even when the write originates on another device.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptSaleItem(item: SaleItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptPayment(payment: PaymentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptAllocation(allocation: PaymentAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptMovement(movement: AccountMovementEntity)

    // ---- Outbox backfill ----
    // Everything a business owns, unfiltered, so an account upgrading to Pro publishes its whole
    // ledger rather than the part that happens to be active. Sale items and allocations have no
    // businessId of their own — they hang off their parent — so those two join to reach it.

    @Query("SELECT * FROM sales WHERE businessId = :businessId")
    suspend fun allSalesForBackfill(businessId: String): List<SaleEntity>

    @Query("SELECT i.* FROM sale_items i JOIN sales s ON s.id = i.saleId WHERE s.businessId = :businessId")
    suspend fun allSaleItemsForBackfill(businessId: String): List<SaleItemEntity>

    @Query("SELECT * FROM payments WHERE businessId = :businessId")
    suspend fun allPaymentsForBackfill(businessId: String): List<PaymentEntity>

    @Query("SELECT a.* FROM payment_allocations a JOIN payments p ON p.id = a.paymentId WHERE p.businessId = :businessId")
    suspend fun allAllocationsForBackfill(businessId: String): List<PaymentAllocationEntity>

    @Query("SELECT * FROM account_movements WHERE businessId = :businessId")
    suspend fun allMovementsForBackfill(businessId: String): List<AccountMovementEntity>

    @Query("SELECT * FROM sale_items WHERE id = :itemId")
    suspend fun saleItemForId(itemId: String): SaleItemEntity?

    @Query("SELECT * FROM payment_allocations WHERE id = :allocationId")
    suspend fun allocationForId(allocationId: String): PaymentAllocationEntity?

    @Query("SELECT * FROM account_movements WHERE id = :movementId")
    suspend fun movementForId(movementId: String): AccountMovementEntity?

    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END), 0) FROM account_movements WHERE businessId = :businessId AND customerId = :customerId")
    suspend fun customerBalanceCents(businessId: String, customerId: String): Long

    @Query("SELECT * FROM account_movements WHERE businessId = :businessId AND customerId = :customerId ORDER BY occurredAtEpochMillis DESC")
    suspend fun customerMovements(businessId: String, customerId: String): List<AccountMovementEntity>

    /**
     * Every line this customer was ever sold, for the whole statement in one read.
     *
     * Joined through `sales` rather than queried per movement: a statement with fifty charges would
     * otherwise be fifty round trips on a phone that is already scrolling.
     */
    @Query(
        "SELECT item.saleId AS saleId, item.productNameSnapshot AS productNameSnapshot, " +
            "item.quantity AS quantity FROM sale_items AS item " +
            "INNER JOIN sales AS sale ON sale.id = item.saleId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId " +
            "ORDER BY item.saleId, item.id"
    )
    suspend fun soldLinesForCustomer(businessId: String, customerId: String): List<SoldLineRow>

    /**
     * The allocations still backing a sale. A canceled payment is excluded because its money went
     * back to the customer, so a correction must not carry it onto the replacement.
     */
    @Query(
        "SELECT allocation.* FROM payment_allocations AS allocation " +
            "INNER JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE allocation.saleId = :saleId AND payment.canceledAtEpochMillis IS NULL " +
            "ORDER BY allocation.id"
    )
    suspend fun activeAllocationsForSale(saleId: String): List<PaymentAllocationEntity>

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId ORDER BY id")
    suspend fun saleItemsForSale(saleId: String): List<SaleItemEntity>

    @Query(
        "SELECT movement.id AS id, movement.customerId AS customerId, " +
            "customer.name AS customerName, movement.type AS type, " +
            "movement.direction AS direction, movement.amountCents AS amountCents, " +
            "movement.occurredAtEpochMillis AS occurredAtEpochMillis, " +
            // La deuda anterior se marca con las mismas dos señales que el historial del detalle:
            // el marcador persistido en `sales.notes`, o —para filas escritas antes del marcador—
            // el renglón sin producto llamado "Saldo anterior". Sin esto, Inicio llamaba "Nueva
            // venta" a la misma fila que el detalle llama "Deuda anterior" (v45).
            "CASE WHEN movement.saleId IS NOT NULL AND EXISTS (" +
            "SELECT 1 FROM sales AS s WHERE s.id = movement.saleId " +
            "AND (s.notes = 'ALTA_CLIENTE_SALDO_ANTERIOR' " +
            "OR EXISTS (SELECT 1 FROM sale_items AS i WHERE i.saleId = s.id " +
            "AND i.productId IS NULL AND i.productNameSnapshot = 'Saldo anterior'))) " +
            "THEN 1 ELSE 0 END AS isPriorDebt " +
            "FROM account_movements AS movement " +
            "INNER JOIN customers AS customer ON customer.id = movement.customerId " +
            "AND customer.businessId = movement.businessId " +
            "WHERE movement.businessId = :businessId " +
            "ORDER BY movement.occurredAtEpochMillis DESC, movement.id DESC LIMIT :limit"
    )
    suspend fun recentDashboardActivity(
        businessId: String,
        limit: Int,
    ): List<DashboardActivityRow>

    @Query("SELECT COALESCE(SUM(CASE WHEN balanceCents > 0 THEN balanceCents ELSE 0 END), 0) FROM (SELECT customerId, SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END) AS balanceCents FROM account_movements WHERE businessId = :businessId GROUP BY customerId)")
    suspend fun businessReceivableCents(businessId: String): Long

    @Query("SELECT COUNT(*) FROM (SELECT customerId FROM account_movements WHERE businessId = :businessId GROUP BY customerId HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END) > 0)")
    suspend fun customersWithDebtCount(businessId: String): Long

    /**
     * "Vendido" de un periodo. **Sin las deudas anteriores**: una deuda que ya existía antes de la
     * app no es una venta del mes (el recorrido "Doña Mary" vio $250 donde había $100 de ventas y
     * $150 previos). Sigue contando en "por cobrar", que sale de `businessReceivableCents`, no de
     * aquí (v44).
     *
     * Se excluye por dos señales: el marcador de `notes` que ahora escribe
     * [com.cuentivo.app.domain.OpeningBalance], y —para las filas ya guardadas antes de este cambio,
     * que nadie puede editar— el renglón sin producto llamado "Saldo anterior".
     */
    @Query(
        "SELECT COALESCE(SUM(s.totalCents), 0) FROM sales AS s " +
            "WHERE s.businessId = :businessId AND s.isCanceled = 0 " +
            "AND s.saleDateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "AND (s.notes IS NULL OR s.notes <> 'ALTA_CLIENTE_SALDO_ANTERIOR') " +
            "AND NOT EXISTS (SELECT 1 FROM sale_items AS i WHERE i.saleId = s.id AND i.productId IS NULL AND i.productNameSnapshot = 'Saldo anterior')"
    )
    suspend fun salesTotalCents(businessId: String, startEpochDay: Long, endEpochDay: Long): Long

    /**
     * The sales that are really a debt carried over from before the app, for one customer.
     *
     * Used by the statement so a prior debt reads as "Deuda anterior" and not as a sale nobody made.
     * Same two signals as [salesTotalCents]: the persisted marker, and — for rows written before the
     * marker existed and that nobody can edit — the uncataloged line named "Saldo anterior".
     */
    @Query(
        "SELECT s.id FROM sales AS s " +
            "WHERE s.businessId = :businessId AND s.customerId = :customerId " +
            "AND (s.notes = 'ALTA_CLIENTE_SALDO_ANTERIOR' " +
            "OR EXISTS (SELECT 1 FROM sale_items AS i WHERE i.saleId = s.id AND i.productId IS NULL AND i.productNameSnapshot = 'Saldo anterior'))"
    )
    suspend fun priorDebtSaleIdsForCustomer(businessId: String, customerId: String): List<String>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM payments WHERE businessId = :businessId AND canceledAtEpochMillis IS NULL AND paymentDateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun collectedTotalCents(businessId: String, startEpochDay: Long, endEpochDay: Long): Long

    @Query("SELECT COALESCE(SUM(CASE WHEN outstandingCents > 0 THEN outstandingCents ELSE 0 END), 0) FROM (SELECT sale.id, sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents FROM sales AS sale LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id LEFT JOIN payments AS payment ON payment.id = allocation.paymentId WHERE sale.businessId = :businessId AND sale.isCanceled = 0 AND sale.dueDateEpochDay IS NOT NULL AND sale.dueDateEpochDay < :todayEpochDay GROUP BY sale.id)")
    suspend fun overdueBalanceCents(businessId: String, todayEpochDay: Long): Long

    @Query("SELECT * FROM sales WHERE id = :saleId")
    suspend fun saleForId(saleId: String): SaleEntity?

    /**
     * The customer's sales that still owe something, for feeding PaymentAllocator.
     *
     * Same shape as the subquery in overdueBalanceCents, minus the due-date filter: a payment is
     * applied to whatever is unpaid, whether or not it is late yet. Canceled payments contribute
     * nothing, so cancelling a payment puts its sale back in the queue automatically.
     */
    @Query(
        "SELECT sale.id AS id, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents, " +
            "sale.dueDateEpochDay AS dueDateEpochDay, sale.saleDateEpochDay AS saleDateEpochDay " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id HAVING outstandingCents > 0"
    )
    suspend fun outstandingSalesForCustomer(businessId: String, customerId: String): List<OutstandingSaleRow>

    /**
     * The customer's sales that can still be corrected from the screen.
     *
     * Only sales with something unpaid are offered: cancelSaleIdempotently compensates exactly the
     * unpaid remainder, so a fully collected sale would produce a zero movement that says nothing
     * about the money already received. That case is a refund, which is cancelling the payment.
     */
    @Query(
        "SELECT sale.id AS id, sale.saleNumber AS saleNumber, sale.saleDateEpochDay AS saleDateEpochDay, " +
            "sale.totalCents AS totalCents, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id HAVING outstandingCents > 0 ORDER BY sale.saleDateEpochDay DESC, sale.id"
    )
    suspend fun cancelableSalesForCustomer(businessId: String, customerId: String): List<CancelableSaleRow>

    /** The customer's payments that have not been reversed yet. */
    @Query("SELECT * FROM payments WHERE businessId = :businessId AND customerId = :customerId AND canceledAtEpochMillis IS NULL ORDER BY paymentDateEpochDay DESC, id")
    suspend fun activePaymentsForCustomer(businessId: String, customerId: String): List<PaymentEntity>

    @Query(
        "SELECT payment.id AS id, payment.businessId AS businessId, payment.customerId AS customerId, " +
            "payment.amountCents AS amountCents, payment.paymentMethod AS paymentMethod, " +
            "payment.paymentDateEpochDay AS paymentDateEpochDay, payment.reference AS reference, " +
            "payment.createdAtEpochMillis AS createdAtEpochMillis, " +
            "EXISTS(SELECT 1 FROM payment_allocations AS allocation " +
            "INNER JOIN sales AS sale ON sale.id = allocation.saleId " +
            "WHERE allocation.paymentId = payment.id AND sale.businessId = payment.businessId " +
            "AND sale.customerId = payment.customerId AND sale.isCanceled = 1) AS blockedByCanceledSale " +
            "FROM payments AS payment " +
            "WHERE payment.businessId = :businessId AND payment.customerId = :customerId " +
            "AND payment.canceledAtEpochMillis IS NULL " +
            "ORDER BY payment.paymentDateEpochDay DESC, payment.createdAtEpochMillis ASC, payment.id ASC"
    )
    suspend fun correctionPaymentsForCustomer(
        businessId: String,
        customerId: String,
    ): List<CancelablePaymentRow>

    // The four primitives CreditLimitAdvisor needs. Kept as separate read-only queries rather than
    // one join so each stays simple enough to eyeball: a credit-limit suggestion is exactly the
    // kind of feature that must never be the thing that makes the ledger queries hard to trust.

    /** Sales fully collected, not cancelled — "this customer has actually paid off debt before". */
    @Query(
        "SELECT COUNT(*) FROM (SELECT sale.id, sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id HAVING outstandingCents <= 0)"
    )
    suspend fun settledSaleCount(businessId: String, customerId: String): Int

    /** Any sale of this customer ever cancelled — one strike is enough to withhold a suggestion. */
    @Query("SELECT EXISTS(SELECT 1 FROM sales WHERE businessId = :businessId AND customerId = :customerId AND isCanceled = 1)")
    suspend fun hasCanceledSale(businessId: String, customerId: String): Boolean

    /** Any active payment that landed after the sale's own due date, within the given window. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM payment_allocations AS allocation " +
            "INNER JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "INNER JOIN sales AS sale ON sale.id = allocation.saleId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId " +
            "AND payment.canceledAtEpochMillis IS NULL AND sale.dueDateEpochDay IS NOT NULL " +
            "AND payment.paymentDateEpochDay > sale.dueDateEpochDay " +
            "AND payment.paymentDateEpochDay >= :sinceEpochDay)"
    )
    suspend fun hasLatePayment(businessId: String, customerId: String, sinceEpochDay: Long): Boolean

    /** The largest sale this customer was ever trusted with — floor for the next credit step. */
    @Query("SELECT COALESCE(MAX(totalCents), 0) FROM sales WHERE businessId = :businessId AND customerId = :customerId AND isCanceled = 0")
    suspend fun largestSaleTotalCents(businessId: String, customerId: String): Long

    /**
     * Every sale ever written for the business, for the free-plan limit.
     *
     * Canceled sales count too: cancelling must not become a way to reset the counter.
     */
    @Query("SELECT COUNT(*) FROM sales WHERE businessId = :businessId")
    suspend fun registeredSaleCount(businessId: String): Int

    /**
     * Every sale that still owes something and has a date attached, across the whole business.
     *
     * `overdueBalanceCents` answers "how much is late" with a single number; this answers "who, and
     * when", which is what an alert has to say to be worth tapping. Read-only, no schema change.
     *
     * Cancelled payments are excluded from the allocated sum, so a refunded payment correctly makes
     * its sale owe money again.
     */
    @Query(
        "SELECT sale.id AS saleId, sale.dueDateEpochDay AS dueDateEpochDay, " +
            "customer.name AS customerName, customer.id AS customerId, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents " +
            "FROM sales AS sale " +
            // Both halves of this join are load-bearing. There are no @ForeignKey constraints
            // anywhere in this schema — integrity is enforced by require(...) inside the FinancialDao
            // transactions — so nothing at the database level stops a row from pointing at a customer
            // that belongs to another business. Matching on businessId too means such a row produces
            // no alert, instead of leaking another business's customer name onto this screen.
            "INNER JOIN customers AS customer ON customer.id = sale.customerId " +
            "AND customer.businessId = sale.businessId " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.isCanceled = 0 AND sale.dueDateEpochDay IS NOT NULL " +
            "GROUP BY sale.id HAVING outstandingCents > 0 ORDER BY sale.dueDateEpochDay"
    )
    suspend fun salesWithDueDate(businessId: String): List<DueSaleRow>

    /**
     * DEBT / TODO: Composite indices on (businessId, saleDateEpochDay) and (businessId, paymentDateEpochDay)
     * do not exist yet in Room schema. For large datasets with thousands of sales, composite index migrations
     * will be required before scaling.
     */
    @Query(
        "SELECT s.id AS id, s.customerId AS customerId, c.name AS customerName, " +
            "s.totalCents AS amountCents, s.saleDateEpochDay AS effectiveDateEpochDay, " +
            "CASE WHEN s.notes = 'ALTA_CLIENTE_SALDO_ANTERIOR' THEN 'PRIOR_DEBT' ELSE 'SALE' END AS kindName, " +
            "(SELECT si.productNameSnapshot FROM sale_items si WHERE si.saleId = s.id LIMIT 1) AS description " +
            "FROM sales s " +
            "INNER JOIN customers c ON c.id = s.customerId " +
            "WHERE s.businessId = :businessId AND s.saleDateEpochDay >= :minEpochDay AND s.saleDateEpochDay <= :maxEpochDay AND s.isCanceled = 0 " +
            "UNION ALL " +
            "SELECT p.id AS id, p.customerId AS customerId, c.name AS customerName, " +
            "p.amountCents AS amountCents, p.paymentDateEpochDay AS effectiveDateEpochDay, " +
            "'PAYMENT' AS kindName, p.notes AS description " +
            "FROM payments p " +
            "INNER JOIN customers c ON c.id = p.customerId " +
            "WHERE p.businessId = :businessId AND p.paymentDateEpochDay >= :minEpochDay AND p.paymentDateEpochDay <= :maxEpochDay AND p.canceledAtEpochMillis IS NULL"
    )
    suspend fun getRegisteredOperationsInRange(
        businessId: String,
        minEpochDay: Long,
        maxEpochDay: Long,
    ): List<RegisteredOperationEntityRow>

    /**
     * Every non-canceled sale of a business, with its settled amount and settlement date, for the
     * customer payment score.
     *
     * The canceled-payment filter sits inside both aggregates rather than in the `ON` clause. Put
     * there, the allocation row survives the join with `payment` null and its amount would still be
     * summed, quietly reporting a refunded payment as money collected — the same trap
     * [salesWithDueDate] avoids the same way. `MAX` needs the guard for the identical reason: the
     * date of a canceled payment is not the date the sale was settled.
     */
    @Query(
        "SELECT sale.customerId AS customerId, sale.dueDateEpochDay AS dueDateEpochDay, " +
            "sale.totalCents AS totalCents, " +
            "COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS allocatedCents, " +
            "MAX(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN payment.paymentDateEpochDay END) AS lastPaymentDateEpochDay " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id"
    )
    suspend fun saleHistoryForScores(businessId: String): List<CustomerSaleHistoryRow>

    @Query("SELECT COALESCE(SUM(allocation.amountCents), 0) FROM payment_allocations AS allocation INNER JOIN payments AS payment ON payment.id = allocation.paymentId WHERE allocation.saleId = :saleId AND payment.canceledAtEpochMillis IS NULL")
    suspend fun activeAllocatedPaymentCents(saleId: String): Long

    @Query("SELECT COUNT(*) FROM account_movements WHERE saleId = :saleId")
    suspend fun movementCountForSale(saleId: String): Int

    @Query("SELECT * FROM payments WHERE id = :paymentId")
    suspend fun paymentForId(paymentId: String): PaymentEntity?

    @Query("SELECT * FROM payment_allocations WHERE paymentId = :paymentId ORDER BY id")
    suspend fun allocationsForPayment(paymentId: String): List<PaymentAllocationEntity>

    @Query("SELECT * FROM account_movements WHERE businessId = :businessId AND paymentId = :paymentId AND type = 'PAYMENT' LIMIT 1")
    suspend fun paymentMovement(businessId: String, paymentId: String): AccountMovementEntity?

    @Query("SELECT COUNT(*) FROM account_movements WHERE paymentId = :paymentId")
    suspend fun movementCountForPayment(paymentId: String): Int
    @Query(
        "SELECT EXISTS(SELECT 1 FROM payment_allocations AS allocation " +
            "INNER JOIN sales AS sale ON sale.id = allocation.saleId " +
            "WHERE allocation.paymentId = :paymentId AND sale.businessId = :businessId " +
            "AND sale.customerId = :customerId AND sale.isCanceled = 1)"
    )
    suspend fun hasCanceledSaleAllocationForPayment(
        businessId: String,
        customerId: String,
        paymentId: String,
    ): Boolean

    @Query("UPDATE payments SET canceledAtEpochMillis = :canceledAtEpochMillis WHERE id = :paymentId")
    suspend fun markPaymentCanceled(paymentId: String, canceledAtEpochMillis: Long)

    @Query("UPDATE sales SET isCanceled = 1, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :saleId")
    suspend fun markSaleCanceled(saleId: String, updatedAtEpochMillis: Long)

    @Transaction
    suspend fun insertSaleWithCharge(sale: SaleEntity, items: List<SaleItemEntity>, charge: AccountMovementEntity) {
        insertSale(sale)
        insertSaleItems(items)
        insertMovement(charge)
    }

    @Transaction
    suspend fun insertPaymentWithMovements(payment: PaymentEntity, allocations: List<PaymentAllocationEntity>, movement: AccountMovementEntity) {
        insertPayment(payment)
        insertAllocations(allocations)
        insertMovement(movement)
    }
    /** Atomically reserves a key and creates the sale's financial records. */
    @Transaction
    suspend fun createSaleIdempotently(
        operation: OperationEntity,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        require(sale.totalCents > 0) { "Sale must be positive." }
        require(charge.type == "CHARGE" && charge.direction == "DEBIT") {
            "Sale charge must be a CHARGE/DEBIT movement."
        }
        require(charge.amountCents > 0 && charge.amountCents == sale.totalCents) {
            "Sale charge must match the sale total and be positive."
        }
        require(
            charge.businessId == sale.businessId &&
                charge.customerId == sale.customerId &&
                charge.saleId == sale.id,
        ) { "Sale charge must belong to the sale business, customer, and sale." }
        insertSale(sale)
        insertSaleItems(items)
        insertMovement(charge)
        recordAudit(operation, AuditAction.SALE_CREATED, sale.businessId, "SALE", sale.id, sale.sellerUserId, sale.createdAtEpochMillis)
        return sale.id
    }

    /** Atomically reserves a key and creates a payment with its ledger entry. */
    @Transaction
    suspend fun registerPaymentIdempotently(
        operation: OperationEntity,
        payment: PaymentEntity,
        allocations: List<PaymentAllocationEntity>,
        movement: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        require(payment.amountCents > 0) { "Payment must be positive." }
        require(movement.type == "PAYMENT" && movement.direction == "CREDIT") {
            "Payment movement must be a PAYMENT/CREDIT movement."
        }
        require(movement.amountCents > 0 && movement.amountCents == payment.amountCents) {
            "Payment movement must match the payment amount and be positive."
        }
        require(
            movement.businessId == payment.businessId &&
                movement.customerId == payment.customerId &&
                movement.paymentId == payment.id,
        ) { "Payment movement must belong to the payment business, customer, and payment." }
        require(allocations.sumOf { it.amountCents } <= payment.amountCents) {
            "Allocations cannot exceed the payment amount."
        }
        allocations.groupBy { it.saleId }.forEach { (saleId, saleAllocations) ->
            val allocationCents = saleAllocations.sumOf { it.amountCents }
            saleAllocations.forEach { allocation ->
                require(allocation.paymentId == payment.id && allocation.amountCents > 0) {
                    "Allocation must belong to this payment and be positive."
                }
            }
            val sale = requireNotNull(saleForId(saleId)) { "Allocated sale does not exist." }
            require(sale.businessId == payment.businessId && sale.customerId == payment.customerId && !sale.isCanceled) {
                "Allocation sale must belong to the payment customer and business."
            }
            val outstandingCents = sale.totalCents - activeAllocatedPaymentCents(sale.id)
            require(allocationCents <= outstandingCents) {
                "Allocation cannot exceed the sale outstanding balance."
            }
        }
        insertPayment(payment)
        insertAllocations(allocations)
        insertMovement(movement)
        recordAudit(operation, AuditAction.PAYMENT_REGISTERED, payment.businessId, "PAYMENT", payment.id, payment.createdByUserId, payment.createdAtEpochMillis)
        return payment.id
    }

    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun customerForPayment(customerId: String): CustomerEntity?

    @Transaction
    suspend fun registerPaymentAgainstExpectedBalanceIdempotently(
        operation: OperationEntity,
        payment: PaymentEntity,
        movement: AccountMovementEntity,
        expectedBalanceCents: Long,
    ): ExpectedBalancePaymentResult {
        operationResultId(operation.idempotencyKey)?.let {
            return ExpectedBalancePaymentResult.Recorded(it)
        }
        val customer = customerForPayment(payment.customerId) ?: return ExpectedBalancePaymentResult.StaleBalance
        if (customer.businessId != payment.businessId || !customer.isActive || customer.deletedAtEpochMillis != null) {
            return ExpectedBalancePaymentResult.StaleBalance
        }
        if (customerBalanceCents(payment.businessId, payment.customerId) != expectedBalanceCents) {
            return ExpectedBalancePaymentResult.StaleBalance
        }
        val outstanding = outstandingSalesForCustomer(payment.businessId, payment.customerId)
            .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
        val allocationResult = PaymentAllocator.allocate(payment.amountCents, outstanding)
        val allocations = allocationResult.allocations.map { allocation ->
            PaymentAllocationEntity(
                id = PaymentOperationIds.allocation(operation.idempotencyKey, allocation.saleId),
                paymentId = payment.id,
                saleId = allocation.saleId,
                amountCents = allocation.amountCents,
            )
        }
        registerPaymentIdempotently(operation, payment, allocations, movement)
        return ExpectedBalancePaymentResult.Recorded(payment.id)
    }

    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun productForSale(productId: String): ProductEntity?

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    suspend fun itemsForSale(saleId: String): List<SaleItemEntity>

    @Query("SELECT * FROM account_movements WHERE saleId = :saleId AND type = 'CHARGE'")
    suspend fun chargeMovementForSale(saleId: String): AccountMovementEntity?

    @Query("SELECT * FROM payment_allocations WHERE saleId = :saleId")
    suspend fun allAllocationsForSale(saleId: String): List<PaymentAllocationEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM payment_allocations WHERE paymentId = :paymentId AND saleId = :saleId)")
    suspend fun hasAllocationForPaymentAndSale(paymentId: String, saleId: String): Boolean

    @Transaction
    suspend fun registerSaleAgainstPreviewIdempotently(
        operation: OperationEntity,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        expectedCustomerFingerprint: String,
        expectedProductFingerprints: Map<String, String>,
        payment: PaymentEntity?,
        paymentMovement: AccountMovementEntity?,
        paymentAllocations: List<PaymentAllocationEntity>,
        expectedInitialPaymentId: String,
    ): ExpectedSaleResult {
        operationResultId(operation.idempotencyKey)?.let { saleId ->
            val existingSale = saleForId(saleId) ?: return ExpectedSaleResult.StaleData
            if (existingSale.businessId != sale.businessId) return ExpectedSaleResult.StaleData
            val hasInitialPayment = hasAllocationForPaymentAndSale(expectedInitialPaymentId, saleId)
            val actualPaymentId = if (hasInitialPayment) expectedInitialPaymentId else null
            return ExpectedSaleResult.Recorded(saleId, actualPaymentId)
        }
        val customer = customerForPayment(sale.customerId) ?: return ExpectedSaleResult.StaleData
        if (customer.businessId != sale.businessId || !customer.isActive || customer.deletedAtEpochMillis != null) {
            return ExpectedSaleResult.StaleData
        }
        val currentCustFingerprint = "${customer.id}:${customer.updatedAtEpochMillis}"
        if (currentCustFingerprint != expectedCustomerFingerprint) {
            return ExpectedSaleResult.StaleData
        }
        for (item in items) {
            require(item.productNameSnapshot.isNotBlank()) { "Product name snapshot must not be blank." }
            val productId = item.productId ?: return ExpectedSaleResult.StaleData
            val product = productForSale(productId) ?: return ExpectedSaleResult.StaleData
            if (product.businessId != sale.businessId || !product.isActive || product.deletedAtEpochMillis != null) {
                return ExpectedSaleResult.StaleData
            }
            val currentProdFingerprint = "${product.id}:${product.updatedAtEpochMillis}:${product.salePriceCents}"
            val expectedFingerprint = expectedProductFingerprints[productId]
            if (currentProdFingerprint != expectedFingerprint) {
                return ExpectedSaleResult.StaleData
            }
        }
        createSaleIdempotently(operation, sale, items, charge)
        if (payment != null) {
            requireNotNull(paymentMovement) { "Payment movement must be provided." }
            require(paymentAllocations.isNotEmpty()) { "Payment allocations must be provided." }
            require(payment.amountCents > 0L && payment.amountCents <= sale.totalCents) {
                "Payment amount must be positive and not exceed sale total."
            }
            require(paymentAllocations.sumOf { it.amountCents } == payment.amountCents) {
                "Payment allocations must sum to payment amount."
            }
            require(paymentAllocations.all { it.saleId == sale.id && it.paymentId == payment.id }) {
                "Payment allocations must link payment to sale."
            }
            val paymentOperation = OperationEntity(
                idempotencyKey = "payment-${payment.id}",
                type = "PAYMENT",
                resultId = payment.id,
                createdAtEpochMillis = payment.createdAtEpochMillis,
            )
            registerPaymentIdempotently(paymentOperation, payment, paymentAllocations, paymentMovement)
        }
        return ExpectedSaleResult.Recorded(sale.id, payment?.id)
    }

    @Query("SELECT * FROM customers WHERE businessId = :businessId AND normalizedName = :normalizedName AND isActive = 1 AND deletedAtEpochMillis IS NULL LIMIT 1")
    suspend fun activeCustomerByNormalizedName(businessId: String, normalizedName: String): CustomerEntity?

    @Query("SELECT * FROM products WHERE businessId = :businessId AND isActive = 1 AND deletedAtEpochMillis IS NULL")
    suspend fun activeProductsForBusiness(businessId: String): List<ProductEntity>

    @Transaction
    suspend fun registerCompositeSaleAgainstPreviewIdempotently(
        operation: OperationEntity,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        expectedCustomerFingerprint: String,
        expectedProductFingerprints: Map<String, String>,
        payment: PaymentEntity?,
        paymentMovement: AccountMovementEntity?,
        paymentAllocations: List<PaymentAllocationEntity>,
        expectedInitialPaymentId: String,
        newCustomer: CustomerEntity?,
        explicitCustomerCreateNew: Boolean,
        newProducts: List<ProductEntity>,
        explicitProductCreateNewByProductId: Map<String, Boolean>,
    ): ExpectedSaleResult {
        operationResultId(operation.idempotencyKey)?.let { saleId ->
            val existingSale = saleForId(saleId) ?: return ExpectedSaleResult.StaleData
            if (existingSale.businessId != sale.businessId) return ExpectedSaleResult.StaleData
            val hasInitialPayment = hasAllocationForPaymentAndSale(expectedInitialPaymentId, saleId)
            val actualPaymentId = if (hasInitialPayment) expectedInitialPaymentId else null
            return ExpectedSaleResult.Recorded(saleId, actualPaymentId)
        }

        if (newCustomer != null) {
            if (newCustomer.businessId != sale.businessId) return ExpectedSaleResult.StaleData
            if (expectedCustomerFingerprint.isNotEmpty()) return ExpectedSaleResult.StaleData
            if (customerForPayment(newCustomer.id) != null) {
                return ExpectedSaleResult.StaleData
            }
            if (!explicitCustomerCreateNew) {
                if (activeCustomerByNormalizedName(sale.businessId, newCustomer.normalizedName) != null) {
                    return ExpectedSaleResult.StaleData
                }
            }
            insertCustomer(newCustomer)
        } else {
            val customer = customerForPayment(sale.customerId) ?: return ExpectedSaleResult.StaleData
            if (customer.businessId != sale.businessId || !customer.isActive || customer.deletedAtEpochMillis != null) {
                return ExpectedSaleResult.StaleData
            }
            val currentCustFingerprint = "${customer.id}:${customer.updatedAtEpochMillis}"
            if (currentCustFingerprint != expectedCustomerFingerprint) {
                return ExpectedSaleResult.StaleData
            }
        }

        val existingActiveProducts = if (newProducts.isNotEmpty()) {
            activeProductsForBusiness(sale.businessId)
        } else {
            emptyList()
        }

        val seenNormalizedNewProductNames = HashSet<String>(newProducts.size)
        for (newProd in newProducts) {
            if (newProd.businessId != sale.businessId) {
                return ExpectedSaleResult.StaleData
            }
            if (productForSale(newProd.id) != null) {
                return ExpectedSaleResult.StaleData
            }
            val normalizedNewName = com.cuentivo.app.domain.CustomerNameNormalizer.key(newProd.name)
            val explicitCreateNew = explicitProductCreateNewByProductId[newProd.id] ?: false
            if (!explicitCreateNew) {
                if (seenNormalizedNewProductNames.contains(normalizedNewName)) {
                    return ExpectedSaleResult.StaleData
                }
                val existsSameName = existingActiveProducts.any {
                    com.cuentivo.app.domain.CustomerNameNormalizer.key(it.name) == normalizedNewName
                }
                if (existsSameName) {
                    return ExpectedSaleResult.StaleData
                }
            }
            seenNormalizedNewProductNames.add(normalizedNewName)
        }
        if (newProducts.isNotEmpty()) {
            insertProducts(newProducts)
        }

        val createdProductIds = newProducts.map { it.id }.toSet()
        for (item in items) {
            require(item.productNameSnapshot.isNotBlank()) { "Product name snapshot must not be blank." }
            val productId = item.productId
            if (productId == null) {
                require(item.quantity == 1L) { "Uncataloged line must have quantity 1." }
                require(item.unitPriceCents > 0L) { "Uncataloged line must have positive unit price." }
                require(item.discountCents == 0L) { "Uncataloged line cannot have discount." }
                require(item.subtotalCents == item.unitPriceCents) { "Uncataloged line subtotal must equal unit price." }
                require((expectedProductFingerprints as Map<*, *>)[null] == null) { "Uncataloged line cannot have product fingerprint." }
            } else {
                if (productId !in createdProductIds) {
                    val product = productForSale(productId) ?: return ExpectedSaleResult.StaleData
                    if (product.businessId != sale.businessId || !product.isActive || product.deletedAtEpochMillis != null) {
                        return ExpectedSaleResult.StaleData
                    }
                    val currentProdFingerprint = "${product.id}:${product.updatedAtEpochMillis}:${product.salePriceCents}"
                    val expectedFingerprint = expectedProductFingerprints[productId]
                    if (currentProdFingerprint != expectedFingerprint) {
                        return ExpectedSaleResult.StaleData
                    }
                }
            }
        }

        createSaleIdempotently(operation, sale, items, charge)
        if (payment != null) {
            requireNotNull(paymentMovement) { "Payment movement must be provided." }
            require(paymentAllocations.isNotEmpty()) { "Payment allocations must be provided." }
            require(payment.amountCents > 0L && payment.amountCents <= sale.totalCents) {
                "Payment amount must be positive and not exceed sale total."
            }
            require(paymentAllocations.sumOf { it.amountCents } == payment.amountCents) {
                "Payment allocations must sum to payment amount."
            }
            require(paymentAllocations.all { it.saleId == sale.id && it.paymentId == payment.id }) {
                "Payment allocations must link payment to sale."
            }
            val paymentOperation = OperationEntity(
                idempotencyKey = "payment-${payment.id}",
                type = "PAYMENT",
                resultId = payment.id,
                createdAtEpochMillis = payment.createdAtEpochMillis,
            )
            registerPaymentIdempotently(paymentOperation, payment, paymentAllocations, paymentMovement)
        }
        return ExpectedSaleResult.Recorded(sale.id, payment?.id)
    }

    /** Stores an immutable, documented balance correction without duplicating retries. */
    @Transaction
    suspend fun registerAdjustmentIdempotently(
        operation: OperationEntity,
        adjustment: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        require(adjustment.type == "ADJUSTMENT") { "Adjustment type is required." }
        require(adjustment.direction == "DEBIT" || adjustment.direction == "CREDIT") {
            "Adjustment direction must be DEBIT or CREDIT."
        }
        require(adjustment.amountCents > 0) { "Adjustment must be positive." }
        require(!adjustment.notes.isNullOrBlank()) { "Adjustment reason is required." }
        insertMovement(adjustment)
        recordAudit(operation, AuditAction.ADJUSTMENT_REGISTERED, adjustment.businessId, "MOVEMENT", adjustment.id, adjustment.createdByUserId, adjustment.occurredAtEpochMillis)
        return adjustment.id
    }

    /** Cancels only the unpaid amount; a refunded payment is a separate ledger event. */
    @Transaction
    suspend fun cancelSaleIdempotently(
        operation: OperationEntity,
        saleId: String,
        cancellation: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        val sale = requireNotNull(saleForId(saleId)) { "Sale does not exist." }
        require(!sale.isCanceled) { "Sale is already canceled." }
        require(cancellation.type == "CANCELLATION" && cancellation.direction == "CREDIT")
        require(cancellation.businessId == sale.businessId && cancellation.customerId == sale.customerId && cancellation.saleId == sale.id)
        val allocatedCents = activeAllocatedPaymentCents(sale.id)
        val outstandingCents = sale.totalCents - allocatedCents
        require(outstandingCents > 0 && cancellation.amountCents == outstandingCents) { "Cancellation must compensate the unpaid amount." }
        markSaleCanceled(sale.id, cancellation.occurredAtEpochMillis)
        insertMovement(cancellation)
        recordAudit(operation, AuditAction.SALE_CANCELED, sale.businessId, "SALE", sale.id, cancellation.createdByUserId, cancellation.occurredAtEpochMillis)
        return sale.id
    }

    /**
     * Corrects a sale by replacing it: the wrong one is voided and a right one is written, together.
     *
     * There is no `UPDATE` on a registered sale anywhere in this app, and this is not one. What the
     * owner sees as "editar" is a cancellation plus a creation committed in a single transaction,
     * so the ledger keeps both halves of the story and can never hold one without the other.
     *
     * **The cancellation credits the previous total in full**, unlike [cancelSaleIdempotently],
     * which credits only the unpaid remainder. That asymmetry is the point: a plain cancellation
     * leaves the paid part settled, while a replacement re-charges everything through [charge], so
     * anything less than a full credit would count the paid part twice. A $400 sale with $200 paid,
     * corrected to $500, lands on `+400 −200 −400 +500 = 300`, which is exactly what is owed.
     *
     * [carriedAllocations] are fresh rows pointing at the corrected sale, so the money already
     * collected still counts as applied to it. The previous sale's own allocations stay where they
     * are, as evidence; every balance query ignores them because their sale is canceled.
     */
    @Transaction
    suspend fun replaceSaleIdempotently(
        operation: OperationEntity,
        previousSaleId: String,
        cancellation: AccountMovementEntity,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        carriedAllocations: List<PaymentAllocationEntity>,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        val previous = requireNotNull(saleForId(previousSaleId)) { "Sale does not exist." }
        require(!previous.isCanceled) { "Sale is already canceled." }
        require(previous.id != sale.id) { "The corrected sale needs its own id." }
        require(previous.businessId == sale.businessId && previous.customerId == sale.customerId) {
            "A correction stays with the same business and customer."
        }
        require(sale.totalCents > 0) { "Sale must be positive." }
        require(cancellation.type == "CANCELLATION" && cancellation.direction == "CREDIT") {
            "The voided sale needs a CANCELLATION/CREDIT movement."
        }
        require(
            cancellation.businessId == previous.businessId &&
                cancellation.customerId == previous.customerId &&
                cancellation.saleId == previous.id,
        ) { "The cancellation must belong to the sale being replaced." }
        require(cancellation.amountCents == previous.totalCents) {
            "A replacement voids the whole previous sale."
        }
        require(charge.type == "CHARGE" && charge.direction == "DEBIT") {
            "Sale charge must be a CHARGE/DEBIT movement."
        }
        require(charge.amountCents > 0 && charge.amountCents == sale.totalCents) {
            "Sale charge must match the sale total and be positive."
        }
        require(
            charge.businessId == sale.businessId &&
                charge.customerId == sale.customerId &&
                charge.saleId == sale.id,
        ) { "Sale charge must belong to the sale business, customer, and sale." }
        require(carriedAllocations.all { it.saleId == sale.id && it.amountCents > 0 }) {
            "Carried allocations must point at the corrected sale and be positive."
        }
        val alreadyPaidCents = activeAllocatedPaymentCents(previous.id)
        require(carriedAllocations.sumOf { it.amountCents } <= minOf(alreadyPaidCents, sale.totalCents)) {
            "Carried payments cannot exceed what was paid or the corrected total."
        }
        markSaleCanceled(previous.id, cancellation.occurredAtEpochMillis)
        insertMovement(cancellation)
        insertSale(sale)
        insertSaleItems(items)
        insertMovement(charge)
        insertAllocations(carriedAllocations)
        recordAudit(operation, AuditAction.SALE_CORRECTED, sale.businessId, "SALE", sale.id, sale.sellerUserId, sale.createdAtEpochMillis)
        return sale.id
    }

    /** Reverses a payment with a debit ledger entry instead of deleting evidence of collection. */
    @Transaction
    suspend fun cancelPaymentIdempotently(
        operation: OperationEntity,
        paymentId: String,
        refund: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        val payment = requireNotNull(paymentForId(paymentId)) { "Payment does not exist." }
        require(payment.canceledAtEpochMillis == null) { "Payment is already canceled." }
        require(refund.type == "REFUND" && refund.direction == "DEBIT")
        require(refund.businessId == payment.businessId && refund.customerId == payment.customerId && refund.paymentId == payment.id)
        require(refund.amountCents == payment.amountCents) { "Refund must match the original payment." }
        require(!hasCanceledSaleAllocationForPayment(payment.businessId, payment.customerId, payment.id)) {
            "Cannot reverse a payment allocated to a canceled sale."
        }
        markPaymentCanceled(payment.id, refund.occurredAtEpochMillis)
        insertMovement(refund)
        recordAudit(operation, AuditAction.PAYMENT_CANCELED, payment.businessId, "PAYMENT", payment.id, refund.createdByUserId, refund.occurredAtEpochMillis)
        return payment.id
    }

    @Transaction
    suspend fun registerLedgerBatchIdempotently(
        batchIdempotencyKey: String,
        businessId: String,
        userId: String,
        nowEpochMillis: Long,
        newCustomers: List<CustomerEntity>,
        sales: List<SaleEntity>,
        saleItems: List<SaleItemEntity>,
        chargeMovements: List<AccountMovementEntity>,
        payments: List<PaymentEntity>,
        paymentMovements: List<AccountMovementEntity>,
        paymentAllocations: List<PaymentAllocationEntity>,
        executableOperationsCount: Int,
    ): ExpectedLedgerBatchResult {
        // Reserve BEFORE writing anything, like every other *Idempotently here. Reserving last
        // would let two concurrent confirmations both find no reservation, both write the whole
        // batch, and the loser report success on top of duplicated money.
        val operation = OperationEntity(
            idempotencyKey = batchIdempotencyKey,
            type = "LEDGER_BATCH",
            resultId = batchIdempotencyKey,
            createdAtEpochMillis = nowEpochMillis,
        )
        if (reserveOperation(operation) == -1L) {
            return ExpectedLedgerBatchResult.AlreadyRecorded(batchIdempotencyKey, executableOperationsCount)
        }

        // Everything is checked before the first insert. A `return` inside a Room transaction
        // COMMITS what was already written, so a rejection discovered halfway would leave half a
        // notebook in the ledger. Rejections must happen while nothing has been written yet.
        require(newCustomers.none { it.businessId != businessId }) { "Batch customer outside its business." }
        require(sales.none { it.businessId != businessId }) { "Batch sale outside its business." }
        require(chargeMovements.none { it.businessId != businessId }) { "Batch charge outside its business." }
        require(payments.none { it.businessId != businessId }) { "Batch payment outside its business." }
        require(paymentMovements.none { it.businessId != businessId }) { "Batch payment movement outside its business." }

        // Referential integrity, applied here because there are no foreign keys in this schema.
        val saleById = sales.associateBy { it.id }
        require(saleItems.all { it.saleId in saleById }) { "A batch sale item has no sale." }
        for (charge in chargeMovements) {
            require(charge.type == "CHARGE" && charge.direction == "DEBIT") { "Batch charge must be CHARGE/DEBIT." }
            val sale = saleById[charge.saleId]
            requireNotNull(sale) { "A batch charge has no sale." }
            require(charge.customerId == sale.customerId) { "Batch charge and sale disagree on the customer." }
            require(charge.amountCents == sale.totalCents) { "Batch charge must equal its sale total." }
        }
        // Sales and their money have to agree with each other before any of it is written.
        for (sale in sales) {
            require(sale.totalCents > 0) { "A batch sale must be positive." }
            require(chargeMovements.count { it.saleId == sale.id } == 1) { "A batch sale needs exactly one charge." }
            require(saleItems.any { it.saleId == sale.id }) { "A batch sale needs at least one item." }
        }
        val itemTotalsBySale = saleItems.groupBy { it.saleId }
            .mapValues { (_, items) -> items.fold(0L) { acc, item -> Math.addExact(acc, item.subtotalCents) } }
        for (sale in sales) {
            require(itemTotalsBySale[sale.id] == sale.totalCents) {
                "A batch sale total must equal its items."
            }
        }

        val paymentById = payments.associateBy { it.id }
        for (payment in payments) {
            require(payment.amountCents > 0) { "A batch payment must be positive." }
            val movements = paymentMovements.filter { it.paymentId == payment.id }
            require(movements.size == 1) { "A batch payment needs exactly one movement." }
            val movement = movements.first()
            require(movement.customerId == payment.customerId) { "Batch payment movement must belong to its payment's customer." }
            require(movement.amountCents == payment.amountCents) { "Batch payment movement must equal its payment." }
            val assigned = paymentAllocations.filter { it.paymentId == payment.id }
                .fold(0L) { acc, allocation -> Math.addExact(acc, allocation.amountCents) }
            require(assigned <= payment.amountCents) { "A batch payment cannot assign more than it received." }
        }
        for (movement in paymentMovements) {
            require(movement.type == "PAYMENT" && movement.direction == "CREDIT") { "Batch payment movement must be PAYMENT/CREDIT." }
        }
        for (allocation in paymentAllocations) {
            require(allocation.amountCents > 0) { "A batch allocation must be positive." }
        }
        // No orphan movements: a payment movement with no payment in this batch is money nobody
        // can trace back to what it settled.
        for (movement in paymentMovements) {
            require(movement.paymentId in paymentById) { "A batch payment movement has no payment." }
        }
        // Every customer the batch writes against must already be here, active and ours. A payment
        // aimed at somebody else's customer is not a typo, it is money in the wrong ledger.
        val batchCustomerIds = (sales.map { it.customerId } + payments.map { it.customerId }).toSet()
        val newCustomerIds = newCustomers.map { it.id }.toSet()
        for (customerId in batchCustomerIds - newCustomerIds) {
            val existing = customerForPayment(customerId)
            requireNotNull(existing) { "A batch row points at a customer that does not exist." }
            require(existing.businessId == businessId) { "A batch row points at another business's customer." }
            require(existing.isActive && existing.deletedAtEpochMillis == null) {
                "A batch row points at an archived customer."
            }
        }
        for (allocation in paymentAllocations) {
            require(allocation.paymentId in paymentById) { "A batch allocation has no payment." }
        }
        // No over-allocation. A payment on a photographed page can settle a sale written earlier in
        // the same page or a debt the customer already had, so what is still owed comes from this
        // batch for the former and from the ledger for the latter.
        paymentAllocations.groupBy { it.saleId }.forEach { (saleId, allocations) ->
            val assigned = allocations.fold(0L) { acc, allocation -> Math.addExact(acc, allocation.amountCents) }
            // Money must not cross customers: a payment can only settle what the person who paid
            // actually owes.
            for (allocation in allocations) {
                val payer = requireNotNull(paymentById[allocation.paymentId]).customerId
                val owner = saleById[saleId]?.customerId ?: saleForId(saleId)?.customerId
                requireNotNull(owner) { "A batch allocation has no sale." }
                require(owner == payer) { "A batch allocation settles another customer's sale." }
            }
            val inBatch = saleById[saleId]
            val outstanding = if (inBatch != null) {
                inBatch.totalCents
            } else {
                val existing = saleForId(saleId)
                requireNotNull(existing) { "A batch allocation has no sale." }
                require(existing.businessId == businessId) { "A batch allocation crosses businesses." }
                require(!existing.isCanceled) { "A batch allocation targets a canceled sale." }
                existing.totalCents - activeAllocatedPaymentCents(saleId)
            }
            require(assigned <= outstanding) { "A batch allocation exceeds what its sale still owes." }
        }
        // An id already in use is not a fresh customer: fail before writing, not after.
        for (customer in newCustomers) {
            require(customerForPayment(customer.id) == null) { "Batch customer id already exists." }
        }

        // Insert new customers
        for (customer in newCustomers) {
            insertCustomer(customer)
        }

        // Insert sales, items, charges
        for (sale in sales) {
            insertSale(sale)
        }
        insertSaleItems(saleItems)
        for (charge in chargeMovements) {
            insertMovement(charge)
        }

        // Insert payments, allocations, payment movements
        for (payment in payments) {
            insertPayment(payment)
        }
        insertAllocations(paymentAllocations)
        for (movement in paymentMovements) {
            insertMovement(movement)
        }

        // One audit row per financial fact, not one for the whole page. A single LEDGER_BATCH entry
        // would say a page was imported and leave no way to answer which sale or which payment it
        // was, which is the question an audit log exists to answer. The event id stays deterministic
        // per row because it derives from the batch key plus the row's own id.
        for (sale in sales) {
            recordAudit(
                operation = operation.copy(idempotencyKey = batchIdempotencyKey + ":sale:" + sale.id),
                action = AuditAction.SALE_CREATED,
                businessId = businessId,
                entityType = "SALE",
                entityId = sale.id,
                userId = userId,
                timestampEpochMillis = nowEpochMillis,
            )
        }
        for (payment in payments) {
            recordAudit(
                operation = operation.copy(idempotencyKey = batchIdempotencyKey + ":payment:" + payment.id),
                action = AuditAction.PAYMENT_REGISTERED,
                businessId = businessId,
                entityType = "PAYMENT",
                entityId = payment.id,
                userId = userId,
                timestampEpochMillis = nowEpochMillis,
            )
        }

        return ExpectedLedgerBatchResult.Recorded(batchIdempotencyKey, executableOperationsCount)
    }

}
