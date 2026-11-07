package com.cuentivo.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.LocaleList
import android.os.Build
import android.app.LocaleManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cuentivo.app.settings.LanguagePreference
import java.util.Locale
import androidx.room.Room
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.cuentivo.app.customer.CreditLimitSuggestionStore
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.DashboardRepository
import com.cuentivo.app.domain.EntitlementReconciler
import com.cuentivo.app.BuildConfig
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.CuentivoRootState
import com.cuentivo.app.ui.CuentivoRoot
import com.cuentivo.app.ui.CuentivoRootDependencies
import com.cuentivo.shared.resources.*
import com.cuentivo.app.platform.nowEpochMillis
import com.cuentivo.app.ui.AndroidBusinessErasure
import com.cuentivo.app.ui.androidCuentivoRootPlatform
import com.cuentivo.app.ui.share.AndroidSharer
import com.cuentivo.app.ui.share.StatementImageShareStore
import com.cuentivo.app.auth.FirebaseAuthenticationGateway
import com.cuentivo.app.observability.CrashEvent
import com.cuentivo.app.observability.CrashReporterFactory
import com.cuentivo.app.settings.CollectionReminderPreferences
import com.cuentivo.app.auth.LocalSessionGuard
import com.cuentivo.app.billing.BillingGateway
import com.cuentivo.app.billing.EntitlementStore
import com.cuentivo.app.billing.allowsCloudSync
import com.cuentivo.app.ui.onboarding.SharedPreferencesFirstDebtFlagStore
import com.cuentivo.app.sync.SyncScheduler
import com.cuentivo.app.sync.AppCheckInstaller
import com.cuentivo.app.sync.FirebaseEmulators
import com.cuentivo.app.sync.CloudRestore
import com.cuentivo.app.sync.OutboxBackfill
import com.cuentivo.app.sync.FirestoreSyncGateway
import com.cuentivo.app.sync.FirestoreDeletionGateway
import com.cuentivo.app.sync.OwnerMembershipBootstrap
import com.cuentivo.app.sync.SyncQueue

private const val TAG = "MainActivity"

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
            val chosenTag = if (locales != null && !locales.isEmpty) locales[0].toLanguageTag() else null
            // El `Locale` del proceso es lo que Compose lee para `Locale.current` (y por tanto para
            // `Res.string`); la configuración del Context sólo alimenta `R.string`. Se mueven juntos
            // o la pantalla mezcla idiomas. Con `chosenTag` nulo se vuelve al del sistema: quien
            // regresa a "seguir al sistema" no puede quedarse con el idioma anterior hasta reiniciar.
            Locale.setDefault(
                LanguagePreference.processLocale(chosenTag, Resources.getSystem().configuration.locales[0]),
            )
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
        Locale.setDefault(
            LanguagePreference.processLocale(tag, Resources.getSystem().configuration.locales[0]),
        )
        val wrapped = if (tag == null) {
            newBase
        } else {
            val configuration = Configuration(newBase.resources.configuration)
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            newBase.createConfigurationContext(configuration)
        }
        super.attachBaseContext(wrapped)
    }

    private val rootState = CuentivoRootState()

    /**
     * Release-only, fixed technical events. Debug and emulator sessions are a no-op; nothing with
     * PII, balances or ids ever reaches Crashlytics — see [CrashReporterFactory].
     */
    private val crashReporter = CrashReporterFactory.create()

    /** The one temporary customer statement currently exposed to the selected share target. */
    private val statementImageShareStore by lazy { StatementImageShareStore(applicationContext) }

    /** La hoja de compartir y el marcador, detrás del contrato común Sharer. */
    private val sharer by lazy { AndroidSharer(this, statementImageShareStore, REQUEST_SHARE_STATEMENT_IMAGE) }

    /**
     * La marca durable de un borrado de cuenta a medias (v44). Vive en SharedPreferences para que
     * sobreviva a la muerte del proceso: es lo que permite retomar el borrado de Auth al volver.
     */
    private val pendingAccountDeletionStore by lazy {
        com.cuentivo.app.auth.PendingAccountDeletionStore(applicationContext)
    }

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
            rootState.openOverdueCustomers.value = true
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
        val tierState = rootState.tier
        val openOverdueCustomersState = rootState.openOverdueCustomers
        val accountMessageState = rootState.accountMessage
        val proPriceState = rootState.proPrice
        val aiPriceState = rootState.aiPrice
        val proTrialMonthsState = rootState.proTrialMonths
        val aiTrialMonthsState = rootState.aiTrialMonths
        val entitlementResolvedState = rootState.entitlementResolved
        val billingMessageState = rootState.billingMessage
        val requireAccountState = rootState.requireAccount
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
        // El reloj del sistema no existe en :shared sin opt-in experimental, así que la fecha de hoy la
        // pone el llamador; es el mismo LocalDate.now() que daba el valor por defecto anterior.
        val dashboardRepository = DashboardRepository(
            database.businessDao(),
            database.financialDao(),
            todayProvider = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
        )
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
        val rootPlatform = androidCuentivoRootPlatform(
            activity = this,
            database = database,
            billing = billing,
            firestoreGateway = firestoreGateway,
            deletionGateway = deletionGateway,
            appScope = lifecycleScope,
            isDebugBuild = BuildConfig.DEBUG,
            sharer = sharer,
            onRestored = { restartAfterRestore() },
        )
        val platform = rootPlatform
        val appScope = platform.appScope
        val reminders = platform.reminders
        val businessErasure = AndroidBusinessErasure(firestoreGateway, deletionGateway)
        val reminderPreferences = CollectionReminderPreferences(applicationContext)
        val firstDebtFlagStore = SharedPreferencesFirstDebtFlagStore(applicationContext)
        val dependencies = CuentivoRootDependencies(
            database = database,
            authenticationGateway = authenticationGateway,
            syncQueue = syncQueue,
            membershipBootstrap = membershipBootstrap,
            remoteBusinessLookup = firestoreGateway,
            businessErasure = businessErasure,
            cloudRestore = cloudRestore,
            dashboardRepository = dashboardRepository,
            sessionGuard = sessionGuard,
            entitlementStore = entitlementStore,
            creditLimitSuggestionStore = creditLimitSuggestionStore,
            pendingAccountDeletionStore = pendingAccountDeletionStore,
            aiAvailabilityGateway = aiAvailabilityGateway,
            mexalmLedgerWarmGateway = mexalmLedgerWarmGateway,
            crashReporter = crashReporter,
            reminderPreferences = reminderPreferences,
            firstDebtFlagStore = firstDebtFlagStore,
        )
        setContent { CuentivoRoot(dependencies, rootState, rootPlatform) }
    }


    @Deprecated("The result is only used to remove the temporary statement PNG.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SHARE_STATEMENT_IMAGE) statementImageShareStore.clear()
    }


}
