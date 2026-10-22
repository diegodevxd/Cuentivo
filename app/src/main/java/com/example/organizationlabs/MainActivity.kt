package com.cuentivo.app

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.os.Build
import android.app.LocaleManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import com.cuentivo.app.customer.CreditLimitSuggestionStore
import com.cuentivo.app.domain.BusinessName
import com.cuentivo.app.domain.CreditHistory
import com.cuentivo.app.domain.CreditLimitAdvisor
import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.domain.CustomerSaleHistory
import com.cuentivo.app.domain.CustomerScore
import com.cuentivo.app.domain.CustomerScoreCalculator
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
import com.cuentivo.app.ai.AiBusinessQueryResolver
import com.cuentivo.app.ai.AiPaymentExecutionResult
import com.cuentivo.app.ai.AiPaymentExecutionService
import com.cuentivo.app.ai.AiPaymentPreviewResolver
import com.cuentivo.app.ai.AiSaleExecutionResult
import com.cuentivo.app.ai.AiSaleExecutionService
import com.cuentivo.app.ai.AiSalePreviewResolver
import com.cuentivo.app.domain.CustomerRepository
import com.cuentivo.app.domain.PaymentRepository
import com.cuentivo.app.domain.ProductRepository
import com.cuentivo.app.domain.SaleRepository
import com.cuentivo.app.data.local.AiDailyChatRepository
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
import com.cuentivo.app.domain.OpeningBalance
import com.cuentivo.app.domain.OpeningBalancePlanner
import com.cuentivo.app.domain.SaleLineSummary
import com.cuentivo.app.domain.SoldLine
import com.cuentivo.app.domain.StatementCustomerRefresh
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.StatementImage
import com.cuentivo.app.domain.BusinessClaim
import com.cuentivo.app.domain.BusinessClaimPolicy
import com.cuentivo.app.domain.DefaultBusinessCreationPolicy
import com.cuentivo.app.domain.DefaultBusinessDecision
import com.cuentivo.app.BuildConfig
import com.cuentivo.app.domain.ShareTier
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.domain.SyncEntitlement
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
import com.cuentivo.app.ui.CuentivoClaimBlockedScreen
import com.cuentivo.app.ui.CuentivoLoadingScreen
import com.cuentivo.app.ui.PaymentRefundFactory
import com.cuentivo.app.ui.share.StatementImageShareStore
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.dashboard.DashboardViewModel
import com.cuentivo.app.auth.FirebaseAuthenticationGateway
import com.cuentivo.app.auth.GoogleAccountSwitchAction
import com.cuentivo.app.auth.GoogleAuthenticationResult
import com.cuentivo.app.notifications.CollectionReminderScheduler
import com.cuentivo.app.observability.CrashEvent
import com.cuentivo.app.observability.CrashReporterFactory
import com.cuentivo.app.settings.CollectionReminderPreferences
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.cuentivo.app.auth.LocalSessionGuard
import com.cuentivo.app.backup.BackupException
import com.cuentivo.app.backup.BackupFailure
import com.cuentivo.app.backup.BackupSection
import com.cuentivo.app.backup.DatabaseBackup
import com.cuentivo.app.billing.BillingGateway
import com.cuentivo.app.billing.EntitlementStore
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import com.cuentivo.app.auth.AccountSwitchDecision
import com.cuentivo.app.auth.AnonymousStartupFailureCause
import com.cuentivo.app.auth.AuthenticationFlowPolicy
import com.cuentivo.app.auth.GuestAccountSwitchPolicy
import com.cuentivo.app.domain.OpeningBalanceRow
import com.cuentivo.app.ui.onboarding.FirstDebtPolicy
import com.cuentivo.app.ui.onboarding.FirstDebtPreferenceStore
import com.cuentivo.app.ui.onboarding.FirstDebtScreen
import com.cuentivo.app.sync.SyncScheduler
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.ui.auth.EmailVerificationScreen
import com.cuentivo.app.domain.EmailVerificationAccess
import com.cuentivo.app.domain.EmailVerificationPolicy
import com.cuentivo.app.sync.AppCheckInstaller
import com.cuentivo.app.sync.FirebaseEmulators
import com.cuentivo.app.domain.CloudRestoreDecision
import com.cuentivo.app.domain.CloudRestorePolicy
import com.cuentivo.app.sync.BusinessLookupResult
import com.cuentivo.app.sync.CloudRestore
import com.cuentivo.app.sync.OutboxBackfill
import com.cuentivo.app.sync.CloudRestoreResult
import com.cuentivo.app.sync.FirestoreSyncGateway
import com.cuentivo.app.sync.FirestoreDeletionGateway
import com.cuentivo.app.domain.AccountDeletionFailure
import com.cuentivo.app.sync.OwnerMembershipBootstrap
import com.cuentivo.app.sync.SyncPayloads
import com.cuentivo.app.sync.SyncQueue
import com.google.firebase.auth.FirebaseAuthException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoSpacing

private const val TAG = "MainActivity"

/**
 * How long an empty device waits for Play before offering to create a business.
 *
 * Long enough for a cold Play Billing handshake on a slow connection, short enough that somebody
 * genuinely on the free plan does not think the app hung.
 */
private const val ENTITLEMENT_WAIT_MILLIS = 8_000L

/**
 * Un cambio de cuenta de Google pendiente de confirmación (v45).
 *
 * Lleva el idToken que dejó el intento de enlace fallido para que, al confirmar, se complete el
 * inicio de sesión sin volver a abrir el selector de Google. El token nunca se registra ni se
 * muestra.
 */
private data class PendingAccountSwitch(
    val targetEmail: String?,
    val idToken: String? = null,
    val onConfirm: (() -> Unit)? = null,
)

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_SHARE_STATEMENT_IMAGE = 831

        /**
         * What the collection reminder's notification carries: just the action. The intent holds no
         * customer, balance or business data — the host navigates and lets Room answer the who.
         */
        const val ACTION_OPEN_OVERDUE_CUSTOMERS = "com.cuentivo.app.OPEN_OVERDUE_CUSTOMERS"
    }

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

    /**
     * A reminder notification was tapped and the navigation is still owed. Held as a state so the
     * host can consume it once through CuentivoApp; the intent's action itself is nulled at read
     * time so a rotation cannot re-fire the navigation.
     */
    private val openOverdueCustomersState = mutableStateOf(false)

    /**
     * Messages raised while the Account sheet is open (e.g. the notification permission being
     * denied). Separate from the branch-local [uiMessage] because the permission callback is
     * declared outside that branch.
     */
    private val accountMessageState = mutableStateOf<String?>(null)

    /**
     * Release-only, fixed technical events. Debug and emulator sessions are a no-op; nothing with
     * PII, balances or ids ever reaches Crashlytics — see [CrashReporterFactory].
     */
    private val crashReporter = CrashReporterFactory.create()

    /** The one temporary customer statement currently exposed to the selected share target. */
    private val statementImageShareStore by lazy { StatementImageShareStore(applicationContext) }

    /**
     * La marca durable de un borrado de cuenta a medias (v44). Vive en SharedPreferences para que
     * sobreviva a la muerte del proceso: es lo que permite retomar el borrado de Auth al volver.
     */
    private val pendingAccountDeletionStore by lazy {
        com.cuentivo.app.auth.PendingAccountDeletionStore(applicationContext)
    }

    /**
     * Pro and AI prices as Play formats them, null until Play answers. Held here rather than read on demand
     * because the query is asynchronous and finishes after the composition that wants it.
     */
    private val proPriceState = mutableStateOf<String?>(null)
    private val aiPriceState = mutableStateOf<String?>(null)
    private val proTrialMonthsState = mutableStateOf(0)
    private val aiTrialMonthsState = mutableStateOf(0)

    /**
     * Whether Play has answered yet for the current account, which FREE alone cannot express.
     *
     * [tierState] starts at FREE and is also set to FREE the instant an account changes, so "free
     * plan" and "we have not heard back" look identical. On a device with data that distinction does
     * not matter; on an empty one it decides whether the person is offered a brand-new business or
     * made to wait for the one they already own. Getting that wrong is how a reinstall ends up with
     * a second business and an app that looks empty.
     */
    private val entitlementResolvedState = mutableStateOf(false)
    private val billingMessageState = mutableStateOf<String?>(null)
    private val requireAccountState = mutableStateOf(false)
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
     * A tapped reminder arrives here when the activity is already at the top of the task
     * (singleTop). Consumed the same way as in [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOverdueCustomersAction(intent)
    }

    /** Reads the reminder tap exactly once; the action is nulled so a recreate cannot re-fire. */
    private fun handleOverdueCustomersAction(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_OVERDUE_CUSTOMERS) {
            intent.action = null
            openOverdueCustomersState.value = true
        }
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
        // Si la app murió con el menú de compartir abierto, el PNG del estado de cuenta de un cliente
        // quedó en la caché. Sólo en arranque en frío: al girar el teléfono con el menú abierto, la
        // Activity se recrea y borrarlo ahí dejaría a WhatsApp sin imagen que leer.
        if (savedInstanceState == null) statementImageShareStore.clear()
        enableEdgeToEdge(
            // The app draws edge-to-edge over its own graphite ground. The system bars stay dark
            // with light icons so the clock, battery and navigation hints remain legible on every
            // device, regardless of the device's own light/dark preference.
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        CrashReporterFactory.install()
        handleOverdueCustomersAction(intent)
        // Before any Firestore or Auth call, so the first request already carries an attestation.
        AppCheckInstaller.installOnce()
        // Before any Firestore or Auth call: useEmulator throws once the instance has been used.
        FirebaseEmulators.connectIfEnabled()
        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        // Sweeps any IN_PROGRESS import metrics left unclosed if the process previously died.
        lifecycleScope.launch(Dispatchers.IO) {
            com.cuentivo.app.domain.LedgerImportMetricRecorder.sweepStaleInProgress(
                dao = database.aiLedgerImportMetricDao(),
                nowEpochMillis = System.currentTimeMillis(),
            )
        }
        // Built before the queue because the queue asks it, on every enqueue, whether this plan
        // includes the cloud at all. Nothing reaches the outbox on the free plan; OutboxBackfill
        // queues it all in one pass the moment Pro is confirmed.
        val entitlementStore = EntitlementStore(applicationContext)
        val syncQueue = SyncQueue(
            database.syncOperationDao(),
            applicationContext,
            allowsCloudSync = { entitlementStore.allowsCloudSync() },
        )
        val outboxBackfill = OutboxBackfill(database, syncQueue)
        val membershipBootstrap = OwnerMembershipBootstrap(database.businessDao(), database.syncOperationDao(), syncQueue)
        // Built here rather than lazily: it has to be ready before the "create a business" button can
        // be reached, since taking that offer is what strands the cloud copy under an unreachable id.
        val firestoreGateway = FirestoreSyncGateway()
        val deletionGateway = FirestoreDeletionGateway()
        val cloudRestore = CloudRestore(database, firestoreGateway, firestoreGateway, membershipBootstrap)
        val dashboardRepository = DashboardRepository(database.businessDao(), database.financialDao())
        val sessionGuard = LocalSessionGuard(applicationContext, database)
        val authenticationGateway = FirebaseAuthenticationGateway()
        val creditLimitSuggestionStore = CreditLimitSuggestionStore(applicationContext)
        val subscriptionVerifier = com.cuentivo.app.billing.subscriptionVerificationGateway()
        val aiAvailabilityGateway = com.cuentivo.app.ai.FirebaseAiAvailabilityGateway()
        // El Ledger de MexaLM se duerme solo; esta es la puerta para despertarlo al abrir la app.
        val mexalmLedgerWarmGateway = com.cuentivo.app.ai.FirebaseMexalmLedgerWarmGateway()
        val billing = BillingGateway(
            context = applicationContext,
            onPriceLoaded = { productId, formatted ->
                when (productId) {
                    SubscriptionTier.PRO_PRODUCT_ID -> proPriceState.value = formatted
                    SubscriptionTier.AI_PRODUCT_ID -> aiPriceState.value = formatted
                }
            },
            onTrialLoaded = { productId, months ->
                when (productId) {
                    SubscriptionTier.PRO_PRODUCT_ID -> proTrialMonthsState.value = months
                    SubscriptionTier.AI_PRODUCT_ID -> aiTrialMonthsState.value = months
                }
            },
            onPurchaseTokenReceived = { token, productId, sessionUserId ->
                // Captured for T3 backend entitlement validation against Play Developer API.
                // Project Rule 4: NEVER log or expose purchase tokens in Logcat, Crashlytics, or Analytics.
                val currentUid = authenticationGateway.currentUserId
                if (productId == SubscriptionTier.AI_PRODUCT_ID && !sessionUserId.isNullOrBlank() && sessionUserId == currentUid) {
                    subscriptionVerifier.verifySubscription(
                        purchaseToken = token,
                        productId = productId,
                    )
                }
            },
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
                // Only an answer that belongs to the signed-in account counts as an answer. A reply
                // for somebody else is dropped above, and must not release the wait below.
                if (decision.persist) entitlementResolvedState.value = true
                if (decision.persist) {
                    // Stored against the uid, with the time Play confirmed it, so a Worker with no
                    // Activity alive can tell both whose entitlement it is and how old it is.
                    // Stored *before* the backfill runs, not after: the queue asks this same store
                    // whether the plan allows the cloud, so seeding while it still reads FREE would
                    // have every enqueue return early and publish nothing at all.
                    entitlementStore.remember(decision.userId, decision.publish)
                    if (decision.publish.includesProBenefits) {
                        lifecycleScope.launch {
                            // Nothing was queued while the plan was free, so there is no pile to
                            // drain — there is a full database and an empty outbox. Seeding is what
                            // closes that gap, and it is idempotent, so running it on every
                            // confirmation of Pro rather than only the first costs nothing.
                            val businessId = database.businessDao().firstOwnedBy(decision.userId ?: "")?.id
                            if (businessId != null) {
                                runCatching { outboxBackfill.seed(businessId) }
                                    // A failed seed must not take the app down with it: the rows are
                                    // still in Room and the next confirmation of Pro tries again.
                                    .onFailure { Log.w(TAG, "No se pudo sembrar la bandeja de salida.", it) }
                            }
                            SyncScheduler.enqueue(applicationContext)
                        }
                    }
                }
            },
            onMessage = { billingMessageState.value = it
                // Every current onMessage is a purchase failure (backing out is silent elsewhere),
                // so the fixed technical event rides along without any message content.
                crashReporter.report(CrashEvent.BILLING_FAILURE)
            },
            onRequireAccount = {
                requireAccountState.value = true
            },
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
                // The uid this process registered, if any. Held as the id rather than a flag so
                // signing out and into an older account cannot inherit the exemption below.
                var signedUpUserId by remember { mutableStateOf<String?>(null) }
                // Bumped whenever the account is re-read, so the access decision below recomposes
                // the moment somebody comes back from clicking the link in their inbox.
                var verificationKey by remember { mutableIntStateOf(0) }

                // Guest anonymous session startup
                var isStartingAnonymousSession by remember { mutableStateOf(authenticationGateway.currentUserId == null) }
                var anonymousStartupCause by remember { mutableStateOf<AnonymousStartupFailureCause?>(null) }
                var showManualAuthentication by remember { mutableStateOf(false) }
                var showAccountLinking by remember { mutableStateOf(false) }
                if (requireAccountState.value) {
                    showAccountLinking = true
                    requireAccountState.value = false
                }

                val startAnonymousSession: () -> Unit = {
                    isStartingAnonymousSession = true
                    anonymousStartupCause = null
                    authenticationGateway.signInAnonymously { result ->
                        isStartingAnonymousSession = false
                        result.onSuccess { userId ->
                            authenticatedUserId = userId
                            anonymousStartupCause = null
                        }.onFailure { error ->
                            val errorCode = (error as? FirebaseAuthException)?.errorCode ?: error.message
                            val cause = AuthenticationFlowPolicy.classifyAnonymousErrorCode(errorCode)
                            anonymousStartupCause = cause
                            when (cause) {
                                AnonymousStartupFailureCause.NETWORK_UNAVAILABLE -> {
                                    crashReporter.report(CrashEvent.AUTH_ANON_OFFLINE)
                                    Log.w(TAG, "Fallo al iniciar sesión anónima: sin conexión de red.")
                                }
                                AnonymousStartupFailureCause.PROVIDER_DISABLED -> {
                                    crashReporter.report(CrashEvent.AUTH_ANON_PROVIDER_DISABLED)
                                    Log.e(TAG, "Fallo al iniciar sesión anónima: proveedor anónimo deshabilitado en Firebase Console ($errorCode).")
                                }
                                AnonymousStartupFailureCause.SERVICE_ERROR -> {
                                    crashReporter.report(CrashEvent.AUTH_ANON_SERVICE_ERROR)
                                    Log.e(TAG, "Fallo al iniciar sesión anónima: error de servicio ($errorCode).")
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (authenticationGateway.currentUserId == null && !showManualAuthentication) {
                        startAnonymousSession()
                    }
                }

                // The daily reminder is a local, per-phone preference. State mirrors preferences so
                // the Account sheet reflects changes immediately; the permission launcher only asks
                // on API 33+ and only after the owner tries to turn the setting on.
                val reminderPreferences = remember { CollectionReminderPreferences(this@MainActivity) }
                var reminderEnabled by remember { mutableStateOf(reminderPreferences.enabled) }
                var reminderHour by remember { mutableStateOf(reminderPreferences.hourOfDay) }
                // Debug only, and never persisted: forces the free-plan footer on shared messages so a
                // Pro tester can see it. Sync and the stored entitlement keep the real plan.
                var debugForceFreeShare by remember { mutableStateOf(false) }
                val applyReminderEnabled: (Boolean) -> Unit = { value ->
                    reminderPreferences.enabled = value
                    reminderEnabled = value
                    if (value) CollectionReminderScheduler.sync(this@MainActivity)
                    else CollectionReminderScheduler.cancel(this@MainActivity)
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        applyReminderEnabled(true)
                    } else {
                        // The setting stays off. Android decides when the system dialog may be
                        // asked again, so this never becomes a request loop.
                        accountMessageState.value = getString(R.string.reminder_permission_denied)
                    }
                }
                val emailAccess = remember(authenticatedUserId, verificationKey) {
                    EmailVerificationPolicy.access(
                        isSignedIn = authenticatedUserId != null,
                        isEmailVerified = authenticationGateway.isEmailVerified,
                        accountCreatedAtEpochMillis = authenticationGateway.accountCreatedAtEpochMillis,
                        isAnonymous = authenticationGateway.isAnonymous,
                    )
                }
                if (authenticatedUserId == null) {
                    if (isStartingAnonymousSession) {
                        Box(
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (anonymousStartupCause != null && !showManualAuthentication) {
                        val cause = anonymousStartupCause
                        val title = when (cause) {
                            AnonymousStartupFailureCause.PROVIDER_DISABLED -> stringResource(R.string.auth_first_launch_provider_disabled_title)
                            AnonymousStartupFailureCause.SERVICE_ERROR -> stringResource(R.string.auth_first_launch_service_error_title)
                            else -> stringResource(R.string.auth_first_launch_offline_title)
                        }
                        val message = when (cause) {
                            AnonymousStartupFailureCause.PROVIDER_DISABLED -> stringResource(R.string.auth_first_launch_provider_disabled_message)
                            AnonymousStartupFailureCause.SERVICE_ERROR -> stringResource(R.string.auth_first_launch_service_error_message)
                            else -> stringResource(R.string.auth_first_launch_offline_message)
                        }
                        val showRetryButton = cause != AnonymousStartupFailureCause.PROVIDER_DISABLED

                        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                            val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
                            val horizontalGutter = maxOf(16, layout.horizontalPaddingDp)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = horizontalGutter.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                NeoPanel(modifier = Modifier.widthIn(max = 480.dp)) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(NeoSpacing.lg),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
                                    ) {
                                        Surface(
                                            shape = NeoShapes.card,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                                            modifier = Modifier.size(56.dp),
                                        ) {
                                            Image(
                                                painter = painterResource(R.mipmap.ic_launcher_foreground),
                                                contentDescription = "Cuentivo",
                                                modifier = Modifier.fillMaxSize().scale(1.5f),
                                            )
                                        }
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(
                                            text = message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        if (showRetryButton) {
                                            NeoAction(
                                                label = stringResource(R.string.auth_first_launch_offline_retry),
                                                enabled = !isStartingAnonymousSession,
                                                onClick = { startAnonymousSession() },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            TextButton(
                                                onClick = { showManualAuthentication = true },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(stringResource(R.string.auth_first_launch_offline_switch_to_sign_in))
                                            }
                                        } else {
                                            NeoAction(
                                                label = stringResource(R.string.auth_first_launch_provider_disabled_action),
                                                onClick = { showManualAuthentication = true },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val signInFailedMessage = stringResource(R.string.auth_sign_in_failed)
                        val signUpFailedMessage = stringResource(R.string.auth_sign_up_failed)
                        val emailInUseMessage = stringResource(R.string.auth_email_in_use)
                        val googleFailedMessage = stringResource(R.string.auth_google_failed)
                        val googleManualPasswordMessage = stringResource(R.string.auth_google_manual_password_needed)
                        val handleGoogleResult: (GoogleAuthenticationResult) -> Unit = { result ->
                            isAuthenticating = false
                            when (result) {
                                is GoogleAuthenticationResult.SignedIn -> {
                                    authenticatedUserId = result.userId
                                    verificationKey++
                                }
                                GoogleAuthenticationResult.ManualAccountNeedsPassword ->
                                    authenticationMessage = googleManualPasswordMessage
                                is GoogleAuthenticationResult.AccountAlreadyExists ->
                                    authenticationMessage = emailInUseMessage
                                GoogleAuthenticationResult.Failed -> {
                                    authenticationMessage = googleFailedMessage
                                    crashReporter.report(CrashEvent.AUTH_FAILURE)
                                }
                                // Cancellation: nothing happened, nothing to say, session untouched.
                                GoogleAuthenticationResult.Cancelled -> Unit
                            }
                        }
                        AuthenticationScreen(
                            isWorking = isAuthenticating,
                            message = authenticationMessage,
                            customerCount = 0,
                            onSignIn = { email, password ->
                                isAuthenticating = true
                                authenticationMessage = null
                                authenticationGateway.signIn(email, password) { result ->
                                    isAuthenticating = false
                                    result.onSuccess { authenticatedUserId = it }
                                        .onFailure {
                                            authenticationMessage = signInFailedMessage
                                            crashReporter.report(CrashEvent.AUTH_FAILURE)
                                        }
                                }
                            },
                            onSignUp = { email, password ->
                                isAuthenticating = true
                                authenticationMessage = null
                                // Regla de oro de identidad y datos locales:
                                // Ante la duda se conservan los datos locales y se le pregunta al usuario.
                                // NUNCA se borra para resolver un conflicto de identidad.
                                // Al registrarse desde una sesión anónima, linkWithCredential conserva el UID y todos los datos locales.
                                if (authenticationGateway.isAnonymous) {
                                    authenticationGateway.linkEmail(email, password) { result ->
                                        isAuthenticating = false
                                        result.onSuccess { userId ->
                                            authenticationGateway.sendEmailVerification { }
                                            signedUpUserId = userId
                                            authenticatedUserId = userId
                                            verificationKey++
                                        }.onFailure { error ->
                                            authenticationMessage =
                                                if (error is FirebaseAuthUserCollisionException) emailInUseMessage
                                                else {
                                                    crashReporter.report(CrashEvent.AUTH_FAILURE)
                                                    signUpFailedMessage
                                                }
                                        }
                                    }
                                } else {
                                    authenticationGateway.signUp(email, password) { result ->
                                        isAuthenticating = false
                                        result.onSuccess { userId ->
                                            authenticationGateway.sendEmailVerification { }
                                            signedUpUserId = userId
                                            authenticatedUserId = userId
                                            verificationKey++
                                        }.onFailure { error ->
                                            authenticationMessage =
                                                if (error is FirebaseAuthUserCollisionException) emailInUseMessage
                                                else {
                                                    crashReporter.report(CrashEvent.AUTH_FAILURE)
                                                    signUpFailedMessage
                                                }
                                        }
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
                            onGoogleSignIn = {
                                isAuthenticating = true
                                authenticationMessage = null
                                // Signed out: Google creates or signs into the session. Signed in / anonymous: linking
                                // adds Google to the existing account, preserving its UID instead of minting a second one.
                                if (authenticationGateway.currentUserId == null) {
                                    authenticationGateway.signInWithGoogle(this@MainActivity, handleGoogleResult)
                                } else {
                                    authenticationGateway.linkGoogle(this@MainActivity, handleGoogleResult)
                                }
                            },
                        )
                    }
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
                            authenticationGateway.clearGoogleCredentialState { }
                            CollectionReminderScheduler.cancel(this@MainActivity)
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
                // El aviso "primero crea tu negocio" no puede ir sólo al snackbar: con el formulario
                // de Nuevo cliente abierto, ese snackbar queda detrás del diálogo y la persona no ve
                // por qué "Guardar" no hizo nada (v46). Este texto viaja al propio formulario.
                var customerFormNotice by remember { mutableStateOf<String?>(null) }
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
                // Set alongside the token above, and for the same reason: only a write that reached
                // Room may send anyone into a cart for that customer.
                var pendingSaleCustomerId by remember { mutableStateOf<String?>(null) }
                // The customer the last successful create actually wrote, whatever the intent. Used
                // by the "Agregar cliente" shortcut inside Nueva venta: the form was opened from the
                // sale, so any save is the signal to return there with that customer selected.
                var lastCreatedCustomerId by remember { mutableStateOf<String?>(null) }
                var isImportingBalances by remember { mutableStateOf(false) }
                var balancesImportedToken by remember { mutableIntStateOf(0) }
                var isSavingProduct by remember { mutableStateOf(false) }
                var productSavedToken by remember { mutableIntStateOf(0) }
                val productImages = remember { ProductImageStore(this@MainActivity) }
                // The real plan, straight from Google Play. Was pinned to FREE until Billing existed.
                val tier by tierState
                val shareTier = ShareTier.resolve(tier, BuildConfig.DEBUG, debugForceFreeShare)
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
                // v44: si este uid tiene un borrado de cuenta a medias (la marca durable), no se crea
                // ni se adopta ningún negocio: lo único que falta es terminar de borrar el usuario de
                // Auth. Mientras se reintenta, la pantalla de carga tapa todo lo que pueda crear algo.
                var pendingDeletionResume by remember(currentUserId) {
                    mutableStateOf(pendingAccountDeletionStore.isPendingFor(currentUserId))
                }
                LaunchedEffect(currentUserId) {
                    // The effective session drops to FREE the instant the account changes, before
                    // anything else runs. Whatever Play said about the previous account stops
                    // applying immediately; then Play is asked again, now attributable to this uid.
                    // This is also the sign-in path, where the first answer arrived with no uid at
                    // all and was therefore discarded.
                    tierState.value = SubscriptionTier.FREE
                    entitlementResolvedState.value = false
                    billing.refresh(currentUserId)

                    if (sessionGuard.adopt(currentUserId)) {
                        uiMessage = getString(R.string.message_account_wiped)
                    }
                    // Backfills the owner membership for businesses created before it was published,
                    // whose queued rows are otherwise parked on PERMISSION_DENIED forever.
                    database.businessDao().firstOwnedBy(currentUserId)?.let { membershipBootstrap.ensure(it) }
                    // Sign-out cancels the reminder work; signing back in restores whatever the
                    // owner had chosen, so a toggle never silently stops working after a session
                    // change. Idempotent: disabled cancels, enabled re-schedules at the right hour.
                    CollectionReminderScheduler.sync(this@MainActivity)
                    sessionReady = true
                }

                // v44: retomar un borrado de cuenta que quedó a medias. Si la marca durable apunta a
                // este uid, la parte destructiva ya ocurrió (o el proceso murió a mitad): se reintenta
                // el borrado de Auth —re-autenticando si hace falta— y, salga o no, se sale de la
                // sesión. Nunca se crea un negocio nuevo para una cuenta que ya perdió sus datos.
                LaunchedEffect(currentUserId, sessionReady) {
                    if (!sessionReady) return@LaunchedEffect
                    val resumeStep = com.cuentivo.app.domain.AccountDeletionPlan.resumeStep(
                        hasPendingDeletion = pendingAccountDeletionStore.isPendingFor(currentUserId),
                    ) ?: return@LaunchedEffect
                    if (resumeStep != com.cuentivo.app.domain.AccountDeletionPlan.Step.DELETE_AUTH) return@LaunchedEffect

                    // Re-autenticar primero: el borrado de Auth que falló pudo ser por sesión vieja.
                    if (!authenticationGateway.isAnonymous) {
                        val reauth = reauthenticateWithGoogle(authenticationGateway)
                        if (reauth !is GoogleAuthenticationResult.SignedIn) {
                            // No se pudo confirmar: se sale igualmente y se dice la verdad. La marca
                            // queda puesta para el siguiente intento.
                            authenticationGateway.signOut()
                            authenticationGateway.clearGoogleCredentialState { }
                            CollectionReminderScheduler.cancel(this@MainActivity)
                            authenticatedUserId = null
                            showManualAuthentication = true
                            uiMessage = getString(R.string.message_account_delete_data_gone_auth_failed)
                            return@LaunchedEffect
                        }
                    }
                    deleteAccountAndAwait(authenticationGateway).onSuccess {
                        pendingAccountDeletionStore.clear()
                        authenticationGateway.clearGoogleCredentialState { }
                        CollectionReminderScheduler.cancel(this@MainActivity)
                        authenticatedUserId = null
                        showManualAuthentication = true
                    }.onFailure {
                        // Sigue sin poder cerrarse la cuenta: se sale de la sesión igualmente y la
                        // marca queda para el próximo arranque.
                        authenticationGateway.signOut()
                        authenticationGateway.clearGoogleCredentialState { }
                        CollectionReminderScheduler.cancel(this@MainActivity)
                        authenticatedUserId = null
                        showManualAuthentication = true
                        uiMessage = getString(R.string.message_account_delete_data_gone_auth_failed)
                    }
                    pendingDeletionResume = false
                }

                // Restoring is keyed on the tier, not on sign-in, because Play answers asynchronously
                // and the session above deliberately starts at FREE. The moment PRO is confirmed for
                // this uid, an empty device goes looking for the business the account already owns.
                var restoreAttempted by remember(currentUserId) { mutableStateOf(false) }
                var isRestoring by remember(currentUserId) { mutableStateOf(false) }

                // The claim: does this account already own a business anywhere? Null while unknown.
                //
                // Asked on every plan, which is the fix. Consulting the cloud used to be part of
                // *restoring*, a Pro feature, so a free account never looked — it just offered to
                // create, and the button minted a fresh random id. Every query filters by
                // businessId, so each of those was an island; one account collected nine of them.
                // Asking whether you already own something is data integrity, not a paid feature.
                // Downloading the ledger still is, and that has not changed.
                var claim by remember(currentUserId) { mutableStateOf<BusinessClaim?>(null) }
                var claimAttempt by remember(currentUserId) { mutableIntStateOf(0) }

                // An empty device waits for Play before it offers to create anything. Without this
                // the offer is on screen within a second of opening, long before the subscription is
                // known, and taking it mints a second business while the real one sits in the cloud.
                // That is not hypothetical: it is how one account ended up owning four of them.
                val entitlementResolved by entitlementResolvedState
                var entitlementWaitExpired by remember(currentUserId) { mutableStateOf(false) }
                LaunchedEffect(currentUserId) {
                    // Bounded, because Play can stay silent — no Play Services, no network, a
                    // sideloaded build. Being locked out of your own app is worse than the race.
                    delay(ENTITLEMENT_WAIT_MILLIS)
                    entitlementWaitExpired = true
                }
                val hasLocalBusiness by produceState<Boolean?>(null, currentUserId, sessionReady, refreshKey) {
                    value = if (!sessionReady) null else database.businessDao().firstOwnedBy(currentUserId) != null
                }
                val waitingForEntitlement =
                    hasLocalBusiness == false && !entitlementResolved && !entitlementWaitExpired

                // Resolves the claim before anything can be created. Re-runs on retry and whenever
                // the plan changes, because a lookup that failed offline has to be able to succeed
                // later without restarting the app.
                // Asking the cloud is pointless for a session the rules will refuse, and worse than
                // pointless: the refusal arrives as a failed lookup, which means Blocked, which is a
                // dead end with a retry button that cannot work. Grandfathered accounts are exactly
                // that case — let in without verifying on purpose — so they skip the question.
                val canQueryCloud = EmailVerificationPolicy.allowsCloudSync(
                    emailAccess,
                    authenticationGateway.isAnonymous,
                )
                LaunchedEffect(currentUserId, sessionReady, refreshKey, claimAttempt, canQueryCloud) {
                    if (!sessionReady) return@LaunchedEffect
                    if (database.businessDao().firstOwnedBy(currentUserId) != null) {
                        claim = BusinessClaim.UseLocal
                        return@LaunchedEffect
                    }
                    claim = null
                    val found = if (!canQueryCloud) null else {
                        withContext(Dispatchers.IO) { firestoreGateway.findBusinessOwnedBy(currentUserId) }
                    }
                    claim = BusinessClaimPolicy.decide(
                        hasLocalBusiness = false,
                        remoteBusinessId = (found as? BusinessLookupResult.Found)?.businessId,
                        lookupFailed = found is BusinessLookupResult.Failure,
                        canQueryCloud = canQueryCloud,
                        accountIsBrandNew = signedUpUserId == currentUserId,
                    )
                    // Adopting on the free plan: take the id, not the ledger. Written with the
                    // remote's own timestamps so this row can never win a merge against the copy it
                    // was adopted from and rename it back to the default.
                    val remote = found as? BusinessLookupResult.Found
                    if (claim is BusinessClaim.AdoptRemote && !tier.includesProBenefits && remote != null) {
                        val adopted = BusinessEntity(
                            remote.businessId, remote.name, currentUserId, remote.currency,
                            remote.timezone, remote.createdAtEpochMillis, remote.updatedAtEpochMillis,
                        )
                        database.businessDao().upsert(adopted)
                        membershipBootstrap.ensure(adopted)
                        refreshKey++
                    }
                }

                // El negocio se crea solo (v46). Una cuenta con sesión, sin negocio, se quedaba
                // atorada: "Guardar" en Nuevo cliente no hacía nada y la foto de la libreta abría el
                // chat sin cámara ni importar, porque el atajo sólo existe con negocio activo. La
                // decisión —crear, esperar o no hacer nada— vive en el objeto puro; aquí sólo se
                // ejecuta, y nunca antes de que el claim haya respondido ni después de que falle.
                val defaultBusinessDecision = DefaultBusinessCreationPolicy.decide(
                    claim = claim,
                    hasLocalBusiness = hasLocalBusiness == true,
                    waitingForEntitlement = waitingForEntitlement,
                )
                LaunchedEffect(currentUserId, defaultBusinessDecision) {
                    if (defaultBusinessDecision != DefaultBusinessDecision.Create) return@LaunchedEffect
                    val now = System.currentTimeMillis()
                    // Idempotente de verdad: la comprobación y la escritura van en la misma
                    // transacción, así que dos arranques seguidos —o dos recomposiciones que lleguen
                    // a la vez— no crean dos negocios. Ese es el defecto que ya costó una cuenta con
                    // nueve, y la razón de que el objeto puro sea tan estricto con el claim.
                    val created = database.withTransaction {
                        if (database.businessDao().firstOwnedBy(currentUserId) != null) {
                            null
                        } else {
                            val business = BusinessEntity(
                                UUID.randomUUID().toString(), BusinessName.DEFAULT, currentUserId,
                                "MXN", "America/Mexico_City", now, now,
                            )
                            database.businessDao().insert(business)
                            business
                        }
                    }
                    if (created != null) {
                        // Best effort pasado este punto, igual que la creación manual: la fila ya está
                        // en Room y un fallo del outbox no puede ocultarla.
                        syncQueue.enqueue(created.id, "BUSINESS", created.id, now, SyncPayloads.business(created))
                        membershipBootstrap.ensure(created)
                        refreshKey++
                    }
                }

                LaunchedEffect(currentUserId, tier, sessionReady, claim) {
                    if (!sessionReady) return@LaunchedEffect
                    // Only a claim that found something remote is worth downloading, and only Pro
                    // may download it. CloudRestorePolicy still owns that half of the decision.
                    if (claim !is BusinessClaim.AdoptRemote) return@LaunchedEffect
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
                            uiMessage = getString(R.string.message_cloud_restored, result.customers)
                            refreshKey++
                        }
                        // Nothing in the cloud is the normal path for a new Pro account: say nothing
                        // and let them create their business.
                        CloudRestoreResult.NothingInCloud -> Unit
                        // Readable first, but never opaque: the stage and Firestore's status code
                        // ride along in parentheses. The purely generic "check your connection"
                        // discarded a cause that was already in hand and cost three rounds of
                        // guessing at a failure logcat was not reporting either. The full text and
                        // stack trace stay in the log; only this short code reaches the screen.
                        is CloudRestoreResult.Failed -> {
                            crashReporter.report(CrashEvent.CLOUD_RESTORE_FAILURE)
                            // La frase se traduce; los códigos técnicos stage/failure viajan tal
                            // cual, que son lo único que sirve para diagnosticar.
                            uiMessage = getString(
                                R.string.message_cloud_restore_failed,
                                result.stage,
                                result.failure.message.substringBefore(":").take(40),
                            )
                        }
                    }
                    isRestoring = false
                }

                // The claim gates the whole screen, not just the restore. While it is unresolved
                // nothing that can mint a business is reachable — that is the point of it.
                // v44: un borrado de cuenta a medias también tapa la pantalla: no se crea ni se
                // adopta nada mientras se termina de borrar el usuario de Auth.
                if (!sessionReady || isRestoring || waitingForEntitlement || claim == null || pendingDeletionResume) {
                    CuentivoLoadingScreen()
                } else if (claim == BusinessClaim.Blocked) {
                    // The lookup failed, so whether this account owns a business is unknown. The
                    // app used to treat that as "owns none" and let the offer through, which is the
                    // single most expensive assumption in this file: it needs no free plan and no
                    // reinstall, just a bad connection at the wrong second. Not knowing has to mean
                    // not creating, so this offers a retry and nothing else.
                    CuentivoClaimBlockedScreen(
                        onRetry = { claimAttempt++ },
                        onUseAnotherAccount = {
                            authenticationGateway.signOut()
                            authenticationGateway.clearGoogleCredentialState { }
                            CollectionReminderScheduler.cancel(this@MainActivity)
                            signedUpUserId = null
                            authenticationMessage = null
                            authenticatedUserId = null
                        },
                    )
                } else {
                val viewModel = remember(currentUserId) {
                    // Bound to the signed-in owner, never to "the first business in the database".
                    DashboardViewModel { dashboardRepository.load(currentUserId) }
                }
                val aiDailyChatRepository = remember(currentUserId) {
                    if (currentUserId.isBlank()) {
                        null
                    } else {
                        AiDailyChatRepository(
                            dao = database.aiDailyMessageDao(),
                            ownerUserId = currentUserId,
                        )
                    }
                }
                val customerRepository = remember { CustomerRepository(database.customerDao()) }
                val productRepository = remember { ProductRepository(database.productDao()) }
                val aiPaymentPreviewResolver = remember {
                    AiPaymentPreviewResolver(
                        searchCustomers = customerRepository::search,
                        loadBalanceCents = database.financialDao()::customerBalanceCents,
                    )
                }
                val aiSalePreviewResolver = remember {
                    AiSalePreviewResolver(
                        searchCustomers = customerRepository::search,
                        searchProducts = productRepository::search,
                    )
                }
                val aiBusinessQueryResolver = remember {
                    AiBusinessQueryResolver(
                        searchCustomers = customerRepository::search,
                        customerBalance = database.financialDao()::customerBalanceCents,
                        customersWithBalances = database.customerDao()::activeWithBalances,
                        salesTotal = database.financialDao()::salesTotalCents,
                        collectedTotal = database.financialDao()::collectedTotalCents,
                    )
                }
                val aiPaymentExecutionService = remember(currentUserId) {
                    val paymentRepository = PaymentRepository(database.financialDao())
                    AiPaymentExecutionService(
                        registerPayment = paymentRepository::registerAgainstExpectedBalance,
                        loadStoredBundle = paymentRepository::storedBundle,
                        enqueueStoredBundle = { bundle ->
                            val payment = bundle.payment
                            syncQueue.enqueue(payment.businessId, "PAYMENT", payment.id, payment.createdAtEpochMillis, SyncPayloads.payment(payment))
                            bundle.allocations.forEach { allocation ->
                                syncQueue.enqueue(payment.businessId, "PAYMENT_ALLOCATION", allocation.id, payment.createdAtEpochMillis, SyncPayloads.allocation(allocation))
                            }
                            syncQueue.enqueue(payment.businessId, "MOVEMENT", bundle.movement.id, payment.createdAtEpochMillis, SyncPayloads.movement(bundle.movement))
                        },
                        currentUserId = { currentUserId },
                        nowMillis = System::currentTimeMillis,
                        todayEpochDay = { LocalDate.now().toEpochDay() },
                    )
                }
                val aiSaleExecutionService = remember(currentUserId) {
                    val saleRepository = SaleRepository(database.financialDao())
                    AiSaleExecutionService(
                        registerSale = saleRepository::registerCompositeAgainstPreview,
                        loadStoredBundle = saleRepository::storedBundle,
                        enqueueStoredBundle = { bundle ->
                            val sale = bundle.sale
                            val now = sale.createdAtEpochMillis
                            bundle.createdCustomer?.let { syncQueue.enqueue(it.businessId, "CUSTOMER", it.id, now, SyncPayloads.customer(it)) }
                            bundle.createdProducts.forEach { syncQueue.enqueue(it.businessId, "PRODUCT", it.id, now, SyncPayloads.product(it)) }
                            syncQueue.enqueue(sale.businessId, "SALE", sale.id, now, SyncPayloads.sale(sale))
                            bundle.items.forEach { item ->
                                syncQueue.enqueue(sale.businessId, "SALE_ITEM", item.id, now, SyncPayloads.saleItem(item))
                            }
                            syncQueue.enqueue(sale.businessId, "MOVEMENT", bundle.chargeMovement.id, now, SyncPayloads.movement(bundle.chargeMovement))
                            bundle.paymentBundle?.let { pBundle ->
                                val payment = pBundle.payment
                                syncQueue.enqueue(payment.businessId, "PAYMENT", payment.id, now, SyncPayloads.payment(payment))
                                pBundle.allocations.forEach { allocation ->
                                    syncQueue.enqueue(payment.businessId, "PAYMENT_ALLOCATION", allocation.id, now, SyncPayloads.allocation(allocation))
                                }
                                syncQueue.enqueue(payment.businessId, "MOVEMENT", pBundle.movement.id, now, SyncPayloads.movement(pBundle.movement))
                            }
                        },
                        currentUserId = { currentUserId },
                        nowMillis = System::currentTimeMillis,
                        todayEpochDay = { LocalDate.now().toEpochDay() },
                    )
                }
                val state by produceState<DashboardUiState>(initialValue = DashboardUiState.NoBusiness, refreshKey) { value = viewModel.load() }
                val customers by produceState<List<CustomerBalanceRow>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.customerDao().activeWithBalances(it.businessId) } ?: emptyList()
                }
                // Derived on every refresh, like the balance and the sale status: settle a debt and
                // the customer's light turns on the next read, with no stored column to go stale.
                val customerScores by produceState<Map<String, CustomerScore>>(emptyMap(), state, refreshKey) {
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    value = if (businessId == null) {
                        emptyMap()
                    } else {
                        val today = LocalDate.now().toEpochDay()
                        database.financialDao().saleHistoryForScores(businessId)
                            .groupBy { it.customerId }
                            .mapValues { (_, rows) ->
                                CustomerScoreCalculator.score(
                                    rows.map { row ->
                                        CustomerSaleHistory(
                                            dueDateEpochDay = row.dueDateEpochDay,
                                            totalCents = row.totalCents,
                                            allocatedCents = row.allocatedCents,
                                            lastPaymentDateEpochDay = row.lastPaymentDateEpochDay,
                                        )
                                    },
                                    todayEpochDay = today,
                                )
                            }
                    }
                }
                val products by produceState<List<ProductEntity>>(initialValue = emptyList(), state, refreshKey) {
                    value = (state as? DashboardUiState.Content)?.let { database.productDao().searchActive(it.businessId, "", "") } ?: emptyList()
                }
                val businessName = (state as? DashboardUiState.Content)?.businessName ?: BusinessName.DEFAULT

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
                    // Which of this customer's charges are really the debt they already had before
                    // the app. Read once for the whole statement, not per row.
                    val priorDebtSaleIds = financialDao
                        .priorDebtSaleIdsForCustomer(businessId, row.customer.id)
                        .toSet()
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
                                isPriorDebt = movement.saleId?.let(priorDebtSaleIds::contains) == true,
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
                    // El saldo se relee del libro mayor, nunca se reusa el de la fila capturada al
                    // navegar. Tras registrar una venta o un abono, el host llama a `loadStatement`
                    // con la fila que tenía en la mano —la de antes de la escritura—, así que
                    // confiar en `row.balanceCents` dejaba el detalle mostrando el saldo viejo
                    // ("Te debe $150" tras una venta de $75 sobre $150) hasta salir y volver a
                    // entrar. La regla de `CLAUDE.md` es explícita: el saldo se deriva, nunca de un
                    // valor viejo. Se conserva el resto de la fila (nombre, teléfono, límite) y sólo
                    // se refresca el monto, que es lo único que la escritura pudo mover.
                    statementCustomer = StatementCustomerRefresh.fromLedger(
                        row = row,
                        businessId = businessId,
                        customerBalanceCents = financialDao::customerBalanceCents,
                    )
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

                val firstDebtPreferenceStore = remember { FirstDebtPreferenceStore(this@MainActivity) }
                var hasSkippedFirstDebt by remember(currentUserId) {
                    mutableStateOf(firstDebtPreferenceStore.isDismissed(currentUserId))
                }
                var isSavingFirstDebt by remember { mutableStateOf(false) }

                val hasFinancialActivity = (state as? DashboardUiState.Content)?.let {
                    it.receivableCents > 0L || it.soldCents > 0L || it.collectedCents > 0L || it.recentActivity.isNotEmpty()
                } ?: false

                val shouldShowFirstDebt = FirstDebtPolicy.shouldShow(
                    customerCount = customers.size,
                    isDismissed = hasSkippedFirstDebt,
                    hasFinancialActivity = hasFinancialActivity,
                )

                if (showAccountLinking) {
                    BackHandler { showAccountLinking = false }
                    var pendingAccountSwitchAction by remember { mutableStateOf<PendingAccountSwitch?>(null) }
                    val signInFailedMessage = stringResource(R.string.auth_sign_in_failed)
                    val signUpFailedMessage = stringResource(R.string.auth_sign_up_failed)
                    val emailInUseMessage = stringResource(R.string.auth_email_in_use)
                    val googleFailedMessage = stringResource(R.string.auth_google_failed)
                    val googleManualPasswordMessage = stringResource(R.string.auth_google_manual_password_needed)

                    val performSignIn: (String, String) -> Unit = { email, password ->
                        isAuthenticating = true
                        authenticationMessage = null
                        authenticationGateway.signIn(email, password) { result ->
                            isAuthenticating = false
                            result.onSuccess {
                                authenticatedUserId = it
                                showAccountLinking = false
                            }.onFailure {
                                authenticationMessage = signInFailedMessage
                                crashReporter.report(CrashEvent.AUTH_FAILURE)
                            }
                        }
                    }

                    val performGoogleSignIn: () -> Unit = {
                        isAuthenticating = true
                        authenticationMessage = null
                        authenticationGateway.signInWithGoogle(this@MainActivity) { result ->
                            isAuthenticating = false
                            when (result) {
                                is GoogleAuthenticationResult.SignedIn -> {
                                    authenticatedUserId = result.userId
                                    showAccountLinking = false
                                    verificationKey++
                                }
                                GoogleAuthenticationResult.ManualAccountNeedsPassword ->
                                    authenticationMessage = googleManualPasswordMessage
                                is GoogleAuthenticationResult.AccountAlreadyExists ->
                                    authenticationMessage = emailInUseMessage
                                GoogleAuthenticationResult.Failed -> {
                                    authenticationMessage = googleFailedMessage
                                    crashReporter.report(CrashEvent.AUTH_FAILURE)
                                }
                                GoogleAuthenticationResult.Cancelled -> Unit
                            }
                        }
                    }

                    // Completa el cambio de cuenta reutilizando el idToken ya obtenido, sin volver a
                    // abrir el selector de Google (v45). Si no hay token, cae al flujo completo.
                    val completeGoogleSwitch: (String?) -> Unit = { idToken ->
                        when (GoogleAccountSwitchAction.route(idToken)) {
                            GoogleAccountSwitchAction.Route.REUSE_TOKEN -> {
                                isAuthenticating = true
                                authenticationMessage = null
                                authenticationGateway.signInWithGoogleIdToken(requireNotNull(idToken)) { result ->
                                    isAuthenticating = false
                                    when (result) {
                                        is GoogleAuthenticationResult.SignedIn -> {
                                            authenticatedUserId = result.userId
                                            showAccountLinking = false
                                            verificationKey++
                                        }
                                        GoogleAuthenticationResult.ManualAccountNeedsPassword ->
                                            authenticationMessage = googleManualPasswordMessage
                                        is GoogleAuthenticationResult.AccountAlreadyExists ->
                                            authenticationMessage = emailInUseMessage
                                        GoogleAuthenticationResult.Failed -> {
                                            authenticationMessage = googleFailedMessage
                                            crashReporter.report(CrashEvent.AUTH_FAILURE)
                                        }
                                        GoogleAuthenticationResult.Cancelled -> Unit
                                    }
                                }
                            }
                            GoogleAccountSwitchAction.Route.RELAUNCH_PICKER -> performGoogleSignIn()
                        }
                    }

                    val handleGoogleResult: (GoogleAuthenticationResult) -> Unit = { result ->
                        isAuthenticating = false
                        when (result) {
                            is GoogleAuthenticationResult.SignedIn -> {
                                authenticatedUserId = result.userId
                                showAccountLinking = false
                                verificationKey++
                            }
                            GoogleAuthenticationResult.ManualAccountNeedsPassword ->
                                authenticationMessage = googleManualPasswordMessage
                            is GoogleAuthenticationResult.AccountAlreadyExists -> {
                                val targetEmail = result.email
                                val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
                                    isAnonymous = authenticationGateway.isAnonymous,
                                    customerCount = customers.size,
                                    hasFinancialActivity = hasFinancialActivity,
                                    targetEmail = targetEmail,
                                )
                                when (decision) {
                                    is AccountSwitchDecision.Confirm -> {
                                        // El token viaja con la confirmación: al aceptar se reutiliza
                                        // en vez de pedir la cuenta otra vez (v45).
                                        pendingAccountSwitchAction = PendingAccountSwitch(
                                            targetEmail = targetEmail,
                                            idToken = result.idToken,
                                        )
                                    }
                                    AccountSwitchDecision.Direct -> completeGoogleSwitch(result.idToken)
                                }
                            }
                            GoogleAuthenticationResult.Failed -> {
                                authenticationMessage = googleFailedMessage
                                crashReporter.report(CrashEvent.AUTH_FAILURE)
                            }
                            GoogleAuthenticationResult.Cancelled -> Unit
                        }
                    }

                    if (pendingAccountSwitchAction != null) {
                        val targetEmail = pendingAccountSwitchAction?.targetEmail
                        val pendingToken = pendingAccountSwitchAction?.idToken
                        val pendingConfirm = pendingAccountSwitchAction?.onConfirm
                        val count = customers.size
                        val messageText = if (!targetEmail.isNullOrBlank()) {
                            when (count) {
                                0 -> stringResource(R.string.auth_guest_switch_confirm_message_generic, targetEmail)
                                1 -> stringResource(R.string.auth_guest_switch_confirm_message_single, targetEmail)
                                else -> stringResource(R.string.auth_guest_switch_confirm_message_plural, targetEmail, count)
                            }
                        } else {
                            when (count) {
                                0 -> stringResource(R.string.auth_guest_switch_confirm_message_no_email_generic)
                                1 -> stringResource(R.string.auth_guest_switch_confirm_message_no_email_single)
                                else -> stringResource(R.string.auth_guest_switch_confirm_message_no_email_plural, count)
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { pendingAccountSwitchAction = null },
                            title = { Text(stringResource(R.string.auth_guest_switch_confirm_title)) },
                            text = { Text(messageText) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingAccountSwitchAction = null
                                        // Google reutiliza el token ya obtenido; correo/contraseña
                                        // reintenta su propio inicio de sesión.
                                        if (pendingConfirm != null) pendingConfirm()
                                        else completeGoogleSwitch(pendingToken)
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.auth_guest_switch_confirm_proceed),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingAccountSwitchAction = null }) {
                                    Text(stringResource(R.string.auth_guest_switch_confirm_cancel))
                                }
                            },
                        )
                    }

                    AuthenticationScreen(
                        isWorking = isAuthenticating,
                        message = authenticationMessage,
                        customerCount = customers.size,
                        onSignIn = { email, password ->
                            val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
                                isAnonymous = authenticationGateway.isAnonymous,
                                customerCount = customers.size,
                                hasFinancialActivity = hasFinancialActivity,
                                targetEmail = email,
                            )
                            when (decision) {
                                is AccountSwitchDecision.Confirm -> {
                                    pendingAccountSwitchAction = PendingAccountSwitch(
                                        targetEmail = email,
                                        onConfirm = { performSignIn(email, password) },
                                    )
                                }
                                AccountSwitchDecision.Direct -> performSignIn(email, password)
                            }
                        },
                        onSignUp = { email, password ->
                            isAuthenticating = true
                            authenticationMessage = null
                            if (authenticationGateway.isAnonymous) {
                                authenticationGateway.linkEmail(email, password) { result ->
                                    isAuthenticating = false
                                    result.onSuccess { userId ->
                                        authenticationGateway.sendEmailVerification { }
                                        signedUpUserId = userId
                                        authenticatedUserId = userId
                                        showAccountLinking = false
                                        verificationKey++
                                    }.onFailure { error ->
                                        if (error is FirebaseAuthUserCollisionException) {
                                            val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
                                                isAnonymous = authenticationGateway.isAnonymous,
                                                customerCount = customers.size,
                                                hasFinancialActivity = hasFinancialActivity,
                                                targetEmail = email,
                                            )
                                            when (decision) {
                                                is AccountSwitchDecision.Confirm -> {
                                                    pendingAccountSwitchAction = PendingAccountSwitch(
                                                        targetEmail = email,
                                                        onConfirm = { performSignIn(email, password) },
                                                    )
                                                }
                                                AccountSwitchDecision.Direct -> performSignIn(email, password)
                                            }
                                        } else {
                                            crashReporter.report(CrashEvent.AUTH_FAILURE)
                                            authenticationMessage = signUpFailedMessage
                                        }
                                    }
                                }
                            } else {
                                authenticationGateway.signUp(email, password) { result ->
                                    isAuthenticating = false
                                    result.onSuccess { userId ->
                                        authenticationGateway.sendEmailVerification { }
                                        signedUpUserId = userId
                                        authenticatedUserId = userId
                                        showAccountLinking = false
                                        verificationKey++
                                    }.onFailure { error ->
                                        if (error is FirebaseAuthUserCollisionException) {
                                            authenticationMessage = emailInUseMessage
                                        } else {
                                            crashReporter.report(CrashEvent.AUTH_FAILURE)
                                            authenticationMessage = signUpFailedMessage
                                        }
                                    }
                                }
                            }
                        },
                        onModeChange = { authenticationMessage = null },
                        onResetPassword = { email ->
                            authenticationGateway.sendPasswordReset(email) { }
                        },
                        onGoogleSignIn = {
                            isAuthenticating = true
                            authenticationMessage = null
                            if (authenticationGateway.currentUserId == null) {
                                authenticationGateway.signInWithGoogle(this@MainActivity, handleGoogleResult)
                            } else {
                                authenticationGateway.linkGoogle(this@MainActivity, handleGoogleResult)
                            }
                        },
                    )
                } else if (shouldShowFirstDebt) {
                    FirstDebtScreen(
                        isSaving = isSavingFirstDebt,
                        onSave = { name, amountText ->
                            lifecycleScope.launch {
                                isSavingFirstDebt = true
                                try {
                                    val now = System.currentTimeMillis()
                                    var activeBusinessId = (state as? DashboardUiState.Content)?.businessId
                                    if (activeBusinessId == null) {
                                        val newBusiness = BusinessEntity(
                                            UUID.randomUUID().toString(),
                                            BusinessName.DEFAULT,
                                            currentUserId,
                                            "MXN",
                                            "America/Mexico_City",
                                            now,
                                            now,
                                        )
                                        database.businessDao().insert(newBusiness)
                                        syncQueue.enqueue(newBusiness.id, "BUSINESS", newBusiness.id, now, SyncPayloads.business(newBusiness))
                                        membershipBootstrap.ensure(newBusiness)
                                        activeBusinessId = newBusiness.id
                                    }
                                    val plan = OpeningBalancePlanner.plan(
                                        businessId = activeBusinessId,
                                        ownerUserId = currentUserId,
                                        rows = listOf(OpeningBalanceRow(name, amountText)),
                                        existingCustomers = emptyList(),
                                        todayEpochDay = LocalDate.now().toEpochDay(),
                                        nowEpochMillis = now,
                                        newId = { UUID.randomUUID().toString() },
                                    )
                                    if (plan.rejected.isEmpty() && plan.entries.isNotEmpty()) {
                                        database.withTransaction {
                                            plan.customers.forEach { database.customerDao().insert(it) }
                                            plan.entries.forEach { entry ->
                                                database.financialDao().createSaleIdempotently(
                                                    OperationEntity("sale-${entry.sale.id}", "SALE", entry.sale.id, now),
                                                    entry.sale,
                                                    listOf(entry.item),
                                                    entry.charge,
                                                )
                                            }
                                        }
                                        plan.customers.forEach { syncQueue.enqueue(it.businessId, "CUSTOMER", it.id, now, SyncPayloads.customer(it)) }
                                        plan.entries.forEach { entry ->
                                            syncQueue.enqueue(entry.sale.businessId, "SALE", entry.sale.id, now, SyncPayloads.sale(entry.sale))
                                            syncQueue.enqueue(entry.sale.businessId, "SALE_ITEM", entry.item.id, now, SyncPayloads.saleItem(entry.item))
                                            syncQueue.enqueue(entry.charge.businessId, "MOVEMENT", entry.charge.id, now, SyncPayloads.movement(entry.charge))
                                        }
                                        firstDebtPreferenceStore.markDismissed(currentUserId)
                                        hasSkippedFirstDebt = true
                                        refreshKey++
                                        uiMessage = getString(R.string.first_debt_created_message)
                                    }
                                } finally {
                                    isSavingFirstDebt = false
                                }
                            }
                        },
                        onSkip = {
                            firstDebtPreferenceStore.markDismissed(currentUserId)
                            hasSkippedFirstDebt = true
                            if (state is DashboardUiState.NoBusiness) {
                                lifecycleScope.launch {
                                    val now = System.currentTimeMillis()
                                    val newBusiness = BusinessEntity(
                                        UUID.randomUUID().toString(),
                                        BusinessName.DEFAULT,
                                        currentUserId,
                                        "MXN",
                                        "America/Mexico_City",
                                        now,
                                        now,
                                    )
                                    database.businessDao().insert(newBusiness)
                                    syncQueue.enqueue(newBusiness.id, "BUSINESS", newBusiness.id, now, SyncPayloads.business(newBusiness))
                                    membershipBootstrap.ensure(newBusiness)
                                    refreshKey++
                                }
                            }
                        },
                    )
                } else {
                    val ledgerExtractionGateway = remember { com.cuentivo.app.ai.ledger.LedgerExtractionGateway() }
                    CuentivoApp(
                        state,
                        customers,
                        products,
                        tier,
                        customerScores = customerScores,
                        proPrice = proPriceState.value,
                        aiPrice = aiPriceState.value,
                        proTrialMonths = proTrialMonthsState.value,
                        aiTrialMonths = aiTrialMonthsState.value,
                        accountLabel = authenticationGateway.currentUserEmail,
                        isAnonymous = authenticationGateway.isAnonymous,
                        onSignUp = { showAccountLinking = true },
                    isSavingCustomer = isSavingCustomer,
                    customerSavedToken = customerSavedToken,
                    pendingSaleCustomerId = pendingSaleCustomerId,
                    onPendingSaleConsumed = { pendingSaleCustomerId = null },
                    lastCreatedCustomerId = lastCreatedCustomerId,
                    isImportingBalances = isImportingBalances,
                    balancesImportedToken = balancesImportedToken,
                    isSavingProduct = isSavingProduct,
                    productSavedToken = productSavedToken,
                    aiDailyChatRepository = aiDailyChatRepository,
                    aiAvailabilityGateway = aiAvailabilityGateway,
                    mexalmLedgerWarmGateway = mexalmLedgerWarmGateway,
                    paymentPreviewResolver = aiPaymentPreviewResolver::resolve,
                    confirmPayment = { request ->
                        val activeBusinessId = (state as? DashboardUiState.Content)?.businessId
                        if (activeBusinessId == null || activeBusinessId != request.businessId || currentUserId.isBlank()) {
                            AiPaymentExecutionResult.Rejected
                        } else {
                            val result = aiPaymentExecutionService.confirm(request)
                            if (result is AiPaymentExecutionResult.Recorded) {
                                refreshKey++
                            }
                            result
                        }
                    },
                    salePreviewResolver = aiSalePreviewResolver::resolve,
                    // Reading a photographed notebook page goes through the same paid boundary as the rest of
                    // CuentivoAI: the callable enforces auth, App Check, entitlement and quota, and the client
                    // only carries the image there and the operations back.
                    // The review shows what each customer already owed, so it has to come from the
                    // ledger itself. Without it every customer looks new with a zero balance and
                    // legitimate payments get refused for having no debt to pay.
                    // Writing a photographed page: plan first with real debts, then one atomic
                    // transaction. Returns how many operations were recorded, or null if nothing
                    // was written, so the screen can tell the truth either way.
                    confirmLedgerBatch = { businessId, importSessionId, operations, pageDateEpochDay, dueDateDays, blockedLineIndices, modelSaysTextMayBeMissing, isPageCompletenessConfirmed ->
                        val dao = database.financialDao()
                        val repository = com.cuentivo.app.domain.SaleRepository(dao)
                        val today = java.time.LocalDate.now().toEpochDay()
                        // v44: confirmar y, si hubo escritura, refrescar. La conducta vive en el
                        // objeto puro `LedgerConfirmAndRefresh` (probado con lambdas), no incrustada
                        // aquí: sin el refresco la hoja quedaba escrita y la pantalla seguía mostrando
                        // el estado anterior, y la persona volvía a registrar la misma página (el
                        // duplicado de Laura). El callback corre en Dispatchers.IO, así que el
                        // refresco se pide al hilo principal.
                        com.cuentivo.app.domain.LedgerConfirmAndRefresh.confirmAndRefresh(
                            confirm = {
                                com.cuentivo.app.domain.LedgerBatchCoordinator.confirm(
                                    businessId = businessId,
                                    userId = currentUserId,
                                    importSessionId = importSessionId,
                                    operations = operations,
                                    blockedLineIndices = blockedLineIndices,
                                    modelSaysTextMayBeMissing = modelSaysTextMayBeMissing,
                                    isPageCompletenessConfirmed = isPageCompletenessConfirmed,
                                    pageDateEpochDay = pageDateEpochDay,
                                    dueDateDays = dueDateDays,
                                    todayEpochDay = today,
                                    nowEpochMillis = System.currentTimeMillis(),
                                    alreadyRecorded = { key -> dao.operationResultId(key) != null },
                                    loadCustomersByNormalizedName = {
                                        database.customerDao().activeWithBalances(businessId)
                                            .associate { row -> row.customer.normalizedName to row.customer }
                                    },
                                    loadOutstandingByCustomerId = {
                                        database.customerDao().activeWithBalances(businessId).associate { row ->
                                            row.customer.id to dao.outstandingSalesForCustomer(businessId, row.customer.id)
                                                .map { com.cuentivo.app.domain.OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
                                        }
                                    },
                                    write = { plan -> repository.registerLedgerBatch(plan) },
                                    enqueue = { plan ->
                                        // Best effort, exactly like the rest of this app: a row already
                                        // committed must not be reported as failed because the outbox
                                        // hiccuped. The known cost is the structural gap CLAUDE.md documents.
                                        val stamp = plan.nowEpochMillis
                                        runCatching {
                                            plan.newCustomers.forEach { syncQueue.enqueue(it.businessId, "CUSTOMER", it.id, stamp, SyncPayloads.customer(it)) }
                                            plan.sales.forEach { syncQueue.enqueue(it.businessId, "SALE", it.id, stamp, SyncPayloads.sale(it)) }
                                            plan.saleItems.forEach { syncQueue.enqueue(businessId, "SALE_ITEM", it.id, stamp, SyncPayloads.saleItem(it)) }
                                            plan.chargeMovements.forEach { syncQueue.enqueue(it.businessId, "MOVEMENT", it.id, stamp, SyncPayloads.movement(it)) }
                                            plan.payments.forEach { syncQueue.enqueue(it.businessId, "PAYMENT", it.id, stamp, SyncPayloads.payment(it)) }
                                            plan.paymentAllocations.forEach { syncQueue.enqueue(businessId, "PAYMENT_ALLOCATION", it.id, stamp, SyncPayloads.allocation(it)) }
                                            plan.paymentMovements.forEach { syncQueue.enqueue(it.businessId, "MOVEMENT", it.id, stamp, SyncPayloads.movement(it)) }
                                        }
                                    },
                                )
                            },
                            onRefresh = { withContext(Dispatchers.Main) { refreshKey++ } },
                        )
                    },
                    ledgerBalancesResolver = { businessId ->
                        database.customerDao().activeWithBalances(businessId).associate { row ->
                            row.customer.normalizedName to row.balanceCents
                        }
                    },
                    ledgerRegisteredResolver = { businessId, minEpochDay, maxEpochDay ->
                        // Bounded to the days around today: the sheet being re-photographed is a
                        // recent one, and scanning the whole history would neither find more real
                        // duplicates nor stay fast once a shop has years of sales. The rows come
                        // back already excluding anything cancelled, because a cancelled sale is
                        // not a debt any more and registering it again is legitimate.
                        database.financialDao()
                            .getRegisteredOperationsInRange(businessId, minEpochDay, maxEpochDay)
                            .mapNotNull { row ->
                                val kind = when (row.kindName) {
                                    "SALE" -> com.cuentivo.app.ai.ledger.ProposedLedgerKind.SALE
                                    "PAYMENT" -> com.cuentivo.app.ai.ledger.ProposedLedgerKind.PAYMENT
                                    "PRIOR_DEBT" -> com.cuentivo.app.ai.ledger.ProposedLedgerKind.PRIOR_DEBT
                                    // A kind this build does not know is dropped rather than
                                    // guessed: a wrong kind here invents a duplicate that is not one.
                                    else -> null
                                }
                                kind?.let {
                                    com.cuentivo.app.domain.RegisteredOperationProjection(
                                        id = row.id,
                                        customerId = row.customerId,
                                        customerName = row.customerName,
                                        amountCents = row.amountCents,
                                        effectiveDateEpochDay = row.effectiveDateEpochDay,
                                        kind = it,
                                        description = row.description,
                                    )
                                }
                            }
                    },
                    ledgerProductsResolver = { businessId ->
                        // searchActive with empty queries is the existing way to say "all the
                        // active ones": both LIKE clauses match everything. Archived and deleted
                        // products stay out, same as everywhere else in the app.
                        database.productDao().searchActive(businessId, "", "").map { product ->
                            com.cuentivo.app.domain.ProductCandidate(
                                id = product.id,
                                name = product.name,
                                salePriceCents = product.salePriceCents,
                                sku = product.sku,
                            )
                        }
                    },
                    ledgerCustomersResolver = { businessId ->
                        // Only active, undeleted customers: the same set the rest of the app treats
                        // as existing. An open balance stands in for "recently active" -- the query
                        // already computes it, and somebody who owes money is far likelier to be the
                        // person a notebook line is about than one settled up a year ago.
                        database.customerDao().activeWithBalances(businessId).map { row ->
                            com.cuentivo.app.domain.CustomerCandidate(
                                id = row.customer.id,
                                name = row.customer.name,
                                hasRecentActivity = row.balanceCents != 0L,
                            )
                        }
                    },
                    extractLedgerPage = { requestId, imageBase64, imageMimeType ->
                        ledgerExtractionGateway.extractPage(requestId, imageBase64, imageMimeType)
                    },
                    // Local import metrics: counts only, never synced, never a reason for the
                    // import itself to fail. See AiLedgerImportMetricEntity for what is NOT here.
                    ledgerImportMetricDao = database.aiLedgerImportMetricDao(),
                    confirmSale = { request ->
                        val activeBusinessId = (state as? DashboardUiState.Content)?.businessId
                        if (activeBusinessId == null || activeBusinessId != request.businessId || currentUserId.isBlank()) {
                            AiSaleExecutionResult.Rejected
                        } else {
                            val salesSoFar = database.financialDao().registeredSaleCount(request.businessId)
                            val customerDecision = if (request.newCustomer != null) {
                                FeatureAccessManager.canAddCustomer(tier, customers.size)
                            } else {
                                AccessDecision.Allowed
                            }
                            val saleDecision = FeatureAccessManager.canRegisterSale(tier, salesSoFar)

                            if (customerDecision is AccessDecision.Blocked) {
                                blocked = customerDecision
                                AiSaleExecutionResult.LimitReached
                            } else if (saleDecision is AccessDecision.Blocked) {
                                blocked = saleDecision
                                AiSaleExecutionResult.LimitReached
                            } else {
                                val result = aiSaleExecutionService.confirm(request)
                                if (result is AiSaleExecutionResult.Recorded) {
                                    refreshKey++
                                    // El saldo que quedó, para que el "Venta registrada con éxito"
                                    // diga cuánto debe ahora (v44). El nombre puede venir del estado
                                    // cargado o del cliente recién creado por la misma venta.
                                    val name = customers.firstOrNull { it.customer.id == request.customerId }?.customer?.name
                                        ?: request.newCustomer?.displayName
                                    val balance = database.financialDao()
                                        .customerBalanceCents(request.businessId, request.customerId)
                                    result.copy(customerName = name, balanceAfterCents = balance)
                                } else {
                                    result
                                }
                            }
                        }
                    },
                    businessQueryResolver = aiBusinessQueryResolver::resolve,
                    onCreateBusiness = {
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val business = BusinessEntity(UUID.randomUUID().toString(), BusinessName.DEFAULT, currentUserId, "MXN", "America/Mexico_City", now, now)
                        database.businessDao().insert(business)
                        syncQueue.enqueue(business.id, "BUSINESS", business.id, now, SyncPayloads.business(business))
                        membershipBootstrap.ensure(business)
                        refreshKey++
                        uiMessage = getString(R.string.message_business_created)
                    }
                }, onCreateCustomer = { newCustomer ->
                    val name = newCustomer.name
                    val phone = newCustomer.phone
                    val creditLimitCents = newCustomer.creditLimitCents
                    val quickSale = newCustomer.quickSale
                    val openingBalanceCents = newCustomer.openingBalanceCents
                    // Cada intento empieza limpio: el aviso de abajo sólo vale para el intento que lo puso.
                    customerFormNotice = null
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    if (businessId == null) {
                        // Al snackbar y al propio formulario: con el diálogo abierto, el snackbar queda
                        // detrás del diálogo y "Guardar" parece no hacer nada (v46).
                        val notice = getString(R.string.message_create_business_first)
                        uiMessage = notice
                        customerFormNotice = notice
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
                                // An opening balance is a sale too, so it counts here. Both are
                                // checked against the count *before* either is written: allowing the
                                // first and refusing the second would leave the person half migrated
                                // with no way to tell which half.
                                val salesToWrite = listOfNotNull(quickSale, openingBalanceCents).size
                                if (salesToWrite > 0) {
                                    val salesSoFar = database.financialDao().registeredSaleCount(businessId)
                                    val saleDecision = FeatureAccessManager.canRegisterSale(tier, salesSoFar + salesToWrite - 1)
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
                                // Each row resolved against the catalog. A row picked from it keeps
                                // its product; a hand-written one is reused when the name already
                                // exists — `products` has no unique index, so without this every new
                                // customer taking a "Jabón" would mint another one — and only minted
                                // when it is genuinely new.
                                val newProducts = mutableListOf<ProductEntity>()
                                val resolved = quickSale?.lines.orEmpty().map { line ->
                                    val existing = line.productId?.let { id -> products.firstOrNull { it.id == id } }
                                        ?: CustomerNameNormalizer.key(line.productName).let { key ->
                                            products.firstOrNull { CustomerNameNormalizer.key(it.name) == key }
                                                ?: newProducts.firstOrNull { CustomerNameNormalizer.key(it.name) == key }
                                        }
                                    val product = existing ?: ProductEntity(UUID.randomUUID().toString(), businessId, line.productName, null, null, null, line.unitPriceCents, null, null, line.photoFileName, true, now, now, null).also { newProducts += it }
                                    line to product
                                }
                                val saleId = UUID.randomUUID().toString()
                                val saleItems = resolved.map { (line, product) ->
                                    SaleItemEntity(UUID.randomUUID().toString(), saleId, product.id, line.productName, line.quantity.toLong(), line.unitPriceCents, 0L, line.totalCents)
                                }
                                val saleEntity = quickSale?.let { cart ->
                                    // The agreed date the form asked for. Resolved here rather than
                                    // in the domain, which stays free of a clock; null still means
                                    // "sin fecha" and keeps the sale out of the score, as chosen.
                                    val dueDateEpochDay = cart.dueInDays?.let { LocalDate.now().plusDays(it).toEpochDay() }
                                    SaleEntity(saleId, businessId, customer.id, currentUserId, "V-${now}", LocalDate.now().toEpochDay(), dueDateEpochDay, cart.totalCents, 0L, cart.totalCents, false, null, now, now)
                                }
                                val charge = quickSale?.let { cart ->
                                    AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.id, saleId, null, "CHARGE", cart.totalCents, "DEBIT", now, currentUserId, null, null)
                                }
                                // What they already owed before the app. A real sale with a single
                                // product-less line, so it can be paid off, chased and shown like
                                // any other debt — see OpeningBalance for why not an adjustment.
                                val opening = openingBalanceCents?.let { amountCents ->
                                    OpeningBalance.entry(
                                        saleId = UUID.randomUUID().toString(),
                                        itemId = UUID.randomUUID().toString(),
                                        chargeId = UUID.randomUUID().toString(),
                                        businessId = businessId,
                                        customerId = customer.id,
                                        ownerUserId = currentUserId,
                                        amountCents = amountCents,
                                        todayEpochDay = LocalDate.now().toEpochDay(),
                                        nowEpochMillis = now,
                                        // El plazo que la persona eligió en el formulario. Sin él la
                                        // deuda anterior nacía sin fecha y nunca vencía (v44).
                                        dueInDays = newCustomer.openingBalanceDueInDays,
                                    )
                                }
                                val saved = try {
                                    // One transaction: a customer who exists without the sale they
                                    // walked out with is worse than nothing happening at all.
                                    database.withTransaction {
                                        database.customerDao().insert(customer)
                                        newProducts.forEach { database.productDao().insert(it) }
                                        if (saleEntity != null && charge != null && saleItems.isNotEmpty()) {
                                            database.financialDao().createSaleIdempotently(
                                                OperationEntity("sale-$saleId", "SALE", saleId, now),
                                                saleEntity,
                                                saleItems,
                                                charge,
                                            )
                                        }
                                        opening?.let { entry ->
                                            database.financialDao().createSaleIdempotently(
                                                OperationEntity("sale-${entry.sale.id}", "SALE", entry.sale.id, now),
                                                entry.sale,
                                                listOf(entry.item),
                                                entry.charge,
                                            )
                                        }
                                    }
                                    true
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    Log.w(TAG, "No se pudo guardar el cliente.", error)
                                    uiMessage = getString(R.string.message_customer_name_taken)
                                    false
                                }
                                if (saved) {
                                    // Past here the customer exists on this device, so nothing after
                                    // it may hide it. The outbox write is best effort relative to a
                                    // row that is already saved.
                                    try {
                                        syncQueue.enqueue(businessId, "CUSTOMER", customer.id, now, SyncPayloads.customer(customer))
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Exception) {
                                        Log.w(TAG, "El cliente quedó guardado pero no se pudo encolar para sincronizar.", error)
                                    }
                                    // Best effort, like the customer row above: these are already
                                    // committed locally and nothing here may hide them.
                                    try {
                                        newProducts.forEach { created ->
                                            syncQueue.enqueue(businessId, "PRODUCT", created.id, now, SyncPayloads.product(created))
                                        }
                                        if (saleEntity != null && charge != null && saleItems.isNotEmpty()) {
                                            syncQueue.enqueue(businessId, "SALE", saleEntity.id, now, SyncPayloads.sale(saleEntity))
                                            saleItems.forEach { item ->
                                                syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, SyncPayloads.saleItem(item))
                                            }
                                            syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, SyncPayloads.movement(charge))
                                        }
                                        opening?.let { entry ->
                                            // Built from the rows themselves, not re-typed from the
                                            // locals nearby: that is how a payload loses a field.
                                            syncQueue.enqueue(businessId, "SALE", entry.sale.id, now, SyncPayloads.sale(entry.sale))
                                            syncQueue.enqueue(businessId, "SALE_ITEM", entry.item.id, now, SyncPayloads.saleItem(entry.item))
                                            syncQueue.enqueue(businessId, "MOVEMENT", entry.charge.id, now, SyncPayloads.movement(entry.charge))
                                        }
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Exception) {
                                        Log.w(TAG, "La venta rápida quedó guardada pero no se pudo encolar.", error)
                                    }
                                    refreshKey++
                                    uiMessage = when {
                                        quickSale != null -> getString(R.string.message_quick_sale_created, Money.format(quickSale.totalCents))
                                        // Same order as the writes above: the sale is the headline
                                        // when there is one, the opening balance when there is not.
                                        opening != null -> getString(R.string.message_opening_balance_created, Money.format(opening.sale.totalCents))
                                        else -> getString(R.string.message_customer_added)
                                    }
                                    // The only signal that closes the form. It advances only here,
                                    // so a failure above leaves it untouched and the dialog stays up.
                                    customerSavedToken++
                                    // Same rule, same place: the hand-off into Nueva venta only
                                    // happens for a customer that actually reached Room.
                                    lastCreatedCustomerId = customer.id
                                    if (newCustomer.continueToSale) pendingSaleCustomerId = customer.id
                                }
                            } finally {
                                isSavingCustomer = false
                            }
                        }
                    }
                }, onImportOpeningBalances = { rows ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    if (businessId == null) {
                        uiMessage = getString(R.string.message_create_business_first)
                        return@CuentivoApp
                    }
                    lifecycleScope.launch {
                        isImportingBalances = true
                        try {
                            val now = System.currentTimeMillis()
                            val plan = OpeningBalancePlanner.plan(
                                businessId = businessId,
                                ownerUserId = currentUserId,
                                rows = rows,
                                existingCustomers = customers.map { it.customer },
                                todayEpochDay = LocalDate.now().toEpochDay(),
                                nowEpochMillis = now,
                                newId = { UUID.randomUUID().toString() },
                            )
                            // The sheet already refuses to confirm a batch with bad rows, so this is
                            // the belt to that braces: the import is all-or-nothing and nothing
                            // financial can be edited afterwards, so a half-planned batch is not
                            // something to write and apologise for.
                            if (plan.rejected.isNotEmpty() || plan.isEmpty) {
                                uiMessage = getString(R.string.message_opening_import_failed)
                                return@launch
                            }
                            // Both plan limits over the whole batch, before the first write. Checking
                            // row by row would stop halfway and leave the person unable to tell which
                            // debts made it in.
                            val customerDecision = FeatureAccessManager.canAddCustomer(
                                tier,
                                customers.size + plan.customers.size - 1,
                            )
                            if (customerDecision is AccessDecision.Blocked) {
                                blocked = customerDecision
                                return@launch
                            }
                            val salesSoFar = database.financialDao().registeredSaleCount(businessId)
                            val saleDecision = FeatureAccessManager.canRegisterSale(
                                tier,
                                salesSoFar + plan.entries.size - 1,
                            )
                            if (saleDecision is AccessDecision.Blocked) {
                                blocked = saleDecision
                                return@launch
                            }
                            val saved = try {
                                database.withTransaction {
                                    plan.customers.forEach { database.customerDao().insert(it) }
                                    plan.entries.forEach { entry ->
                                        database.financialDao().createSaleIdempotently(
                                            OperationEntity("sale-${entry.sale.id}", "SALE", entry.sale.id, now),
                                            entry.sale,
                                            listOf(entry.item),
                                            entry.charge,
                                        )
                                    }
                                }
                                true
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                Log.w(TAG, "No se pudo guardar la libreta de deudas.", error)
                                uiMessage = getString(R.string.message_opening_import_failed)
                                false
                            }
                            if (saved) {
                                // Best effort past this point, like every other write path: the rows
                                // are already committed locally and nothing here may hide them.
                                try {
                                    plan.customers.forEach {
                                        syncQueue.enqueue(businessId, "CUSTOMER", it.id, now, SyncPayloads.customer(it))
                                    }
                                    plan.entries.forEach { entry ->
                                        syncQueue.enqueue(businessId, "SALE", entry.sale.id, now, SyncPayloads.sale(entry.sale))
                                        syncQueue.enqueue(businessId, "SALE_ITEM", entry.item.id, now, SyncPayloads.saleItem(entry.item))
                                        syncQueue.enqueue(businessId, "MOVEMENT", entry.charge.id, now, SyncPayloads.movement(entry.charge))
                                    }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    Log.w(TAG, "La libreta quedó guardada pero no se pudo encolar.", error)
                                }
                                refreshKey++
                                uiMessage = getString(
                                    R.string.message_opening_import_done,
                                    plan.entries.size,
                                    Money.format(plan.totalCents),
                                )
                                // Closes the sheet, and only on a write that landed.
                                balancesImportedToken++
                            }
                        } finally {
                            isImportingBalances = false
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
                            syncQueue.enqueue(businessId, "CUSTOMER", updated.id, now, SyncPayloads.customer(updated))
                            refreshKey++
                            uiMessage = getString(R.string.message_customer_updated)
                        }.onFailure {
                            uiMessage = getString(R.string.message_customer_name_taken)
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
                        val archived = row.customer.copy(isActive = false, deletedAtEpochMillis = now, updatedAtEpochMillis = now)
                        // The local tombstone and its durable outbox row are one SQLite transaction.
                        // If the process dies after hiding the row, a later reinstall must not pull
                        // the still-active cloud copy and resurrect it.
                        database.withTransaction {
                            database.customerDao().archive(row.customer.id, now)
                            // Soft delete travels as an ordinary update: firestore.rules forbids
                            // client-side deletes in every collection.
                            syncQueue.enqueue(businessId, "CUSTOMER", archived.id, now, SyncPayloads.customer(archived))
                        }
                        refreshKey++
                        uiMessage = getString(R.string.message_removed, row.customer.name)
                    }
                }, onCreateProduct = { name, priceCents, imageFileName ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    if (businessId == null) {
                        uiMessage = getString(R.string.message_create_business_first)
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
                                uiMessage = getString(R.string.message_product_save_failed)
                                false
                            }
                            if (saved) {
                                try {
                                    syncQueue.enqueue(businessId, "PRODUCT", product.id, now, SyncPayloads.product(product))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Exception) {
                                    Log.w(TAG, "El producto quedó guardado pero no se pudo encolar para sincronizar.", error)
                                }
                                refreshKey++
                                uiMessage = getString(R.string.message_product_added)
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
                        syncQueue.enqueue(businessId, "PRODUCT", updated.id, now, SyncPayloads.product(updated))
                        refreshKey++
                        uiMessage = getString(R.string.message_product_updated)
                    }
                }, onArchiveProduct = { product ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        // No balance guard here: sale_items keeps the name and price charged on each
                        // past sale, so removing the product from the catalog rewrites nothing.
                        val archived = product.copy(isActive = false, deletedAtEpochMillis = now, updatedAtEpochMillis = now)
                        // Keep the archive tombstone and its outbox row atomic. Otherwise a process
                        // death between these calls leaves Firestore active and a clean reinstall
                        // can legitimately download the old catalog entry again.
                        database.withTransaction {
                            database.productDao().archive(product.id, now)
                            syncQueue.enqueue(businessId, "PRODUCT", archived.id, now, SyncPayloads.product(archived))
                        }
                        refreshKey++
                        uiMessage = getString(R.string.message_removed, product.name)
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
                                    syncQueue.enqueue(importBusinessId, "PRODUCT", product.id, now, SyncPayloads.product(product))
                                }
                                result
                            }?.onSuccess { result ->
                                refreshKey++
                                importReport = result
                                uiMessage = when {
                                    result.accepted.isEmpty() && result.rejected.isEmpty() ->
                                        getString(R.string.message_import_no_products)
                                    result.rejected.isEmpty() ->
                                        getString(R.string.message_import_products_ok, result.accepted.size)
                                    else ->
                                        getString(
                                            R.string.message_import_products_partial,
                                            result.accepted.size,
                                            result.rejected.size,
                                        )
                                }
                            }?.onFailure {
                                uiMessage = getString(R.string.message_file_read_failed)
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
                                syncQueue.enqueue(businessId, "SALE", sale.id, now, SyncPayloads.sale(sale))
                                items.forEach { item ->
                                    syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, SyncPayloads.saleItem(item))
                                }
                                syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, SyncPayloads.movement(charge))
                                refreshKey++
                                // El saldo real, leído del libro mayor —no calculado en la UI— y
                                // con él el aviso: sin esto el formulario se vaciaba en la misma
                                // pantalla y no había nada visible de que la venta se registró.
                                val balanceCents = database.financialDao().customerBalanceCents(businessId, customer.customer.id)
                                uiMessage = getString(
                                    R.string.message_sale_registered_balance,
                                    balancePhrase(customer.customer.name, balanceCents),
                                )
                                // Y se lleva al detalle de esa clienta, que es donde la persona ve
                                // cómo quedó la deuda.
                                loadStatement(businessId, customer)
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
                        syncQueue.enqueue(businessId, "PAYMENT", payment.id, now, SyncPayloads.payment(payment))
                        allocations.forEach { allocation ->
                            syncQueue.enqueue(businessId, "PAYMENT_ALLOCATION", allocation.id, now, SyncPayloads.allocation(allocation))
                        }
                        syncQueue.enqueue(businessId, "MOVEMENT", movement.id, now, SyncPayloads.movement(movement))
                        refreshKey++
                        // "Abono aplicado a 2 venta(s)" no le dice nada a la persona, que lo que
                        // quiere saber es cuánto le queda debiendo. El saldo sale del libro mayor.
                        val balanceCents = database.financialDao().customerBalanceCents(businessId, customer.customer.id)
                        uiMessage = getString(
                            R.string.message_payment_registered_balance,
                            balancePhrase(customer.customer.name, balanceCents),
                        )
                        loadStatement(businessId, customer)
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
                            syncQueue.enqueue(businessId, "MOVEMENT", cancellation.id, now, SyncPayloads.movement(cancellation))
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
                        // A sale with no lines at all cannot be rebuilt into a draft. A line with no
                        // product id now can: the name and price were snapshotted onto the line when
                        // it was sold, which is also what makes a hand-written line correctable and
                        // what finally lets the sales imported from the old web app be fixed.
                        if (soldItems.isEmpty()) {
                            uiMessage = getString(R.string.message_sale_not_editable)
                            return@launch
                        }
                        saleCorrection = SaleCorrection(
                            saleId = sale.id,
                            saleNumber = sale.saleNumber,
                            draft = SaleDraft(
                                lines = soldItems.map { soldItem ->
                                    SaleDraftLine(
                                        lineId = soldItem.id,
                                        productId = soldItem.productId,
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
                            syncQueue.enqueue(businessId, "SALE", sale.id, now, SyncPayloads.sale(sale))
                            items.forEach { item ->
                                syncQueue.enqueue(businessId, "SALE_ITEM", item.id, now, SyncPayloads.saleItem(item))
                            }
                            syncQueue.enqueue(businessId, "MOVEMENT", charge.id, now, SyncPayloads.movement(charge))
                            // The cancellation travels as its own ledger row: sales are create-only
                            // in firestore.rules, so the void can never go up as an edit.
                            syncQueue.enqueue(businessId, "MOVEMENT", cancellation.id, now, SyncPayloads.movement(cancellation))
                            carriedAllocations.forEach { allocation ->
                                syncQueue.enqueue(businessId, "PAYMENT_ALLOCATION", allocation.id, now, SyncPayloads.allocation(allocation))
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
                        // The same tier the plan card below is drawn from, so the two cannot
                        // disagree on screen the way they did.
                        hasCloudBackup = SyncEntitlement.allowsCloudSync(tier),
                        isAnonymous = authenticationGateway.isAnonymous,
                        customerCount = customers.size,
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
                            DebtorShareText.forCustomer(shareTier, businessName, row.customer.name, row.balanceCents),
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
                            syncQueue.enqueue(businessId, "MOVEMENT", refund.id, now, SyncPayloads.movement(refund))
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
                            syncQueue.enqueue(businessId, "CUSTOMER", updated.id, now, SyncPayloads.customer(updated))
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
                        share(
                            getString(R.string.share_statement_subject, row.customer.name),
                            CustomerStatementCsv.build(statementEntries),
                        )
                    }
                }, onShareStatementText = {
                    statementCustomer?.let { row ->
                        // Both payloads are built from this one customer's already-scoped statement.
                        // The pure image builder receives today's date at this Android boundary, never
                        // reads a clock itself, so its content remains deterministic in JVM tests.
                        //
                        // v44: con imagen, el texto va corto (los movimientos ya van en la imagen).
                        // Si la imagen falla, se comparte el texto completo de siempre: la persona
                        // pierde la imagen, no el estado de cuenta. La elección vive en el objeto
                        // puro `StatementShareTextChoice`, no aquí.
                        val subject = getString(R.string.share_statement_subject, row.customer.name)
                        val shortText = DebtorShareText.forCustomerWithImage(
                            shareTier,
                            businessName,
                            row.customer.name,
                            row.balanceCents,
                        )
                        val fullText = DebtorShareText.forCustomer(
                            shareTier,
                            businessName,
                            row.customer.name,
                            row.balanceCents,
                            statementEntries,
                        )
                        val content = StatementImage.build(
                            businessName = businessName,
                            customerName = row.customer.name,
                            balanceCents = row.balanceCents,
                            entries = statementEntries,
                            tier = shareTier,
                            statementDateIso = LocalDate.now().toString(),
                        )
                        shareStatementImage(subject = subject, shortText = shortText, fullText = fullText, content = content)
                    }
                }, onShareDebtorList = {
                    val debtors = customers.map { Debtor(it.customer.name, it.balanceCents) }
                    share(getString(R.string.share_debtor_list_subject), DebtorShareText.forBusiness(shareTier, businessName, debtors))
                }, alerts = alerts, onOpenAlertCustomer = { customerId ->
                    // The alert only knows who; the statement sheet needs the row with the balance,
                    // which the screen already has loaded.
                    val businessId = (state as? DashboardUiState.Content)?.businessId
                    val row = customers.firstOrNull { it.customer.id == customerId }
                    // loadStatement sets statementCustomer itself, so the sheet opens from here too.
                    if (businessId != null && row != null) {
                        lifecycleScope.launch { loadStatement(businessId, row) }
                    }
                }, blocked = blocked, onDismissBlocked = { blocked = null }, onUpgrade = { targetTier ->
                    blocked = null
                    // The real Activity, not applicationContext: Play needs somewhere to put its
                    // sheet. The uid travels with it so the purchase is credited to whoever opened
                    // the flow, even if the session changes before Play calls back.
                    billing.launchPurchase(
                        activity = this@MainActivity,
                        userId = currentUserId,
                        targetTier = targetTier,
                        isAnonymous = authenticationGateway.isAnonymous,
                    )
                }, onSignOut = {
                    authenticationGateway.signOut()
                    authenticationGateway.clearGoogleCredentialState { }
                    // A sign-out must never leave the previous owner's reminder job behind.
                    CollectionReminderScheduler.cancel(this@MainActivity)
                    authenticatedUserId = null
                    showManualAuthentication = true
                }, onDeleteAccount = {
                    val businessToErase = (state as? DashboardUiState.Content)?.businessId
                    lifecycleScope.launch {
                        // v44: el orden es re-autenticar → nube → local → Auth, y la Activity
                        // **despacha por el `Step`** del plan puro en vez de codificar los pasos
                        // aparte, para que la prueba del plan pruebe lo que de verdad corre.
                        //
                        // Antes se borraba la nube y Room y **al final** se intentaba borrar el
                        // usuario de Auth; con una sesión vieja Firebase lo rechazaba y la persona
                        // quedaba con sesión abierta y sin datos (el estado raro de Diego). Y aunque
                        // la re-autenticación baje la probabilidad, `delete()` todavía puede fallar
                        // por red **después** de borrar: por eso hay una marca durable y una salida
                        // segura (cerrar sesión igualmente) para ese caso.
                        val uid = authenticationGateway.currentUserId
                        var step = com.cuentivo.app.domain.AccountDeletionPlan.firstStep(authenticationGateway.isAnonymous)

                        while (step != com.cuentivo.app.domain.AccountDeletionPlan.Step.DONE) {
                            when (step) {
                                com.cuentivo.app.domain.AccountDeletionPlan.Step.REAUTHENTICATE -> {
                                    val reauth = reauthenticateWithGoogle(authenticationGateway)
                                    if (reauth !is GoogleAuthenticationResult.SignedIn) {
                                        uiMessage = when (reauth) {
                                            GoogleAuthenticationResult.Cancelled ->
                                                getString(R.string.message_account_delete_reauth_cancelled)
                                            else ->
                                                getString(R.string.message_account_delete_reauth_failed)
                                        }
                                        return@launch
                                    }
                                }

                                com.cuentivo.app.domain.AccountDeletionPlan.Step.ERASE_REMOTE -> {
                                    // La marca durable va ANTES de la parte destructiva: si el proceso
                                    // muere a mitad, es lo que permite retomar al siguiente arranque.
                                    if (uid != null) pendingAccountDeletionStore.mark(uid)
                                    val remoteErased = withContext(Dispatchers.IO) {
                                        if (businessToErase == null) {
                                            // Nothing ever reached the cloud; nothing to erase remotely.
                                            true
                                        } else {
                                            val requested = firestoreGateway.requestBusinessDeletion(businessToErase, System.currentTimeMillis())
                                            requested.isSuccess && deletionGateway.deleteBusiness(businessToErase).isSuccess
                                        }
                                    }
                                    if (!AccountDeletionFailure.mayWipeLocal(remoteErased)) {
                                        // Nada destructivo ocurrió todavía: se puede reintentar con todo
                                        // intacto, así que la marca se retira.
                                        pendingAccountDeletionStore.clear()
                                        uiMessage = getString(R.string.message_account_delete_cloud_failed)
                                        return@launch
                                    }
                                }

                                com.cuentivo.app.domain.AccountDeletionPlan.Step.WIPE_LOCAL -> {
                                    // clearAllTables() is a blocking Room call and Room refuses to run
                                    // one on the main thread; lifecycleScope launches on Main, so this
                                    // used to crash the app the moment the button was confirmed.
                                    withContext(Dispatchers.IO) { database.clearAllTables() }
                                    sessionGuard.forget()
                                    // The entitlement dies with the account it belonged to; leaving it
                                    // behind would hand Pro to whoever signs up next on this phone.
                                    entitlementStore.forget()
                                }

                                com.cuentivo.app.domain.AccountDeletionPlan.Step.DELETE_AUTH -> {
                                    // Se detecta por TIPO, no por el texto del código: en Android la
                                    // excepción es `FirebaseAuthRecentLoginRequiredException` y el
                                    // código `ERROR_REQUIRES_RECENT_LOGIN` (el `requires-recent-login`
                                    // es el formato web, y comparar contra él hacía que el aviso nunca
                                    // se mostrara).
                                    val outcome = deleteAccountAndAwait(authenticationGateway)
                                    outcome.onSuccess {
                                        pendingAccountDeletionStore.clear()
                                        authenticationGateway.clearGoogleCredentialState { }
                                        // A deleted account must not leave a reminder job for it behind.
                                        CollectionReminderScheduler.cancel(this@MainActivity)
                                        authenticatedUserId = null
                                        // Fuera de la sesión y a la pantalla de inicio de sesión, sin
                                        // reiniciar la app.
                                        showManualAuthentication = true
                                    }.onFailure { error ->
                                        val action = com.cuentivo.app.domain.AccountDeletionPlan.onFailure(
                                            step = com.cuentivo.app.domain.AccountDeletionPlan.Step.DELETE_AUTH,
                                            authErrorCode = (error as? FirebaseAuthException)?.errorCode,
                                        )
                                        // Los datos YA se borraron. Nunca se puede quedar la app con
                                        // sesión abierta y sin datos: se cierra la sesión igualmente y
                                        // se dice la verdad. La marca durable queda puesta para que al
                                        // volver a entrar se termine el borrado de Auth.
                                        authenticationGateway.signOut()
                                        authenticationGateway.clearGoogleCredentialState { }
                                        CollectionReminderScheduler.cancel(this@MainActivity)
                                        authenticatedUserId = null
                                        showManualAuthentication = true
                                        uiMessage = when {
                                            error is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException ->
                                                getString(R.string.message_account_delete_recent_login)
                                            action == com.cuentivo.app.domain.AccountDeletionPlan.FailureAction.ASK_RECENT_LOGIN ->
                                                getString(R.string.message_account_delete_recent_login)
                                            else ->
                                                getString(R.string.message_account_delete_data_gone_auth_failed)
                                        }
                                    }
                                }

                                com.cuentivo.app.domain.AccountDeletionPlan.Step.DONE -> Unit
                            }
                            step = com.cuentivo.app.domain.AccountDeletionPlan.nextStepAfterSuccess(step)
                        }
                    }
                }, reminderEnabled = reminderEnabled, reminderHour = reminderHour,
                debugForceFreeShare = if (BuildConfig.DEBUG) debugForceFreeShare else null,
                onDebugForceFreeShareChange = { debugForceFreeShare = it },
                onReminderEnabledChange = { wanted ->
                    if (wanted) {
                        // Only Android 13+ has a notification permission to ask for; below it the
                        // reminder just works. A denial keeps the setting off and explains why.
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            applyReminderEnabled(true)
                        }
                    } else {
                        applyReminderEnabled(false)
                    }
                }, onReminderHourChange = { hour ->
                    reminderPreferences.hourOfDay = hour
                    reminderHour = hour
                    CollectionReminderScheduler.sync(this@MainActivity)
                }, openOverdueCustomers = openOverdueCustomersState.value,
                onOpenOverdueCustomersConsumed = { openOverdueCustomersState.value = false },
                message = uiMessage ?: accountMessageState.value,
                customerFormNotice = customerFormNotice,
                onMessageShown = {
                    uiMessage = null
                    accountMessageState.value = null
                })
                }
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

    /**
     * "Rosa ahora debe $125" / "ya no debe nada" / "tiene $50 a favor", desde el saldo real.
     *
     * El signo decide la frase: mostrar "ahora debe -$50" sería una resta que nadie pidió. El saldo
     * llega del libro mayor (`customerBalanceCents`) y la presentación la decide `BalancePresenter`,
     * la misma que usa el archivo de clientes.
     */
    private fun balancePhrase(customerName: String, balanceCents: Long): String {
        val presentation = com.cuentivo.app.ui.customer.BalancePresenter.from(balanceCents)
        return when (presentation.kind) {
            com.cuentivo.app.ui.customer.BalanceKind.OWED ->
                getString(R.string.message_balance_owes, customerName, Money.format(presentation.amountCents))
            com.cuentivo.app.ui.customer.BalanceKind.CUSTOMER_CREDIT ->
                getString(R.string.message_balance_credit, customerName, Money.format(presentation.amountCents))
            com.cuentivo.app.ui.customer.BalanceKind.SETTLED ->
                getString(R.string.message_balance_settled, customerName)
        }
    }

    /**
     * Pide la credencial de Google y re-autentica la sesión, para el borrado de cuenta (v44).
     *
     * `suspendCancellableCoroutine` porque el gateway habla con callbacks: así el flujo de borrado
     * se lee de arriba abajo y la re-autenticación ocurre **antes** de borrar nada. La cancelación
     * del selector llega como [GoogleAuthenticationResult.Cancelled], no como excepción.
     */
    private suspend fun reauthenticateWithGoogle(
        gateway: com.cuentivo.app.auth.AuthenticationGateway,
    ): GoogleAuthenticationResult =
        suspendCancellableCoroutine { continuation ->
            gateway.reauthenticateWithGoogle(this@MainActivity) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    /**
     * Borra el usuario de Auth y espera el callback, para que el flujo de borrado pueda decidir qué
     * hacer con el resultado (cerrar sesión igualmente si falla) en vez de dejarlo en un callback
     * suelto que sólo muestra un mensaje.
     */
    private suspend fun deleteAccountAndAwait(
        gateway: com.cuentivo.app.auth.AuthenticationGateway,
    ): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            gateway.deleteAccount { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    /**
     * Shares one temporary PNG and removes it after the Sharesheet returns to this Activity.
     *
     * v44: si el PNG no se puede escribir (`createShareIntent` lanza: sin espacio, error de disco),
     * se comparte el **texto completo** por la ruta de texto en vez de dejar caer la app. La persona
     * pierde la imagen, no el estado de cuenta. Qué texto va en cada caso lo decide el objeto puro
     * `StatementShareTextChoice`.
     */
    private fun shareStatementImage(
        subject: String,
        shortText: String,
        fullText: String,
        content: com.cuentivo.app.domain.StatementImageContent,
    ) {
        // Las DOS ramas pasan por `choose`, para que la prueba pura cubra lo que de verdad corre:
        // con imagen → texto corto; sin imagen (el PNG no se pudo escribir) → texto completo.
        val textWithImage = com.cuentivo.app.domain.StatementShareTextChoice.choose(true, shortText, fullText)
        val intent = runCatching { statementImageShareStore.createShareIntent(subject, textWithImage, content) }.getOrNull()
        if (intent == null) {
            share(subject, com.cuentivo.app.domain.StatementShareTextChoice.choose(false, shortText, fullText))
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, subject), REQUEST_SHARE_STATEMENT_IMAGE)
    }

    @Deprecated("The result is only used to remove the temporary statement PNG.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SHARE_STATEMENT_IMAGE) statementImageShareStore.clear()
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
