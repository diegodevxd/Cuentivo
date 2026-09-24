package com.cuentivo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cuentivo.app.R
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.domain.LedgerDeterministicObjections
import com.cuentivo.app.ui.ai.LedgerReviewContent
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The review screen is the last thing a person sees before a page of their paper notebook becomes
 * debt in the app, so what it refuses to do matters more than what it draws.
 */
class LedgerReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun sale(lineIndex: Int, name: String, cents: String, date: com.cuentivo.app.ai.ledger.SafeDateCandidate? = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1)) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        date = date,
    )

    @Test fun a_page_with_readable_rows_can_be_confirmed() {
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-op-row-0").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_discarded_row_blocks_the_page_and_the_screen_says_how_many() {
        // Un renglón descartado por el parser NO está en `operations`. Si la pantalla no lo dice, la
        // página se registra sin él y no queda rastro de que faltaba: el hueco que este bloque cierra.
        val review = LedgerReviewEngine.build(
            operations = listOf(sale(0, "Rosa", "25000")),
            discards = listOf(
                LedgerDiscardDiagnostic(inputIndex = 1, reason = LedgerDiscardReason.entries.first()),
                LedgerDiscardDiagnostic(inputIndex = 2, reason = LedgerDiscardReason.entries.first()),
            ),
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        // El motivo en pantalla, con el conteo real de renglones que no se pudieron leer.
        composeRule.onNodeWithTag("ledger-page-unreadable-rows-notice")
            .assertIsDisplayed()
            .assertTextContains("2", substring = true)
        // Y la página no se registra hasta que alguien lo confirme: el botón está apagado y el de
        // confirmar la hoja está a la vista.
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("ledger-confirm-page-complete-button").assertIsDisplayed()
    }

    /**
     * El hueco del recorte mide 48×48 dp FIJOS, así que no puede llevar texto: a 360 dp —ancho de
     * teléfono normal— la frase "Sin recorte disponible" se partía en dos líneas y se montaba encima
     * del renglón. Esta prueba fija las dos mitades del arreglo: que el hueco no dibuje texto y que no
     * se salga de su renglón. Antes fallaba, porque ahí estaba el `Text`.
     */
    @Test fun at_three_hundred_sixty_dp_the_crop_slot_draws_no_text_and_stays_inside_its_row() {
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            // 360×640 dp: el ancho de un teléfono normal y la forma 9:16 de las capturas. Las dos
            // medidas importan — sin altura acotada, el `weight(1f)` de la lista no compone ni una fila
            // y la prueba no estaría mirando nada.
            Box(Modifier.requiredWidth(360.dp).requiredHeight(640.dp)) {
                LedgerReviewContent(
                    review = review,
                    captureFile = null,
                    onUpdateAmount = { _, _ -> },
                    onUpdateCustomer = { _, _ -> },
                    onUpdateKind = { _, _ -> },
                    onUpdateDate = { _, _ -> },
                    onUpdatePageDate = { },
                    onRemoveRow = { },
                    onConfirmObjection = { },
                    isPageCompletenessConfirmed = false,
                    onConfirmPageCompleteness = { },
                    customerMatches = emptyMap(),
                    productMatches = emptyMap(),
                    duplicateMatches = emptyMap(), objections = emptyList(),
                    isRegistrationBlockedByLegacySave = false,
                    onConfirm = { },
                    onCancel = { },
                )
            }
        }

        // Árbol SIN fusionar: la fila es `clickable`, así que fusiona la semántica de sus hijos y el
        // tag del hueco no existe en el árbol fusionado.
        val slot = composeRule.onAllNodesWithTag("ledger-crop-slot", useUnmergedTree = true).onFirst()
        // El hueco no dibuja NADA dentro cuando no hay recorte. Con el texto de antes, este hijo
        // existía: se partía en dos líneas y se montaba encima del renglón. Se comprueba por los hijos
        // del hueco, que es exactamente donde el defecto metía el texto.
        slot.onChildren().assertCountEquals(0)

        val slotNode = slot.fetchSemanticsNode()
        assertTrue(
            "y la descripcion tiene que seguir ahi para un lector de pantalla",
            !slotNode.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty(),
        )

        val row = composeRule.onNodeWithTag("ledger-op-row-0").getUnclippedBoundsInRoot()
        val slotBounds = slot.getUnclippedBoundsInRoot()
        assertTrue(
            "el hueco no puede salirse de su renglon",
            slotBounds.top >= row.top && slotBounds.bottom <= row.bottom,
        )
    }

    /**
     * Las tres fichas de tipo dentro del diálogo, a 360 dp —el ancho de un teléfono normal—. Antes
     * iban en una fila con `weight(1f)` y "Deuda anterior" no cabía: se partía letra por letra
     * ("Deud/a/anteri/or"). Ahora fluyen y bajan enteras si hace falta; esta prueba fija que las
     * tres siguen alcanzables y con su texto íntegro.
     */
    @Test fun at_360_dp_the_dialog_type_chips_are_all_reachable() {
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp)) {
                LedgerReviewContent(
                    review = review,
                    captureFile = null,
                    onUpdateAmount = { _, _ -> },
                    onUpdateCustomer = { _, _ -> },
                    onUpdateKind = { _, _ -> },
                    onUpdateDate = { _, _ -> },
                    onUpdatePageDate = { },
                    onRemoveRow = { },
                    onConfirmObjection = { },
                    isPageCompletenessConfirmed = false,
                    onConfirmPageCompleteness = { },
                    customerMatches = emptyMap(),
                    productMatches = emptyMap(),
                    duplicateMatches = emptyMap(), objections = emptyList(),
                    isRegistrationBlockedByLegacySave = false,
                    onConfirm = { },
                    onCancel = { },
                )
            }
        }

        composeRule.onNodeWithTag("ledger-op-row-0").performClick()

        composeRule.onNodeWithTag("ledger-kind-SALE", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-kind-SALE").assertTextContains("Venta", substring = true)
        composeRule.onNodeWithTag("ledger-kind-PAYMENT").assertTextContains("Abono", substring = true)
        composeRule.onNodeWithTag("ledger-kind-PRIOR_DEBT").assertTextContains("Deuda anterior", substring = true)
    }

    /**
     * Con un solo renglón dudoso la frase decía "Revisar 1 renglones". Es un `<plurals>`: la cadena
     * depende de la cantidad y hay que leerla con `pluralStringResource` —sobre un plurals,
     * `stringResource` no compila—, así que la prueba resuelve el recurso **con cantidad 1** y compara
     * contra lo que se dibuja. Sin depender del idioma del emulador.
     */
    @Test fun the_review_button_says_one_row_in_the_singular() {
        val doubtful = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Carmen",
            amountCents = "14900",
            description = "Cuaderno",
            rawText = "1012 Cuaderno 149 (Carmen)",
            date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
            sources = com.cuentivo.app.ai.ledger.LedgerFieldSources(
                customer = com.cuentivo.app.ai.ledger.LedgerFieldSource.EXPLICIT,
            ),
        )
        val operations = listOf(doubtful)
        val candidates = listOf(com.cuentivo.app.domain.CustomerCandidate(id = "carmen-id", name = "Carmen"))
        val review = LedgerReviewEngine.build(
            operations = operations,
            pageDateEpochDay = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1)
                .let { java.time.LocalDate.of(it.year, it.month, it.day).toEpochDay() },
            todayEpochDay = java.time.LocalDate.of(2026, 9, 1).toEpochDay(),
        )

        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp).requiredHeight(640.dp)) {
                LedgerReviewContent(
                    review = review,
                    captureFile = null,
                    onUpdateAmount = { _, _ -> },
                    onUpdateCustomer = { _, _ -> },
                    onUpdateKind = { _, _ -> },
                    onUpdateDate = { _, _ -> },
                    onUpdatePageDate = { },
                    onRemoveRow = { },
                    onConfirmObjection = { },
                    isPageCompletenessConfirmed = false,
                    onConfirmPageCompleteness = { },
                    customerMatches = emptyMap(),
                    productMatches = emptyMap(),
                    duplicateMatches = emptyMap(),
                    objections = com.cuentivo.app.domain.LedgerDeterministicObjections
                        .evaluate(operations, candidates, emptyList()),
                    isRegistrationBlockedByLegacySave = false,
                    onConfirm = { },
                    onCancel = { },
                )
            }
        }

        val oneRow = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.getQuantityString(R.plurals.ai_ledger_action_review_uncertain, 1, 1)
        composeRule.onNodeWithTag("ledger-start-review-button").assertTextContains(oneRow)
    }

    @Test fun a_page_without_discards_does_not_ask_for_the_page_confirmation() {
        // La otra mitad: que esto no pida confirmaciones que nadie necesita.
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-page-unreadable-rows-notice").assertDoesNotExist()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_row_with_an_unreadable_amount_blocks_the_only_confirmation() {
        val review = LedgerReviewEngine.build(
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.SALE,
                    customerName = "Rosa",
                    amountCents = null,
                    date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
                    unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
                ),
            ),
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    @Test fun a_payment_without_prior_debt_is_refused_and_says_so() {
        val review = LedgerReviewEngine.build(
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.PAYMENT,
                    customerName = "Juan",
                    amountCents = "300000",
                    date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
                ),
                sale(1, "Rosa", "25000"),
            ),
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-financial-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    @Test fun discarding_the_page_reaches_the_caller() {
        var cancelled = false
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { cancelled = true },
            )
        }

        composeRule.onNodeWithTag("ledger-review-cancel-button").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue("Discarding the page must reach the caller.", cancelled) }
    }

    @Test fun typing_partial_page_date_does_not_clear_or_reset_input() {
        val undatedSale = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
            date = null,
        )
        val validPageDate = java.time.LocalDate.of(2026, 6, 15).toEpochDay()
        val today = java.time.LocalDate.of(2026, 9, 1).toEpochDay()
        val initialReview = LedgerReviewEngine.build(
            operations = listOf(undatedSale),
            pageDateEpochDay = validPageDate,
            todayEpochDay = today,
        )

        composeRule.setContent {
            var currentReview by remember { mutableStateOf(initialReview) }
            LedgerReviewContent(
                review = currentReview,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { newPageDate ->
                    currentReview = LedgerReviewEngine.build(
                        operations = currentReview.operations,
                        discards = currentReview.discards,
                        unreadTextRemaining = currentReview.unreadTextRemaining,
                        pageIncomplete = currentReview.pageIncomplete,
                        pageDateEpochDay = newPageDate,
                        todayEpochDay = currentReview.todayEpochDay,
                    )
                },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        val field = composeRule.onNodeWithTag("ledger-page-date-field")
        field.assertIsDisplayed()
        field.assertTextContains("2026-06-15")

        field.performTextReplacement("2026-06-")

        composeRule.runOnIdle { }

        field.assertTextContains("2026-06-")
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    @Test fun a_detected_page_date_is_offered_but_never_applied_until_tapped() {
        // The server read "2026-07-30" off the sheet. The card must say so and do nothing else:
        // a wrong date sends ten movements to the day that is not theirs, and in an append-only
        // ledger that is undone with ten compensating movements. The tap is the acceptance.
        val undatedSale = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
            date = null,
        )
        val today = java.time.LocalDate.now().toEpochDay()
        val detected = java.time.LocalDate.of(2026, 7, 30)
        var appliedPageDate: Long? = null

        composeRule.setContent {
            var currentReview by remember {
                mutableStateOf(
                    LedgerReviewEngine.build(
                        operations = listOf(undatedSale),
                        todayEpochDay = today,
                        detectedPageDate = com.cuentivo.app.ai.ledger.LedgerDetectedPageDate(
                            com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 7, 30),
                            0.95,
                        ),
                    ),
                )
            }
            LedgerReviewContent(
                review = currentReview,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { newPageDate ->
                    appliedPageDate = newPageDate
                    currentReview = LedgerReviewEngine.build(
                        operations = currentReview.operations,
                        discards = currentReview.discards,
                        unreadTextRemaining = currentReview.unreadTextRemaining,
                        pageIncomplete = currentReview.pageIncomplete,
                        pageDateEpochDay = newPageDate,
                        todayEpochDay = currentReview.todayEpochDay,
                        detectedPageDate = currentReview.detectedPageDate,
                    )
                },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        // Offered, with the date it read, and nothing applied yet.
        val offer = composeRule.onNodeWithTag("ledger-page-date-detected-offer")
        offer.assertIsDisplayed()
        composeRule.runOnIdle { org.junit.Assert.assertNull("offering is not applying", appliedPageDate) }

        // The tap is the acceptance.
        offer.performClick()

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(
                "tapping the offer applies the detected date",
                detected.toEpochDay(),
                appliedPageDate,
            )
        }
        composeRule.onNodeWithTag("ledger-page-date-field").assertTextContains("2026-07-30")
    }

    @Test fun a_detected_page_date_in_the_future_is_not_offered_at_all() {
        // A date that has not happened yet is not a date from a notebook. The offer must not
        // exist, no matter how confident the model claimed to be.
        val undatedSale = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
            date = null,
        )
        val today = java.time.LocalDate.now().toEpochDay()
        val tomorrow = java.time.LocalDate.now().plusDays(1)

        composeRule.setContent {
            val review = remember {
                LedgerReviewEngine.build(
                    operations = listOf(undatedSale),
                    todayEpochDay = today,
                    detectedPageDate = com.cuentivo.app.ai.ledger.LedgerDetectedPageDate(
                        com.cuentivo.app.ai.ledger.SafeDateCandidate(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth),
                        0.99,
                    ),
                )
            }
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-page-date-detected-offer").assertDoesNotExist()
    }

    @Test fun product_proposal_action_button_offers_product_name_without_changing_amount() {
        var acceptedProductCandidateName: String? = null
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "8500",
            description = "Protect tap",
            date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
        )
        val review = LedgerReviewEngine.build(operations = listOf(op))
        val productCandidate = com.cuentivo.app.domain.ProductCandidate(id = "prod-1", name = "Protector Tapete", salePriceCents = 7990L)
        val productMatches = mapOf(
            0 to com.cuentivo.app.domain.EntityMatchResult(
                candidate = productCandidate,
                score = 0.95,
                decision = com.cuentivo.app.domain.MatchDecision.AUTOMATIC,
            )
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                productMatches = productMatches,
                onAcceptProductAnchor = { _, candidate -> acceptedProductCandidateName = candidate.name },
                onConfirm = { },
                onCancel = { },
            )
        }

        // Tap row 0 to open edit dialog
        composeRule.onNodeWithTag("ledger-op-row-0").performClick()

        // Verify product candidate proposal action button is displayed
        val matchButton = composeRule.onNodeWithTag("ledger-accept-product-match")
        matchButton.assertIsDisplayed()

        // Tap product match proposal button
        matchButton.performClick()

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals("Protector Tapete", acceptedProductCandidateName)
        }
    }

    /**
     * El aviso que el encargo exige debajo del selector: con una página vieja y 15 días marcados,
     * la persona tiene que ver cuántas ventas nacerían vencidas antes de pulsar Registrar.
     * El número lo calcula el objeto puro (`LedgerDueDatePolicy`); aquí sólo se comprueba que la
     * pantalla lo dice — y que lo dice con `pluralStringResource`, no con `stringResource` sobre un
     * `plurals`, que ya reventó una pantalla así.
     */
    @Test fun el_aviso_de_ventas_vencidas_aparece_debajo_del_selector_con_15_marcado() {
        val today = java.time.LocalDate.of(2026, 9, 22).toEpochDay()
        // Venta del 1 de septiembre + 15 = 16 de septiembre, ya pasada frente al 22: una venta
        // que nace vencida, que es el caso que no puede pasar sin que la persona lo vea.
        val review = LedgerReviewEngine.build(
            operations = listOf(sale(0, "Rosa", "25000")),
            todayEpochDay = today,
        )

        composeRule.setContent {
            // dueDateDays sin pasarlo: arranca con el default de 15, igual que en producción.
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-due-option-FIFTEEN").assertIsDisplayed()
        // El texto completo, no "1" como subcadena: eso también aceptaría 10, 11 o 12.
        val oneSale = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.getQuantityString(R.plurals.ai_ledger_due_born_overdue, 1, 1)
        composeRule.onNodeWithTag("ledger-due-born-overdue-warning")
            .assertIsDisplayed()
            .assertTextEquals(oneSale)
    }

    /** Sin fecha no hay fecha límite, así que no hay nada que avisar: el aviso no puede aparecer. */
    @Test fun sin_fecha_elegida_no_hay_aviso_de_vencidas() {
        val today = java.time.LocalDate.of(2026, 9, 22).toEpochDay()
        val review = LedgerReviewEngine.build(
            operations = listOf(sale(0, "Rosa", "25000")),
            todayEpochDay = today,
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                dueDateDays = null,
                onRemoveRow = { },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-due-option-NONE").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-due-born-overdue-warning").assertDoesNotExist()
    }

    /**
     * v47: con la hoja real salían **las dos** tarjetas por los mismos doce renglones ("dicen 2023,
     * ¿los cambio a 2026?" y "son de hace más de 6 meses, ¿son correctos?"), y pulsar "Confirmar
     * todos" primero aceptaba las fechas de 2023 — justo lo que la tarjeta de año impide.
     *
     * Un renglón de año distinto pertenece a la tarjeta de año; sólo pasa a la de viejos cuando su año
     * ya es el correcto. Aquí se pulsa el botón real de "Cambiar todos".
     */
    @Test fun las_dos_tarjetas_de_fecha_nunca_salen_juntas() {
        // La hoja real llegó SIN fecha de página aceptada (el campo vacío, la oferta sin pulsar), que
        // es justo el caso en que el motivo no comparaba el año y caía a "viejo".
        val today = LocalDate.of(2026, 9, 22).toEpochDay()
        var operations by mutableStateOf(
            (0 until 12).map { sale(it, "Rosa $it", "25000", SafeDateCandidate(2023, 3, 1)) },
        )

        composeRule.setContent {
            val current = operations
            val review = LedgerReviewEngine.build(current, todayEpochDay = today)
            val objections = LedgerDeterministicObjections.evaluate(
                operations = current,
                existingCustomerCandidates = emptyList(),
                catalogProducts = emptyList(),
                pageDateEpochDay = null,
                todayEpochDay = today,
            )
            Box(Modifier.requiredWidth(360.dp).requiredHeight(800.dp)) {
                LedgerReviewContent(
                    review = review,
                    captureFile = null,
                    onUpdateAmount = { _, _ -> },
                    onUpdateCustomer = { _, _ -> },
                    onUpdateKind = { _, _ -> },
                    onUpdateDate = { lineIndex, date ->
                        operations = LedgerReviewEdits.applyDate(operations, lineIndex, date, today)
                    },
                    onUpdatePageDate = { },
                    onRemoveRow = { },
                    onConfirmObjection = { },
                    isPageCompletenessConfirmed = false,
                    onConfirmPageCompleteness = { },
                    customerMatches = emptyMap(),
                    productMatches = emptyMap(),
                    duplicateMatches = emptyMap(),
                    objections = objections,
                    isRegistrationBlockedByLegacySave = false,
                    onConfirm = { },
                    onCancel = { },
                )
            }
        }

        composeRule.onNodeWithTag("ledger-year-bulk-card", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-old-dates-bulk-card", useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithTag("ledger-year-bulk-button").performClick()

        composeRule.onNodeWithTag("ledger-old-dates-bulk-card", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-year-bulk-card", useUnmergedTree = true).assertDoesNotExist()
    }
}
