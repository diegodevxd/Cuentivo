package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ui.ai.LedgerReviewContent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The way out of a page whose every row came back doubtful.
 *
 * Once the model was told what a real notebook looks like it started marking almost everything as
 * uncertain, which is correct -- a value it inherited from a quote mark is not a value it read. But
 * an uncertain row is not registerable until a person confirms it, so the screen sat there with the
 * button dead and "register 0 operations" written on it, and the person had no way of knowing that
 * the fix was to tap every row. Diego's words: "lo lee muy bien... solo que no se activa".
 *
 * The guarantee is not relaxed here: each row is still confirmed one at a time by a human. What is
 * tested is that there is a visible way through, and that walking it actually ends with a page that
 * can be registered.
 */
class LedgerGuidedReviewTest {
    @get:Rule val composeRule = createComposeRule()

    private val today = LocalDate.now().toEpochDay()

    private fun doubtfulSale(lineIndex: Int, name: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = "25000",
        date = SafeDateCandidate(2026, 9, 1),
        // What an inherited quote mark produces: read, but not vouched for.
        uncertainFields = listOf(UncertainLedgerField.CUSTOMER),
    )

    /**
     * Hosts the screen over the real edit functions, so the walk is exercised against the code that
     * production runs rather than against a rule rewritten inside the test.
     */
    @Composable
    private fun host(initial: List<LedgerOperation>) {
        var operations by remember { mutableStateOf(initial) }
        val review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today)
        LedgerReviewContent(
            review = review,
            captureFile = null,
            onUpdateAmount = { line, amount -> operations = LedgerReviewEdits.applyAmount(operations, line, amount) },
            onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
            onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
            onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
            onUpdatePageDate = { },
            onRemoveRow = { line -> operations = LedgerReviewEdits.removeRow(operations, line) },
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

    @Test fun a_page_where_every_row_is_doubtful_offers_a_way_through_instead_of_a_dead_button() {
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("ledger-start-review-button").assertIsDisplayed()
    }

    @Test fun walking_the_doubtful_rows_ends_with_a_page_that_can_be_registered() {
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        // First row open.
        composeRule.onNodeWithTag("ledger-edit-save-button").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        // Straight on to the second, without going back to hunt for it.
        composeRule.onNodeWithTag("ledger-edit-save-button").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_half_reviewed_page_cannot_be_registered_and_says_why() {
        // Una página se registra entera o no se registra. Antes, dejar la revisión a medias permitía
        // registrar lo ya confirmado y omitir el resto en silencio; eso es exactamente lo que hacía
        // cambiar el conjunto escribible entre intentos, y ahí es donde un reintento podía escribir
        // un cargo dos veces. Ahora el botón está desactivado mientras falte un renglón.
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()
        composeRule.onNodeWithTag("ledger-edit-cancel-button").performClick()

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
        // El renglón que falta se sigue anunciando, y desde ahí se puede retomar la revisión.
        composeRule.onNodeWithTag("ledger-financial-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-start-review-button").assertIsDisplayed()
    }

    @Test fun the_amount_is_asked_in_the_units_the_notebook_is_written_in() {
        // The trap that let a misread price through: the field used to show raw cents, so a page
        // saying "$999" read as 99.90 arrived as "9990" -- close enough to the paper to wave past.
        // Diego waved it past. It has to say 99.90, so it looks as wrong as it is.
        composeRule.setContent {
            host(
                listOf(
                    doubtfulSale(0, "Rosa").copy(amountCents = "9990"),
                ),
            )
        }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()

        composeRule.onNodeWithTag("ledger-edit-amount-field").assertTextContains("99.90")
    }

    @Test fun a_price_typed_in_pesos_is_saved_as_cents() {
        var savedCents: String? = null
        composeRule.setContent {
            var operations by remember { mutableStateOf(listOf(doubtfulSale(0, "Rosa").copy(amountCents = "9990"))) }
            LedgerReviewContent(
                review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today),
                captureFile = null,
                onUpdateAmount = { line, cents ->
                    savedCents = cents
                    operations = LedgerReviewEdits.applyAmount(operations, line, cents)
                },
                onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
                onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
                onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
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

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        composeRule.onNodeWithTag("ledger-edit-amount-field").performTextReplacement("999")
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        // 999 pesos, not 999 cents. Off by a hundred is the whole bug.
        composeRule.runOnIdle { assertEquals("99900", savedCents) }
    }

    @Test fun a_customer_the_business_already_has_is_offered_but_never_applied_on_its_own() {
        // The whole point of the match, and its limit. "Rosa Martnez" on the paper resembles the
        // real "Rosa Martinez", so the app says so -- and does nothing else. Anchoring decides whose
        // debt this is, and a wrong anchor on a payment settles somebody else's real debt, which in
        // an append-only ledger costs a compensating movement and an awkward conversation.
        val rosa = com.cuentivo.app.domain.CustomerCandidate("cust-rosa", "Rosa Martinez")
        var anchoredTo: String? = null

        composeRule.setContent {
            var operations by remember { mutableStateOf(listOf(doubtfulSale(0, "Rosa Martnez"))) }
            val review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today)
            LedgerReviewContent(
                review = review,
                captureFile = null,
                customerMatches = com.cuentivo.app.domain.LedgerCustomerMatching.matchesFor(
                    operations,
                    listOf(rosa),
                ),
                onAcceptCustomerAnchor = { line, candidate ->
                    anchoredTo = candidate.id
                    operations = LedgerReviewEngine.setOperationCustomerAnchor(
                        currentOperations = operations,
                        lineIndex = line,
                        customerId = candidate.id,
                        customerName = candidate.name,
                    )
                },
                onUpdateAmount = { line, amount -> operations = LedgerReviewEdits.applyAmount(operations, line, amount) },
                onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
                onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
                onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
            isPageCompletenessConfirmed = false,
            onConfirmPageCompleteness = { },
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        // Offered, and nothing anchored until it is tapped.
        composeRule.onNodeWithTag("ledger-accept-customer-match").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, anchoredTo) }

        composeRule.onNodeWithTag("ledger-accept-customer-match").performClick()

        composeRule.runOnIdle { assertEquals("cust-rosa", anchoredTo) }
        // Accepting is a human confirmation, so the row becomes registerable.
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_doubtful_row_says_which_field_needs_looking_at() {
        // One badge for the whole row said something was wrong without saying where, so every
        // doubtful row cost the same to check: read all four fields against the crop. The chips
        // turn that into a glance.
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"))) }

        // Unmerged: the whole row is clickable, so Compose folds its children into one node.
        composeRule.onNodeWithTag("ledger-field-health-0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun a_row_that_is_ready_does_not_shout_for_attention() {
        val clean = doubtfulSale(0, "Rosa").copy(uncertainFields = emptyList())

        composeRule.setContent { host(listOf(clean)) }

        composeRule.onNodeWithTag("ledger-field-health-0", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    /**
     * La cola avanza de renglón sin desmontar el diálogo de edición, así que cada renglón tiene que
     * abrir con SUS campos y guardarlos. Antes, los `remember` sin key del diálogo sobrevivían a esa
     * recomposición: el renglón 2 abría con los datos del 1 y Guardar los escribía ENCIMA del 2. En la
     * prueba del teléfono de la primera tanda, una página Laura $999 / Estefanía $250 / Nosotros $150
     * quedó como 3 × Laura $999. Esta prueba recorre la cola con dos renglones distintos y comprueba
     * que el segundo muestra y guarda lo suyo; sin el arreglo, el segundo abre con los datos del
     * primero y esto falla.
     */
    @Test fun cada_renglon_de_la_cola_abre_con_sus_datos_y_guarda_los_suyos() {
        var currentOperations: List<LedgerOperation> = emptyList()
        val first = doubtfulSale(0, "Laura").copy(
            amountCents = "99900",
            date = SafeDateCandidate(2026, 3, 1),
        )
        val second = doubtfulSale(1, "Estefania").copy(
            amountCents = "25000",
            date = SafeDateCandidate(2026, 8, 14),
        )

        composeRule.setContent {
            var operations by remember { mutableStateOf(listOf(first, second)) }
            currentOperations = operations
            LedgerReviewContent(
                review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today),
                captureFile = null,
                onUpdateAmount = { line, amount -> operations = LedgerReviewEdits.applyAmount(operations, line, amount) },
                onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
                onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
                onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
                onUpdatePageDate = { },
                onRemoveRow = { line -> operations = LedgerReviewEdits.removeRow(operations, line) },
                onConfirmObjection = { },
                isPageCompletenessConfirmed = false,
                onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                productMatches = emptyMap(),
                duplicateMatches = emptyMap(),
                objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()

        // El primer renglón abre con lo suyo.
        composeRule.onNodeWithTag("ledger-edit-customer-field").assertTextContains("Laura")
        composeRule.onNodeWithTag("ledger-edit-amount-field").assertTextContains("999")
        composeRule.onNodeWithTag("ledger-edit-date-field").assertTextContains("2026-03-01")

        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        // El siguiente abre con LO SUYO, no con lo del anterior. Aquí es donde fallaba.
        composeRule.onNodeWithTag("ledger-edit-customer-field").assertTextContains("Estefania")
        composeRule.onNodeWithTag("ledger-edit-amount-field").assertTextContains("250")
        composeRule.onNodeWithTag("ledger-edit-date-field").assertTextContains("2026-08-14")

        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        // Y lo guardado son dos renglones distintos, no dos veces el primero.
        composeRule.runOnIdle {
            assertEquals(listOf("Laura", "Estefania"), currentOperations.map { it.customerName })
            assertEquals(listOf("99900", "25000"), currentOperations.map { it.amountCents })
            assertEquals(listOf(2026 to 3, 2026 to 8), currentOperations.map { it.date?.year to it.date?.month })
        }
    }

    @Test fun accepting_a_catalogue_name_never_touches_the_amount() {
        // The catalogue says 79.90 and the paper says 85. The paper wins, always: the price list is
        // what the business charges in general, the notebook is what it charged this person.
        val catalogue = com.cuentivo.app.domain.ProductCandidate("prod-1", "Protector Tapete", 7990L)
        var amountAfter: String? = null
        var descriptionAfter: String? = null

        composeRule.setContent {
            var operations by remember {
                mutableStateOf(listOf(doubtfulSale(0, "Rosa").copy(amountCents = "8500", description = "Protect tap")))
            }
            amountAfter = operations[0].amountCents
            descriptionAfter = operations[0].description
            LedgerReviewContent(
                review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today),
                captureFile = null,
                productMatches = com.cuentivo.app.domain.LedgerProductMatching.matchesFor(operations, listOf(catalogue)),
                onAcceptProductAnchor = { line, candidate ->
                    operations = LedgerReviewEngine.setOperationDescription(operations, line, candidate.name)
                },
                onUpdateAmount = { line, amount -> operations = LedgerReviewEdits.applyAmount(operations, line, amount) },
                onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
                onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
                onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirmObjection = { },
            isPageCompletenessConfirmed = false,
            onConfirmPageCompleteness = { },
                customerMatches = emptyMap(),
                duplicateMatches = emptyMap(), objections = emptyList(),
                isRegistrationBlockedByLegacySave = false,
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        composeRule.onNodeWithTag("ledger-accept-product-match").performClick()

        composeRule.runOnIdle {
            assertEquals("Protector Tapete", descriptionAfter)
            assertEquals("8500", amountAfter)
        }
    }

    @Test fun only_the_fields_that_need_something_spend_room_saying_so() {
        // Diego photographed a catalogue sheet with sixteen movements and got one card per screen.
        // Every field painted a chip whether it needed one or not, and a long label like
        // "Cliente (del renglon de arriba)" ate the width, wrapped, and pushed the card to the
        // height of the phone. Sixteen rows meant sixteen scrolls before registering anything.
        //
        // Height cannot be asserted on this Compose version, so the cause is asserted instead: only
        // the amount is doubtful here, so only the amount earns a chip.
        val onlyAmountDoubtful = doubtfulSale(0, "Rosa").copy(
            uncertainFields = listOf(com.cuentivo.app.ai.ledger.UncertainLedgerField.AMOUNT),
        )

        composeRule.setContent { host(listOf(onlyAmountDoubtful)) }

        composeRule.onNodeWithTag("ledger-field-health-0", useUnmergedTree = true).assertIsDisplayed()
        // Doubtful, not unreadable: the mark is a question, not an alarm.
        composeRule.onNodeWithText("? Monto", useUnmergedTree = true).assertIsDisplayed()
        // The three that are fine say nothing at all.
        composeRule.onNodeWithText("OK Cliente", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("OK Tipo", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("OK Fecha", useUnmergedTree = true).assertDoesNotExist()
    }
}
