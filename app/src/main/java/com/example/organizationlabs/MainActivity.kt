package com.cuentivo.app

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
import java.util.UUID
import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.ui.theme.CuentivoTheme
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.DashboardRepository
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
                var statementCustomer by remember { mutableStateOf<String?>(null) }
                var statementEntries by remember { mutableStateOf<List<com.cuentivo.app.domain.StatementEntry>>(emptyList()) }
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
                CuentivoApp(state, customers, products, onCreateBusiness = {
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val business = com.cuentivo.app.data.local.BusinessEntity(UUID.randomUUID().toString(), "Mi negocio", currentUserId, "MXN", "America/Mexico_City", now, now)
                        database.businessDao().insert(business)
                        syncQueue.enqueue(business.id, "BUSINESS", business.id, now, mapOf("name" to business.name, "ownerUserId" to business.ownerUserId, "currency" to business.currency, "timezone" to business.timezone, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                        membershipBootstrap.ensure(business)
                        refreshKey++
                        uiMessage = "Negocio creado"
                    }
                }, onCreateCustomer = { name ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val customer = com.cuentivo.app.data.local.CustomerEntity(UUID.randomUUID().toString(), businessId, name, CustomerNameNormalizer.key(name), null, null, null, null, null, true, now, now, null)
                        database.customerDao().insert(customer)
                        syncQueue.enqueue(businessId, "CUSTOMER", customer.id, now, mapOf("businessId" to businessId, "name" to customer.name, "normalizedName" to customer.normalizedName, "phone" to null, "email" to null, "address" to null, "notes" to null, "creditLimitCents" to null, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                        refreshKey++
                        uiMessage = "Cliente agregado"
                    }
                }, onCreateProduct = { name, priceCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val product = com.cuentivo.app.data.local.ProductEntity(UUID.randomUUID().toString(), businessId, name, null, null, null, priceCents, null, null, null, true, now, now, null)
                        database.productDao().insert(product)
                        syncQueue.enqueue(businessId, "PRODUCT", product.id, now, mapOf("businessId" to businessId, "name" to name, "description" to null, "sku" to null, "category" to null, "salePriceCents" to priceCents, "costPriceCents" to null, "stock" to null, "imageUri" to null, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                        refreshKey++
                        uiMessage = "Producto agregado"
                    }
                }, onCreateSale = { customer, product ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val saleId = UUID.randomUUID().toString()
                        val sale = com.cuentivo.app.data.local.SaleEntity(saleId, businessId, customer.customer.id, currentUserId, "V-${now}", java.time.LocalDate.now().toEpochDay(), null, product.salePriceCents, 0, product.salePriceCents, false, null, now, now)
                        val item = com.cuentivo.app.data.local.SaleItemEntity(UUID.randomUUID().toString(), saleId, product.id, product.name, 1, product.salePriceCents, 0, product.salePriceCents)
                        val charge = com.cuentivo.app.data.local.AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, saleId, null, "CHARGE", product.salePriceCents, "DEBIT", now, currentUserId, null, null)
                        database.financialDao().createSaleIdempotently(com.cuentivo.app.data.local.OperationEntity("sale-$saleId", "SALE", saleId, now), sale, listOf(item), charge)
                        syncQueue.enqueue(businessId, "SALE", sale.id, now, mapOf("businessId" to businessId, "customerId" to sale.customerId, "sellerUserId" to currentUserId, "saleNumber" to sale.saleNumber, "saleDateEpochDay" to sale.saleDateEpochDay, "dueDateEpochDay" to null, "subtotalCents" to sale.subtotalCents, "discountCents" to sale.discountCents, "totalCents" to sale.totalCents, "isCanceled" to false, "notes" to null, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                        syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, mapOf("saleId" to sale.id, "productId" to item.productId, "productNameSnapshot" to item.productNameSnapshot, "quantity" to item.quantity, "unitPriceCents" to item.unitPriceCents, "discountCents" to item.discountCents, "subtotalCents" to item.subtotalCents))
                        syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, mapOf("businessId" to businessId, "customerId" to charge.customerId, "saleId" to sale.id, "paymentId" to null, "type" to charge.type, "amountCents" to charge.amountCents, "direction" to charge.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                        refreshKey++
                        uiMessage = "Venta registrada"
                    }
                }, onCreatePayment = { customer, amountCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val paymentId = UUID.randomUUID().toString()
                        val payment = com.cuentivo.app.data.local.PaymentEntity(paymentId, businessId, customer.customer.id, amountCents, "CASH", java.time.LocalDate.now().toEpochDay(), null, null, currentUserId, now, null)
                        val movement = com.cuentivo.app.data.local.AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, null, paymentId, "PAYMENT", amountCents, "CREDIT", now, currentUserId, null, null)
                        database.financialDao().registerPaymentIdempotently(com.cuentivo.app.data.local.OperationEntity("payment-$paymentId", "PAYMENT", paymentId, now), payment, emptyList(), movement)
                        syncQueue.enqueue(businessId, "PAYMENT", payment.id, now, mapOf("businessId" to businessId, "customerId" to payment.customerId, "amountCents" to payment.amountCents, "paymentMethod" to payment.paymentMethod, "paymentDateEpochDay" to payment.paymentDateEpochDay, "reference" to null, "notes" to null, "createdByUserId" to currentUserId, "createdAtEpochMillis" to now, "canceledAtEpochMillis" to null))
                        syncQueue.enqueue(businessId, "MOVEMENT", movement.id, now, mapOf("businessId" to businessId, "customerId" to movement.customerId, "saleId" to null, "paymentId" to payment.id, "type" to movement.type, "amountCents" to movement.amountCents, "direction" to movement.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                        refreshKey++
                        uiMessage = "Abono registrado"
                    }
                }, statementCustomer = statementCustomer, statement = statementEntries, onShowStatement = { row ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        // The DAO returns newest-first; the statement needs chronological order
                        // because the CSV builder derives the running balance from it.
                        statementEntries = database.financialDao().customerMovements(businessId, row.customer.id)
                            .asReversed()
                            .map { movement ->
                                com.cuentivo.app.domain.StatementEntry(
                                    dateIso = java.time.Instant.ofEpochMilli(movement.occurredAtEpochMillis)
                                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                                    type = movement.type,
                                    direction = movement.direction,
                                    amountCents = movement.amountCents,
                                    notes = movement.notes,
                                )
                            }
                        statementCustomer = row.customer.name
                    }
                }, onDismissStatement = { statementCustomer = null }, onShareStatement = {
                    val name = statementCustomer
                    if (name != null) {
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Estado de cuenta - $name")
                            putExtra(android.content.Intent.EXTRA_TEXT, com.cuentivo.app.domain.CustomerStatementCsv.build(statementEntries))
                        }
                        startActivity(android.content.Intent.createChooser(share, "Compartir estado de cuenta"))
                    }
                }, message = uiMessage, onMessageShown = { uiMessage = null })
                }
            }
        }
    }
}
