package com.cuentivo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.OperationEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.ui.theme.CuentivoTheme
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.CustomerStatementCsv
import com.cuentivo.app.domain.DashboardRepository
import com.cuentivo.app.domain.Debtor
import com.cuentivo.app.domain.DebtorShareText
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.PaymentAllocator
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.CuentivoApp
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.dashboard.DashboardViewModel
import com.cuentivo.app.auth.FirebaseAuthenticationGateway
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.sync.FirebaseEmulators
import com.cuentivo.app.sync.OwnerMembershipBootstrap
import com.cuentivo.app.sync.SyncQueue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before any Firestore or Auth call: useEmulator throws once the instance has been used.
        FirebaseEmulators.connectIfEnabled()
        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        val syncQueue = SyncQueue(database.syncOperationDao(), applicationContext)
        val membershipBootstrap = OwnerMembershipBootstrap(database.businessDao(), database.syncOperationDao(), syncQueue)
        val viewModel = DashboardViewModel(DashboardRepository(database.businessDao(), database.financialDao())::load)
        val authenticationGateway = FirebaseAuthenticationGateway()
        setContent {
            CuentivoTheme {
                var authenticatedUserId by remember { mutableStateOf(authenticationGateway.currentUserId) }
                var authenticationMessage by remember { mutableStateOf<String?>(null) }
                var isAuthenticating by remember { mutableStateOf(false) }
                if (authenticatedUserId == null) {
                    AuthenticationScreen(
                        isWorking = isAuthenticating,
                        message = authenticationMessage,
                        onSignIn = { email, password ->
                            isAuthenticating = true
                            authenticationMessage = null
                            authenticationGateway.signIn(email, password) { result ->
                                isAuthenticating = false
                                result.onSuccess { authenticatedUserId = it }
                                    .onFailure { authenticationMessage = it.message ?: "No fue posible iniciar sesión" }
                            }
                        },
                        onSignUp = { email, password ->
                            isAuthenticating = true
                            authenticationMessage = null
                            authenticationGateway.signUp(email, password) { result ->
                                isAuthenticating = false
                                result.onSuccess { authenticatedUserId = it }
                                    .onFailure { authenticationMessage = it.message ?: "No fue posible crear la cuenta" }
                            }
                        },
                    )
                } else {
                val currentUserId = authenticatedUserId ?: error("El usuario autenticado no está disponible")
                // Bumping this key re-runs the produceState loaders below. Replaces the old
                // recreate() refresh, which flashed the whole Activity and lost the active tab.
                var refreshKey by remember { mutableStateOf(0) }
                var uiMessage by remember { mutableStateOf<String?>(null) }
                var statementCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
                var statementEntries by remember { mutableStateOf<List<StatementEntry>>(emptyList()) }
                var blocked by remember { mutableStateOf<AccessDecision.Blocked?>(null) }
                // No Billing yet: every install is on the free plan until the Play subscription is
                // wired up. Reading it through one variable means only this line changes later.
                val tier = SubscriptionTier.FREE

                // Backfills the owner membership for businesses created before it was published,
                // whose queued rows are otherwise parked on PERMISSION_DENIED forever.
                LaunchedEffect(currentUserId) {
                    database.businessDao().first()
                        ?.takeIf { it.ownerUserId == currentUserId }
                        ?.let { membershipBootstrap.ensure(it) }
                }
                val state by produceState<DashboardUiState>(initialValue = DashboardUiState.NoBusiness, refreshKey) { value = viewModel.load() }
                val customers by produceState<List<CustomerBalanceRow>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.customerDao().activeWithBalances(it.businessId) } ?: emptyList()
                }
                val products by produceState<List<ProductEntity>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.productDao().searchActive(it.businessId, "", "") } ?: emptyList()
                }
                val businessName = (state as? DashboardUiState.Content)?.businessName ?: "Mi negocio"

                CuentivoApp(state, customers, products, tier, onCreateBusiness = {
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val business = BusinessEntity(UUID.randomUUID().toString(), "Mi negocio", currentUserId, "MXN", "America/Mexico_City", now, now)
                        database.businessDao().insert(business)
                        syncQueue.enqueue(business.id, "BUSINESS", business.id, now, mapOf("name" to business.name, "ownerUserId" to business.ownerUserId, "currency" to business.currency, "timezone" to business.timezone, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                        membershipBootstrap.ensure(business)
                        refreshKey++
                        uiMessage = "Negocio creado"
                    }
                }, onCreateCustomer = { name, phone ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    // Plan limit checked before writing: refusing after the insert would leave a
                    // customer the app then pretends does not exist.
                    when (val decision = FeatureAccessManager.canAddCustomer(tier, customers.size)) {
                        is AccessDecision.Blocked -> blocked = decision
                        AccessDecision.Allowed -> lifecycleScope.launch {
                            val now = System.currentTimeMillis()
                            val customer = CustomerEntity(UUID.randomUUID().toString(), businessId, name, CustomerNameNormalizer.key(name), phone, null, null, null, null, true, now, now, null)
                            database.customerDao().insert(customer)
                            syncQueue.enqueue(businessId, "CUSTOMER", customer.id, now, mapOf("businessId" to businessId, "name" to customer.name, "normalizedName" to customer.normalizedName, "phone" to phone, "email" to null, "address" to null, "notes" to null, "creditLimitCents" to null, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                            refreshKey++
                            uiMessage = "Cliente agregado"
                        }
                    }
                }, onCreateProduct = { name, priceCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val product = ProductEntity(UUID.randomUUID().toString(), businessId, name, null, null, null, priceCents, null, null, null, true, now, now, null)
                        database.productDao().insert(product)
                        syncQueue.enqueue(businessId, "PRODUCT", product.id, now, mapOf("businessId" to businessId, "name" to name, "description" to null, "sku" to null, "category" to null, "salePriceCents" to priceCents, "costPriceCents" to null, "stock" to null, "imageUri" to null, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                        refreshKey++
                        uiMessage = "Producto agregado"
                    }
                }, onCreateSale = { customer, draft ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val salesSoFar = database.financialDao().registeredSaleCount(businessId)
                        when (val decision = FeatureAccessManager.canRegisterSale(tier, salesSoFar)) {
                            is AccessDecision.Blocked -> blocked = decision
                            AccessDecision.Allowed -> {
                                val now = System.currentTimeMillis()
                                val saleId = UUID.randomUUID().toString()
                                val sale = SaleEntity(saleId, businessId, customer.customer.id, currentUserId, "V-${now}", LocalDate.now().toEpochDay(), draft.dueDateEpochDay, draft.subtotalCents, draft.discountCents, draft.totalCents, false, null, now, now)
                                // One item row per cart line, each keeping the name and price charged today.
                                val items = draft.lines.map { line ->
                                    SaleItemEntity(UUID.randomUUID().toString(), saleId, line.productId, line.productName, line.quantity.toLong(), line.unitPriceCents, line.discountCents, line.subtotalCents)
                                }
                                // A single CHARGE for the sale total: the ledger records what the customer owes,
                                // not how the cart was composed.
                                val charge = AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, saleId, null, "CHARGE", draft.totalCents, "DEBIT", now, currentUserId, null, null)
                                database.financialDao().createSaleIdempotently(OperationEntity("sale-$saleId", "SALE", saleId, now), sale, items, charge)
                                syncQueue.enqueue(businessId, "SALE", sale.id, now, mapOf("businessId" to businessId, "customerId" to sale.customerId, "sellerUserId" to currentUserId, "saleNumber" to sale.saleNumber, "saleDateEpochDay" to sale.saleDateEpochDay, "dueDateEpochDay" to sale.dueDateEpochDay, "subtotalCents" to sale.subtotalCents, "discountCents" to sale.discountCents, "totalCents" to sale.totalCents, "isCanceled" to false, "notes" to null, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                                items.forEach { item ->
                                    syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, mapOf("saleId" to sale.id, "productId" to item.productId, "productNameSnapshot" to item.productNameSnapshot, "quantity" to item.quantity, "unitPriceCents" to item.unitPriceCents, "discountCents" to item.discountCents, "subtotalCents" to item.subtotalCents))
                                }
                                syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, mapOf("businessId" to businessId, "customerId" to charge.customerId, "saleId" to sale.id, "paymentId" to null, "type" to charge.type, "amountCents" to charge.amountCents, "direction" to charge.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                                refreshKey++
                                uiMessage = "Venta de ${Money.format(draft.totalCents)} registrada"
                            }
                        }
                    }
                }, onCreatePayment = { customer, amountCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val paymentId = UUID.randomUUID().toString()
                        // Apply the payment to the customer's unpaid sales, oldest due first.
                        // Anything left over stays as credit on the customer's balance.
                        val outstanding = database.financialDao()
                            .outstandingSalesForCustomer(businessId, customer.customer.id)
                            .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
                        val allocationResult = PaymentAllocator.allocate(amountCents, outstanding)
                        val allocations = allocationResult.allocations.map { allocation ->
                            PaymentAllocationEntity(UUID.randomUUID().toString(), paymentId, allocation.saleId, allocation.amountCents)
                        }
                        val payment = PaymentEntity(paymentId, businessId, customer.customer.id, amountCents, "CASH", LocalDate.now().toEpochDay(), null, null, currentUserId, now, null)
                        val movement = AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, null, paymentId, "PAYMENT", amountCents, "CREDIT", now, currentUserId, null, null)
                        database.financialDao().registerPaymentIdempotently(OperationEntity("payment-$paymentId", "PAYMENT", paymentId, now), payment, allocations, movement)
                        syncQueue.enqueue(businessId, "PAYMENT", payment.id, now, mapOf("businessId" to businessId, "customerId" to payment.customerId, "amountCents" to payment.amountCents, "paymentMethod" to payment.paymentMethod, "paymentDateEpochDay" to payment.paymentDateEpochDay, "reference" to null, "notes" to null, "createdByUserId" to currentUserId, "createdAtEpochMillis" to now, "canceledAtEpochMillis" to null))
                        allocations.forEach { allocation ->
                            syncQueue.enqueue(businessId, "PAYMENT_ALLOCATION", allocation.id, now, mapOf("paymentId" to allocation.paymentId, "saleId" to allocation.saleId, "amountCents" to allocation.amountCents))
                        }
                        syncQueue.enqueue(businessId, "MOVEMENT", movement.id, now, mapOf("businessId" to businessId, "customerId" to movement.customerId, "saleId" to null, "paymentId" to payment.id, "type" to movement.type, "amountCents" to movement.amountCents, "direction" to movement.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                        refreshKey++
                        uiMessage = when {
                            allocations.isEmpty() -> "Abono registrado como saldo a favor"
                            allocationResult.creditCents > 0 ->
                                "Abono aplicado a ${allocations.size} venta(s); ${Money.format(allocationResult.creditCents)} queda a favor"
                            else -> "Abono aplicado a ${allocations.size} venta(s)"
                        }
                    }
                }, statementCustomer = statementCustomer?.customer?.name, statement = statementEntries, onShowStatement = { row ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        // The DAO returns newest-first; the statement needs chronological order
                        // because the CSV builder derives the running balance from it.
                        statementEntries = database.financialDao().customerMovements(businessId, row.customer.id)
                            .asReversed()
                            .map { movement ->
                                StatementEntry(
                                    dateIso = Instant.ofEpochMilli(movement.occurredAtEpochMillis)
                                        .atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                                    type = movement.type,
                                    direction = movement.direction,
                                    amountCents = movement.amountCents,
                                    notes = movement.notes,
                                )
                            }
                        statementCustomer = row
                    }
                }, onDismissStatement = { statementCustomer = null }, onShareStatementCsv = {
                    statementCustomer?.let { row ->
                        share("Estado de cuenta - ${row.customer.name}", CustomerStatementCsv.build(statementEntries))
                    }
                }, onShareStatementText = {
                    statementCustomer?.let { row ->
                        // Only this customer's numbers travel in the message.
                        share(
                            "Estado de cuenta - ${row.customer.name}",
                            DebtorShareText.forCustomer(businessName, row.customer.name, row.balanceCents, statementEntries),
                        )
                    }
                }, onShareDebtorList = {
                    val debtors = customers.map { Debtor(it.customer.name, it.balanceCents) }
                    share("Clientes por cobrar", DebtorShareText.forBusiness(businessName, debtors))
                }, blocked = blocked, onDismissBlocked = { blocked = null }, onUpgrade = {
                    blocked = null
                    uiMessage = "Las suscripciones se activan en la próxima versión"
                }, onSignOut = {
                    authenticationGateway.signOut()
                    authenticatedUserId = null
                }, onDeleteAccount = {
                    // Local data goes first: if the account delete fails, the person is still signed
                    // in and can retry, rather than being locked out of rows that outlived them.
                    lifecycleScope.launch {
                        database.clearAllTables()
                        authenticationGateway.deleteAccount { result ->
                            result.onSuccess {
                                authenticatedUserId = null
                            }.onFailure {
                                uiMessage = it.message
                                    ?: "Vuelve a iniciar sesión para poder eliminar la cuenta"
                            }
                        }
                    }
                }, message = uiMessage, onMessageShown = { uiMessage = null })
                }
            }
        }
    }

    /** Hands text to the Android Sharesheet, where WhatsApp shows up on its own. */
    private fun share(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, subject))
    }
}
