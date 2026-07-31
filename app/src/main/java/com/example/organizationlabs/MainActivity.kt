package com.cuentivo.app

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.cuentivo.app.data.local.ProductImageStore
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
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.AppAlerts
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.EntitlementReconciler
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.PaidFeature
import com.cuentivo.app.domain.PaymentAllocator
import com.cuentivo.app.domain.ProductSheetParser
import com.cuentivo.app.domain.SheetImport
import com.cuentivo.app.domain.XlsxReader
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.CancelableItem
import com.cuentivo.app.ui.product.ImportStart
import com.cuentivo.app.ui.product.ProductImportPolicy
import com.cuentivo.app.ui.product.ProductImportRunner
import com.cuentivo.app.ui.CuentivoApp
import com.cuentivo.app.ui.CuentivoLoadingScreen
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.dashboard.DashboardViewModel
import com.cuentivo.app.auth.FirebaseAuthenticationGateway
import com.cuentivo.app.auth.LocalSessionGuard
import com.cuentivo.app.billing.BillingGateway
import com.cuentivo.app.billing.EntitlementStore
import com.cuentivo.app.sync.SyncScheduler
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.sync.FirebaseEmulators
import com.cuentivo.app.sync.OwnerMembershipBootstrap
import com.cuentivo.app.sync.SyncQueue

class MainActivity : ComponentActivity() {
    /**
     * The live plan, owned by the Activity because Play answers asynchronously and can change it at
     * any moment — a purchase made on another device shows up on the next refresh.
     */
    private val tierState = mutableStateOf(SubscriptionTier.FREE)
    private val billingMessageState = mutableStateOf<String?>(null)
    private var billingGateway: BillingGateway? = null

    /**
     * Play can confirm a purchase while the app is in the background, so re-read it on return.
     *
     * This is also what keeps a Pro subscriber's stored confirmation from going stale: it expires
     * after a day, and every visit to the app renews it.
     */
    override fun onResume() {
        super.onResume()
        billingGateway?.refresh(FirebaseAuthenticationGateway().currentUserId)
    }

    override fun onDestroy() {
        billingGateway?.close()
        super.onDestroy()
    }

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
        val dashboardRepository = DashboardRepository(database.businessDao(), database.financialDao())
        val sessionGuard = LocalSessionGuard(applicationContext, database)
        val authenticationGateway = FirebaseAuthenticationGateway()
        // Held as state rather than shown directly: Play answers whenever it wants, and the
        // composition that owns the Snackbar may not exist yet when it does.
        val entitlementStore = EntitlementStore(applicationContext)
        val billing = BillingGateway(
            context = applicationContext,
            onTierChanged = { tier, sessionUserId ->
                // Play and Firebase Auth move independently, so an answer can arrive before anyone
                // has signed in, or after the account changed. The reconciler decides whether this
                // reply still belongs to somebody; anything else is published as FREE and dropped.
                val decision = EntitlementReconciler.resolve(
                    reported = tier,
                    sessionUserId = sessionUserId,
                    currentUserId = authenticationGateway.currentUserId,
                )
                tierState.value = decision.publish
                if (decision.persist) {
                    // Stored against the uid, with the time Play confirmed it, so a Worker with no
                    // Activity alive can tell both whose entitlement it is and how old it is.
                    entitlementStore.remember(decision.userId, decision.publish)
                    // Getting Pro back drains whatever piled up while it was gone. Those rows were
                    // never deleted, only left unsent.
                    if (decision.publish == SubscriptionTier.PRO) SyncScheduler.enqueue(applicationContext)
                }
            },
            onMessage = { billingMessageState.value = it },
        )
        billingGateway = billing
        // Started for whoever is signed in right now — usually nobody on a cold start, in which case
        // the answer is deliberately discarded and asked again once somebody signs in.
        billing.start(authenticationGateway.currentUserId)
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
                var cancelables by remember { mutableStateOf<List<CancelableItem>>(emptyList()) }
                var blocked by remember { mutableStateOf<AccessDecision.Blocked?>(null) }
                // Kept after the import so the rejected rows can be listed: a snackbar saying "13 con
                // problemas" without saying which ones leaves the person with nothing to fix.
                var importReport by remember { mutableStateOf<SheetImport?>(null) }
                // Progress belongs here, not to the screen: three routes below never produce a
                // SheetImport, and a screen that waits for one stays stuck on "Importando…".
                var isImportingProducts by remember { mutableStateOf(false) }
                val productImages = remember { ProductImageStore(this@MainActivity) }
                // The real plan, straight from Google Play. Was pinned to FREE until Billing existed.
                val tier by tierState
                val billingMessage by billingMessageState
                LaunchedEffect(billingMessage) {
                    billingMessage?.let {
                        uiMessage = it
                        billingMessageState.value = null
                    }
                }

                // Nothing may read Room before the device has been claimed for this account: the
                // loaders below would otherwise hand back the previous person's business in the
                // instant before it gets wiped, which is the two-accounts-one-ledger bug.
                var sessionReady by remember(currentUserId) { mutableStateOf(false) }
                LaunchedEffect(currentUserId) {
                    // The effective session drops to FREE the instant the account changes, before
                    // anything else runs. Whatever Play said about the previous account stops
                    // applying immediately; then Play is asked again, now attributable to this uid.
                    // This is also the sign-in path, where the first answer arrived with no uid at
                    // all and was therefore discarded.
                    tierState.value = SubscriptionTier.FREE
                    billing.refresh(currentUserId)

                    if (sessionGuard.adopt(currentUserId)) {
                        uiMessage = "Este teléfono tenía datos de otra cuenta y se borraron"
                    }
                    // Backfills the owner membership for businesses created before it was published,
                    // whose queued rows are otherwise parked on PERMISSION_DENIED forever.
                    database.businessDao().firstOwnedBy(currentUserId)?.let { membershipBootstrap.ensure(it) }
                    sessionReady = true
                }
                if (!sessionReady) {
                    CuentivoLoadingScreen()
                } else {
                val viewModel = remember(currentUserId) {
                    // Bound to the signed-in owner, never to "the first business in the database".
                    DashboardViewModel { dashboardRepository.load(currentUserId) }
                }
                val state by produceState<DashboardUiState>(initialValue = DashboardUiState.NoBusiness, refreshKey) { value = viewModel.load() }
                val customers by produceState<List<CustomerBalanceRow>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.customerDao().activeWithBalances(it.businessId) } ?: emptyList()
                }
                val products by produceState<List<ProductEntity>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.productDao().searchActive(it.businessId, "", "") } ?: emptyList()
                }
                val businessName = (state as? DashboardUiState.Content)?.businessName ?: "Mi negocio"

                // Recomputed from the ledger on every refresh rather than stored: pay a sale and its
                // alert simply stops existing, with no row left anywhere to go stale.
                val alerts by produceState<List<Alert>>(initialValue = emptyList(), state, customers, tier, refreshKey) {
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    value = if (businessId == null) emptyList() else AppAlerts.from(
                        sales = database.financialDao().salesWithDueDate(businessId).map { row ->
                            DueSale(row.saleId, row.customerId, row.customerName, row.dueDateEpochDay, row.outstandingCents)
                        },
                        todayEpochDay = LocalDate.now().toEpochDay(),
                        tier = tier,
                        activeCustomers = customers.size,
                    )
                }

                // Statement and correctable items load together so the sheet stays consistent: a
                // canceled sale leaves the buttons in the same pass in which its compensating
                // movement appears in the list above.
                val loadStatement: suspend (String, CustomerBalanceRow) -> Unit = { businessId, row ->
                    val financialDao = database.financialDao()
                    // The DAO returns newest-first; the statement needs chronological order
                    // because the CSV builder derives the running balance from it.
                    statementEntries = financialDao.customerMovements(businessId, row.customer.id)
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
                    cancelables = financialDao.cancelableSalesForCustomer(businessId, row.customer.id)
                        .map { sale ->
                            CancelableItem(
                                id = sale.id,
                                kind = CancelableItem.Kind.SALE,
                                title = "Venta del ${LocalDate.ofEpochDay(sale.saleDateEpochDay)}",
                                detail = "${Money.format(sale.totalCents)} · debe ${Money.format(sale.outstandingCents)}",
                                amountCents = sale.outstandingCents,
                            )
                        } + financialDao.activePaymentsForCustomer(businessId, row.customer.id)
                        .map { payment ->
                            CancelableItem(
                                id = payment.id,
                                kind = CancelableItem.Kind.PAYMENT,
                                title = "Abono de ${Money.format(payment.amountCents)}",
                                detail = "Recibido el ${LocalDate.ofEpochDay(payment.paymentDateEpochDay)}",
                                amountCents = payment.amountCents,
                            )
                        }
                    statementCustomer = row
                }

                CuentivoApp(state, customers, products, tier, accountLabel = authenticationGateway.currentUserEmail, onCreateBusiness = {
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
                }, onEditCustomer = { row, name, phone ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        // Descriptive data, so it is edited in place — unlike anything financial.
                        // normalizedName is recomputed with the name it guards: leaving it stale
                        // would let a duplicate customer slip past the unique index.
                        val updated = row.customer.copy(
                            name = name,
                            normalizedName = CustomerNameNormalizer.key(name),
                            phone = phone,
                            updatedAtEpochMillis = now,
                        )
                        runCatching { database.customerDao().update(updated) }.onSuccess {
                            syncQueue.enqueue(businessId, "CUSTOMER", updated.id, now, mapOf("businessId" to businessId, "name" to updated.name, "normalizedName" to updated.normalizedName, "phone" to updated.phone, "email" to updated.email, "address" to updated.address, "notes" to updated.notes, "creditLimitCents" to updated.creditLimitCents, "isActive" to updated.isActive, "createdAtEpochMillis" to updated.createdAtEpochMillis, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to updated.deletedAtEpochMillis))
                            refreshKey++
                            uiMessage = "Cliente actualizado"
                        }.onFailure {
                            uiMessage = "Ya tienes otro cliente con ese nombre"
                        }
                    }
                }, onArchiveCustomer = { row ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        // Re-read the balance from the ledger instead of trusting the row the screen
                        // drew: it may be seconds old, and a sale registered meanwhile would be
                        // archived out of the totals.
                        val balanceCents = database.financialDao().customerBalanceCents(businessId, row.customer.id)
                        if (balanceCents != 0L) {
                            uiMessage = "${row.customer.name} tiene saldo de ${Money.format(balanceCents)}; no se puede eliminar"
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        database.customerDao().archive(row.customer.id, now)
                        // Soft delete, so it travels as an ordinary update: firestore.rules allows no
                        // delete from any client, in any collection.
                        val archived = row.customer.copy(isActive = false, deletedAtEpochMillis = now, updatedAtEpochMillis = now)
                        syncQueue.enqueue(businessId, "CUSTOMER", archived.id, now, mapOf("businessId" to businessId, "name" to archived.name, "normalizedName" to archived.normalizedName, "phone" to archived.phone, "email" to archived.email, "address" to archived.address, "notes" to archived.notes, "creditLimitCents" to archived.creditLimitCents, "isActive" to false, "createdAtEpochMillis" to archived.createdAtEpochMillis, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to now))
                        refreshKey++
                        uiMessage = "${row.customer.name} eliminado"
                    }
                }, onCreateProduct = { name, priceCents, imageFileName ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val product = ProductEntity(UUID.randomUUID().toString(), businessId, name, null, null, null, priceCents, null, null, imageFileName, true, now, now, null)
                        database.productDao().insert(product)
                        syncQueue.enqueue(businessId, "PRODUCT", product.id, now, mapOf("businessId" to businessId, "name" to name, "description" to null, "sku" to null, "category" to null, "salePriceCents" to priceCents, "costPriceCents" to null, "stock" to null, "imageUri" to imageFileName, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                        refreshKey++
                        uiMessage = "Producto agregado"
                    }
                }, onEditProduct = { product, name, priceCents, imageFileName ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        // Past sales keep the price they charged in their own item rows, so this
                        // only changes what future sales will offer.
                        val updated = product.copy(name = name, salePriceCents = priceCents, imageUri = imageFileName, updatedAtEpochMillis = now)
                        database.productDao().update(updated)
                        // Only now, and only if the row no longer references it: deleting earlier
                        // left imageUri pointing at a file that was already gone.
                        product.imageUri?.takeIf { it != imageFileName }?.let(productImages::delete)
                        syncQueue.enqueue(businessId, "PRODUCT", updated.id, now, mapOf("businessId" to businessId, "name" to updated.name, "description" to updated.description, "sku" to updated.sku, "category" to updated.category, "salePriceCents" to updated.salePriceCents, "costPriceCents" to updated.costPriceCents, "stock" to updated.stock, "imageUri" to updated.imageUri, "isActive" to updated.isActive, "createdAtEpochMillis" to updated.createdAtEpochMillis, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to updated.deletedAtEpochMillis))
                        refreshKey++
                        uiMessage = "Producto actualizado"
                    }
                }, onArchiveProduct = { product ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        database.productDao().archive(product.id, now)
                        // No balance guard here: sale_items keeps the name and price charged on each
                        // past sale, so removing the product from the catalog rewrites nothing.
                        val archived = product.copy(isActive = false, deletedAtEpochMillis = now, updatedAtEpochMillis = now)
                        syncQueue.enqueue(businessId, "PRODUCT", archived.id, now, mapOf("businessId" to businessId, "name" to archived.name, "description" to archived.description, "sku" to archived.sku, "category" to archived.category, "salePriceCents" to archived.salePriceCents, "costPriceCents" to archived.costPriceCents, "stock" to archived.stock, "imageUri" to archived.imageUri, "isActive" to false, "createdAtEpochMillis" to archived.createdAtEpochMillis, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to now))
                        refreshKey++
                        uiMessage = "${product.name} eliminado"
                    }
                }, onImportProducts = { uri ->
                    // Announced as part of Pro, so it is gated like every other paid feature. Until
                    // Billing exists `tier` is pinned to FREE above, so flip that one line to test it.
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    when (val start = ProductImportPolicy.start(businessId, FeatureAccessManager.canUse(tier, PaidFeature.SPREADSHEET))) {
                        ImportStart.NoBusiness -> Unit
                        is ImportStart.Denied -> blocked = start.decision
                        is ImportStart.Begin -> lifecycleScope.launch {
                            // The runner owns the flag: it raises it before the block and lowers it
                            // in a finally, so an unreadable file cannot strand "Importando…".
                            ProductImportRunner.runIfStarted(
                                start = start,
                                setProgress = { isImportingProducts = it },
                            ) { importBusinessId ->
                                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("No se pudo abrir el archivo")
                                val existing = database.productDao().searchActive(importBusinessId, "", "")
                                val existingNames = existing.map { CustomerNameNormalizer.key(it.name) }.toSet()
                                // Excel keeps its cells in a zip of XML; a CSV is just text. The
                                // zip signature decides, because a file picked from a cloud drive
                                // often arrives without a usable name or MIME type.
                                val isXlsx = bytes.size > 1 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                                val result = if (isXlsx) {
                                    ProductSheetParser.parse(XlsxReader.read(bytes), existingNames)
                                } else {
                                    ProductSheetParser.parseCsv(bytes.toString(Charsets.UTF_8), existingNames)
                                }
                                result.accepted.forEach { parsed ->
                                    val now = System.currentTimeMillis()
                                    val product = ProductEntity(UUID.randomUUID().toString(), importBusinessId, parsed.name, parsed.description, parsed.sku, parsed.category, parsed.priceCents, null, null, null, true, now, now, null)
                                    database.productDao().insert(product)
                                    syncQueue.enqueue(importBusinessId, "PRODUCT", product.id, now, mapOf("businessId" to importBusinessId, "name" to product.name, "description" to product.description, "sku" to product.sku, "category" to product.category, "salePriceCents" to product.salePriceCents, "costPriceCents" to null, "stock" to null, "imageUri" to null, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                                }
                                result
                            }?.onSuccess { result ->
                                refreshKey++
                                importReport = result
                                uiMessage = when {
                                    result.accepted.isEmpty() && result.rejected.isEmpty() ->
                                        "No se encontraron productos en ese archivo"
                                    result.rejected.isEmpty() -> "Se importaron ${result.accepted.size} productos"
                                    else -> "Se importaron ${result.accepted.size}; ${result.rejected.size} con problemas"
                                }
                            }?.onFailure {
                                uiMessage = "No fue posible leer ese archivo"
                            }
                        }
                    }
                }, isImportingProducts = isImportingProducts, importReport = importReport, onDismissImportReport = { importReport = null }, onShareImportTemplate = {
                    share("Plantilla de productos - Cuentivo", ProductSheetParser.TEMPLATE_CSV)
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
                }, statementCustomer = statementCustomer, statement = statementEntries, cancelables = cancelables, onCancelSale = { item ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        // The unpaid amount is read again here instead of trusting what the sheet
                        // showed: the DAO refuses a cancellation that does not match it exactly.
                        val sale = financialDao.saleForId(item.id)
                        val outstandingCents = sale?.let { it.totalCents - financialDao.activeAllocatedPaymentCents(it.id) }
                        if (sale == null || outstandingCents == null || outstandingCents <= 0) {
                            uiMessage = "Esa venta ya no se puede cancelar"
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val cancellation = AccountMovementEntity(UUID.randomUUID().toString(), businessId, sale.customerId, sale.id, null, "CANCELLATION", outstandingCents, "CREDIT", now, currentUserId, null, "Venta cancelada desde la app")
                        runCatching {
                            financialDao.cancelSaleIdempotently(OperationEntity("cancel-sale-${sale.id}", "SALE", sale.id, now), sale.id, cancellation)
                        }.onSuccess {
                            // Only the movement is queued: sales are create-only in firestore.rules,
                            // so the cancellation travels as its own ledger row, never as an edit.
                            syncQueue.enqueue(businessId, "MOVEMENT", cancellation.id, now, mapOf("businessId" to businessId, "customerId" to cancellation.customerId, "saleId" to sale.id, "paymentId" to null, "type" to cancellation.type, "amountCents" to cancellation.amountCents, "direction" to cancellation.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to cancellation.notes))
                            refreshKey++
                            loadStatement(businessId, row)
                            uiMessage = "Venta cancelada; se descontaron ${Money.format(outstandingCents)}"
                        }.onFailure {
                            uiMessage = it.message ?: "No fue posible cancelar la venta"
                        }
                    }
                }, onCancelPayment = { item ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        val payment = financialDao.paymentForId(item.id)
                        if (payment == null || payment.canceledAtEpochMillis != null) {
                            uiMessage = "Ese abono ya está cancelado"
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val refund = AccountMovementEntity(UUID.randomUUID().toString(), businessId, payment.customerId, null, payment.id, "REFUND", payment.amountCents, "DEBIT", now, currentUserId, null, "Abono cancelado desde la app")
                        runCatching {
                            financialDao.cancelPaymentIdempotently(OperationEntity("cancel-payment-${payment.id}", "PAYMENT", payment.id, now), payment.id, refund)
                        }.onSuccess {
                            syncQueue.enqueue(businessId, "MOVEMENT", refund.id, now, mapOf("businessId" to businessId, "customerId" to refund.customerId, "saleId" to null, "paymentId" to payment.id, "type" to refund.type, "amountCents" to refund.amountCents, "direction" to refund.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to refund.notes))
                            refreshKey++
                            loadStatement(businessId, row)
                            uiMessage = "Abono cancelado; la deuda subió ${Money.format(payment.amountCents)}"
                        }.onFailure {
                            uiMessage = it.message ?: "No fue posible cancelar el abono"
                        }
                    }
                }, onCallCustomer = { phone -> dial(phone) }, onShowStatement = { row ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch { loadStatement(businessId, row) }
                }, onDismissStatement = { statementCustomer = null; cancelables = emptyList() }, onShareStatementCsv = {
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
                }, alerts = alerts, onOpenAlertCustomer = { customerId ->
                    // The alert only knows who; the statement sheet needs the row with the balance,
                    // which the screen already has loaded.
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    val row = customers.firstOrNull { it.customer.id == customerId }
                    // loadStatement sets statementCustomer itself, so the sheet opens from here too.
                    if (businessId != null && row != null) {
                        lifecycleScope.launch { loadStatement(businessId, row) }
                    }
                }, blocked = blocked, onDismissBlocked = { blocked = null }, onUpgrade = {
                    blocked = null
                    // The real Activity, not applicationContext: Play needs somewhere to put its
                    // sheet. The uid travels with it so the purchase is credited to whoever opened
                    // the flow, even if the session changes before Play calls back.
                    billing.launchPurchase(this@MainActivity, currentUserId)
                }, onSignOut = {
                    authenticationGateway.signOut()
                    authenticatedUserId = null
                }, onDeleteAccount = {
                    // Local data goes first: if the account delete fails, the person is still signed
                    // in and can retry, rather than being locked out of rows that outlived them.
                    lifecycleScope.launch {
                        // clearAllTables() is a blocking Room call and Room refuses to run one on the
                        // main thread; lifecycleScope launches on Main, so this used to crash the app
                        // the moment the button was confirmed.
                        withContext(Dispatchers.IO) { database.clearAllTables() }
                        sessionGuard.forget()
                        // The entitlement dies with the account it belonged to; leaving it behind
                        // would hand Pro to whoever signs up next on this phone.
                        entitlementStore.forget()
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
    }

    /**
     * Opens the dialer with the number filled in. Deliberately ACTION_DIAL, not ACTION_CALL: the
     * latter needs the CALL_PHONE permission and would place the call without the owner seeing it.
     */
    private fun dial(phone: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
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
