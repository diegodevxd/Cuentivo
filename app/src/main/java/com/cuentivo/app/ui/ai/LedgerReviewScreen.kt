package com.cuentivo.app.ui.ai

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.ai.ledger.CustomerReviewGroup
import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerAmountInput
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.MexalmCrosscheckConfirmation
import com.cuentivo.app.ai.ledger.MexalmCrosscheckOutcome
import com.cuentivo.app.ai.ledger.MexalmCrosscheckUnreadReason
import com.cuentivo.app.ai.ledger.MexalmCrosscheckVerdict
import com.cuentivo.app.ai.ledger.MexalmCrosscheckVerdictCalculator
import com.cuentivo.app.ai.ledger.OperationFinancialDiagnostic
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.domain.FieldHealthColor
import com.cuentivo.app.domain.LedgerDeterministicObjectionType
import com.cuentivo.app.domain.LedgerDueDatePolicy
import com.cuentivo.app.domain.LedgerFieldHealth
import com.cuentivo.app.domain.LedgerImportSummaryCalculator
import com.cuentivo.app.domain.LedgerOldDatesBulkCorrection
import com.cuentivo.app.domain.LedgerYearBulkCorrection
import com.cuentivo.app.domain.LedgerYearCorrection
import com.cuentivo.app.domain.ProductCandidate
import com.cuentivo.app.ui.transaction.DueOption
import com.cuentivo.app.ui.transaction.NeoChoiceChip
import com.cuentivo.app.ui.theme.NeoSlow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure review screen for imported ledger operations.
 * Renders state derived by [LedgerReview] and forwards user interactions to machine callbacks.
 * Strictly adheres to: "Ninguna decisión vive en el Composable."
 */
@Composable
fun LedgerReviewContent(
    review: LedgerReview,
    captureFile: File?,
    onUpdateAmount: (lineIndex: Int, amountCents: String) -> Unit,
    onUpdateCustomer: (lineIndex: Int, customerName: String) -> Unit,
    onUpdateKind: (lineIndex: Int, kind: ProposedLedgerKind) -> Unit,
    onUpdateDate: (lineIndex: Int, date: SafeDateCandidate?) -> Unit,
    onUpdatePageDate: (pageDateEpochDay: Long?) -> Unit,
    /**
     * El plazo de pago de **toda la página**, en días, o `null` para "Sin fecha". Las mismas
     * opciones de la venta manual ([DueOption]); 15 marcado por defecto. La fecha límite de cada
     * cargo se calcula en `domain/LedgerDueDatePolicy`, no aquí.
     */
    dueDateDays: Long? = DueOption.FIFTEEN.days,
    onUpdateDueDateDays: (dueDateDays: Long?) -> Unit = {},
    onRemoveRow: (lineIndex: Int) -> Unit,
    onConfirmObjection: (lineIndex: Int) -> Unit,
    /** Existing customers this page's rows resemble, keyed by lineIndex. Offers, never decisions. */
    customerMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.CustomerCandidate>>,
    onAcceptCustomerAnchor: (lineIndex: Int, candidate: com.cuentivo.app.domain.CustomerCandidate) -> Unit = { _, _ -> },
    /** Catalog products this page's rows resemble, keyed by lineIndex. Offers product name for description, never changes amount. */
    productMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<ProductCandidate>>,
    onAcceptProductAnchor: (lineIndex: Int, candidate: ProductCandidate) -> Unit = { _, _ -> },
    /** Potential duplicate operations already in Room or elsewhere on this page, keyed by lineIndex. */
    duplicateMatches: Map<Int, com.cuentivo.app.domain.LedgerDuplicateMatch>,
    /** Objeciones ya calculadas con las listas completas de candidatos. */
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    isRegistrationBlockedByLegacySave: Boolean,
    isPageCompletenessConfirmed: Boolean,
    onConfirmPageCompleteness: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingOperation by remember { mutableStateOf<LedgerOperation?>(null) }
    // The rows still waiting to be confirmed, as line indices. Kept as indices and not as objects
    // because every edit rebuilds the review, and the object held here would go stale mid-walk.
    var pendingReviewQueue by remember { mutableStateOf<List<Int>>(emptyList()) }
    val hasRowsWithoutDate = review.operations.filter { isRegisterableLedgerOperation(it) }.any { it.date == null }
    // ¿Trae cargos? El selector de plazo sólo se dibuja cuando algo de la página puede vencer:
    // una página sólo de abonos no tiene fecha límite que elegir. Se mira el tipo, no si el
    // renglón ya está resuelto, para que el selector no aparezca y desaparezca conforme se
    // resuelven las objeciones.
    val hasChargeRows = remember(review.operations) {
        review.operations.any {
            it.proposedKind == ProposedLedgerKind.SALE || it.proposedKind == ProposedLedgerKind.PRIOR_DEBT
        }
    }
    // What the model read but would not vouch for. Registering one of these without a person having
    // looked at it is the whole thing this feature must never do, so they stay out of the count --
    // but leaving the person to discover, unaided, that every row needs a tap is how a working
    // import looked like a dead button.
    val objectionLineIndices = remember(objections) { objections.map { it.lineIndex }.toSet() }
    // El posible duplicado frena el renglón igual que una objeción determinista (v44): hasta que la
    // persona confirme "Sí, es otra venta", el renglón no está resuelto. La decisión vive en el
    // objeto puro `LedgerDuplicateBlocking`; aquí sólo se le pasa lo que ya está calculado.
    val duplicateBlockedLineIndices = remember(review.operations, duplicateMatches) {
        com.cuentivo.app.domain.LedgerDuplicateBlocking.blockedLineIndices(review.operations, duplicateMatches)
    }
    val blockedLineIndices = remember(objectionLineIndices, duplicateBlockedLineIndices) {
        objectionLineIndices + duplicateBlockedLineIndices
    }
    val allRowsNeedingReview = remember(review.operations, objections, duplicateBlockedLineIndices) {
        review.operations.filter { !isRegisterableLedgerOperation(it) || it.lineIndex in blockedLineIndices }
    }
    // Cuántas ventas nacerían vencidas con este plazo. El cálculo —la fecha efectiva de cada
    // renglón, la suma, y sobre todo la decisión de "vencida"— vive en el objeto puro
    // `LedgerDueDatePolicy`, que le pregunta al mismo `SaleStatusCalculator` que después
    // clasifica la venta ya escrita. Aquí sólo se lo pasamos: un número no se calcula en un
    // Composable, y menos uno que la persona ve antes de pulsar Registrar.
    val bornOverdueCount = remember(
        review.operations,
        review.pageDateEpochDay,
        review.todayEpochDay,
        objectionLineIndices,
        dueDateDays,
    ) {
        review.todayEpochDay?.let { today ->
            LedgerDueDatePolicy.bornOverdueCount(
                operations = review.operations,
                blockedLineIndices = objectionLineIndices,
                pageDateEpochDay = review.pageDateEpochDay,
                todayEpochDay = today,
                dueDateDays = dueDateDays,
            )
        } ?: 0
    }

    val summaryResult = LedgerImportSummaryCalculator.calculate(review, objections, isPageCompletenessConfirmed, duplicateBlockedLineIndices)
    // El año mal leído en toda la hoja (v44): con dos o más renglones que dicen un año distinto al
    // de la página, se ofrece corregirlos de golpe. La decisión —qué renglones, de qué año a qué
    // año, y que no se toque ninguno sin objeción de FECHA— vive en `LedgerYearCorrection`, que
    // recibe las objeciones tipadas y filtra `IMPLAUSIBLE_DATE` él mismo.
    val yearBulkPlan = remember(review.operations, objections, review.pageDateEpochDay, review.todayEpochDay) {
        val today = review.todayEpochDay
        if (today == null) null else LedgerYearCorrection.plan(
            operations = review.operations,
            objections = objections,
            pageDateEpochDay = review.pageDateEpochDay,
            todayEpochDay = today,
        )
    }
    // Las fechas simplemente viejas (v46): con deudas de hace meses el año ya está bien, y
    // confirmarlas una por una son doce diálogos por algo que no es un error. Sólo se ofrecen en
    // bloque desde dos, y la lista de índices la decide el objeto puro, no la pantalla.
    val oldDatesBulkLineIndices = remember(objections) {
        LedgerOldDatesBulkCorrection.lineIndices(objections)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_import_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("ledger-review-cancel-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_discard))
            }
        }

        // Customer groups and operations list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ledger-review-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // El encabezado (resumen, plazo, avisos) viaja DENTRO de la lista y se desplaza con ella,
            // en elementos separados: como UN solo elemento más alto que la pantalla, en horizontal la
            // lista se quedaba recalculándose en bucle (LedgerReviewRotationTest colgado).
            // Fijo arriba, en un teléfono normal se comía toda la altura y los renglones —lo que la
            // persona tiene que revisar— quedaban reducidos a una franja o desaparecían.
            item(key = "ledger-review-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Summary Card
                LedgerSummaryCard(
                    review = review,
                    objections = objections,
                    modifier = Modifier.fillMaxWidth(),
                )

                }
            }
            // El veredicto del Ledger de MexaLM, si el servidor comparó la página con él. Va en su
            // PROPIO elemento de la lista, como los demás avisos: dos avisos en el mismo elemento
            // crecen por encima del alto de la pantalla.
            //
            // Sin bloque no hay nada que dibujar y la pantalla queda exactamente como estaba: el
            // servidor con la bandera apagada no manda la llave, y esa es la respuesta normal.
            //
            // El veredicto INTERPRETADO es lo que se dibuja: cuando el Ledger no leyó la página
            // (`BLOCKED`, `REVIEW_REQUIRED`) se dice una sola vez, y los renglones no llevan la
            // insignia de "no lo vio" —no la leyó la página, no le faltaron renglones.
            val crosscheckVerdict = review.mexalmCrosscheck?.let {
                MexalmCrosscheckVerdictCalculator.calculate(it)
            }
            if (crosscheckVerdict != null) item(key = "ledger-review-crosscheck") {
                CrosscheckSummaryCard(
                    verdict = crosscheckVerdict,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (yearBulkPlan != null) item(key = "ledger-review-year-bulk") {
                YearBulkCard(
                    plan = yearBulkPlan,
                    onApply = {
                        val today = review.todayEpochDay ?: return@YearBulkCard
                        // Se corrige renglón por renglón con el mismo `applyDate` de siempre, así
                        // que la objeción desaparece sola cuando la fecha ya es plausible y nunca se
                        // marca como confirmada a mano.
                        for (op in review.operations) {
                            if (op.lineIndex !in yearBulkPlan.lineIndices) continue
                            val corrected = LedgerYearCorrection.correctedDateFor(op, yearBulkPlan.targetYear) ?: continue
                            onUpdateDate(op.lineIndex, corrected)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (oldDatesBulkLineIndices.isNotEmpty()) item(key = "ledger-review-old-dates-bulk") {
                OldDatesBulkCard(
                    count = oldDatesBulkLineIndices.size,
                    // Mismo `onConfirmObjection` que el diálogo del renglón: la confirmación lleva la
                    // huella de fecha y tipo, así que si después cambian la fecha vuelve a objetar.
                    onApply = { oldDatesBulkLineIndices.forEach { lineIndex -> onConfirmObjection(lineIndex) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hasRowsWithoutDate) item(key = "ledger-review-page-date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Page date selector: shown ONLY when at least one registrable operation lacks a date
                if (hasRowsWithoutDate) {
                    PageDateCard(
                        pageDateEpochDay = review.pageDateEpochDay,
                        detectedPageDate = review.detectedPageDate,
                        onUpdatePageDate = onUpdatePageDate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                }
            }
            if (hasChargeRows) item(key = "ledger-review-due-date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Un plazo para toda la página, con las mismas opciones de la venta manual. Debajo, cuando
                // aplique, el aviso que no puede faltar: si la página es vieja, con 15 días marcadas varias
                // ventas nacen vencidas, y eso la persona tiene que verlo antes de pulsar Registrar.
                if (hasChargeRows) {
                    DueDateCard(
                        dueDateDays = dueDateDays,
                        onUpdateDueDateDays = onUpdateDueDateDays,
                        bornOverdueCount = bornOverdueCount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                }
            }
            // Cada aviso en su propio elemento de la lista, nunca dos dentro del mismo. El encabezado
            // se partió en bloques en la v43 por esto mismo: un elemento que junta tarjetas y avisos
            // crece por encima del alto de la pantalla y, en horizontal, la lista se queda recalculando
            // en bucle (LedgerReviewRotationTest colgado). Éste era el último que aún los juntaba.
            // Mismo orden y mismas condiciones que cuando compartían un solo elemento.
            //
            // A page that did not fit, or whose text the model could not finish reading, must say so
            // here. Confirming a partial import silently is how a notebook ends up half migrated with
            // nobody aware which half is missing.
            // Cuando además hay que confirmar la hoja, la frase va dentro de esa tarjeta, junto al botón:
            // dos bloques seguidos diciendo lo mismo se comían el espacio de la lista de renglones.
            val incompleteCardShown = summaryResult.pageIncompleteNoticeRequired && !isPageCompletenessConfirmed
            if ((review.pageIncomplete || review.unreadTextRemaining) && !incompleteCardShown) {
                item(key = "ledger-review-incomplete-warning") {
                    Text(
                        text = stringResource(R.string.ai_ledger_page_incomplete_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-page-incomplete-warning"),
                    )
                }
            }

            // Estos avisos van DENTRO de la lista. Estuvieron fuera de ella y se dibujaban flotando
            // encima del título, con el texto transparente sobre la pantalla: parecía un error.
            if (isRegistrationBlockedByLegacySave) {
                item(key = "ledger-review-legacy-save-blocked") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.ai_ledger_legacy_save_blocked))
                            TextButton(onClick = onCancel) { Text(stringResource(R.string.ai_ledger_legacy_save_discard)) }
                        }
                    }
                }
            }

            if (incompleteCardShown) {
                item(key = "ledger-review-page-completeness") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // POR QUÉ se pide la confirmación. Sin el motivo, "confirma" es una orden sin
                            // contexto; y el caso de los renglones descartados es el que más importa: no están
                            // en la lista, así que si nadie dice cuántos fueron, se registra la página sin
                            // ellos y no queda rastro. La duda del modelo va aquí y no suelta arriba, para
                            // no decirla dos veces.
                            if (summaryResult.confirmationReasons.contains(com.cuentivo.app.domain.LedgerPageConfirmationReason.MODEL_DOUBT)) {
                                Text(
                                    text = stringResource(R.string.ai_ledger_page_incomplete_warning),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("ledger-page-incomplete-warning"),
                                )
                            }
                            if (summaryResult.confirmationReasons.contains(com.cuentivo.app.domain.LedgerPageConfirmationReason.UNREADABLE_ROWS)) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.ai_ledger_page_unreadable_rows,
                                        summaryResult.unreadableRowsCount,
                                        summaryResult.unreadableRowsCount,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("ledger-page-unreadable-rows-notice"),
                                )
                            }
                            if (summaryResult.confirmationReasons.contains(com.cuentivo.app.domain.LedgerPageConfirmationReason.NOT_AN_ACCOUNTS_PAGE)) {
                                Text(
                                    text = stringResource(R.string.ai_ledger_page_not_accounts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("ledger-page-not-accounts-notice"),
                                )
                            }
                            OutlinedButton(
                                onClick = onConfirmPageCompleteness,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ledger-confirm-page-complete-button"),
                            ) {
                                Text(stringResource(R.string.ai_ledger_action_confirm_page_complete))
                            }
                        }
                    }
                }
            } else if (summaryResult.pageIncompleteNoticeRequired) {
                item(key = "ledger-review-page-completeness-confirmed") {
                    Text(
                        stringResource(R.string.ai_ledger_page_completeness_confirmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ledger-page-completeness-confirmed"),
                    )
                }
            }
            item(key = "ledger-review-banner") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Financial & Date Block Alert (if any)
                // Also when some rows are fine and others are not: a page that registers 8 of its 20 rows
                // and says nothing about the other 12 is the quiet half-import this screen exists to prevent.
                if (review.summary.hasFinancialErrors || review.summary.executableOperationsCount == 0 ||
                    review.summary.hasMissingDates || allRowsNeedingReview.isNotEmpty()
                ) {
                    FinancialErrorBanner(
                        diagnostics = review.summary.financialDiagnostics,
                        hasZeroOps = review.summary.executableOperationsCount == 0,
                        hasMissingDates = review.summary.hasMissingDates,
                    rowsNeedingReviewCount = allRowsNeedingReview.size,
                    objections = objections,
                    onStartReview = {
                            pendingReviewQueue = allRowsNeedingReview.map { it.lineIndex }.drop(1)
                            editingOperation = allRowsNeedingReview.firstOrNull()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                }
            }
            // Computed once for the page, not per row: the evaluator is pure and the diagnostics
            // it needs belong to the whole review.
            val fieldHealth = review.operations.associate { op ->
                op.lineIndex to LedgerFieldHealth.evaluate(
                    op = op,
                    financialDiagnostics = review.summary.financialDiagnostics,
                    pageDateEpochDay = review.pageDateEpochDay,
                    todayEpochDay = review.todayEpochDay,
                    objections = objections,
                )
            }
            // Las diferencias del Ledger de MexaLM, agrupadas por renglón. Una insignia por
            // DIFERENCIA, no una por renglón: un renglón puede diferir en el monto y en el cliente,
            // y callarse uno de los dos escondería justo el detalle que hay que ver.
            //
            // Son las diferencias VISIBLES del veredicto: cuando el Ledger no leyó la página, las
            // de "no lo vio" no van en ningún renglón, porque no son la diferencia de un renglón.
            val crosscheckByLineIndex = crosscheckVerdict?.visibleDisagreements?.groupBy { it.lineIndex }
                ?: emptyMap()
            // Los renglones que el Ledger de MexaLM confirmó. La decisión vive en el objeto puro
            // `MexalmCrosscheckConfirmation`; aquí sólo se pasa lo que ya está calculado.
            val ledgerConfirmedLineIndices =
                MexalmCrosscheckConfirmation.confirmedLineIndices(review.mexalmCrosscheck)
            items(review.customerGroups) { group ->
                CustomerGroupCard(
                    group = group,
                    captureFile = captureFile,
                    fieldHealth = fieldHealth,
                    duplicateMatches = duplicateMatches,
                    crosscheckDisagreements = crosscheckByLineIndex,
                    ledgerConfirmedLineIndices = ledgerConfirmedLineIndices,
                    onEditOperation = { op -> editingOperation = op },
                    onUpdateKind = onUpdateKind,
                    onRemoveRow = onRemoveRow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Single confirmation button at the bottom
        Button(
            onClick = onConfirm,
            enabled = summaryResult.isConfirmButtonEnabled && !isRegistrationBlockedByLegacySave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ledger-review-confirm-button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            val buttonText = if (summaryResult.isConfirmButtonEnabled) {
                pluralStringResource(
                    R.plurals.ai_ledger_action_register_count,
                    summaryResult.readyCount,
                    summaryResult.readyCount,
                )
            } else {
                if (summaryResult.unresolvedCount > 0) {
                    pluralStringResource(R.plurals.ai_ledger_action_register_blocked_pending, summaryResult.unresolvedCount, summaryResult.unresolvedCount)
                } else if (summaryResult.pageIncompleteNoticeRequired && !isPageCompletenessConfirmed) {
                    stringResource(R.string.ai_ledger_action_register_blocked_incomplete)
                } else stringResource(R.string.ai_ledger_action_register_disabled)
            }
            Text(
                text = buttonText,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }


    // Edit modal dialog when a row or unresolved field is tapped.
    //
    // `key(op.lineIndex)` re-monta el diálogo cuando la cola avanza al renglón siguiente. Es la mitad
    // que faltaba: la cola cambia `editingOperation` sin desmontar el diálogo (`onSave`/`onConfirm`
    // avanzan y vuelven a llamar a `editingOperation`), y los `remember` sin key de dentro sobreviven
    // a esa recomposición, así que la fila N abría con los campos de la N-1 y Guardar los escribía
    // ENCIMA de la N --una página Laura/Estefanía/Nosotros quedó como 3 x Laura--.
    //
    // Se elige `key` alrededor del diálogo y no un `remember(operation.lineIndex)` por campo porque el
    // defecto es exactamente "un `remember` sin key": con la llave en el sitio, cualquier `remember`
    // que se añada mañana dentro del diálogo nace ya aislado por renglón, sin depender de que alguien
    // se acuerde de pasar la key a cada uno.
    editingOperation?.let { op ->
        key(op.lineIndex) {
            EditLedgerOperationDialog(
                operation = op,
                captureFile = captureFile,
                pageDateEpochDay = review.pageDateEpochDay,
                todayEpochDay = review.todayEpochDay,
                customerMatch = customerMatches[op.lineIndex],
                productMatch = productMatches[op.lineIndex],
                duplicateMatch = duplicateMatches[op.lineIndex],
                objections = objections,
                onConfirmObjection = {
                    onConfirmObjection(op.lineIndex)
                    val nextLineIndex = pendingReviewQueue.firstOrNull()
                    pendingReviewQueue = pendingReviewQueue.drop(1)
                    editingOperation = nextLineIndex?.let { next ->
                        review.operations.firstOrNull { it.lineIndex == next }
                    }
                },
                onAcceptCustomerAnchor = { candidate ->
                    onAcceptCustomerAnchor(op.lineIndex, candidate)
                    val nextLineIndex = pendingReviewQueue.firstOrNull()
                    pendingReviewQueue = pendingReviewQueue.drop(1)
                    editingOperation = nextLineIndex?.let { next ->
                        review.operations.firstOrNull { it.lineIndex == next }
                    }
                },
                onAcceptProductAnchor = { candidate ->
                    onAcceptProductAnchor(op.lineIndex, candidate)
                },
                onDismiss = {
                    editingOperation = null
                    pendingReviewQueue = emptyList()
                },
                onSave = { updatedName, updatedAmount, updatedKind, updatedDate ->
                    // Saving is an explicit confirmation of all three fields, not a diff. Someone who
                    // opens a doubtful row, checks it against the crop and saves has resolved the
                    // doubt even when they changed nothing; forwarding only what differs would leave
                    // that row out with no way to ever let it in.
                    onUpdateCustomer(op.lineIndex, updatedName)
                    onUpdateAmount(op.lineIndex, updatedAmount)
                    onUpdateKind(op.lineIndex, updatedKind)
                    onUpdateDate(op.lineIndex, updatedDate)
                    // Straight on to the next doubtful row. Twenty rows means twenty confirmations
                    // either way -- what changes is whether the person has to hunt for each one.
                    val nextLineIndex = pendingReviewQueue.firstOrNull()
                    pendingReviewQueue = pendingReviewQueue.drop(1)
                    editingOperation = nextLineIndex?.let { next ->
                        review.operations.firstOrNull { it.lineIndex == next }
                    }
                },
            )
        }
    }
}

@Composable
private fun LedgerSummaryCard(
    review: LedgerReview,
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    modifier: Modifier = Modifier,
) {
    val summaryResult = LedgerImportSummaryCalculator.calculate(review, objections, false)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_review_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val detectedText = pluralStringResource(
                R.plurals.ai_ledger_summary_detected_count,
                summaryResult.detectedCount,
                summaryResult.detectedCount,
            )
            Text(
                text = detectedText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val readyText = pluralStringResource(
                R.plurals.ai_ledger_summary_ready_count,
                summaryResult.readyCount,
                summaryResult.readyCount,
            )
            val pendingText = pluralStringResource(
                R.plurals.ai_ledger_summary_pending_count,
                summaryResult.pendingReviewCount,
                summaryResult.pendingReviewCount,
            )
            Text(
                text = stringResource(R.string.ai_ledger_summary_status_format, readyText, pendingText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summaryResult.pageIncompleteNoticeRequired) {
                Text(
                    text = stringResource(R.string.ai_ledger_page_incomplete_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ledger-summary-page-incomplete-notice"),
                )
            }
        }
    }
}

@Composable
private fun PageDateCard(
    pageDateEpochDay: Long?,
    detectedPageDate: com.cuentivo.app.ai.ledger.LedgerDetectedPageDate?,
    onUpdatePageDate: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dateText by remember {
        mutableStateOf(
            pageDateEpochDay?.let {
                val localDate = java.time.LocalDate.ofEpochDay(it)
                String.format("%04d-%02d-%02d", localDate.year, localDate.monthValue, localDate.dayOfMonth)
            } ?: ""
        )
    }
    val parsedDate = parseLedgerDateInput(dateText)
    val dateIsBlank = dateText.isBlank()
    val todayEpochDay = remember { java.time.LocalDate.now().toEpochDay() }
    val isValidDate = parsedDate != null && LedgerReviewEdits.isAcceptableDate(parsedDate, todayEpochDay)
    val dateIsWrong = !dateIsBlank && !isValidDate

    // The date the server read off the sheet, offered for a tap. Never applied on its own, not
    // even at confidence 0.99: a wrong date sends ten movements to a day that is not theirs, and
    // in an append-only ledger that is undone with ten compensating movements. Offering it is
    // help; writing it alone is guessing. Only a real, non-future calendar day is offered at all,
    // and only while the field is empty — once the person has typed something, their reading of
    // the paper outranks the model's.
    val offerableDetectedDate = detectedPageDate?.takeIf { dateIsBlank }?.let { detected ->
        val asCalendarDay = runCatching {
            java.time.LocalDate.of(detected.date.year, detected.date.month, detected.date.day)
        }.getOrNull()
        asCalendarDay?.takeIf { it.toEpochDay() <= todayEpochDay }?.let { detected }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_field_page_date_plain),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = dateText,
                onValueChange = { newText ->
                    dateText = newText
                    val candidate = parseLedgerDateInput(newText)
                    if (candidate != null && LedgerReviewEdits.isAcceptableDate(candidate, todayEpochDay)) {
                        val epochDay = java.time.LocalDate.of(candidate.year, candidate.month, candidate.day).toEpochDay()
                        onUpdatePageDate(epochDay)
                    } else {
                        onUpdatePageDate(null)
                    }
                },
                label = { Text(stringResource(R.string.ai_ledger_field_page_date_plain)) },
                placeholder = { Text(stringResource(R.string.ai_ledger_field_date_placeholder)) },
                isError = dateIsWrong,
                supportingText = if (dateIsWrong) {
                    { Text(stringResource(R.string.ai_ledger_field_page_date_invalid)) }
                } else null,
                modifier = Modifier.fillMaxWidth().testTag("ledger-page-date-field"),
                singleLine = true,
            )
            if (offerableDetectedDate != null) {
                val detectedCalendarDay = java.time.LocalDate.of(
                    offerableDetectedDate.date.year,
                    offerableDetectedDate.date.month,
                    offerableDetectedDate.date.day,
                )
                val detectedText = detectedCalendarDay.toString()
                // Low certainty is said in the offer itself, so whoever taps it knows they are
                // confirming something the model was unsure about rather than something settled.
                val offerText = if (offerableDetectedDate.confidence < LOW_PAGE_DATE_CONFIDENCE) {
                    stringResource(R.string.ai_ledger_page_date_detected_low_confidence, detectedText)
                } else {
                    stringResource(R.string.ai_ledger_page_date_detected, detectedText)
                }
                TextButton(
                    onClick = {
                        // The tap is the acceptance. Until it happens, the detected date has
                        // changed nothing: not the field, not the review, not a single row.
                        dateText = detectedText
                        onUpdatePageDate(detectedCalendarDay.toEpochDay())
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-page-date-detected-offer"),
                ) {
                    Text(
                        text = offerText,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Below this, a detected page date says so in its own offer text. */
private const val LOW_PAGE_DATE_CONFIDENCE = 0.5

/**
 * "N renglones dicen 2023. ¿Los cambio a 2026?" — la corrección del año en toda la hoja.
 *
 * El botón de un renglón resuelve un renglón; con doce renglones mal leídos eso es doce diálogos.
 * Esta tarjeta existe para el caso real del recorrido (una hoja de 2026 leída como 2023) y sólo
 * aparece cuando el objeto puro lo pide: dos o más renglones con la objeción de fecha.
 */
@Composable
private fun YearBulkCard(
    plan: LedgerYearBulkCorrection,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag("ledger-year-bulk-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (plan.hasMixedReadYears) {
                    // Años mezclados: el dominante no representa a todos los renglones, así que la
                    // tarjeta describe lo único verdadero para todos —que su año no es el de la
                    // hoja— y el conteo sigue siendo exactamente el de filas que se modificarán.
                    pluralStringResource(
                        R.plurals.ai_ledger_year_bulk_question_mixed,
                        plan.lineIndices.size,
                        plan.lineIndices.size,
                        plan.targetYear,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.ai_ledger_year_bulk_question,
                        plan.lineIndices.size,
                        plan.lineIndices.size,
                        plan.readYear,
                        plan.targetYear,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ledger-year-bulk-button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(stringResource(R.string.ai_ledger_action_change_all))
            }
        }
    }
}

/**
 * "N renglones son de hace más de 6 meses. ¿Son correctos?" (v46).
 *
 * El gemelo de [YearBulkCard] para el caso que no es un error: una libreta con deudas de meses. El
 * año ya está bien y lo único que falta es que alguien diga "sí, esa deuda es de verdad". Sin esto
 * eran doce aperturas de renglón para confirmar lo mismo doce veces.
 *
 * El conteo y los índices vienen de `LedgerOldDatesBulkCorrection`; aquí sólo se dibuja, y el botón
 * usa el mismo `onConfirmObjection` del diálogo del renglón —con la huella de fecha y tipo, que
 * caduca si la fecha cambia—.
 */
@Composable
private fun OldDatesBulkCard(
    count: Int,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag("ledger-old-dates-bulk-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.ai_ledger_old_dates_bulk_question,
                    count,
                    count,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ledger-old-dates-bulk-button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(stringResource(R.string.ai_ledger_action_confirm_all_old_dates))
            }
        }
    }
}

/**
 * El plazo de pago de la página entera: Sin fecha / 7 / 15 / 30 días, con 15 marcado.
 *
 * Reutiliza [DueOption] y sus textos —la misma pregunta y las mismas etiquetas de la venta
 * manual— porque dos listas de plazos que se desincronicen es como el mismo negocio acabara con
 * ventas que el score trata distinto según por dónde se registraron.
 *
 * Un solo plazo para la página, no uno por renglón: en la libreta se acuerda una fecha para la
 * compra, no una por renglón.
 *
 * Debajo, el aviso de ventas que nacerían vencidas, calculado fuera por
 * [LedgerDueDatePolicy.bornOverdueCount] y leído con `pluralStringResource` —un `plurals`, nunca
 * `stringResource` sobre un `plurals`, que ya reventó una pantalla así.
 */
@Composable
private fun DueDateCard(
    dueDateDays: Long?,
    onUpdateDueDateDays: (Long?) -> Unit,
    bornOverdueCount: Int,
    modifier: Modifier = Modifier,
) {
    // "Sin fecha" es una opción con texto, no la ausencia de respuesta, así que se muestra siempre
    // y se puede volver a ella. El `?:` sólo cubre un valor fuera del conjunto, que esta pantalla
    // no puede producir: sólo se escriben valores de [DueOption].
    val selected = DueOption.entries.firstOrNull { it.days == dueDateDays } ?: DueOption.FIFTEEN

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.new_sale_due_question),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Two by two, same as Nueva venta: four chips in one row truncate "Sin fecha" at 320 dp.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DueOption.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { option ->
                            NeoChoiceChip(
                                label = stringResource(option.labelRes),
                                isSelected = selected == option,
                                onClick = { onUpdateDueDateDays(option.days) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ledger-due-option-" + option.name),
                            )
                        }
                    }
                }
            }
            if (bornOverdueCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.ai_ledger_due_born_overdue,
                        bornOverdueCount,
                        bornOverdueCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ledger-due-born-overdue-warning"),
                )
            }
        }
    }
}

/**
 * El aviso de una objeción, en palabras.
 *
 * Un solo sitio para traducirlo, porque lo dicen dos: el banner de la pantalla —que es donde la
 * persona decide si registra— y el diálogo del renglón. Antes vivía sólo en el diálogo, así que en la
 * pantalla la persona veía un número y tenía que abrir el renglón para saber por qué.
 */
@Composable
private fun ledgerObjectionMessage(objection: com.cuentivo.app.domain.LedgerDeterministicObjection): String =
    when (objection.type) {
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT ->
            stringResource(R.string.ai_ledger_objection_decimal_shift)
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT ->
            stringResource(R.string.ai_ledger_objection_price_mismatch)
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.UNUSUAL_CENTS ->
            stringResource(R.string.ai_ledger_objection_unusual_cents)
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED ->
            stringResource(R.string.ai_ledger_objection_customer_confirmation)
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.PAGE_TOTAL_MISMATCH ->
            stringResource(R.string.ai_ledger_objection_page_total_mismatch, objection.declaredCents ?: 0L, objection.expectedCents ?: 0L)
        com.cuentivo.app.domain.LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE ->
            // El motivo decide la frase (v46): mandar a "revisa el año" a quien tiene una deuda real
            // de hace meses era el defecto — el año ya estaba bien y el aviso pedía lo mismo doce veces.
            when (objection.dateReason) {
                com.cuentivo.app.domain.LedgerImplausibleDateReason.TOO_OLD ->
                    stringResource(R.string.ai_ledger_objection_date_old)
                com.cuentivo.app.domain.LedgerImplausibleDateReason.FUTURE ->
                    stringResource(R.string.ai_ledger_objection_date_future)
                // YEAR_MISMATCH, y `null` por si una objeción vieja no trae motivo: el texto del año
                // es el más prudente, porque el año es lo que la tarjeta grande sabe corregir.
                else -> stringResource(R.string.ai_ledger_objection_implausible_date)
            }
    }

@Composable
private fun FinancialErrorBanner(
    diagnostics: List<OperationFinancialDiagnostic>,
    hasZeroOps: Boolean,
    hasMissingDates: Boolean = false,
    rowsNeedingReviewCount: Int = 0,
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection> = emptyList(),
    onStartReview: () -> Unit = {},
    modifier: Modifier = Modifier,
    ) {
    Surface(
        modifier = modifier.testTag("ledger-financial-error-banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        // Una hoja dudosa mostraba tres avisos rojos seguidos —el resumen, el botón y una frase por
        // cada tipo de objeción—, y el detalle ahogaba lo único que la persona tiene que hacer:
        // revisar. El resumen se queda a la vista y el porqué vive dentro, a un toque (v44).
        var detailsExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasZeroOps && diagnostics.isEmpty()) {
                Text(
                    text = if (rowsNeedingReviewCount > 0) {
                        stringResource(R.string.ai_ledger_error_rows_need_review, rowsNeedingReviewCount)
                    } else {
                        stringResource(R.string.ai_ledger_error_zero_operations)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (rowsNeedingReviewCount > 0) {
                TextButton(
                    onClick = onStartReview,
                    modifier = Modifier.testTag("ledger-start-review-button"),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.ai_ledger_action_review_uncertain,
                            rowsNeedingReviewCount,
                            rowsNeedingReviewCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            // POR QUÉ no se puede registrar todavía, en palabras. Se deduplica por tipo —cinco
            // renglones con el mismo aviso son un aviso, no cinco— y se esconde tras el resumen para
            // no ahogar el resto del banner.
            if (objections.isNotEmpty()) {
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.testTag("ledger-banner-details-toggle"),
                ) {
                    Text(
                        text = stringResource(
                            if (detailsExpanded) R.string.ai_ledger_action_hide_details else R.string.ai_ledger_action_show_details,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (detailsExpanded) {
                    for (objection in objections.distinctBy { it.type }) {
                        Text(
                            text = ledgerObjectionMessage(objection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.testTag("ledger-banner-objection"),
                        )
                    }
                }
            }
            if (hasMissingDates && diagnostics.isEmpty() && !hasZeroOps) {
                Text(
                    text = stringResource(R.string.ai_ledger_error_missing_page_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            diagnostics.forEach { diag ->
                val message = when (diag.reason) {
                    FinancialRejectionReason.ZERO_AMOUNT -> stringResource(R.string.ai_ledger_error_zero_amount)
                    FinancialRejectionReason.OVERFLOW -> stringResource(R.string.ai_ledger_error_overflow)
                    FinancialRejectionReason.PAYMENT_WITHOUT_DEBT -> stringResource(R.string.ai_ledger_error_payment_without_debt)
                    FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT -> stringResource(R.string.ai_ledger_error_payment_exceeds_debt)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CustomerGroupCard(
    group: CustomerReviewGroup,
    captureFile: File?,
    fieldHealth: Map<Int, com.cuentivo.app.domain.OperationFieldHealth>,
    duplicateMatches: Map<Int, com.cuentivo.app.domain.LedgerDuplicateMatch>,
    /** The Ledger de MexaLM differences, by line index. Empty when the server sent no block. */
    crosscheckDisagreements: Map<Int, List<com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreement>> = emptyMap(),
    /** The rows the Ledger de MexaLM confirmed. Empty when the server sent no block. */
    ledgerConfirmedLineIndices: Set<Int> = emptySet(),
    onEditOperation: (LedgerOperation) -> Unit,
    onUpdateKind: (lineIndex: Int, kind: ProposedLedgerKind) -> Unit,
    onRemoveRow: (lineIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Customer header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.customerName.ifBlank { stringResource(R.string.ai_slot_customer) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Badge: New vs Existing
                Surface(
                    color = if (group.isNew) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = if (group.isNew) {
                            stringResource(R.string.ai_ledger_customer_badge_new)
                        } else {
                            stringResource(R.string.ai_ledger_customer_badge_existing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (group.isNew) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Balance transition: $prev -> $resulting
            val prevFormatted = formatCents(group.previousBalanceCents)
            val resFormatted = formatCents(group.resultingBalanceCents)
            Text(
                text = stringResource(R.string.ai_ledger_customer_balance_transition, prevFormatted, resFormatted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // "Saldo: $0.00 → $0.00" con un renglón de $999 en duda parece decir que no se anotará
            // nada. Este renglón dice lo que está esperando a resolverse, aparte del saldo.
            val pendingInDoubtCents = com.cuentivo.app.domain.LedgerPendingInDoubt.chargeCents(group)
            if (pendingInDoubtCents > 0L) {
                Text(
                    text = stringResource(R.string.ai_ledger_pending_in_doubt, formatCents(pendingInDoubtCents)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("ledger-group-pending-in-doubt"),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Rows
            group.operations.forEach { op ->
                LedgerOperationRow(
                    operation = op,
                    captureFile = captureFile,
                    health = fieldHealth[op.lineIndex],
                    duplicateMatch = duplicateMatches[op.lineIndex],
                    crosscheckDisagreements = crosscheckDisagreements[op.lineIndex] ?: emptyList(),
                    isLedgerConfirmed = op.lineIndex in ledgerConfirmedLineIndices,
                    onEdit = { onEditOperation(op) },
                    onUpdateKind = { kind -> onUpdateKind(op.lineIndex, kind) },
                    onRemove = { onRemoveRow(op.lineIndex) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LedgerOperationRow(
    operation: LedgerOperation,
    captureFile: File?,
    health: com.cuentivo.app.domain.OperationFieldHealth? = null,
    duplicateMatch: com.cuentivo.app.domain.LedgerDuplicateMatch? = null,
    /** What the Ledger de MexaLM read differently on this row. Empty when there is no block. */
    crosscheckDisagreements: List<com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreement> = emptyList(),
    /**
     * The Ledger de MexaLM confirmed this row: both readings said the same thing about the kind,
     * the customer and the amount.
     *
     * It is why the row is no longer asking for a review, and the discreet mark says so: a row
     * that silently changed colour is a row the person cannot explain.
     */
    isLedgerConfirmed: Boolean = false,
    onEdit: () -> Unit,
    onUpdateKind: (ProposedLedgerKind) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUnresolved = operation.unreadableFields.isNotEmpty() || operation.uncertainFields.isNotEmpty()

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit)
            .testTag("ledger-op-row-" + operation.lineIndex),
        color = if (isUnresolved) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Thumbnail Crop or "Sin recorte"
            RowCropThumbnail(
                captureFile = captureFile,
                operation = operation,
                modifier = Modifier.size(48.dp),
            )

            // Details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val kindText = when (operation.proposedKind) {
                        ProposedLedgerKind.SALE -> stringResource(R.string.ai_ledger_kind_sale)
                        ProposedLedgerKind.PAYMENT -> stringResource(R.string.ai_ledger_kind_payment)
                        ProposedLedgerKind.PRIOR_DEBT -> stringResource(R.string.ai_ledger_kind_prior_debt)
                        null -> stringResource(R.string.ai_ledger_kind_unspecified)
                    }
                    Text(
                        text = kindText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isUnresolved) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = if (operation.unreadableFields.isNotEmpty()) {
                                    stringResource(R.string.ai_ledger_badge_unreadable)
                                } else {
                                    stringResource(R.string.ai_ledger_badge_uncertain)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (duplicateMatch != null) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.testTag("ledger-op-duplicate-badge-" + operation.lineIndex),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_duplicate_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                // One badge for the whole row said something was wrong without saying where, so
                // every doubtful row looked equally broken and equally expensive to check. These
                // say which field to look at, which is the difference between reading a row and
                // glancing at it.
                // Only the fields that need something. Painting all four turned a page of sixteen
                // rows into sixteen screens: a long label like "Cliente (del renglon de arriba)" ate
                // the width, wrapped, and pushed every card to the height of the phone. A chip that
                // says "everything is fine" is also the one nobody reads, so it earns no space.
                if (health != null && health.needsAttention) {
                    val attention = listOfNotNull(
                        chipFor(stringResource(R.string.ai_ledger_field_short_customer), health.customerHealth, health.customerOrigin),
                        chipFor(stringResource(R.string.ai_ledger_field_short_amount), health.amountHealth, com.cuentivo.app.domain.FieldOrigin.READ_HERE),
                        chipFor(stringResource(R.string.ai_ledger_field_short_kind), health.kindHealth, com.cuentivo.app.domain.FieldOrigin.READ_HERE),
                        chipFor(stringResource(R.string.ai_ledger_field_short_date), health.dateHealth, health.dateOrigin),
                    )
                    if (attention.isNotEmpty()) {
                        Row(
                            modifier = Modifier.testTag("ledger-field-health-" + operation.lineIndex),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attention.forEach { chip ->
                                FieldHealthChip(chip.label, chip.color, chip.inherited)
                            }
                        }
                    }
                }

                // El Ledger de MexaLM leyó este renglón de otra manera.
                //
                // Es INFORMACIÓN, no un bloqueo: el renglón se registra igual que los demás, con lo
                // que dijo la lectura de la página. Por eso no usa el color de error —el error es
                // "esto está roto" y aquí nada está roto— ni cambia el color de fondo del renglón.
                //
                // Va en su propia fila y no junto al tipo porque una etiqueta larga se come el
                // ancho, se parte en dos y empuja la tarjeta al alto del teléfono.
                if (crosscheckDisagreements.isNotEmpty()) {
                    Row(
                        modifier = Modifier.testTag("ledger-crosscheck-" + operation.lineIndex),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        crosscheckDisagreements.forEach { disagreement ->
                            CrosscheckBadge(disagreement, operation.lineIndex)
                        }
                    }
                }

                // El Ledger de MexaLM confirmó este renglón: las dos lecturas dicen lo mismo del
                // tipo, el cliente y el monto, y por eso el renglón ya no pide revisión.
                //
                // Es una marca DISCRETA, en el color positivo de la app: no es un error ni un
                // aviso, es un hecho. Y está en su propia fila porque una etiqueta larga se come
                // el ancho y empuja la tarjeta al alto del teléfono.
                if (isLedgerConfirmed) {
                    Row(
                        modifier = Modifier.testTag("ledger-crosscheck-confirmed-" + operation.lineIndex),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_crosscheck_row_confirmed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                val amountCentsVal = operation.amountCents?.toLongOrNull()
                val amountText = if (amountCentsVal != null) {
                    formatCents(amountCentsVal)
                } else {
                    operation.amountCents ?: stringResource(R.string.ai_slot_amount)
                }
                // Amount and customer on one line: they are what a person scans for, and the two of
                // them stacked was half the height of a card that only ever holds one movement.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = amountText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (operation.customerName != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = operation.customerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }

                // The date the notebook carries, shown even when it is missing: importing a year of
                // history as if it all happened today is exactly what this feature must not do.
                val rowDate = operation.date
                Text(
                    text = if (rowDate != null) {
                        stringResource(
                            R.string.ai_ledger_row_date,
                            String.format("%04d-%02d-%02d", rowDate.year, rowDate.month, rowDate.day),
                        )
                    } else {
                        stringResource(R.string.ai_ledger_row_date_missing)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Row actions: quick kind toggle & delete
            IconButton(
                onClick = {
                    val nextKind = when (operation.proposedKind) {
                        ProposedLedgerKind.SALE -> ProposedLedgerKind.PAYMENT
                        ProposedLedgerKind.PAYMENT -> ProposedLedgerKind.PRIOR_DEBT
                        ProposedLedgerKind.PRIOR_DEBT, null -> ProposedLedgerKind.SALE
                    }
                    onUpdateKind(nextKind)
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_receipt_long),
                    contentDescription = stringResource(R.string.ai_ledger_field_kind),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.ai_ledger_action_delete_row),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RowCropThumbnail(
    captureFile: File?,
    operation: LedgerOperation,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(captureFile, operation.region) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(captureFile, operation.region) { mutableStateOf(false) }

    LaunchedEffect(captureFile, operation.region) {
        if (captureFile != null && operation.region != null) {
            val decoded = withContext(Dispatchers.IO) {
                LedgerCropHelper.decodeCrop(captureFile, operation.region)
            }
            bitmap = decoded
        } else {
            bitmap = null
        }
        loaded = true
    }

    val currentBitmap = bitmap
    // El hueco mide 48×48 dp FIJOS (ver `RowCropThumbnail(...)`) y la frase traducida no cabe: se
    // partía en dos líneas y se montaba encima del renglón. Por eso aquí NO se dibuja texto.
    //
    // Las tres alternativas se descartaron a propósito:
    // - un texto más corto vuelve a depender del largo de la traducción, que es el defecto;
    // - recortarlo con puntos suspensivos deja "Sin recorte disp…", que se lee como un error;
    // - un icono diría "sin imagen" sólo si existe uno que lo diga, y los que el proyecto ya usa
    //   (persona, carrito, estrella, aviso…) no lo dicen: poner uno sería mentir en pequeño.
    //
    // Lo que se conserva es la descripción para lectores de pantalla, que es donde el texto sí cabe.
    // El costo visual es un cuadro gris vacío, que ya se lee como "aquí no hay miniatura".
    val noCropDescription = stringResource(R.string.ai_ledger_crop_not_available)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("ledger-crop-slot")
            .then(
                if (currentBitmap == null) {
                    Modifier.semantics { contentDescription = noCropDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.ai_ledger_crop_content_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditLedgerOperationDialog(
    operation: LedgerOperation,
    captureFile: File?,
    pageDateEpochDay: Long? = null,
    todayEpochDay: Long? = null,
    customerMatch: com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.CustomerCandidate>? = null,
    productMatch: com.cuentivo.app.domain.EntityMatchResult<ProductCandidate>? = null,
    duplicateMatch: com.cuentivo.app.domain.LedgerDuplicateMatch? = null,
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    onAcceptCustomerAnchor: (com.cuentivo.app.domain.CustomerCandidate) -> Unit = {},
    onAcceptProductAnchor: (ProductCandidate) -> Unit = {},
    onConfirmObjection: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (customerName: String, amountCents: String, kind: ProposedLedgerKind, date: SafeDateCandidate?) -> Unit,
) {
    var name by remember { mutableStateOf(operation.customerName ?: "") }
    // In pesos, because that is what the notebook says and what this dialog exists to compare
    // against. Showing raw cents turned a misread "$999" into a field reading 9990, which is close
    // enough to the paper to be waved through.
    var amount by remember { mutableStateOf(LedgerAmountInput.toPesosText(operation.amountCents)) }
    val amountCents = LedgerAmountInput.toCents(amount)
    val amountIsWrong = amount.isNotBlank() && amountCents == null
    // Deliberately nullable: an operation whose kind Gemini could not read must be chosen by the
    // person, not defaulted to SALE. Defaulting would let a name correction silently classify a
    // line as a sale nobody picked.
    var kind by remember { mutableStateOf(operation.proposedKind) }
    var dateText by remember {
        mutableStateOf(
            operation.date?.let { String.format("%04d-%02d-%02d", it.year, it.month, it.day) } ?: "",
        )
    }
    val parsedDate = parseLedgerDateInput(dateText)
    val dateIsBlank = dateText.isBlank()
    val dateIsWrong = !dateIsBlank && parsedDate == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.ai_ledger_dialog_edit_title, operation.lineIndex + 1))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // El recorte se dibuja SÓLO si existe. Antes se reservaba un hueco de 80 dp de alto
                // aunque no hubiera región: un vacío grande que empujaba abajo todo lo demás sin
                // decir nada (recorrido "Doña Mary", v44).
                if (captureFile != null && operation.region != null) {
                    RowCropThumbnail(
                        captureFile = captureFile,
                        operation = operation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                    )
                }

                // Deterministic objections banner
                val rowObjections = objections.filter { it.lineIndex == operation.lineIndex }
                for (objection in rowObjections) {
                    val objectionMessage = ledgerObjectionMessage(objection)
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-edit-objection-warning"),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = objectionMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
                val objectionConfirmed = com.cuentivo.app.domain.LedgerObjectionConfirmation.isConfirmed(operation)
                if (rowObjections.isNotEmpty() && !objectionConfirmed) {
                    val implausibleDate = rowObjections.any {
                        it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE &&
                            it.dateReason == com.cuentivo.app.domain.LedgerImplausibleDateReason.YEAR_MISMATCH
                    }
                    // El año al que se corrige: el de la página, o el de hoy si la hoja no trae
                    // fecha. La fecha conserva mes y día; el 29 de febrero cae al último día del mes.
                    val correctedYearDate = if (implausibleDate && todayEpochDay != null) {
                        val targetYear = LedgerYearCorrection.targetYear(pageDateEpochDay, todayEpochDay)
                        operation.date?.let { LedgerYearCorrection.correctedDate(it, targetYear) }
                    } else {
                        null
                    }
                    val chosenKindForYear = kind
                    val amountCentsForYear = amountCents
                    if (correctedYearDate != null &&
                        correctedYearDate != operation.date &&
                        chosenKindForYear != null &&
                        amountCentsForYear != null &&
                        !dateIsWrong
                    ) {
                        // El botón fácil corrige el año en vez de aceptar la fecha imposible (v44):
                        // con prisa, "Sí, es correcto" confirmaba 2023 en toda la hoja.
                        Button(
                            onClick = { onSave(name, amountCentsForYear, chosenKindForYear, correctedYearDate) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ledger-edit-correct-year-button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_action_change_year, correctedYearDate.year),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        // "Sí, es correcto" pasa a salida secundaria: sigue existiendo para la fecha
                        // que de verdad era rara, pero deja de ser el botón grande.
                        TextButton(
                            onClick = onConfirmObjection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ledger-edit-confirm-objection-button"),
                        ) {
                            Text(stringResource(R.string.ai_ledger_action_confirm_objection))
                        }
                    } else {
                        Button(
                            onClick = onConfirmObjection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ledger-edit-confirm-objection-button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_action_confirm_objection),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                if (duplicateMatch != null) {
                    val dateStr = runCatching {
                        java.time.LocalDate.ofEpochDay(duplicateMatch.effectiveDateEpochDay).toString()
                    }.getOrDefault("")
                    val descSuffix = if (!duplicateMatch.description.isNullOrBlank()) ": ${duplicateMatch.description}" else ""
                    val kindStr = when (duplicateMatch.kind) {
                        ProposedLedgerKind.SALE -> stringResource(R.string.ai_ledger_kind_sale)
                        ProposedLedgerKind.PAYMENT -> stringResource(R.string.ai_ledger_kind_payment)
                        ProposedLedgerKind.PRIOR_DEBT -> stringResource(R.string.ai_ledger_kind_prior_debt)
                    }
                    val detailText = stringResource(
                        R.string.ai_ledger_duplicate_detail_format,
                        kindStr.lowercase(),
                        duplicateMatch.customerName,
                        formatCents(duplicateMatch.amountCents),
                        dateStr,
                        descSuffix,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-edit-duplicate-warning"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_duplicate_warning, detailText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    // El duplicado frena el renglón hasta que la persona diga que es otra venta
                    // (v44). Mismo mecanismo que las objeciones —la huella en el renglón—, con su
                    // propio texto: "Sí, es otra venta". Si corrige el renglón y deja de casar, la
                    // duda desaparece sola y este botón ya no aparece.
                    if (!com.cuentivo.app.domain.LedgerObjectionConfirmation.isConfirmed(operation)) {
                        Button(
                            onClick = onConfirmObjection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ledger-edit-confirm-duplicate-button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_action_confirm_duplicate),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_customer)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_customer_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-edit-customer-field"),
                    singleLine = true,
                )

                // What the notebook says next to who the business already knows. Offered, not
                // applied: the name on the paper stays until somebody says these are the same
                // person, because that decision is what the money follows afterwards.
                val candidate = customerMatch?.candidate
                if (candidate != null) {
                    TextButton(
                        onClick = { onAcceptCustomerAnchor(candidate) },
                        modifier = Modifier.fillMaxWidth().testTag("ledger-accept-customer-match"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_customer_match_action, candidate.name),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // Catalog product offer: offered, never forced. Accepting only updates description,
                // leaving amount, price, productId, and inventory untouched.
                val prodCandidate = productMatch?.candidate
                if (prodCandidate != null) {
                    TextButton(
                        onClick = { onAcceptProductAnchor(prodCandidate) },
                        modifier = Modifier.fillMaxWidth().testTag("ledger-accept-product-match"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_product_match_action, prodCandidate.name),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_amount)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_amount_placeholder)) },
                    isError = amountIsWrong,
                    supportingText = if (amountIsWrong) {
                        { Text(stringResource(R.string.ai_ledger_field_amount_invalid)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-edit-amount-field"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_date)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_date_placeholder)) },
                    isError = dateIsWrong,
                    supportingText = if (dateIsWrong) {
                        { Text(stringResource(R.string.ai_ledger_field_date_invalid)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-edit-date-field"),
                    singleLine = true,
                )

                // Tres fichas ("Venta", "Abono", "Deuda anterior") en una fila con `weight(1f)`: a
                // 360 dp "Deuda anterior" no cabía y se partía letra por letra ("Deud/a/anteri/or").
                // Ahora fluyen y, si no caben, bajan enteras, sin partir ninguna palabra.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_sale),
                        selected = kind == ProposedLedgerKind.SALE,
                        onClick = { kind = ProposedLedgerKind.SALE },
                        modifier = Modifier.testTag("ledger-kind-SALE"),
                    )
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_payment),
                        selected = kind == ProposedLedgerKind.PAYMENT,
                        onClick = { kind = ProposedLedgerKind.PAYMENT },
                        modifier = Modifier.testTag("ledger-kind-PAYMENT"),
                    )
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_prior_debt),
                        selected = kind == ProposedLedgerKind.PRIOR_DEBT,
                        onClick = { kind = ProposedLedgerKind.PRIOR_DEBT },
                        modifier = Modifier.testTag("ledger-kind-PRIOR_DEBT"),
                    )
                }
            }
        },
        confirmButton = {
            val chosenKind = kind
            TextButton(
                onClick = {
                    if (chosenKind != null && !dateIsWrong && amountCents != null) {
                        onSave(name, amountCents, chosenKind, parsedDate)
                    }
                },
                enabled = chosenKind != null && !dateIsWrong && amountCents != null,
                modifier = Modifier.testTag("ledger-edit-save-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ledger-edit-cancel-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_cancel))
            }
        },
    )
}

@Composable
private fun KindChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Reads a date the person typed as YYYY-MM-DD. Returns null for anything that is not a real
 * calendar day, so the dialog can refuse to save it rather than storing a day that never existed.
 */
internal fun parseLedgerDateInput(text: String): SafeDateCandidate? {
    val parts = text.trim().split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return runCatching {
        java.time.LocalDate.of(year, month, day)
        SafeDateCandidate(year, month, day)
    }.getOrNull()
}

/**
 * One field, one colour, one mark. The colour is decided by [LedgerFieldHealth]; this only draws it.
 *
 * The mark is not only colour: a green chip and a yellow one have to be told apart by somebody who
 * cannot distinguish them, and by somebody looking at a phone in the sun on a market stall.
 */
@Composable
private fun FieldHealthChip(
    label: String,
    color: com.cuentivo.app.domain.FieldHealthColor,
    inherited: Boolean = false,
) {
    val background = when (color) {
        com.cuentivo.app.domain.FieldHealthColor.GREEN -> MaterialTheme.colorScheme.secondaryContainer
        com.cuentivo.app.domain.FieldHealthColor.YELLOW -> MaterialTheme.colorScheme.tertiaryContainer
        com.cuentivo.app.domain.FieldHealthColor.RED -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (color) {
        com.cuentivo.app.domain.FieldHealthColor.GREEN -> MaterialTheme.colorScheme.onSecondaryContainer
        com.cuentivo.app.domain.FieldHealthColor.YELLOW -> MaterialTheme.colorScheme.onTertiaryContainer
        com.cuentivo.app.domain.FieldHealthColor.RED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val mark = when {
        // An inherited value is green in every other sense and still worth confirming, so it gets a
        // mark of its own rather than being folded into the same "?" as an unclear reading.
        inherited -> "↑"
        color == com.cuentivo.app.domain.FieldHealthColor.GREEN -> "OK"
        color == com.cuentivo.app.domain.FieldHealthColor.YELLOW -> "?"
        else -> "!"
    }
    Surface(color = background, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = mark + " " + label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private data class FieldChip(
    val label: String,
    val color: com.cuentivo.app.domain.FieldHealthColor,
    val inherited: Boolean,
)

/**
 * A chip only for a field that needs something, and null for one that does not.
 *
 * Green means "nothing to do here", and a badge that says nothing to do is a badge that teaches
 * people to stop reading badges. It also costs width, which on a row of four is what pushed the
 * card past the height of the screen.
 */
private fun chipFor(
    label: String,
    color: com.cuentivo.app.domain.FieldHealthColor,
    origin: com.cuentivo.app.domain.FieldOrigin,
): FieldChip? {
    val inherited = origin != com.cuentivo.app.domain.FieldOrigin.READ_HERE
    if (color == com.cuentivo.app.domain.FieldHealthColor.GREEN && !inherited) return null
    return FieldChip(label = label, color = color, inherited = inherited)
}

/**
 * Lo que el Ledger de MexaLM dijo de la página, en una tarjeta.
 *
 * Es un RESUMEN, no una decisión: dice si las dos lecturas coinciden y, si no, cuántos renglones
 * discrepan. No bloquea nada —el botón de registrar no consulta esta tarjeta— y la segunda línea
 * lo dice explícitamente, porque una tarjeta que aparece de la nada encima de los renglones se lee
 * como un aviso de que algo está mal y algo está mal: no.
 *
 * Sin bloque no se dibuja esta tarjeta, y la pantalla queda exactamente como estaba.
 *
 * **El caso de "el Ledger no leyó la página"** (`BLOCKED`, `REVIEW_REQUIRED`) se dice UNA vez, con
 * el motivo en lenguaje sencillo, y los renglones NO llevan la insignia de "no lo vio": no le
 * faltaron renglones al Ledger, no pudo certificar la página. Ponerle la insignia a cada renglón
 * hacía creer que había doce diferencias cuando no había ninguna.
 */
@Composable
private fun CrosscheckSummaryCard(
    verdict: MexalmCrosscheckVerdict,
    modifier: Modifier = Modifier,
) {
    // Cuántos RENGLONES discrepan, no cuántas diferencias: un renglón puede diferir en el monto y
    // en el cliente, y "leyó 3 renglones distintos" con dos insignias en el mismo renglón se
    // contradice.
    val disagreeingRows = verdict.disagreeingRowCount
    val message = when (verdict.outcome) {
        MexalmCrosscheckOutcome.AGREED -> stringResource(R.string.ai_ledger_crosscheck_summary_agreed)
        MexalmCrosscheckOutcome.DISAGREEMENTS -> pluralStringResource(
            R.plurals.ai_ledger_crosscheck_summary_disagreements,
            disagreeingRows,
            disagreeingRows,
        )
        // El Ledger no leyó la página. Se dice una vez, y con el motivo.
        MexalmCrosscheckOutcome.COULD_NOT_READ ->
            stringResource(R.string.ai_ledger_crosscheck_summary_could_not_read)
        // `agreed` falso sin ninguna diferencia: el Ledger no dio veredicto. No es lo mismo que
        // "están de acuerdo", y decirlo como si lo estuviera sería mentir.
        MexalmCrosscheckOutcome.NO_VERDICT ->
            stringResource(R.string.ai_ledger_crosscheck_summary_no_verdict)
    }
    // El motivo, en lenguaje sencillo: qué pasó exactamente, sin jerga de servidor.
    val reason = when (verdict.unreadReason) {
        MexalmCrosscheckUnreadReason.BLOCKED ->
            stringResource(R.string.ai_ledger_crosscheck_reason_blocked)
        MexalmCrosscheckUnreadReason.REVIEW_REQUIRED ->
            stringResource(R.string.ai_ledger_crosscheck_reason_review_required)
        null -> null
    }
    // El acento de la app —el mismo borde que llevan las tarjetas del Inicio— para que esta
    // tarjeta no se confunda con las otras tres grises de esta pantalla. El mismo 42 % de alfa
    // que usa `NeoPanel` para un borde de acento.
    val accent = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.testTag("ledger-crosscheck-summary"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // La palabra dice qué pasó; el color sólo la refuerza:
            //
            // - de acuerdo: el color POSITIVO de la app (el mismo verde del dinero cobrado);
            // - hay renglones que el Ledger leyó distinto: el color de aviso de la app;
            // - sin veredicto, o el Ledger no leyó la página: neutro, porque no hay nada que
            //   celebrar ni que lamentar.
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (verdict.outcome) {
                    MexalmCrosscheckOutcome.AGREED -> MaterialTheme.colorScheme.secondary
                    MexalmCrosscheckOutcome.DISAGREEMENTS -> NeoSlow
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Cuántos renglones confirmó el Ledger de verdad. Es lo que explica por qué un
            // renglón que antes pedía revisión ya no la pide: no es que la app haya decidido
            // saltárselo, es que una segunda lectura dijo lo mismo.
            if (verdict.confirmedRowCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.ai_ledger_crosscheck_confirmed_count,
                        verdict.confirmedRowCount,
                        verdict.confirmedRowCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                text = stringResource(R.string.ai_ledger_crosscheck_summary_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Una insignia por DIFERENCIA, no una por renglón.
 *
 * El color no es el de error: esto no bloquea el renglón ni cambia lo que se registra. Es la
 * diferencia entre el Ledger y la lectura de la página, y lo que se registra sigue siendo lo que
 * dijo la lectura.
 *
 * El color es el de la marca, y no el de las otras insignias de la fila —el rojo es "esto está
 * roto" y el verde es "puede ser duplicado"— para que el ojo sepa que esto es otra cosa.
 *
 * La insignia tiene que VERSE: es lo único que dice, en la fila, que el Ledger leyó otra cosa.
 * Por eso va en el acento de la app, con su peso de etiqueta y con una marca de prueba propia.
 */
@Composable
private fun CrosscheckBadge(
    disagreement: com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreement,
    lineIndex: Int,
) {
    val label = when (disagreement.field) {
        com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreementField.AMOUNT ->
            stringResource(R.string.ai_ledger_crosscheck_badge_amount)
        com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreementField.CUSTOMER ->
            stringResource(R.string.ai_ledger_crosscheck_badge_customer)
        com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreementField.TYPE ->
            stringResource(R.string.ai_ledger_crosscheck_badge_type)
        com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreementField.MISSING_IN_LEDGER ->
            stringResource(R.string.ai_ledger_crosscheck_badge_missing_ledger)
        com.cuentivo.app.ai.ledger.MexalmCrosscheckDisagreementField.MISSING_IN_GEMINI ->
            stringResource(R.string.ai_ledger_crosscheck_badge_missing_gemini)
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.testTag("ledger-crosscheck-badge-" + lineIndex),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
