package com.cuentivo.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.ui.account.AiEntryPolicy
import com.cuentivo.app.ui.account.AiEntryRoute
import com.cuentivo.app.ai.AiPaymentConfirmationRequest
import com.cuentivo.app.ai.AiPaymentExecutionResult
import com.cuentivo.app.ai.AiPaymentPreviewResult
import com.cuentivo.app.ai.AiSaleConfirmationRequest
import com.cuentivo.app.ai.AiSaleExecutionResult
import com.cuentivo.app.ai.AiSalePreviewResult
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cuentivo.app.ai.AiAvailabilityGateway
import com.cuentivo.app.ai.AiAvailabilityCoordinator
import com.cuentivo.app.ai.AiAvailabilityPolicy
import com.cuentivo.app.ai.MexalmLedgerWarmGateway
import com.cuentivo.app.ai.MexalmLedgerWarmPolicy
import com.cuentivo.app.data.local.AiDailyChatRepository
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.ui.ai.AiDebugLabScreen
import com.cuentivo.app.ui.ai.debugAiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.CustomerScore
import com.cuentivo.app.domain.Insight
import com.cuentivo.app.domain.NewCustomerDraft
import com.cuentivo.app.domain.OpeningBalanceRow
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.domain.SheetImport
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.account.AccountSheet
import com.cuentivo.app.ui.account.AiPaywallSheet
import com.cuentivo.app.ui.account.LanguageSheet
import com.cuentivo.app.ui.customer.CustomerDetailSheet
import com.cuentivo.app.ui.customer.CustomerFilter
import com.cuentivo.app.ui.customer.CustomersScreen
import com.cuentivo.app.ui.dashboard.DashboardScreen
import com.cuentivo.app.ui.product.ProductsScreen
import com.cuentivo.app.ui.transaction.NewSaleScreen
import com.cuentivo.app.ui.transaction.PaymentScreen
import com.cuentivo.app.ui.dashboard.DashboardUiState

/**
 * Shown while the device is being claimed for the account that just signed in.
 *
 * Brief on purpose, but it must exist: whatever renders here cannot query Room yet, because the
 * previous account's rows may still be on their way out.
 */
@Composable
fun CuentivoLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Shown when the app cannot tell whether this account already owns a business.
 *
 * It offers a retry and nothing else — deliberately no way forward. Creating a business here is
 * what mints a duplicate id, and every query filters by businessId, so the customers already in the
 * cloud would become unreachable: present, paid for, and invisible. Waiting is recoverable;
 * a second business is not.
 */
@Composable
fun CuentivoClaimBlockedScreen(onRetry: () -> Unit, onUseAnotherAccount: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(NeoSpacing.lg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            Text(
                stringResource(R.string.claim_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.claim_blocked_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            NeoAction(
                label = stringResource(R.string.claim_blocked_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("claim-retry"),
            )
            // The way out. Retry is the only thing worth trying first, but it is not worth trying
            // forever: somebody signed into the wrong account, or with no signal they will not get
            // back today, had nothing here but uninstalling. This screen gates creating a business,
            // not the person — and a dead end is how a first impression becomes a bad review.
            TextButton(onClick = onUseAnotherAccount, modifier = Modifier.testTag("claim-sign-out")) {
                Text(stringResource(R.string.verify_use_another_account))
            }
        }
    }
}

/** Enough rejected rows to act on; a dialog listing two hundred of them helps nobody. */
private const val MAX_LISTED_REJECTIONS = 10

@Composable
fun CuentivoApp(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow> = emptyList(),
    products: List<ProductEntity> = emptyList(),
    tier: SubscriptionTier = SubscriptionTier.FREE,
    /**
     * Payment reliability by customer id, derived on every load rather than stored. Empty until it
     * arrives, which shows no dots instead of a wrong one.
     *
     * Declared after the positional arguments the host already passes, so adding it did not
     * silently re-bind `products` to a map at the one call site that passes them by position.
     */
    customerScores: Map<String, CustomerScore> = emptyMap(),
    accountLabel: String? = null,
    onCreateBusiness: () -> Unit = {},
    onCreateCustomer: (NewCustomerDraft) -> Unit = {},
    onEditCustomer: (CustomerBalanceRow, String, String?, Long?) -> Unit = { _, _, _, _ -> },
    onArchiveCustomer: (CustomerBalanceRow) -> Unit = {},
    /** True while a create is being written. Disables Guardar; does not by itself close anything. */
    isSavingCustomer: Boolean = false,
    /**
     * Bumped by the host only once a create actually reaches Room. The dialog closes on this, not
     * on the tap — a duplicate name or a missing business leaves the token untouched, so the form
     * stays open and the person sees why instead of watching it vanish.
     */
    customerSavedToken: Int = 0,
    /**
     * The customer a just-finished create asked to keep selling to, or null.
     *
     * Set by the host in the same place as [customerSavedToken] and for the same reason: it is the
     * only point that knows the write actually reached Room. Sending someone into an empty cart for
     * a customer who was never saved would be worse than not offering the shortcut at all.
     */
    pendingSaleCustomerId: String? = null,
    /** Called once the hand-off above has been acted on, so it cannot fire twice. */
    onPendingSaleConsumed: () -> Unit = {},
    /**
     * The customer a just-finished create actually wrote, whatever the intent. Set by the host in
     * the same place as [customerSavedToken]. It is what lets "Agregar cliente" from inside Nueva
     * venta come back with that customer selected, without the person having to press "Guardar y
     * vender" — the form was opened from the sale, so saving is the whole intent.
     */
    lastCreatedCustomerId: String? = null,
    onImportOpeningBalances: (List<OpeningBalanceRow>) -> Unit = {},
    isImportingBalances: Boolean = false,
    /** Same shape as [customerSavedToken], for the notebook import. */
    balancesImportedToken: Int = 0,
    onCreateProduct: (String, Long, String?) -> Unit = { _, _, _ -> },
    onEditProduct: (ProductEntity, String, Long, String?) -> Unit = { _, _, _, _ -> },
    onArchiveProduct: (ProductEntity) -> Unit = {},
    /** Same shape as [isSavingCustomer], for the product create form. */
    isSavingProduct: Boolean = false,
    /** Same shape as [customerSavedToken], for the product create form. */
    productSavedToken: Int = 0,
    onImportProducts: (Uri) -> Unit = {},
    importReport: SheetImport? = null,
    onDismissImportReport: () -> Unit = {},
    canImportProducts: Boolean = true,
    onImportBlocked: () -> Unit = {},
    isImportingProducts: Boolean = false,
    onCreateSale: (CustomerBalanceRow, SaleDraft) -> Unit = { _, _ -> },
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit = { _, _ -> },
    statementCustomer: CustomerBalanceRow? = null,
    statement: List<StatementEntry> = emptyList(),
    cancelables: List<CorrectionCandidate> = emptyList(),
    onCancelSale: (CorrectionCandidate.Sale) -> Unit = {},
    onEditSale: (CorrectionCandidate.Sale) -> Unit = {},
    /** Non-null while a registered sale is open for correction; it takes over the viewport. */
    saleCorrection: SaleCorrection? = null,
    onSaveCorrection: (SaleDraft) -> Unit = {},
    onDismissCorrection: () -> Unit = {},
    backupSection: @Composable () -> Unit = {},
    onRemindCustomer: (String) -> Unit = {},
    onCancelPayment: (CorrectionCandidate.Payment) -> Unit = {},
    onShowStatement: (CustomerBalanceRow) -> Unit = {},
    onCallCustomer: (String) -> Unit = {},
    onDismissStatement: () -> Unit = {},
    onShareStatementCsv: () -> Unit = {},
    onShareStatementText: () -> Unit = {},
    suggestedCreditLimitCents: Long? = null,
    onAcceptCreditLimitSuggestion: (Long) -> Unit = {},
    onDismissCreditLimitSuggestion: (Long) -> Unit = {},
    onShareDebtorList: () -> Unit = {},
    alerts: List<Alert> = emptyList(),
    onOpenAlertCustomer: (String) -> Unit = {},
    blocked: AccessDecision.Blocked? = null,
    onDismissBlocked: () -> Unit = {},
    onUpgrade: (SubscriptionTier) -> Unit = {},
    /** Pro's price as Play formats it, or null while it has not loaded. */
    proPrice: String? = null,
    /** AI's price as Play formats it, or null while it has not loaded. */
    aiPrice: String? = null,
    /** Whole free-trial months Play offers for Pro / AI; 0 = none. */
    proTrialMonths: Int = 0,
    aiTrialMonths: Int = 0,
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    /** Whether the current session is an anonymous guest session. */
    isAnonymous: Boolean = false,
    /** Callback to prompt sign up / account linking from within the app. */
    onSignUp: () -> Unit = {},
    /** Whether the owner wants the daily overdue reminder, from the host's preferences. */
    reminderEnabled: Boolean = false,
    /** The chosen local hour (0..23), from the host's preferences. */
    reminderHour: Int = 9,
    onReminderEnabledChange: (Boolean) -> Unit = {},
    onReminderHourChange: (Int) -> Unit = {},
    /** Debug builds only: forces the free-plan footer on shares. Null hides the switch. */
    debugForceFreeShare: Boolean? = null,
    onDebugForceFreeShareChange: (Boolean) -> Unit = {},
    /**
     * A reminder notification was tapped: the host wants Clientes with the overdue filter. Consumed
     * once through [onOpenOverdueCustomersConsumed], so the navigation cannot re-fire.
     */
    openOverdueCustomers: Boolean = false,
    onOpenOverdueCustomersConsumed: () -> Unit = {},
    aiDailyChatRepository: AiDailyChatRepository? = null,
    aiAvailabilityGateway: AiAvailabilityGateway? = null,
    /**
     * Despierta el Ledger de MexaLM al abrir la app. Sólo se usa si la cuenta tiene
     * CuentivoAI, y ningún error llega a la persona.
     */
    mexalmLedgerWarmGateway: MexalmLedgerWarmGateway? = null,
    paymentPreviewResolver: (suspend (businessId: String, proposal: RecordPaymentProposal) -> AiPaymentPreviewResult)? = null,
    confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)? = null,
    salePreviewResolver: (suspend (businessId: String, proposal: CreateSaleProposal, choices: com.cuentivo.app.ai.AiSaleResolutionChoices) -> AiSalePreviewResult)? = null,
    confirmSale: (suspend (AiSaleConfirmationRequest) -> AiSaleExecutionResult)? = null,
    extractLedgerPage: (suspend (requestId: String, imageBase64: String, imageMimeType: String) -> com.cuentivo.app.ai.ledger.LedgerExtractionCallResult)? = null,
    ledgerBalancesResolver: (suspend (businessId: String) -> Map<String, Long>)? = null,
    ledgerCustomersResolver: (suspend (businessId: String) -> List<com.cuentivo.app.domain.CustomerCandidate>)? = null,
    ledgerProductsResolver: (suspend (businessId: String) -> List<com.cuentivo.app.domain.ProductCandidate>)? = null,
    ledgerRegisteredResolver: (suspend (businessId: String, minEpochDay: Long, maxEpochDay: Long) -> List<com.cuentivo.app.domain.RegisteredOperationProjection>)? = null,
    confirmLedgerBatch: (suspend (businessId: String, importSessionId: String, operations: List<com.cuentivo.app.ai.ledger.LedgerOperation>, pageDateEpochDay: Long?, dueDateDays: Long?, blockedLineIndices: Set<Int>, modelSaysTextMayBeMissing: Boolean, isPageCompletenessConfirmed: Boolean) -> com.cuentivo.app.domain.LedgerBatchConfirmation)? = null,
    /** Local import metrics DAO; null keeps the lab working without metrics, e.g. in previews. */
    ledgerImportMetricDao: com.cuentivo.app.data.local.AiLedgerImportMetricDao? = null,
    businessQueryResolver: (suspend (businessId: String, proposal: com.cuentivo.app.ai.BusinessQueryProposal, choice: com.cuentivo.app.ai.AiEntityChoice?) -> com.cuentivo.app.ai.AiBusinessQueryResult)? = null,
    message: String? = null,
    /**
     * Aviso que debe verse **dentro** del formulario de Nuevo cliente (v46).
     *
     * El snackbar de [message] queda detrás del diálogo, así que "primero crea tu negocio" era
     * invisible justo cuando hacía falta: con el formulario abierto y "Guardar" sin hacer nada.
     */
    customerFormNotice: String? = null,
    onMessageShown: () -> Unit = {},
) {
    var destination by remember { mutableStateOf(MainDestination.HOME) }
    var showNewSale by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    // Hoisted out of the screens so the floating button can open the same form the screen owns.
    var showCustomerForm by remember { mutableStateOf(false) }
    var showProductForm by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    // The overdue filter a tapped reminder asked for, handed to Clientes exactly once.
    var pendingOverdueFilter by remember { mutableStateOf(false) }
    // Which customer a directly-launched sale or payment should start on. Held here because the
    // shell owns navigation; the task screens stay free of routing state.
    var saleCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var paymentCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val aiUnavailableInVersionMessage = stringResource(R.string.ai_feature_unavailable_in_version)
    val aiDisabledMessage = stringResource(R.string.ai_debug_lab_disabled)
    // Transport for AI lab: release builds resolve to null until T6, where AiEntryPolicy routes
    // paying subscribers to an unavailable notice and non-subscribers to the paywall.
    val debugAiLabTransport = remember { debugAiTransport() }
    // One definition of "the lab can run here": the transport exists and there is a chat store. The
    // robot entry, the notebook-photo shortcut and the lab itself must agree, or a tap leads nowhere.
    val hasAiTransport = debugAiLabTransport != null && aiDailyChatRepository != null
    // Saveable on purpose: a review of a photographed page can be open when the Activity is
    // recreated (the system reclaiming the process, a uiMode change, the camera app in front).
    // A plain remember here closed the whole lab on the way back, and the review state saved
    // inside it never got the chance to restore.
    var showAiDebugLab by rememberSaveable { mutableStateOf(false) }
    var showAiPaywall by remember { mutableStateOf(false) }
    // Bumped when a tap on the bar asks a destination to go back to its own root (clear the search,
    // the filter, the amount being typed). 0 means "never tapped", so the first composition is not
    // mistaken for a reset.
    var customersResetToken by remember { mutableStateOf(0) }
    var productsResetToken by remember { mutableStateOf(0) }
    var paymentsResetToken by remember { mutableStateOf(0) }
    // Set when "Agregar cliente" is tapped from inside Nueva venta. The form opens over Clientes,
    // and once the write lands the person is returned to the sale with the new customer selected.
    var saleReturnPending by remember { mutableStateOf(false) }
    var saleReturnDestination by remember { mutableStateOf(MainDestination.HOME) }
    var saleReturnCustomerId by remember { mutableStateOf<String?>(null) }
    // Set when "Tomar foto de mi libreta" is tapped: the AI lab opens straight on the camera/gallery
    // chooser instead of the chat. Consumed once the lab has acted on it.
    var pendingLedgerImport by remember { mutableStateOf(false) }
    val coordinator = remember(aiAvailabilityGateway) {
        aiAvailabilityGateway?.let { AiAvailabilityCoordinator(it) }
    }
    // In-memory per-session remote service availability flag. Never persisted to Room.
    var isAiServiceAvailable by remember(tier) { mutableStateOf(coordinator?.isAvailable ?: true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val refreshAiAvailability: () -> Unit = {
        if (coordinator != null) {
            val isAiTier = tier == SubscriptionTier.AI
            coroutineScope.launch {
                isAiServiceAvailable = coordinator.refresh(isAiTier)
            }
        }
    }

    // El Ledger de MexaLM se apaga solo cuando nadie lo usa y tarda entre 30 y 60 s en volver.
    // Al abrir la app se le pide que empiece a despertarse, para que la primera lectura de la
    // libreta no espere a que la instancia levante.
    //
    // Sólo para quien tiene CuentivoAI —es el único plan que usa el Ledger—, sin bloquear la UI
    // y sin que ningún error llegue a la persona: el despertar es un favor, y si no sale, la app
    // sigue igual. Y NO hay nada que apagar al cerrar: Cloud Run se apaga solo.
    val warmMexalmLedger: () -> Unit = {
        val gateway = mexalmLedgerWarmGateway
        if (gateway != null && MexalmLedgerWarmPolicy.shouldWarm(tier)) {
            coroutineScope.launch {
                gateway.warm()
            }
        }
    }

    LaunchedEffect(tier) {
        if (tier == SubscriptionTier.AI) {
            refreshAiAvailability()
        } else {
            coordinator?.reset()
            isAiServiceAvailable = true
        }
    }

    DisposableEffect(lifecycleOwner, tier, mexalmLedgerWarmGateway) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAiAvailability()
                warmMexalmLedger()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    // Closes the form on a successful save, and only on that: the token only advances once the
    // host's write actually reached Room, so a duplicate name or a missing business — which leave
    // the token untouched — leave the dialog open with its message instead of closing it blind.
    LaunchedEffect(customerSavedToken) {
        if (customerSavedToken > 0) {
            showCustomerForm = false
            // A create that landed while the form was opened from the sale hands its id over here;
            // the effect below waits for the row to appear in `customers` before returning.
            if (saleReturnPending) {
                saleReturnPending = false
                saleReturnCustomerId = lastCreatedCustomerId
            }
        }
    }
    // Keyed on the list too, not just the id, for the same reason as the pending-sale hand-off: on
    // the composition where the id first arrives the refreshed list may not include the new row yet.
    LaunchedEffect(saleReturnCustomerId, customers) {
        val target = saleReturnCustomerId ?: return@LaunchedEffect
        val row = customers.firstOrNull { it.customer.id == target } ?: return@LaunchedEffect
        saleReturnCustomerId = null
        showCustomerForm = false
        destination = saleReturnDestination
        saleCustomer = row
        showNewSale = true
    }
    LaunchedEffect(productSavedToken) {
        if (productSavedToken > 0) showProductForm = false
    }
    // Keyed on the list too, not just the id: the host refreshes `customers` after the write, so on
    // the composition where the id first arrives the new row may not be in the list yet. Waiting for
    // it to appear is what makes the hand-off land on the right person instead of an empty selector.
    LaunchedEffect(pendingSaleCustomerId, customers) {
        val target = pendingSaleCustomerId ?: return@LaunchedEffect
        val row = customers.firstOrNull { it.customer.id == target } ?: return@LaunchedEffect
        showCustomerForm = false
        saleCustomer = row
        showNewSale = true
        onPendingSaleConsumed()
    }
    // System Back closes the sale task and nothing else — and it must drop the preselection with
    // it, or the next sale would silently inherit a customer nobody chose for it.
    BackHandler(enabled = showNewSale) { showNewSale = false; saleCustomer = null }
    // Un registro exitoso abre el detalle de la clienta (lo hace el host, vía `onShowStatement`).
    // El detalle manda sobre el formulario, así que mientras está abierto la venta sigue "abierta"
    // por debajo y, al cerrar el detalle, reaparecía el formulario vacío. La venta ya se registró:
    // la tarea terminó. Cerrarla cuando el detalle se abre es lo que hace que Atrás caiga en
    // Inicio/Clientes y no en un formulario vacío. Se deja la corrección/edición intacta: ahí
    // `saleCorrection` abre su propia pantalla y `showNewSale` no está activo.
    LaunchedEffect(statementCustomer) {
        if (statementCustomer != null && showNewSale) {
            showNewSale = false
            saleCustomer = null
        }
    }
    // The tapped reminder navigates to Clientes with the overdue chip. Only the action travels —
    // the host consumed the intent, and Room answers the "who owes" from here.
    LaunchedEffect(openOverdueCustomers) {
        if (openOverdueCustomers) {
            destination = MainDestination.CUSTOMERS
            showNewSale = false
            saleCustomer = null
            paymentCustomer = null
            pendingOverdueFilter = true
            onOpenOverdueCustomersConsumed()
        }
    }
    // The overdue filter stays applied for as long as Clientes is showing it; leaving the
    // destination consumes the request, so the next visit starts at "Todos".
    LaunchedEffect(destination, pendingOverdueFilter) {
        if (pendingOverdueFilter && destination != MainDestination.CUSTOMERS) {
            pendingOverdueFilter = false
        }
    }
    NeoFintechShell(
        destination = destination,
        // A sale owns the screen and is not a destination: the bar marks its action, not the tab
        // the person happened to be on when they opened it.
        newSaleActive = showNewSale || (saleCorrection != null && statementCustomer != null),
        // A customer's account belongs to Clientes, whichever tab it was opened from.
        detailOpen = statementCustomer != null,
        onDestination = { selected ->
            val currentSelection = MainNavigationPolicy.barSelection(
                destination = destination,
                newSaleActive = showNewSale || (saleCorrection != null && statementCustomer != null),
                detailOpen = statementCustomer != null,
            )
            when (MainNavigationPolicy.tabTapAction(currentSelection, selected)) {
                TabTapAction.RESET_ROOT -> when (selected) {
                    MainDestination.CUSTOMERS -> customersResetToken++
                    MainDestination.PRODUCTS -> productsResetToken++
                    MainDestination.PAYMENTS -> paymentsResetToken++
                    MainDestination.HOME -> Unit
                }
                TabTapAction.OPEN -> Unit
            }
            destination = selected
            showNewSale = false
            saleCustomer = null
            // Reaching Abonos from the bar is a fresh decision, not a continuation of whoever was
            // open in the detail a minute ago.
            paymentCustomer = null
            // Tapping a tab also closes whatever task was open — the customer detail, the forms —
            // so "Clientes" from a customer's account lands back on the list.
            if (statementCustomer != null) onDismissStatement()
            showCustomerForm = false
            showProductForm = false
            saleReturnPending = false
            pendingOverdueFilter = false
        },
        onNewSale = {
            destination = MainNavigationPolicy.destinationAfterNewSale(destination)
            // With a customer's detail open the central action continues that thread; anywhere
            // else it is a fresh sale and must not inherit a stale preselection.
            saleCustomer = statementCustomer
            if (statementCustomer != null) onDismissStatement()
            showNewSale = true
        },
        onOpenAccount = { showAccount = true },
        onOpenLanguage = { showLanguage = true },
        onOpenAlerts = { showAlerts = true },
        onOpenAi = {
            when (AiEntryPolicy.route(tier, hasAiTransport, isAiServiceAvailable)) {
                AiEntryRoute.Paywall -> showAiPaywall = true
                AiEntryRoute.Assistant -> showAiDebugLab = true
                AiEntryRoute.UnavailableInCurrentBuild -> {
                    coroutineScope.launch {
                        snackbar.showSnackbar(aiUnavailableInVersionMessage)
                    }
                }
                AiEntryRoute.ServiceDisabled -> {
                    coroutineScope.launch {
                        snackbar.showSnackbar(aiDisabledMessage)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        showBrandHeader = statementCustomer == null,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (saleCorrection != null && statementCustomer != null) {
            // Ahead of the statement on purpose: the correction is the task the owner just opened,
            // and closing it drops them back onto the sheet they came from.
            NewSaleScreen(
                customers = customers,
                products = products,
                modifier = Modifier.padding(padding),
                initialCustomer = statementCustomer,
                initialDraft = saleCorrection.draft,
                onCreateSale = { _, draft -> onSaveCorrection(draft) },
                onAddCustomer = {
                    saleReturnDestination = destination
                    saleReturnPending = true
                    showNewSale = false
                    saleCustomer = null
                    destination = MainDestination.CUSTOMERS
                    showCustomerForm = true
                },
                onExit = onDismissCorrection,
            )
        } else if (statementCustomer != null) {
            // The detail is a task that owns the viewport, so it outranks the selected destination
            // while it is open. The bottom bar stays visible underneath it.
            CustomerDetailSheet(
                customer = statementCustomer,
                entries = statement,
                cancelables = cancelables,
                modifier = Modifier.padding(padding),
                dueSales = (state as? DashboardUiState.Content)?.dueSales.orEmpty(),
                score = customerScores[statementCustomer.customer.id],
                onRegisterPayment = {
                    onDismissStatement()
                    paymentCustomer = statementCustomer
                    destination = MainDestination.PAYMENTS
                },
                onCallCustomer = onCallCustomer,
                onCancelSale = onCancelSale,
                onEditSale = onEditSale,
                onCancelPayment = onCancelPayment,
                onShareCsv = onShareStatementCsv,
                onShareText = onShareStatementText,
                onDismiss = onDismissStatement,
                suggestedCreditLimitCents = suggestedCreditLimitCents,
                onAcceptCreditLimitSuggestion = onAcceptCreditLimitSuggestion,
                onDismissCreditLimitSuggestion = onDismissCreditLimitSuggestion,
            )
        } else if (showNewSale) {
            NewSaleScreen(
                customers = customers,
                products = products,
                modifier = Modifier.padding(padding),
                initialCustomer = saleCustomer,
                onCreateSale = onCreateSale,
                onAddCustomer = {
                    // The sale in progress is not discarded: its destination is remembered so that
                    // saving the new customer returns here with them selected.
                    saleReturnDestination = destination
                    saleReturnPending = true
                    showNewSale = false
                    saleCustomer = null
                    destination = MainDestination.CUSTOMERS
                    showCustomerForm = true
                },
                onExit = { showNewSale = false; saleCustomer = null },
            )
        } else when (destination) {
            MainDestination.HOME -> DashboardScreen(
                state = state,
                customers = customers,
                modifier = Modifier.padding(padding),
                onCreateBusiness = onCreateBusiness,
                onNewSale = {
                    destination = MainNavigationPolicy.destinationAfterNewSale(destination)
                    showNewSale = true
                },
                onRegisterPayment = { destination = MainDestination.PAYMENTS },
                onCustomer = onShowStatement,
                onSeeAllCustomers = { destination = MainDestination.CUSTOMERS },
            )
            MainDestination.CUSTOMERS -> CustomersScreen(
                customers = customers,
                // The same dated sales Inicio uses, so a row can say why it is late without a
                // second query.
                dueSales = (state as? DashboardUiState.Content)?.dueSales.orEmpty(),
                scores = customerScores.mapValues { (_, score) -> score.reliability },
                modifier = Modifier.padding(padding),
                showForm = showCustomerForm,
                onShowFormChange = {
                    showCustomerForm = it
                    // Closing the form by hand abandons the return-to-sale intent; otherwise a later
                    // create from this same tab would jump into a sale nobody asked for.
                    if (!it) saleReturnPending = false
                },
                products = products,
                onCreateCustomer = onCreateCustomer,
                onImportOpeningBalances = onImportOpeningBalances,
                isImportingBalances = isImportingBalances,
                balancesImportedToken = balancesImportedToken,
                onEditCustomer = onEditCustomer,
                onArchiveCustomer = onArchiveCustomer,
                onOpen = onShowStatement,
                onOpenLedgerPhoto = {
                    when (AiEntryPolicy.route(tier, hasAiTransport, isAiServiceAvailable)) {
                        AiEntryRoute.Paywall -> showAiPaywall = true
                        AiEntryRoute.Assistant -> {
                            pendingLedgerImport = true
                            showAiDebugLab = true
                        }
                        AiEntryRoute.UnavailableInCurrentBuild -> coroutineScope.launch {
                            snackbar.showSnackbar(aiUnavailableInVersionMessage)
                        }
                        AiEntryRoute.ServiceDisabled -> coroutineScope.launch {
                            snackbar.showSnackbar(aiDisabledMessage)
                        }
                    }
                },
                isSaving = isSavingCustomer,
                initialFilter = if (pendingOverdueFilter) CustomerFilter.OVERDUE else null,
                resetToken = customersResetToken,
                createFormNotice = customerFormNotice,
            )
            MainDestination.PRODUCTS -> ProductsScreen(
                products = products,
                modifier = Modifier.padding(padding),
                showForm = showProductForm,
                onShowFormChange = { showProductForm = it },
                onCreateProduct = onCreateProduct,
                onEditProduct = onEditProduct,
                onArchiveProduct = onArchiveProduct,
                onImportProducts = onImportProducts,
                canImportProducts = canImportProducts,
                onImportBlocked = onImportBlocked,
                isImportingProducts = isImportingProducts,
                isSaving = isSavingProduct,
                resetToken = productsResetToken,
            )
            MainDestination.PAYMENTS -> PaymentScreen(
                customers = customers,
                modifier = Modifier.padding(padding),
                initialCustomer = paymentCustomer,
                onCreatePayment = onCreatePayment,
                resetToken = paymentsResetToken,
            )
        }
        if (hasAiTransport && showAiDebugLab) {
            val activeBusinessId = (state as? DashboardUiState.Content)?.businessId
            key(aiDailyChatRepository) {
                AiDebugLabScreen(
                    transport = debugAiLabTransport,
                    repository = aiDailyChatRepository,
                    paymentPreviewResolver = paymentPreviewResolver,
                    salePreviewResolver = salePreviewResolver,
                    businessQueryResolver = businessQueryResolver,
                    activeBusinessId = activeBusinessId,
                    confirmPayment = confirmPayment,
                    confirmSale = confirmSale,
                    extractLedgerPage = extractLedgerPage,
                    ledgerBalancesResolver = ledgerBalancesResolver,
                    ledgerCustomersResolver = ledgerCustomersResolver,
                    ledgerProductsResolver = ledgerProductsResolver,
                    ledgerRegisteredResolver = ledgerRegisteredResolver,
                    confirmLedgerBatch = confirmLedgerBatch,
                    ledgerImportMetricDao = ledgerImportMetricDao,
                    startLedgerImport = pendingLedgerImport,
                    onStartLedgerImportConsumed = { pendingLedgerImport = false },
                    onClose = { showAiDebugLab = false },
                    onOpenPaywall = {
                        showAiDebugLab = false
                        showAiPaywall = true
                    },
                )
            }
        }
        }
    }
    blocked?.let { limit ->
        AlertDialog(
            onDismissRequest = onDismissBlocked,
            title = { Text(limit.title) },
            text = { Text(limit.detail) },
            confirmButton = {
                TextButton(onClick = { onDismissBlocked(); showAccount = true }) { Text(stringResource(R.string.app_see_pro)) }
            },
            dismissButton = { TextButton(onClick = onDismissBlocked) { Text(stringResource(R.string.app_not_now)) } },
        )
    }
    if (showAlerts) {
        AlertsSheet(
            alerts = alerts,
            onOpenCustomer = onOpenAlertCustomer,
            onRemindCustomer = onRemindCustomer,
            onDismiss = { showAlerts = false },
        )
    }
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = onDismissImportReport,
            title = { Text(stringResource(R.string.app_import_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.app_import_added, report.accepted.size))
                    if (report.rejected.isNotEmpty()) {
                        Text(stringResource(R.string.app_import_rejected_header), style = MaterialTheme.typography.labelLarge)
                        // The line number is the point: it is what lets someone find the row in
                        // their own file and correct it.
                        report.rejected.take(MAX_LISTED_REJECTIONS).forEach { rejected ->
                            Text(stringResource(R.string.app_import_rejected_line, rejected.lineNumber, rejected.reason), style = MaterialTheme.typography.labelLarge)
                        }
                        if (report.rejected.size > MAX_LISTED_REJECTIONS) {
                            Text(stringResource(R.string.app_import_rejected_more, report.rejected.size - MAX_LISTED_REJECTIONS), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissImportReport) { Text(stringResource(R.string.app_understood)) } },
        )
    }
    if (showAccount) {
        AccountSheet(
            tier = tier,
            accountLabel = accountLabel,
            onUpgrade = { targetTier ->
                showAccount = false
                onUpgrade(targetTier)
            },
            proPrice = proPrice,
            aiPrice = aiPrice,
            proTrialMonths = proTrialMonths,
            aiTrialMonths = aiTrialMonths,
            onSignOut = onSignOut,
            onDeleteAccount = onDeleteAccount,
            onDismiss = { showAccount = false },
            backupSection = backupSection,
            isAnonymous = isAnonymous,
            customerCount = customers.size,
            onSignUp = { showAccount = false; onSignUp() },
            reminderEnabled = reminderEnabled,
            reminderHour = reminderHour,
            onReminderEnabledChange = onReminderEnabledChange,
            onReminderHourChange = onReminderHourChange,
            debugForceFreeShare = debugForceFreeShare,
            onDebugForceFreeShareChange = onDebugForceFreeShareChange,
        )
    }
    if (showAiPaywall) {
        AiPaywallSheet(
            currentTier = tier,
            onUpgrade = { targetTier ->
                showAiPaywall = false
                onUpgrade(targetTier)
            },
            onDismiss = { showAiPaywall = false },
            aiPrice = aiPrice,
            aiTrialMonths = aiTrialMonths,
        )
    }
    if (showLanguage) {
        LanguageSheet(onDismiss = { showLanguage = false })
    }
}
