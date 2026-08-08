package com.cuentivo.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.ProductImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.CustomerScore
import com.cuentivo.app.domain.Insight
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.QuickSale
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.domain.SheetImport
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.account.AccountSheet
import com.cuentivo.app.ui.account.LanguageSheet
import com.cuentivo.app.ui.customer.CustomerDetailSheet
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
    onCreateCustomer: (String, String?, Long?, QuickSale?) -> Unit = { _, _, _, _ -> },
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
    onUpgrade: () -> Unit = {},
    /** Pro's price as Play formats it, or null while it has not loaded. */
    proPrice: String? = null,
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    message: String? = null,
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
    // Which customer a directly-launched sale or payment should start on. Held here because the
    // shell owns navigation; the task screens stay free of routing state.
    var saleCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var paymentCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
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
        if (customerSavedToken > 0) showCustomerForm = false
    }
    LaunchedEffect(productSavedToken) {
        if (productSavedToken > 0) showProductForm = false
    }
    // System Back closes the sale task and nothing else — and it must drop the preselection with
    // it, or the next sale would silently inherit a customer nobody chose for it.
    BackHandler(enabled = showNewSale) { showNewSale = false; saleCustomer = null }
    NeoFintechShell(
        destination = destination,
        onDestination = { selected ->
            destination = selected
            showNewSale = false
            // Reaching Abonos from the bar is a fresh decision, not a continuation of whoever was
            // open in the detail a minute ago.
            paymentCustomer = null
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
        snackbarHost = { SnackbarHost(snackbar) },
        showBrandHeader = statementCustomer == null,
    ) { padding ->
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
                onShowFormChange = { showCustomerForm = it },
                onCreateCustomer = onCreateCustomer,
                onEditCustomer = onEditCustomer,
                onArchiveCustomer = onArchiveCustomer,
                onOpen = onShowStatement,
                isSaving = isSavingCustomer,
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
            )
            MainDestination.PAYMENTS -> PaymentScreen(
                customers = customers,
                modifier = Modifier.padding(padding),
                initialCustomer = paymentCustomer,
                onCreatePayment = onCreatePayment,
            )
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
            onUpgrade = { showAccount = false; onUpgrade() },
                        proPrice = proPrice,
            onSignOut = onSignOut,
            onDeleteAccount = onDeleteAccount,
            onDismiss = { showAccount = false },
            backupSection = backupSection,
        )
    }
    if (showLanguage) {
        LanguageSheet(onDismiss = { showLanguage = false })
    }
}

/**
 * A product photo, or a placeholder when there is none — and when the file is simply not here.
 *
 * A product adopted from another device carries a file name only its author's phone has, so a
 * missing file is expected, not an error worth shouting about.
 */
private enum class DebtorFilter(val label: String) {
    OWING("Con deuda"),
    ALL("Todos"),
}

/**
 * Home: the money totals, then who actually owes it.
 *
 * The list is the point — a dashboard of four numbers tells the owner nothing about who to call
 * today.
 */
@Composable
private fun LegacyDashboardScreen(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow>,
    padding: PaddingValues,
    onCreateBusiness: () -> Unit,
    onNewSale: () -> Unit,
    onShowStatement: (CustomerBalanceRow) -> Unit,
    onShareDebtorList: () -> Unit,
) {
    var filter by remember { mutableStateOf(DebtorFilter.OWING) }
    NeoResponsiveColumn(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
    ) { layout ->
        Spacer(Modifier.height(8.dp))
        when (state) {
            DashboardUiState.NoBusiness -> {
                Text(stringResource(R.string.app_welcome_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.app_welcome_detail))
                FilledTonalButton(onClick = onCreateBusiness, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_create_business)) }
            }
            is DashboardUiState.Content -> {
                // No "Nueva venta" button here any more: the floating button does it, and two ways
                // to start the same thing on one screen is clutter.
                Text(state.businessName, style = MaterialTheme.typography.headlineSmall)
                NeoBalanceHero(
                    label = stringResource(R.string.dashboard_receivable_short),
                    amountCents = state.receivableCents,
                    supportingText = if (state.overdueCents > 0) {
                        "${formatMoney(state.overdueCents)} requieren atención"
                    } else {
                        "Tu cartera está al día"
                    },
                )
                state.insights.forEach { InsightCard(it) }
                if (layout.stackPrimaryActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoMoneyPill("Vendido este mes", state.soldCents, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                        NeoMoneyPill("Cobrado este mes", state.collectedCents, MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        NeoMoneyPill("Vendido este mes", state.soldCents, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        NeoMoneyPill("Cobrado este mes", state.collectedCents, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                    }
                }
                if (state.overdueCents > 0) {
                    Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.app_overdue_payments), style = MaterialTheme.typography.labelLarge)
                            Text(
                                formatMoney(state.overdueCents),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                NeoSectionTitle(
                    title = stringResource(R.string.app_who_owes_me),
                    eyebrow = stringResource(R.string.customers_title),
                    trailing = {
                        AssistChip(onClick = onShareDebtorList, label = { Text(stringResource(R.string.app_share)) })
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebtorFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                val listed = when (filter) {
                    DebtorFilter.OWING -> customers.filter { it.balanceCents > 0 }
                    DebtorFilter.ALL -> customers
                }.sortedByDescending { it.balanceCents }
                if (listed.isEmpty()) {
                    Text(
                        if (customers.isEmpty()) "Agrega tu primer cliente en la pestaña Clientes"
                        else "Nadie tiene saldo pendiente. Todo cobrado."
                    )
                }
                listed.forEach { row ->
                    Card(
                onClick = { onShowStatement(row) },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                        if (layout.stackLedgerAmounts) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                                row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                                Spacer(Modifier.height(8.dp))
                                Text(formatMoney(row.balanceCents), style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                                    row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                                }
                                Text(
                                    formatMoney(row.balanceCents),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (row.balanceCents > 0) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * How the business is actually doing, in one sentence.
 *
 * The tone comes from the number, not from a wish to be encouraging: a bad month is tinted like bad
 * news. And it never leans on colour alone — the accent bar on the left plus the wording carry the
 * meaning, because at arm's length in the sun a coloured tint is the first thing to disappear.
 */
@Composable
private fun InsightCard(insight: Insight) {
    val accent = when (insight.tone) {
        Insight.Tone.GOOD -> MaterialTheme.colorScheme.primary
        Insight.Tone.BAD -> MaterialTheme.colorScheme.error
        Insight.Tone.NEUTRAL -> MaterialTheme.colorScheme.secondary
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).height(56.dp).background(accent))
            Text(
                insight.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, cents: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(cents), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private fun formatMoney(cents: Long): String = Money.format(cents)
