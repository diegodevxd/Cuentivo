package com.cuentivo.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.app.LocaleManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cuentivo.app.settings.LanguagePreference
import java.util.Locale
import androidx.room.Room
import androidx.room.withTransaction
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import com.cuentivo.app.customer.CreditLimitSuggestionStore
import com.cuentivo.app.domain.CreditHistory
import com.cuentivo.app.domain.CreditLimitAdvisor
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
import com.cuentivo.app.domain.QuickSale
import com.cuentivo.app.domain.SaleLineSummary
import com.cuentivo.app.domain.SoldLine
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.CorrectionCandidate
import com.cuentivo.app.ui.SaleCorrection
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.ui.customer.CorrectionCandidatePresentation
import com.cuentivo.app.ui.customer.BalanceKind
import com.cuentivo.app.ui.customer.BalancePresenter
import com.cuentivo.app.ui.product.ImportStart
import com.cuentivo.app.ui.product.ProductImportPolicy
import com.cuentivo.app.ui.product.ProductImportRunner
import com.cuentivo.app.ui.CuentivoApp
import com.cuentivo.app.ui.CuentivoLoadingScreen
import com.cuentivo.app.ui.PaymentRefundFactory
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.dashboard.DashboardViewModel
import com.cuentivo.app.auth.FirebaseAuthenticationGateway
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.cuentivo.app.auth.LocalSessionGuard
import com.cuentivo.app.backup.BackupException
import com.cuentivo.app.backup.BackupFailure
import com.cuentivo.app.backup.BackupSection
import com.cuentivo.app.backup.DatabaseBackup
import com.cuentivo.app.billing.BillingGateway
import com.cuentivo.app.billing.EntitlementStore
import com.cuentivo.app.sync.SyncScheduler
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.ui.auth.EmailVerificationScreen
import com.cuentivo.app.domain.EmailVerificationAccess
import com.cuentivo.app.domain.EmailVerificationPolicy
import com.cuentivo.app.sync.AppCheckInstaller
import com.cuentivo.app.sync.FirebaseEmulators
import com.cuentivo.app.domain.CloudRestoreDecision
import com.cuentivo.app.domain.CloudRestorePolicy
import com.cuentivo.app.sync.CloudRestore
import com.cuentivo.app.sync.CloudRestoreResult
import com.cuentivo.app.sync.FirestoreSyncGateway
import com.cuentivo.app.sync.OwnerMembershipBootstrap
import com.cuentivo.app.sync.SyncQueue

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    /**
     * Below API 33, the private preference is applied before the Activity sees its resources. On
     * API 33+, LocaleManager owns the selection and this context mirrors its application locales;
     * the private preference is consulted only once for legacy migration when the platform choice
     * is empty.
     */
    override fun attachBaseContext(newBase: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            LanguagePreference.migrateLegacyToPlatformIfNeeded(newBase)
            val locales = newBase.getSystemService(LocaleManager::class.java)?.applicationLocales
            val wrapped = if (locales != null && !locales.isEmpty) {
                val configuration = Configuration(newBase.resources.configuration).apply { setLocales(locales) }
                newBase.createConfigurationContext(configuration)
            } else {
                newBase
            }
            super.attachBaseContext(wrapped)
            return
        }

        val tag = LanguagePreference.effectiveTag(newBase)
        val wrapped = if (tag == null) {
            newBase
        } else {
            val configuration = Configuration(newBase.resources.configuration)
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            newBase.createConfigurationContext(configuration)
        }
        super.attachBaseContext(wrapped)
    }

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

    /**
     * Relaunches the app after its database file was swapped underneath it.
     *
     * `exit(0)` rather than just recreating the Activity: a Worker or a Room connection created
     * before the restore would still be holding the file that no longer exists, and there is no
     * way to reach in and close them all from here.
     */
    private fun restartAfterRestore() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) startActivity(intent)
        finish()
        Runtime.getRuntime().exit(0)
    }

    override fun onDestroy() {
        billingGateway?.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before any Firestore or Auth call, so the first request already carries an attestation.
        AppCheckInstaller.installOnce()
        // Before any Firestore or Auth call: useEmulator throws once the instance has been used.
        FirebaseEmulators.connectIfEnabled()
        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        val syncQueue = SyncQueue(database.syncOperationDao(), applicationContext)
        val membershipBootstrap = OwnerMembershipBootstrap(database.businessDao(), database.syncOperationDao(), syncQueue)
        // Built here rather than lazily: it has to be ready before the "create a business" button can
        // be reached, since taking that offer is what strands the cloud copy under an unreachable id.
        val firestoreGateway = FirestoreSyncGateway()
        val cloudRestore = CloudRestore(database, firestoreGateway, firestoreGateway, membershipBootstrap)
        val dashboardRepository = DashboardRepository(database.businessDao(), database.financialDao())
        val sessionGuard = LocalSessionGuard(applicationContext, database)
        val authenticationGateway = FirebaseAuthenticationGateway()
        // Held as state rather than shown directly: Play answers whenever it wants, and the
        // composition that owns the Snackbar may not exist yet when it does.
        val entitlementStore = EntitlementStore(applicationContext)
        val creditLimitSuggestionStore = CreditLimitSuggestionStore(applicationContext)
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
                // Bumped whenever the account is re-read, so the access decision below recomposes
                // the moment somebody comes back from clicking the link in their inbox.
                var verificationKey by remember { mutableIntStateOf(0) }
                val emailAccess = remember(authenticatedUserId, verificationKey) {
                    EmailVerificationPolicy.access(
                        isSignedIn = authenticatedUserId != null,
                        isEmailVerified = authenticationGateway.isEmailVerified,
                        accountCreatedAtEpochMillis = authenticationGateway.accountCreatedAtEpochMillis,
                    )
                }
                if (authenticatedUserId == null) {
                    val signInFailedMessage = stringResource(R.string.auth_sign_in_failed)
                    val signUpFailedMessage = stringResource(R.string.auth_sign_up_failed)
                    val emailInUseMessage = stringResource(R.string.auth_email_in_use)
                    AuthenticationScreen(
                        isWorking = isAuthenticating,
                        message = authenticationMessage,
                        onSignIn = { email, password ->
                            isAuthenticating = true
                            authenticationMessage = null
                            authenticationGateway.signIn(email, password) { result ->
                                isAuthenticating = false
                                result.onSuccess { authenticatedUserId = it }
                                    .onFailure { authenticationMessage = signInFailedMessage }
                            }
                        },
                        onSignUp = { email, password ->
                            isAuthenticating = true
                            authenticationMessage = null
                            authenticationGateway.signUp(email, password) { result ->
                                isAuthenticating = false
                                result.onSuccess { userId ->
                                    // Sent straight away: the verification screen this lands on is
                                    // useless without the mail it tells people to go and open.
                                    authenticationGateway.sendEmailVerification { }
                                    authenticatedUserId = userId
                                    verificationKey++
                                }.onFailure { error ->
                                    // The one failure worth naming: "that address already has an
                                    // account, sign in instead" is actionable, and Firebase already
                                    // discloses it. Everything else stays generic.
                                    authenticationMessage =
                                        if (error is FirebaseAuthUserCollisionException) emailInUseMessage
                                        else signUpFailedMessage
                                }
                            }
                        },
                        onModeChange = { authenticationMessage = null },
                        onResetPassword = { email ->
                            // The result is deliberately ignored. The screen says the same thing
                            // either way, so a stranger cannot use this to discover who has an
                            // account here.
                            authenticationGateway.sendPasswordReset(email) { }
                        },
                    )
                } else if (emailAccess == EmailVerificationAccess.MUST_VERIFY) {
                    val stillPendingMessage = stringResource(R.string.verify_still_pending)
                    val resentMessage = stringResource(R.string.verify_resent)
                    val sendFailedMessage = stringResource(R.string.verify_send_failed)
                    EmailVerificationScreen(
                        email = authenticationGateway.currentUserEmail,
                        isWorking = isAuthenticating,
                        message = authenticationMessage,
                        onCheck = {
                            isAuthenticating = true
                            authenticationMessage = null
                            authenticationGateway.reloadUser { result ->
                                isAuthenticating = false
                                result.onSuccess { verified ->
                                    // Recomposes the decision either way: verified lets them in,
                                    // and not verified needs the message rather than silence.
                                    if (!verified) authenticationMessage = stillPendingMessage
                                    verificationKey++
                                }.onFailure { authenticationMessage = stillPendingMessage }
                            }
                        },
                        onResend = {
                            isAuthenticating = true
                            authenticationGateway.sendEmailVerification { result ->
                                isAuthenticating = false
                                authenticationMessage =
                                    if (result.isSuccess) resentMessage else sendFailedMessage
                            }
                        },
                        onUseAnotherAccount = {
                            authenticationGateway.signOut()
                            authenticationMessage = null
                            authenticatedUserId = null
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
                var saleCorrection by remember { mutableStateOf<SaleCorrection?>(null) }
                var isBackupWorking by remember { mutableStateOf(false) }
                var cancelables by remember { mutableStateOf<List<CorrectionCandidate>>(emptyList()) }
                var suggestedCreditLimitCents by remember { mutableStateOf<Long?>(null) }
                var blocked by remember { mutableStateOf<AccessDecision.Blocked?>(null) }
                // Kept after the import so the rejected rows can be listed: a snackbar saying "13 con
                // problemas" without saying which ones leaves the person with nothing to fix.
                var importReport by remember { mutableStateOf<SheetImport?>(null) }
                // Progress belongs here, not to the screen: three routes below never produce a
                // SheetImport, and a screen that waits for one stays stuck on "Importando…".
                var isImportingProducts by remember { mutableStateOf(false) }
                // Own the create dialogs' open/close-on-success and their double-tap guard here,
                // not in the screen: the screen cannot tell a real failure from a tap that simply
                // has not finished yet, and closing on the tap is exactly what let a save fail in
                // total silence.
                var isSavingCustomer by remember { mutableStateOf(false) }
                var customerSavedToken by remember { mutableIntStateOf(0) }
                var isSavingProduct by remember { mutableStateOf(false) }
                var productSavedToken by remember { mutableIntStateOf(0) }
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

                // Restoring is keyed on the tier, not on sign-in, because Play answers asynchronously
                // and the session above deliberately starts at FREE. The moment PRO is confirmed for
                // this uid, an empty device goes looking for the business the account already owns.
                var restoreAttempted by remember(currentUserId) { mutableStateOf(false) }
                var isRestoring by remember(currentUserId) { mutableStateOf(false) }
                LaunchedEffect(currentUserId, tier, sessionReady) {
                    if (!sessionReady) return@LaunchedEffect
                    val decision = CloudRestorePolicy.decide(
                        tier = tier,
                        hasLocalBusiness = database.businessDao().firstOwnedBy(currentUserId) != null,
                        alreadyAttempted = restoreAttempted,
                    )
                    if (decision != CloudRestoreDecision.Attempt) return@LaunchedEffect

                    restoreAttempted = true
                    isRestoring = true
                    // The loading screen stays up for the whole download so the "create a business"
                    // button is never reachable while its data is still on its way back.
                    when (val result = cloudRestore.restore(currentUserId)) {
                        is CloudRestoreResult.Restored -> {
                            uiMessage = "Se restauraron ${result.customers} clientes de la nube"
                            refreshKey++
                        }
                        // Nothing in the cloud is the normal path for a new Pro account: say nothing
                        // and let them create their business.
                        CloudRestoreResult.NothingInCloud -> Unit
                        is CloudRestoreResult.Failed ->
                            uiMessage = "No se pudo recuperar tu información de la nube. Revisa tu conexión y vuelve a abrir la app"
                    }
                    isRestoring = false
                }

                if (!sessionReady || isRestoring) {
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
                val verifyAlertTitle = stringResource(R.string.verify_pending_alert_title)
                val verifyAlertDetail = stringResource(R.string.verify_pending_alert_detail)
                // Accounts that predate verification keep working, but they are told every time
                // they open the alerts: an address nobody proved exists cannot be mailed a password
                // reset, and their ledger lives on this phone alone.
                val verificationAlert = if (emailAccess == EmailVerificationAccess.ALLOWED_WITH_WARNING) {
                    Alert(verifyAlertTitle, verifyAlertDetail, Alert.Severity.INFO)
                } else {
                    null
                }
                val alerts by produceState<List<Alert>>(initialValue = emptyList(), state, customers, tier, refreshKey, verificationAlert) {
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    value = listOfNotNull(verificationAlert) + if (businessId == null) emptyList() else AppAlerts.from(
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
                    // One read for every line this customer was ever sold, then grouped in memory:
                    // a charge that only says an amount is unreadable once it reaches WhatsApp.
                    val soldLines = financialDao.soldLinesForCustomer(businessId, row.customer.id)
                        .groupBy { it.saleId }
                        .mapValues { (_, lines) ->
                            SaleLineSummary.describe(lines.map { SoldLine(it.productNameSnapshot, it.quantity) })
                        }
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
                                paymentId = movement.paymentId,
                                saleId = movement.saleId,
                                detail = movement.saleId?.let(soldLines::get),
                            )
                        }
                    val sales = financialDao.cancelableSalesForCustomer(businessId, row.customer.id)
                        .map { sale ->
                            CorrectionCandidate.Sale(
                                id = sale.id,
                                saleNumber = sale.saleNumber,
                                saleDateEpochDay = sale.saleDateEpochDay,
                                totalCents = sale.totalCents,
                                activePaidCents = sale.totalCents - sale.outstandingCents,
                                outstandingCents = sale.outstandingCents,
                            )
                        }
                    val payments = CorrectionCandidatePresentation.paymentCandidates(
                        financialDao.correctionPaymentsForCustomer(businessId, row.customer.id),
                    )
                    cancelables = sales + payments
                    statementCustomer = row
                    // "Cumplido en los últimos 6 meses", not "cumplido alguna vez": a payment that
                    // was late a year ago should stop weighing against the customer eventually.
                    val sinceEpochDay = LocalDate.now().minusMonths(6).toEpochDay()
                    val history = CreditHistory(
                        settledSaleCount = financialDao.settledSaleCount(businessId, row.customer.id),
                        hasCanceledSale = financialDao.hasCanceledSale(businessId, row.customer.id),
                        hasLatePayment = financialDao.hasLatePayment(businessId, row.customer.id, sinceEpochDay),
                        largestSaleTotalCents = financialDao.largestSaleTotalCents(businessId, row.customer.id),
                    )
                    val suggestion = CreditLimitAdvisor.suggest(row.customer.creditLimitCents, history)
                    suggestedCreditLimitCents = suggestion?.takeUnless {
                        creditLimitSuggestionStore.isDismissed(row.customer.id, it)
                    }
                }

                CuentivoApp(state, customers, products, tier, accountLabel = authenticationGateway.currentUserEmail, isSavingCustomer = isSavingCustomer, customerSavedToken = customerSavedToken, isSavingProduct = isSavingProduct, productSavedToken = productSavedToken, onCreateBusiness = {
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val business = BusinessEntity(UUID.randomUUID().toString(), "Mi negocio", currentUserId, "MXN", "America/Mexico_City", now, now)
                        database.businessDao().insert(business)
                        syncQueue.enqueue(business.id, "BUSINESS", business.id, now, mapOf("name" to business.name, "ownerUserId" to business.ownerUserId, "currency" to business.currency, "timezone" to business.timezone, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                        membershipBootstrap.ensure(business)
                        refreshKey++
                        uiMessage = "Negocio creado"
                    }
                }, onCreateCustomer = { name, phone, creditLimitCents, quickSale ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    if (businessId == null) {
                        // The old return@CuentivoApp here was silent: the form had already closed
                        // on the tap, and nothing told the person why nothing happened.
                        uiMessage = "Primero crea tu negocio en Inicio"
                        return@CuentivoApp
                    }
                    // Plan limit checked before writing: refusing after the insert would leave a
                    // customer the app then pretends does not exist.
                    // Both limits before writing anything. Checking the sale limit afterwards
                    // would create the customer and then refuse their purchase, leaving the person
                    // with half the job done and no way to tell which half.
                    when (val decision = FeatureAccessManager.canAddCustomer(tier, customers.size)) {
                        is AccessDecision.Blocked -> blocked = decision
                        AccessDecision.Allowed -> lifecycleScope.launch {
                            isSavingCustomer = true
                            try {
                                // The sale limit too, and still before the first write: refusing
                                // afterwards would create the customer and then reject what they
                                // walked out with, leaving half the job done.
                                if (quickSale != null) {
                                    val salesSoFar = database.financialDao().registeredSaleCount(businessId)
                                    val saleDecision = FeatureAccessManager.canRegisterSale(tier, salesSoFar)
                                    if (saleDecision is AccessDecision.Blocked) {
                                        blocked = saleDecision
                                        return@launch
                                    }
                                }
                                val now = System.currentTimeMillis()
                                val customer = CustomerEntity(UUID.randomUUID().toString(), businessId, name, CustomerNameNormalizer.key(name), phone, null, null, null, creditLimitCents, true, now, now, null)
                                // The insert is the step that may legitimately refuse: the unique
                                // index on (businessId, normalizedName) rejects a name that already
                                // exists once accents and case are folded away. Editing already
                                // reported that; creating used to let the exception kill the
                                // coroutine, so the screen neither refreshed nor said anything.
                                // The product this customer walked out with, if any. Reused from
                                // the catalog when the name already exists there: `products` has no
                                // unique index, so without this every new customer taking a "Jabón"
                                // would mint another one.
                                val existingProduct = quickSale?.let { sale ->
                                    val key = CustomerNameNormalizer.key(sale.productName)
                                    products.firstOrNull { CustomerNameNormalizer.key(it.name) == key }
                                }
                                val newProduct = quickSale?.takeIf { existingProduct == null }?.let { sale ->
                                    ProductEntity(UUID.randomUUID().toString(), businessId, sale.productName, null, null, null, sale.unitPriceCents, null, null, sale.photoFileName, true, now, now, null)
                                }
                                val product = existingProduct ?: newProduct
                                val saleId = UUID.randomUUID().toString()
                                val saleItem = quickSale?.let { sale ->
                                    SaleItemEntity(UUID.randomUUID().toString(), saleId, product?.id, sale.productName, sale.quantity.toLong(), sale.unitPriceCents, 0L, sale.totalCents)
                                }
                                val saleEntity = quickSale?.let { sale ->
                                    SaleEntity(saleId, businessId, customer.id, currentUserId, "V-${now}", LocalDate.now().toEpochDay(), null, sale.totalCents, 0L, sale.totalCents, false, null, now, now)
                                }
                                val charge = quickSale?.let { sale ->
                                    AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.id, saleId, null, "CHARGE", sale.totalCents, "DEBIT", now, currentUserId, null, null)
                                }
                                val saved = try {
                                    // One transaction: a customer who exists without the sale they
                                    // walked out with is worse than nothing happening at all.
                                    database.withTransaction {
                                        database.customerDao().insert(customer)
                                        newProduct?.let { database.productDao().insert(it) }
                                        if (saleEntity != null && charge != null && saleItem != null) {
                                            database.financialDao().createSaleIdempotently(
                                                OperationEntity("sale-$saleId", "SALE", saleId, now),
                                                saleEntity,
                                                listOf(saleItem),
                                                charge,
                                            )
                                        }
                                    }
                                    true
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    Log.w(TAG, "No se pudo guardar el cliente.", error)
                                    uiMessage = "Ya tienes otro cliente con ese nombre"
                                    false
                                }
                                if (saved) {
                                    // Past here the customer exists on this device, so nothing after
                                    // it may hide it. The outbox write is best effort relative to a
                                    // row that is already saved.
                                    try {
                                        syncQueue.enqueue(businessId, "CUSTOMER", customer.id, now, mapOf("businessId" to businessId, "name" to customer.name, "normalizedName" to customer.normalizedName, "phone" to phone, "email" to null, "address" to null, "notes" to null, "creditLimitCents" to creditLimitCents, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Exception) {
                                        Log.w(TAG, "El cliente quedó guardado pero no se pudo encolar para sincronizar.", error)
                                    }
                                    // Best effort, like the customer row above: these are already
                                    // committed locally and nothing here may hide them.
                                    try {
                                        newProduct?.let { created ->
                                            syncQueue.enqueue(businessId, "PRODUCT", created.id, now, mapOf("businessId" to businessId, "name" to created.name, "description" to null, "sku" to null, "category" to null, "salePriceCents" to created.salePriceCents, "costPriceCents" to null, "stock" to null, "imageUri" to created.imageUri, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                                        }
                                        if (saleEntity != null && charge != null && saleItem != null) {
                                            syncQueue.enqueue(businessId, "SALE", saleEntity.id, now, mapOf("businessId" to businessId, "customerId" to customer.id, "sellerUserId" to currentUserId, "saleNumber" to saleEntity.saleNumber, "saleDateEpochDay" to saleEntity.saleDateEpochDay, "dueDateEpochDay" to null, "subtotalCents" to saleEntity.subtotalCents, "discountCents" to saleEntity.discountCents, "totalCents" to saleEntity.totalCents, "isCanceled" to false, "notes" to null, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                                            syncQueue.enqueue(businessId, "SALE_ITEM", saleItem.id, now, mapOf("saleId" to saleEntity.id, "productId" to saleItem.productId, "productNameSnapshot" to saleItem.productNameSnapshot, "quantity" to saleItem.quantity, "unitPriceCents" to saleItem.unitPriceCents, "discountCents" to saleItem.discountCents, "subtotalCents" to saleItem.subtotalCents))
                                            syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, mapOf("businessId" to businessId, "customerId" to customer.id, "saleId" to saleEntity.id, "paymentId" to null, "type" to charge.type, "amountCents" to charge.amountCents, "direction" to charge.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                                        }
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Exception) {
                                        Log.w(TAG, "La venta rápida quedó guardada pero no se pudo encolar.", error)
                                    }
                                    refreshKey++
                                    uiMessage = quickSale?.let {
                                        getString(R.string.message_quick_sale_created, Money.format(it.totalCents))
                                    } ?: "Cliente agregado"
                                    // The only signal that closes the form. It advances only here,
                                    // so a failure above leaves it untouched and the dialog stays up.
                                    customerSavedToken++
                                }
                            } finally {
                                isSavingCustomer = false
                            }
                        }
                    }
                }, onEditCustomer = { row, name, phone, creditLimitCents ->
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
                            creditLimitCents = creditLimitCents,
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
                            val presentation = BalancePresenter.from(balanceCents)
                            uiMessage = getString(
                                if (presentation.kind == BalanceKind.CUSTOMER_CREDIT) {
                                    com.cuentivo.app.R.string.customers_archive_has_credit
                                } else {
                                    com.cuentivo.app.R.string.customers_archive_has_balance
                                },
                                row.customer.name,
                                Money.format(presentation.amountCents),
                            )
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
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    if (businessId == null) {
                        uiMessage = "Primero crea tu negocio en Inicio"
                        return@CuentivoApp
                    }
                    lifecycleScope.launch {
                        isSavingProduct = true
                        try {
                            val now = System.currentTimeMillis()
                            val product = ProductEntity(UUID.randomUUID().toString(), businessId, name, null, null, null, priceCents, null, null, imageFileName, true, now, now, null)
                            // Same shape as creating a customer, and it had the same hole: a
                            // scheduling failure after the insert used to skip the refresh and the
                            // message.
                            val saved = try {
                                database.productDao().insert(product)
                                true
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                Log.w(TAG, "No se pudo guardar el producto.", error)
                                uiMessage = "No fue posible guardar el producto"
                                false
                            }
                            if (saved) {
                                try {
                                    syncQueue.enqueue(businessId, "PRODUCT", product.id, now, mapOf("businessId" to businessId, "name" to name, "description" to null, "sku" to null, "category" to null, "salePriceCents" to priceCents, "costPriceCents" to null, "stock" to null, "imageUri" to imageFileName, "isActive" to true, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to null))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    Log.w(TAG, "El producto quedó guardado pero no se pudo encolar para sincronizar.", error)
                                }
                                refreshKey++
                                uiMessage = "Producto agregado"
                                productSavedToken++
                            }
                        } finally {
                            isSavingProduct = false
                        }
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
                }, isImportingProducts = isImportingProducts, importReport = importReport, onDismissImportReport = { importReport = null },
                // Asked before the file picker opens, not after: the paywall used to appear once
                // the person had already gone and found their spreadsheet.
                canImportProducts = FeatureAccessManager.canUse(tier, PaidFeature.SPREADSHEET) is AccessDecision.Allowed,
                onImportBlocked = {
                    blocked = FeatureAccessManager.canUse(tier, PaidFeature.SPREADSHEET) as? AccessDecision.Blocked
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
                                uiMessage = getString(R.string.message_sale_registered, Money.format(draft.totalCents))
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
                             allocations.isEmpty() -> getString(R.string.message_payment_registered_as_credit)
                             allocationResult.creditCents > 0 -> getString(
                                 R.string.message_payment_applied_with_credit,
                                 allocations.size,
                                 Money.format(allocationResult.creditCents),
                             )
                             else -> getString(R.string.message_payment_applied, allocations.size)
                         }
                    }
                }, statementCustomer = statementCustomer, statement = statementEntries, cancelables = cancelables, suggestedCreditLimitCents = suggestedCreditLimitCents, onCancelSale = { item ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        // The unpaid amount is read again here instead of trusting what the sheet
                        // showed: the DAO refuses a cancellation that does not match it exactly.
                        val sale = financialDao.saleForId(item.id)
                        val outstandingCents = sale?.let { it.totalCents - financialDao.activeAllocatedPaymentCents(it.id) }
                        if (sale == null || outstandingCents == null || outstandingCents <= 0) {
                            uiMessage = getString(R.string.message_sale_not_cancelable)
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
                            uiMessage = getString(R.string.message_sale_canceled, Money.format(outstandingCents))
                        }.onFailure {
                            uiMessage = getString(R.string.message_sale_cancel_failed)
                        }
                    }
                }, saleCorrection = saleCorrection, onDismissCorrection = { saleCorrection = null }, onEditSale = { item ->
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        val sale = financialDao.saleForId(item.id)
                        // Read again rather than trusting the row on screen: correcting a sale
                        // cancels it, and the DAO refuses a cancellation with nothing left unpaid.
                        val outstandingCents = sale?.let { it.totalCents - financialDao.activeAllocatedPaymentCents(it.id) }
                        if (sale == null || sale.isCanceled || outstandingCents == null || outstandingCents <= 0) {
                            uiMessage = getString(R.string.message_sale_not_editable)
                            return@launch
                        }
                        val soldItems = financialDao.saleItemsForSale(sale.id)
                        // Sales imported from the old web app carry no product ids, and a draft line
                        // cannot be rebuilt without one. Better to say so than to invent a product.
                        if (soldItems.isEmpty() || soldItems.any { it.productId == null }) {
                            uiMessage = getString(R.string.message_sale_legacy_not_editable)
                            return@launch
                        }
                        saleCorrection = SaleCorrection(
                            saleId = sale.id,
                            saleNumber = sale.saleNumber,
                            draft = SaleDraft(
                                lines = soldItems.map { soldItem ->
                                    SaleDraftLine(
                                        productId = requireNotNull(soldItem.productId),
                                        productName = soldItem.productNameSnapshot,
                                        quantity = soldItem.quantity.toInt(),
                                        unitPriceCents = soldItem.unitPriceCents,
                                        discountCents = soldItem.discountCents,
                                    )
                                },
                                discountCents = sale.discountCents,
                                dueDateEpochDay = sale.dueDateEpochDay,
                            ),
                        )
                    }
                }, onSaveCorrection = { draft ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val correction = saleCorrection ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        val previous = financialDao.saleForId(correction.saleId)
                        if (previous == null || previous.isCanceled) {
                            uiMessage = getString(R.string.message_sale_correct_failed)
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val saleId = UUID.randomUUID().toString()
                        // The corrected sale keeps the original sale date and customer: it is the
                        // same sale written right, not a new one made today for someone else.
                        val sale = SaleEntity(saleId, businessId, previous.customerId, currentUserId, "V-${now}", previous.saleDateEpochDay, draft.dueDateEpochDay, draft.subtotalCents, draft.discountCents, draft.totalCents, false, null, now, now)
                        val items = draft.lines.map { line ->
                            SaleItemEntity(UUID.randomUUID().toString(), saleId, line.productId, line.productName, line.quantity.toLong(), line.unitPriceCents, line.discountCents, line.subtotalCents)
                        }
                        val charge = AccountMovementEntity(UUID.randomUUID().toString(), businessId, previous.customerId, saleId, null, "CHARGE", draft.totalCents, "DEBIT", now, currentUserId, null, null)
                        val cancellation = AccountMovementEntity(UUID.randomUUID().toString(), businessId, previous.customerId, previous.id, null, "CANCELLATION", previous.totalCents, "CREDIT", now, currentUserId, null, "Venta corregida desde la app")
                        // What was already collected follows the corrected sale, up to its new total.
                        // Anything above it stays as credit on the customer's balance.
                        var remainingCents = draft.totalCents
                        val carriedAllocations = financialDao.activeAllocationsForSale(previous.id).mapNotNull { allocation ->
                            val amountCents = minOf(allocation.amountCents, remainingCents)
                            if (amountCents <= 0) return@mapNotNull null
                            remainingCents -= amountCents
                            PaymentAllocationEntity(UUID.randomUUID().toString(), allocation.paymentId, saleId, amountCents)
                        }
                        runCatching {
                            financialDao.replaceSaleIdempotently(
                                OperationEntity("replace-sale-$saleId", "SALE", saleId, now),
                                previous.id,
                                cancellation,
                                sale,
                                items,
                                charge,
                                carriedAllocations,
                            )
                        }.onSuccess {
                            syncQueue.enqueue(businessId, "SALE", sale.id, now, mapOf("businessId" to businessId, "customerId" to sale.customerId, "sellerUserId" to currentUserId, "saleNumber" to sale.saleNumber, "saleDateEpochDay" to sale.saleDateEpochDay, "dueDateEpochDay" to sale.dueDateEpochDay, "subtotalCents" to sale.subtotalCents, "discountCents" to sale.discountCents, "totalCents" to sale.totalCents, "isCanceled" to false, "notes" to null, "createdAtEpochMillis" to now, "updatedAtEpochMillis" to now))
                            items.forEach { item ->
                                syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, mapOf("saleId" to sale.id, "productId" to item.productId, "productNameSnapshot" to item.productNameSnapshot, "quantity" to item.quantity, "unitPriceCents" to item.unitPriceCents, "discountCents" to item.discountCents, "subtotalCents" to item.subtotalCents))
                            }
                            syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, mapOf("businessId" to businessId, "customerId" to charge.customerId, "saleId" to sale.id, "paymentId" to null, "type" to charge.type, "amountCents" to charge.amountCents, "direction" to charge.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to null))
                            // The cancellation travels as its own ledger row: sales are create-only
                            // in firestore.rules, so the void can never go up as an edit.
                            syncQueue.enqueue(businessId, "MOVEMENT", cancellation.id, now, mapOf("businessId" to businessId, "customerId" to cancellation.customerId, "saleId" to previous.id, "paymentId" to null, "type" to cancellation.type, "amountCents" to cancellation.amountCents, "direction" to cancellation.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to cancellation.notes))
                            carriedAllocations.forEach { allocation ->
                                syncQueue.enqueue(businessId, "PAYMENT_ALLOCATION", allocation.id, now, mapOf("paymentId" to allocation.paymentId, "saleId" to allocation.saleId, "amountCents" to allocation.amountCents))
                            }
                            saleCorrection = null
                            refreshKey++
                            loadStatement(businessId, row)
                            uiMessage = getString(R.string.message_sale_corrected)
                        }.onFailure {
                            uiMessage = getString(R.string.message_sale_correct_failed)
                        }
                    }
                }, backupSection = {
                    val exportDone = stringResource(R.string.backup_export_done)
                    val exportFailed = stringResource(R.string.backup_export_failed)
                    val restoreFailed = stringResource(R.string.backup_restore_failed)
                    val notABackup = stringResource(R.string.backup_restore_not_a_backup)
                    val newerApp = stringResource(R.string.backup_restore_newer_app)
                    val wrongPassword = stringResource(R.string.backup_restore_wrong_password)
                    BackupSection(
                        isWorking = isBackupWorking,
                        onExport = { uri, password ->
                            lifecycleScope.launch {
                                isBackupWorking = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val bytes = DatabaseBackup.export(applicationContext, database, password.toCharArray())
                                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                            ?: error("El destino del respaldo no se pudo abrir")
                                    }
                                    uiMessage = exportDone
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    // No detail beyond the type: the message could name a file the
                                    // person chose, and logs must stay free of anything personal.
                                    Log.w(TAG, "No se pudo crear el respaldo: ${error.javaClass.simpleName}")
                                    uiMessage = exportFailed
                                } finally {
                                    isBackupWorking = false
                                }
                            }
                        },
                        onRestore = { uri, password ->
                            lifecycleScope.launch {
                                isBackupWorking = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            ?: error("El respaldo no se pudo abrir")
                                        DatabaseBackup.restore(applicationContext, database, bytes, password.toCharArray())
                                    }
                                    // Past this point the file on disk is somebody else's database
                                    // and every open connection points at the old one. Restarting is
                                    // not politeness, it is the only safe next step.
                                    restartAfterRestore()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: BackupException) {
                                    uiMessage = when (error.failure) {
                                        BackupFailure.NOT_A_BACKUP -> notABackup
                                        BackupFailure.NEWER_APP -> newerApp
                                        BackupFailure.WRONG_PASSWORD_OR_DAMAGED -> wrongPassword
                                    }
                                } catch (error: Exception) {
                                    Log.w(TAG, "No se pudo restaurar: ${error.javaClass.simpleName}")
                                    uiMessage = restoreFailed
                                } finally {
                                    isBackupWorking = false
                                }
                            }
                        },
                    )
                }, onRemindCustomer = { customerId ->
                    // The same statement text the detail sheet shares, minus the movement list: a
                    // reminder is "you owe this much", not a full account history.
                    val row = customers.firstOrNull { it.customer.id == customerId }
                    if (row == null) {
                        uiMessage = getString(com.cuentivo.app.R.string.detail_payment_not_found)
                    } else {
                        share(
                            row.customer.name,
                            DebtorShareText.forCustomer(businessName, row.customer.name, row.balanceCents),
                        )
                    }
                }, onCancelPayment = { item ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val financialDao = database.financialDao()
                        val current = financialDao.correctionPaymentsForCustomer(businessId, row.customer.id)
                            .firstOrNull { it.id == item.id }
                        if (current == null) {
                            uiMessage = getString(com.cuentivo.app.R.string.detail_payment_not_found)
                            return@launch
                        }
                        if (current.blockedByCanceledSale) {
                            uiMessage = getString(com.cuentivo.app.R.string.detail_payment_cancel_blocked)
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val refund = PaymentRefundFactory.create(
                            row = current,
                            movementId = UUID.randomUUID().toString(),
                            occurredAtEpochMillis = now,
                            createdByUserId = currentUserId,
                        )
                        runCatching {
                            financialDao.cancelPaymentIdempotently(OperationEntity("cancel-payment-${current.id}", "PAYMENT", current.id, now), current.id, refund)
                        }.onSuccess {
                            syncQueue.enqueue(businessId, "MOVEMENT", refund.id, now, mapOf("businessId" to businessId, "customerId" to refund.customerId, "saleId" to null, "paymentId" to current.id, "type" to refund.type, "amountCents" to refund.amountCents, "direction" to refund.direction, "occurredAtEpochMillis" to now, "createdByUserId" to currentUserId, "reversesMovementId" to null, "notes" to refund.notes))
                            refreshKey++
                            loadStatement(businessId, row)
                            uiMessage = getString(R.string.message_payment_canceled, Money.format(current.amountCents))
                        }.onFailure {
                            uiMessage = getString(com.cuentivo.app.R.string.detail_payment_guard_failed)
                        }
                    }
                }, onCallCustomer = { phone -> dial(phone) }, onShowStatement = { row ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch { loadStatement(businessId, row) }
                }, onDismissStatement = { statementCustomer = null; cancelables = emptyList(); suggestedCreditLimitCents = null }, onAcceptCreditLimitSuggestion = { newLimitCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    val row = statementCustomer ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val updated = row.customer.copy(creditLimitCents = newLimitCents, updatedAtEpochMillis = now)
                        runCatching { database.customerDao().update(updated) }.onSuccess {
                            syncQueue.enqueue(businessId, "CUSTOMER", updated.id, now, mapOf("businessId" to businessId, "name" to updated.name, "normalizedName" to updated.normalizedName, "phone" to updated.phone, "email" to updated.email, "address" to updated.address, "notes" to updated.notes, "creditLimitCents" to updated.creditLimitCents, "isActive" to updated.isActive, "createdAtEpochMillis" to updated.createdAtEpochMillis, "updatedAtEpochMillis" to now, "deletedAtEpochMillis" to updated.deletedAtEpochMillis))
                            refreshKey++
                            suggestedCreditLimitCents = null
                            uiMessage = getString(R.string.message_credit_limit_updated, row.customer.name, Money.format(newLimitCents))
                        }
                    }
                }, onDismissCreditLimitSuggestion = { suggestedCents ->
                    statementCustomer?.let { row -> creditLimitSuggestionStore.dismiss(row.customer.id, suggestedCents) }
                    suggestedCreditLimitCents = null
                }, onShareStatementCsv = {
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
                                uiMessage = getString(R.string.message_account_delete_failed)
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
