package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiDailyMessageKind
import com.cuentivo.app.ai.AiPaymentBlockReason
import com.cuentivo.app.ai.AiPaymentCustomerCandidate
import com.cuentivo.app.ai.AiPaymentPreviewResult
import com.cuentivo.app.ai.AiProposalSaleLine
import com.cuentivo.app.ai.AiResolvedPaymentPreview
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.BusinessQueryType
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.ClarificationReason
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.RequiresClarificationProposal
import com.cuentivo.app.ai.UnresolvedSlot
import com.cuentivo.app.data.local.AiDailyStoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDebugLabMachineTest {

    private val validPayment = RecordPaymentProposal("Maria", 15000L)

    @Test
    fun `blank text does not call the gateway`() {
        val model = AiDebugLabModel(text = "   ")
        assertEquals(AiDebugLabSubmitOutcome.IgnoredBlank, AiDebugLabMachine.onSubmit(model))
    }

    @Test
    fun `double tap is single flight`() {
        val editing = AiDebugLabModel(text = "venta")
        val first = AiDebugLabMachine.onSubmit(editing)
        assertTrue(first is AiDebugLabSubmitOutcome.Submitted)
        first as AiDebugLabSubmitOutcome.Submitted
        assertEquals(AiDebugLabState.Sending, first.model.state)

        // A second submit while Sending must be ignored (no second gateway call).
        val second = AiDebugLabMachine.onSubmit(first.model)
        assertEquals(AiDebugLabSubmitOutcome.IgnoredBusy, second)
    }

    @Test
    fun `cancel invalidates a late result by generation token`() {
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val cancelled = AiDebugLabMachine.onCancel(submitted.model)

        assertEquals(AiDebugLabState.TextEditing, cancelled.state)
        assertTrue(cancelled.generation > submitted.generation)

        // A late result from the old generation must not change the state.
        val after = AiDebugLabMachine.onResult(
            cancelled,
            CallableAiProposalResult.Proposal(validPayment),
            submitted.generation,
        )
        assertSame(cancelled, after)
        assertEquals(AiDebugLabState.TextEditing, after.state)
    }

    @Test
    fun `unavailable result maps to the safe unavailable state`() {
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val after = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Unavailable,
            submitted.generation,
        )
        assertEquals(AiDebugLabState.Unavailable, after.state)
    }

    @Test
    fun `valid clarification proposal maps to clarification state`() {
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val proposal = RequiresClarificationProposal(
            ClarificationReason.AMBIGUOUS_CUSTOMER,
            setOf(UnresolvedSlot.CUSTOMER, UnresolvedSlot.AMOUNT),
        )
        val after = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(proposal),
            submitted.generation,
        )
        assertEquals(
            AiDebugLabState.Clarification(ClarificationReason.AMBIGUOUS_CUSTOMER, setOf(UnresolvedSlot.CUSTOMER, UnresolvedSlot.AMOUNT)),
            after.state,
        )
    }

    @Test
    fun `ocr empty preserves existing text`() {
        val editing = AiDebugLabModel(text = "texto previo")
        val after = AiDebugLabMachine.onOcrResult(editing, "", editing.generation)
        assertEquals("texto previo", after.text)
    }

    @Test
    fun `ocr null preserves existing text`() {
        val editing = AiDebugLabModel(text = "texto previo")
        val after = AiDebugLabMachine.onOcrResult(editing, null, editing.generation)
        assertEquals("texto previo", after.text)
    }

    @Test
    fun `ocr success appends recognized text to editor`() {
        val editing = AiDebugLabModel(text = "previo")
        val after = AiDebugLabMachine.onOcrResult(editing, "línea reconocida", editing.generation)
        assertEquals("previo\nlínea reconocida", after.text)
    }

    @Test
    fun `ocr on fresh model sets text directly`() {
        val editing = AiDebugLabModel(text = "")
        val after = AiDebugLabMachine.onOcrResult(editing, "reconocido", editing.generation)
        assertEquals("reconocido", after.text)
    }

    @Test
    fun `ocr stale generation is ignored`() {
        val editing = AiDebugLabModel(text = "original", generation = 5)
        val after = AiDebugLabMachine.onOcrResult(editing, "nuevo texto", 3)
        assertEquals("original", after.text)
    }

    @Test
    fun `ocr success transitions to editing state so the result can be submitted`() {
        val afterResult = AiDebugLabModel(state = AiDebugLabState.Unavailable, text = "")
        val after = AiDebugLabMachine.onOcrResult(afterResult, "reconocido", afterResult.generation)
        assertEquals(AiDebugLabState.TextEditing, after.state)
        assertEquals("reconocido", after.text)
    }

    @Test
    fun `ocr null keeps the result state unchanged`() {
        val afterResult = AiDebugLabModel(state = AiDebugLabState.Clarification(
            ClarificationReason.AMBIGUOUS_CUSTOMER,
            setOf(UnresolvedSlot.CUSTOMER),
        ), text = "")
        val after = AiDebugLabMachine.onOcrResult(afterResult, null, afterResult.generation)
        assertEquals(afterResult.state, after.state)
        assertEquals("", after.text)
    }

    @Test
    fun `valid safe proposal maps to proposal preview`() {
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val after = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(validPayment),
            submitted.generation,
        )
        assertEquals(AiDebugLabState.ProposalPreview(validPayment), after.state)
    }

    @Test
    fun `unsafe proposal maps to unavailable`() {
        // A proposal that passes the transport but fails SafeAiProposalValidator (blank customer).
        val unsafe = CreateSaleProposal(
            customerName = "",
            lines = listOf(AiProposalSaleLine("Perfume", 1, 30000L)),
            declaredInitialPaymentCents = 0L,
        )
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val after = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(unsafe),
            submitted.generation,
        )
        assertEquals(AiDebugLabState.Unavailable, after.state)
    }

    @Test
    fun `result while editing with a matching generation is also ignored`() {
        val editing = AiDebugLabModel(text = "venta")
        val result = AiDebugLabMachine.onResult(
            editing,
            CallableAiProposalResult.Proposal(validPayment),
            editing.generation,
        )
        assertSame(editing, result)
    }

    @Test
    fun `business query preview carries only the intent not a financial result`() {
        val editing = AiDebugLabModel(text = "saldo de María")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val queryProposal = BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, customerName = "María")
        val after = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(queryProposal),
            submitted.generation,
        )
        assertEquals(AiDebugLabState.ProposalPreview(queryProposal), after.state)
    }

    @Test
    fun `reset returns to an empty editing state and invalidates the generation`() {
        val editing = AiDebugLabModel(text = "venta")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val reset = AiDebugLabMachine.onReset(submitted.model)
        assertEquals(AiDebugLabState.TextEditing, reset.state)
        assertEquals("", reset.text)
        assertTrue(reset.generation > submitted.generation)
    }

    @Test
    fun `text editing is ignored while sending`() {
        val editing = AiDebugLabModel(text = "a")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val changed = AiDebugLabMachine.onTextChanged(submitted.model, "otro")
        assertEquals("a", changed.text) // ignored while Sending (single-flight preserved)
        assertEquals(AiDebugLabState.Sending, changed.state)

        val editable = AiDebugLabMachine.onTextChanged(editing, "otro")
        assertEquals("otro", editable.text)
    }

    // ---- Task 1: temporary operational chat transcript ----

    @Test
    fun `after a result the user can send a second message`() {
        val first = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola"))
        assertTrue(first is AiDebugLabSubmitOutcome.Submitted)
        val firstSubmitted = first as AiDebugLabSubmitOutcome.Submitted
        val afterResult = AiDebugLabMachine.onResult(
            firstSubmitted.model,
            CallableAiProposalResult.Unavailable,
            firstSubmitted.generation,
        )
        assertEquals("", afterResult.text) // text cleared to prevent accidental resend

        val edited = AiDebugLabMachine.onTextChanged(afterResult, "siguiente")
        val second = AiDebugLabMachine.onSubmit(edited)
        assertTrue(second is AiDebugLabSubmitOutcome.Submitted)
        val secondSubmitted = second as AiDebugLabSubmitOutcome.Submitted

        assertEquals(
            listOf(
                AiChatMessage.UserText("hola"),
                AiChatMessage.AssistantUnavailable,
                AiChatMessage.UserText("siguiente"),
                AiChatMessage.AssistantPending,
            ),
            secondSubmitted.model.messages,
        )
    }

    @Test
    fun `submitted text becomes one user message and a pending assistant message`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "María abonó 150"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = (outcome as AiDebugLabSubmitOutcome.Submitted).model

        assertEquals(
            listOf(
                AiChatMessage.UserText("María abonó 150"),
                AiChatMessage.AssistantPending,
            ),
            submitted.messages,
        )
    }

    @Test
    fun `unavailable result replaces pending message without creating an executable action`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val result = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Unavailable,
            submitted.generation,
        )

        assertEquals(
            listOf(AiChatMessage.UserText("hola"), AiChatMessage.AssistantUnavailable),
            result.messages,
        )
    }

    @Test
    fun `clarification result replaces pending message with clarification`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "venta"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val proposal = RequiresClarificationProposal(
            ClarificationReason.AMBIGUOUS_CUSTOMER,
            setOf(UnresolvedSlot.CUSTOMER),
        )
        val result = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(proposal),
            submitted.generation,
        )

        assertEquals(
            listOf(
                AiChatMessage.UserText("venta"),
                AiChatMessage.AssistantClarification(
                    ClarificationReason.AMBIGUOUS_CUSTOMER,
                    setOf(UnresolvedSlot.CUSTOMER),
                ),
            ),
            result.messages,
        )
    }

    @Test
    fun `proposal result replaces pending message with proposal`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "venta"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val result = AiDebugLabMachine.onResult(
            submitted.model,
            CallableAiProposalResult.Proposal(validPayment),
            submitted.generation,
        )

        assertEquals(
            listOf(
                AiChatMessage.UserText("venta"),
                AiChatMessage.AssistantProposal(validPayment),
            ),
            result.messages,
        )
    }

    @Test
    fun `stale result does not touch the transcript`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val cancelled = AiDebugLabMachine.onCancel(submitted.model)
        val after = AiDebugLabMachine.onResult(
            cancelled,
            CallableAiProposalResult.Unavailable,
            submitted.generation,
        )

        assertEquals(cancelled.messages, after.messages)
        assertEquals(listOf(AiChatMessage.UserText("hola"), AiChatMessage.AssistantPending), after.messages)
    }

    @Test
    fun `reset clears draft text and transcript`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val reset = AiDebugLabMachine.onReset(submitted.model)

        assertEquals("", reset.text)
        assertEquals(emptyList<AiChatMessage>(), reset.messages)
    }

    @Test
    fun `cancel keeps the transcript and only bumps generation`() {
        val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola"))
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted
        val cancelled = AiDebugLabMachine.onCancel(submitted.model)

        assertEquals(listOf(AiChatMessage.UserText("hola"), AiChatMessage.AssistantPending), cancelled.messages)
        assertTrue(cancelled.generation > submitted.generation)
    }

    @Test
    fun `history hydration maps only persisted deterministic kinds`() {
        val history = listOf(
            AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null),
            AiDailyStoredMessage(AiDailyMessageKind.USER_TEXT, "María abonó 150"),
            AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE, null),
            AiDailyStoredMessage(AiDailyMessageKind.USER_TEXT, "cobro a Juan"),
            AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_UNAVAILABLE, null),
        )
        val initial = AiDebugLabModel(text = "draft", state = AiDebugLabState.Unavailable)
        val loaded = AiDebugLabMachine.onHistoryLoaded(initial, history)

        assertEquals(
            listOf(
                AiChatMessage.AssistantGreeting,
                AiChatMessage.UserText("María abonó 150"),
                AiChatMessage.AssistantOutOfScope,
                AiChatMessage.UserText("cobro a Juan"),
                AiChatMessage.AssistantUnavailable,
            ),
            loaded.messages,
        )
        assertEquals(AiDebugLabState.TextEditing, loaded.state)
        assertEquals("draft", loaded.text)
        assertFalse(loaded.messages.any { it is AiChatMessage.AssistantPending })
        assertFalse(loaded.messages.any { it is AiChatMessage.AssistantProposal })
        assertFalse(loaded.messages.any { it is AiChatMessage.AssistantClarification })
    }

    @Test
    fun `out of scope replaces the draft with user and deterministic assistant messages and clears text`() {
        val model = AiDebugLabModel(
            state = AiDebugLabState.TextEditing,
            text = "  ¿Cómo estás?  ",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val result = AiDebugLabMachine.onOutOfScope(model)

        assertEquals(
            listOf(
                AiChatMessage.AssistantGreeting,
                AiChatMessage.UserText("¿Cómo estás?"),
                AiChatMessage.AssistantOutOfScope,
            ),
            result.messages,
        )
        assertEquals("", result.text)
        assertEquals(AiDebugLabState.TextEditing, result.state)
    }

    @Test
    fun `out of scope while sending or blank is ignored`() {
        val sendingModel = AiDebugLabModel(
            state = AiDebugLabState.Sending,
            text = "¿Cómo estás?",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val afterSending = AiDebugLabMachine.onOutOfScope(sendingModel)
        assertSame(sendingModel, afterSending)

        val blankModel = AiDebugLabModel(
            state = AiDebugLabState.TextEditing,
            text = "   ",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val afterBlank = AiDebugLabMachine.onOutOfScope(blankModel)
        assertSame(blankModel, afterBlank)
    }

    @Test
    fun `out of scope result with current token replaces trailing pending with AssistantOutOfScope and returns to editing with empty text`() {
        val initial = AiDebugLabModel(
            text = "hola",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val outcome = AiDebugLabMachine.onSubmit(initial)
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted

        val result = AiDebugLabMachine.onOutOfScopeResult(submitted.model, submitted.generation)

        assertEquals(
            listOf(
                AiChatMessage.AssistantGreeting,
                AiChatMessage.UserText("hola"),
                AiChatMessage.AssistantOutOfScope,
            ),
            result.messages,
        )
        assertEquals("", result.text)
        assertEquals(AiDebugLabState.TextEditing, result.state)
        assertFalse(result.messages.any { it is AiChatMessage.AssistantPending })
    }

    @Test
    fun `out of scope result with stale token is ignored`() {
        val initial = AiDebugLabModel(text = "hola")
        val outcome = AiDebugLabMachine.onSubmit(initial)
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted

        val result = AiDebugLabMachine.onOutOfScopeResult(submitted.model, submitted.generation - 1)
        assertSame(submitted.model, result)
    }

    @Test
    fun `out of scope result when not sending is ignored`() {
        val model = AiDebugLabModel(
            state = AiDebugLabState.TextEditing,
            text = "hola",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val result = AiDebugLabMachine.onOutOfScopeResult(model, model.generation)
        assertSame(model, result)
    }

    @Test
    fun `out of scope result preserves exactly one UserText without duplicating`() {
        val initial = AiDebugLabModel(
            text = "  cuentame un chiste  ",
            messages = listOf(AiChatMessage.AssistantGreeting),
        )
        val outcome = AiDebugLabMachine.onSubmit(initial)
        assertTrue(outcome is AiDebugLabSubmitOutcome.Submitted)
        val submitted = outcome as AiDebugLabSubmitOutcome.Submitted

        val result = AiDebugLabMachine.onOutOfScopeResult(submitted.model, submitted.generation)
        val userMessages = result.messages.filterIsInstance<AiChatMessage.UserText>()

        assertEquals(1, userMessages.size)
        assertEquals("cuentame un chiste", userMessages.first().text)
    }

    // ---- Task 3: generation-safe payment preview resolution ----

    private fun paymentModel(generation: Long): AiDebugLabModel {
        val proposal = RecordPaymentProposal(customerName = "María", declaredAmountCents = 15_000L)
        return AiDebugLabModel(
            state = AiDebugLabState.ProposalPreview(proposal),
            text = "",
            messages = listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantProposal(proposal),
            ),
            generation = generation,
        )
    }

    private fun readyResult() = AiPaymentPreviewResult.Ready(
        AiResolvedPaymentPreview("k1", "c1", "María", 40_000L, 15_000L, 25_000L),
    )

    @Test
    fun `ready produces exactly PaymentConfirmation state and AssistantPaymentPreview message`() {
        val model = paymentModel(generation = 4)
        val ready = readyResult()
        val updated = AiDebugLabMachine.onPaymentPreviewResult(model, ready, generation = 4)
        assertEquals(AiDebugLabState.PaymentConfirmation(ready.preview), updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentPreview(ready.preview),
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `customer not found produces exactly PaymentResolution CustomerNotFound state and message`() {
        val model = paymentModel(generation = 4)
        val updated = AiDebugLabMachine.onPaymentPreviewResult(
            model,
            AiPaymentPreviewResult.CustomerNotFound,
            generation = 4,
        )
        assertEquals(
            AiDebugLabState.PaymentResolution(AiPaymentPreviewResult.CustomerNotFound),
            updated.state,
        )
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentCustomerNotFound,
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `customer selection required produces exactly PaymentResolution state and AssistantPaymentCustomerSelection message`() {
        val model = paymentModel(generation = 4)
        val candidates = listOf(
            AiPaymentCustomerCandidate("c1", "María López"),
            AiPaymentCustomerCandidate("c2", "María García"),
        )
        val result = AiPaymentPreviewResult.CustomerSelectionRequired(candidates)
        val updated = AiDebugLabMachine.onPaymentPreviewResult(model, result, generation = 4)
        assertEquals(AiDebugLabState.PaymentResolution(result), updated.state)
        val last = updated.messages.last()
        assertTrue(last is AiChatMessage.AssistantPaymentCustomerSelection)
        assertEquals(candidates, (last as AiChatMessage.AssistantPaymentCustomerSelection).candidates)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentCustomerSelection(candidates),
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `blocked no outstanding debt produces exactly PaymentResolution state and AssistantPaymentBlocked message`() {
        val model = paymentModel(generation = 4)
        val blocked = AiPaymentPreviewResult.Blocked(AiPaymentBlockReason.NO_OUTSTANDING_DEBT, null)
        val updated = AiDebugLabMachine.onPaymentPreviewResult(model, blocked, generation = 4)
        assertEquals(AiDebugLabState.PaymentResolution(blocked), updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentBlocked(AiPaymentBlockReason.NO_OUTSTANDING_DEBT, null),
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `blocked amount exceeds debt produces exactly PaymentResolution state and AssistantPaymentBlocked message`() {
        val model = paymentModel(generation = 4)
        val preview = AiResolvedPaymentPreview("", "c1", "María", 10_000L, 15_000L, -5_000L)
        val blocked = AiPaymentPreviewResult.Blocked(AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT, preview)
        val updated = AiDebugLabMachine.onPaymentPreviewResult(model, blocked, generation = 4)
        assertEquals(AiDebugLabState.PaymentResolution(blocked), updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentBlocked(AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT, preview),
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `unavailable produces exactly Unavailable state and AssistantUnavailable message`() {
        val model = paymentModel(generation = 4)
        val updated = AiDebugLabMachine.onPaymentPreviewResult(
            model,
            AiPaymentPreviewResult.Unavailable,
            generation = 4,
        )
        assertEquals(AiDebugLabState.Unavailable, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantUnavailable,
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `assistant payment customer selection runtime immutability defends against list mutation and cast`() {
        val mutableSource = mutableListOf(
            AiPaymentCustomerCandidate("c1", "María López"),
            AiPaymentCustomerCandidate("c2", "María García"),
        )
        val selection = AiChatMessage.AssistantPaymentCustomerSelection(mutableSource)
        mutableSource.add(AiPaymentCustomerCandidate("c3", "María Hernández"))
        assertEquals(2, selection.candidates.size)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (selection.candidates as MutableList<AiPaymentCustomerCandidate>).add(
                AiPaymentCustomerCandidate("c4", "Attack"),
            )
        }
    }

    @Test
    fun `stale payment resolution is ignored`() {
        val model = paymentModel(generation = 5)
        val ready = readyResult()
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, ready, generation = 4)
        assertSame(model, result)
    }

    @Test
    fun `cancel invalidates late payment resolution`() {
        val model = paymentModel(generation = 4)
        val cancelled = AiDebugLabMachine.onCancel(model)
        val result = AiDebugLabMachine.onPaymentPreviewResult(
            cancelled,
            readyResult(),
            generation = 4,
        )
        assertSame(cancelled, result)
    }

    @Test
    fun `reset invalidates late payment resolution`() {
        val model = paymentModel(generation = 4)
        val reset = AiDebugLabMachine.onReset(model)
        val result = AiDebugLabMachine.onPaymentPreviewResult(
            reset,
            readyResult(),
            generation = 4,
        )
        assertSame(reset, result)
    }

    @Test
    fun `no duplicate user text after payment resolution`() {
        val model = paymentModel(generation = 4)
        val ready = readyResult()
        val updated = AiDebugLabMachine.onPaymentPreviewResult(model, ready, generation = 4)
        val userTexts = updated.messages.filterIsInstance<AiChatMessage.UserText>()
        assertEquals(1, userTexts.size)
    }

    @Test
    fun `state not proposal preview returns same instance`() {
        val model = AiDebugLabModel(state = AiDebugLabState.TextEditing, text = "hola", generation = 4)
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4)
        assertSame(model, result)
    }

    @Test
    fun `empty transcript returns same instance`() {
        val model = AiDebugLabModel(
            state = AiDebugLabState.ProposalPreview(RecordPaymentProposal("María", 15_000L)),
            text = "",
            messages = emptyList(),
            generation = 4,
        )
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4)
        assertSame(model, result)
    }

    @Test
    fun `last message not assistant proposal returns same instance`() {
        val model = AiDebugLabModel(
            state = AiDebugLabState.ProposalPreview(RecordPaymentProposal("María", 15_000L)),
            text = "",
            messages = listOf(AiChatMessage.UserText("María pagó 150")),
            generation = 4,
        )
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4)
        assertSame(model, result)
    }

    @Test
    fun `last assistant proposal not payment returns same instance`() {
        val saleProposal = CreateSaleProposal(
            customerName = "María",
            lines = listOf(AiProposalSaleLine("Perfume", 1, 30000L)),
            declaredInitialPaymentCents = 0L,
        )
        val model = AiDebugLabModel(
            state = AiDebugLabState.ProposalPreview(saleProposal),
            text = "",
            messages = listOf(AiChatMessage.AssistantProposal(saleProposal)),
            generation = 4,
        )
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4)
        assertSame(model, result)
    }

    @Test
    fun `state and last message payment proposals must match`() {
        val proposalA = RecordPaymentProposal(customerName = "María", declaredAmountCents = 15_000L)
        val proposalB = RecordPaymentProposal(customerName = "Juan", declaredAmountCents = 20_000L)
        val model = AiDebugLabModel(
            state = AiDebugLabState.ProposalPreview(proposalA),
            text = "",
            messages = listOf(AiChatMessage.AssistantProposal(proposalB)),
            generation = 4,
        )
        val result = AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4)
        assertSame(model, result)
    }

    // ---- Confirmation State Machine Tests ----

    private fun confirmationModel(generation: Long = 4): AiDebugLabModel {
        val preview = AiResolvedPaymentPreview("k1", "c1", "María", 40_000L, 15_000L, 25_000L)
        return AiDebugLabModel(
            state = AiDebugLabState.PaymentConfirmation(preview),
            text = "",
            messages = listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentPreview(preview),
            ),
            generation = generation,
        )
    }

    @Test
    fun `onConfirmPayment transitions to PaymentConfirming and returns Started outcome with exact request`() {
        val model = confirmationModel(generation = 4)
        val outcome = AiDebugLabMachine.onConfirmPayment(model, "business-1")

        assertTrue("Expected Started but got $outcome", outcome is AiPaymentConfirmOutcome.Started)
        val started = outcome as AiPaymentConfirmOutcome.Started
        assertEquals(5L, started.generation)
        assertEquals("k1", started.request.confirmationKey)
        assertEquals("business-1", started.request.businessId)
        assertEquals("c1", started.request.customerId)
        assertEquals(40_000L, started.request.expectedBalanceCents)
        assertEquals(15_000L, started.request.amountCents)

        assertEquals(AiDebugLabState.PaymentConfirming(readyResult().preview), started.model.state)
        assertEquals(5L, started.model.generation)
    }

    @Test
    fun `second onConfirmPayment while confirming is ignored`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started
        val secondOutcome = AiDebugLabMachine.onConfirmPayment(started.model, "business-1")

        assertEquals(AiPaymentConfirmOutcome.Ignored, secondOutcome)
    }

    @Test
    fun `onConfirmPayment from non-PaymentConfirmation state is ignored`() {
        val model = paymentModel(generation = 4)
        val outcome = AiDebugLabMachine.onConfirmPayment(model, "business-1")
        assertEquals(AiPaymentConfirmOutcome.Ignored, outcome)
    }

    @Test
    fun `onConfirmPayment with blank businessId is ignored`() {
        val model = confirmationModel(generation = 4)
        assertEquals(AiPaymentConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmPayment(model, ""))
        assertEquals(AiPaymentConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmPayment(model, "   "))
    }

    @Test
    fun `stale generation result onPaymentExecutionResult is ignored`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started
        val confirmingModel = started.model

        val updated = AiDebugLabMachine.onPaymentExecutionResult(
            confirmingModel,
            com.cuentivo.app.ai.AiPaymentExecutionResult.Recorded("pay-1"),
            generation = 4L, // stale token (current is 5L)
        )
        assertSame(confirmingModel, updated)
    }

    @Test
    fun `onPaymentExecutionResult with Recorded transitions to TextEditing and replaces preview with AssistantPaymentRecorded`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started
        val confirmingModel = started.model

        val updated = AiDebugLabMachine.onPaymentExecutionResult(
            confirmingModel,
            com.cuentivo.app.ai.AiPaymentExecutionResult.Recorded("pay-1"),
            generation = 5L,
        )

        assertEquals(AiDebugLabState.TextEditing, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentRecorded("pay-1"),
            ),
            updated.messages,
        )
        assertEquals("", updated.text)
    }

    @Test
    fun `onPaymentExecutionResult with StalePreview transitions to TextEditing and appends AssistantPaymentStale`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started

        val updated = AiDebugLabMachine.onPaymentExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiPaymentExecutionResult.StalePreview,
            generation = 5L,
        )

        assertEquals(AiDebugLabState.TextEditing, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentStale,
            ),
            updated.messages,
        )
    }

    @Test
    fun `onPaymentExecutionResult with Rejected transitions to TextEditing and appends AssistantPaymentRejected`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started

        val updated = AiDebugLabMachine.onPaymentExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiPaymentExecutionResult.Rejected,
            generation = 5L,
        )

        assertEquals(AiDebugLabState.TextEditing, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantPaymentRejected,
            ),
            updated.messages,
        )
    }

    @Test
    fun `onPaymentExecutionResult with Unavailable transitions to Unavailable and appends AssistantUnavailable`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started

        val updated = AiDebugLabMachine.onPaymentExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiPaymentExecutionResult.Unavailable,
            generation = 5L,
        )

        assertEquals(AiDebugLabState.Unavailable, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("María pagó 150"),
                AiChatMessage.AssistantUnavailable,
            ),
            updated.messages,
        )
    }

    @Test
    fun `onCancel during PaymentConfirming increments generation and returns to TextEditing`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started
        val cancelled = AiDebugLabMachine.onCancel(started.model)

        assertEquals(AiDebugLabState.TextEditing, cancelled.state)
        assertEquals(6L, cancelled.generation)
    }

    @Test
    fun `text editing is ignored while in PaymentConfirming state`() {
        val model = confirmationModel(generation = 4)
        val started = AiDebugLabMachine.onConfirmPayment(model, "business-1") as AiPaymentConfirmOutcome.Started
        val edited = AiDebugLabMachine.onTextChanged(started.model, "new typing")

        assertSame(started.model, edited)
    }

    private val validSaleProposal = CreateSaleProposal(
        customerName = "María Gómez",
        lines = listOf(AiProposalSaleLine("Jabón", 2, 5000L)),
        declaredInitialPaymentCents = 3000L,
        promisedPaymentCandidate = com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate(2026, 8, 30),
    )

    private val sampleSalePreview = com.cuentivo.app.ai.AiResolvedSalePreview(
        confirmationKey = "sale-key-1",
        customerId = "cust-1",
        customerName = "María Gómez",
        rawLines = listOf(
            com.cuentivo.app.ai.AiResolvedSaleLine(
                productId = "prod-1",
                productName = "Jabón",
                quantity = 2,
                unitPriceCents = 5000L,
                lineTotalCents = 10000L,
                sourceDeclaredPriceCents = 5000L,
                sourceFingerprint = "prod-1:10000:5000",
            ),
        ),
        totalCents = 10000L,
        initialPaymentCents = 3000L,
        remainderCents = 7000L,
        promisedPaymentCandidate = com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate(2026, 8, 30),
        sourceCustomerFingerprint = "cust-1:10000",
    )

    private fun saleProposalModel(generation: Long = 2L): AiDebugLabModel = AiDebugLabModel(
        state = AiDebugLabState.ProposalPreview(validSaleProposal),
        generation = generation,
        messages = listOf(
            AiChatMessage.UserText("Venta a María"),
            AiChatMessage.AssistantProposal(validSaleProposal),
        ),
    )

    private fun saleConfirmationModel(generation: Long = 4L): AiDebugLabModel = AiDebugLabModel(
        state = AiDebugLabState.SaleConfirmation(sampleSalePreview),
        generation = generation,
        messages = listOf(
            AiChatMessage.UserText("Venta a María"),
            AiChatMessage.AssistantSalePreview(sampleSalePreview),
        ),
    )

    @Test
    fun `onSalePreviewResult transitions ProposalPreview to SaleConfirmation on Ready preview`() {
        val model = saleProposalModel(generation = 3L)
        val result = com.cuentivo.app.ai.AiSalePreviewResult.Ready(sampleSalePreview)

        val updated = AiDebugLabMachine.onSalePreviewResult(model, result, generation = 3L)

        assertEquals(AiDebugLabState.SaleConfirmation(sampleSalePreview, validSaleProposal), updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSalePreview(sampleSalePreview),
            ),
            updated.messages,
        )
    }

    @Test
    fun `onSalePreviewResult ignores stale generation`() {
        val model = saleProposalModel(generation = 3L)
        val result = com.cuentivo.app.ai.AiSalePreviewResult.Ready(sampleSalePreview)

        val updated = AiDebugLabMachine.onSalePreviewResult(model, result, generation = 2L)
        assertSame(model, updated)
    }

    @Test
    fun `onSalePreviewResult maps all unresolved and blocked variants`() {
        val model = saleProposalModel(generation = 3L)

        // Customer selection
        val custCandidate = com.cuentivo.app.ai.AiSaleCustomerCandidate("c1", "María G.")
        val custSelection = AiDebugLabMachine.onSalePreviewResult(
            model,
            com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired(listOf(custCandidate)),
            generation = 3L,
        )
        assertTrue(custSelection.state is AiDebugLabState.SaleResolution)
        assertTrue(custSelection.messages.last() is AiChatMessage.AssistantSaleCustomerSelection)

        // Product selection
        val prodCandidate = com.cuentivo.app.ai.AiSaleProductCandidate("p1", "Jabón Dove", 5000L)
        val prodSelection = AiDebugLabMachine.onSalePreviewResult(
            model,
            com.cuentivo.app.ai.AiSalePreviewResult.ProductSelectionRequired("Jabón", listOf(prodCandidate)),
            generation = 3L,
        )
        assertTrue(prodSelection.state is AiDebugLabState.SaleResolution)
        assertTrue(prodSelection.messages.last() is AiChatMessage.AssistantSaleProductSelection)

        // Blocked
        val blocked = AiDebugLabMachine.onSalePreviewResult(
            model,
            com.cuentivo.app.ai.AiSalePreviewResult.Blocked(com.cuentivo.app.ai.AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL, sampleSalePreview),
            generation = 3L,
        )
        assertTrue(blocked.state is AiDebugLabState.SaleResolution)
        assertEquals(
            listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleBlocked(com.cuentivo.app.ai.AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL, sampleSalePreview),
            ),
            blocked.messages,
        )

        // Unavailable
        val unavailable = AiDebugLabMachine.onSalePreviewResult(
            model,
            com.cuentivo.app.ai.AiSalePreviewResult.Unavailable,
            generation = 3L,
        )
        assertEquals(AiDebugLabState.Unavailable, unavailable.state)
        assertEquals(listOf(AiChatMessage.UserText("Venta a María"), AiChatMessage.AssistantUnavailable), unavailable.messages)
    }

    @Test
    fun `onConfirmSale with valid state and business ID starts confirming with incremented generation and builds AiSaleConfirmationRequest`() {
        val model = saleConfirmationModel(generation = 4L)

        val outcome = AiDebugLabMachine.onConfirmSale(model, "biz-123")
        assertTrue("outcome must be Started", outcome is AiSaleConfirmOutcome.Started)
        val started = outcome as AiSaleConfirmOutcome.Started

        assertEquals(5L, started.generation)
        assertEquals(AiDebugLabState.SaleConfirming(sampleSalePreview), started.model.state)
        assertEquals("sale-key-1", started.request.confirmationKey)
        assertEquals("biz-123", started.request.businessId)
        assertEquals("cust-1", started.request.customerId)
        assertEquals("cust-1:10000", started.request.sourceCustomerFingerprint)
        assertEquals(1, started.request.lines.size)
        assertEquals("prod-1", started.request.lines[0].productId)
        assertEquals("Jabón", started.request.lines[0].productNameSnapshot)
        assertEquals(2, started.request.lines[0].quantity)
        assertEquals(5000L, started.request.lines[0].unitPriceCents)
        assertEquals(10000L, started.request.lines[0].lineTotalCents)
        assertEquals("prod-1:10000:5000", started.request.lines[0].sourceProductFingerprint)
        assertEquals(10000L, started.request.totalCents)
        assertEquals(3000L, started.request.initialPaymentCents)
        assertEquals(java.time.LocalDate.of(2026, 8, 30).toEpochDay(), started.request.promisedPaymentEpochDay)
    }

    @Test
    fun `onConfirmSale ignores blank businessId, wrong state, or mismatched preview`() {
        val model = saleConfirmationModel(generation = 4L)

        // Blank businessId
        assertEquals(AiSaleConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmSale(model, ""))
        assertEquals(AiSaleConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmSale(model, "   "))

        // Wrong state (TextEditing)
        assertEquals(AiSaleConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmSale(AiDebugLabModel(), "biz-123"))

        // Mismatched preview in messages
        val mismatched = model.copy(messages = listOf(AiChatMessage.UserText("hello")))
        assertEquals(AiSaleConfirmOutcome.Ignored, AiDebugLabMachine.onConfirmSale(mismatched, "biz-123"))
    }

    @Test
    fun `onConfirmSale fails closed with Ignored when promised DateCandidate is invalid`() {
        val invalidDatePreview = com.cuentivo.app.ai.AiResolvedSalePreview(
            confirmationKey = "sale-key-1",
            customerId = "cust-1",
            customerName = "María Gómez",
            rawLines = sampleSalePreview.lines,
            totalCents = 10000L,
            initialPaymentCents = 3000L,
            remainderCents = 7000L,
            promisedPaymentCandidate = com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate(2026, 2, 30),
            sourceCustomerFingerprint = "cust-1:10000",
        )
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleConfirmation(invalidDatePreview),
            generation = 4L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSalePreview(invalidDatePreview),
            ),
        )

        val outcome = AiDebugLabMachine.onConfirmSale(model, "biz-123")
        assertEquals(AiSaleConfirmOutcome.Ignored, outcome)
    }

    @Test
    fun `onSaleExecutionResult with Recorded transitions to TextEditing and appends AssistantSaleRecorded`() {
        val model = saleConfirmationModel(generation = 4L)
        val started = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started

        val updated = AiDebugLabMachine.onSaleExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiSaleExecutionResult.Recorded("sale-99", "pay-99"),
            generation = 5L,
        )

        assertEquals(AiDebugLabState.TextEditing, updated.state)
        assertEquals(
            listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleRecorded("sale-99", "pay-99"),
            ),
            updated.messages,
        )
    }

    @Test
    fun `onSaleExecutionResult with StalePreview, Rejected, and Unavailable`() {
        val model = saleConfirmationModel(generation = 4L)
        val started = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started

        // StalePreview
        val stale = AiDebugLabMachine.onSaleExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiSaleExecutionResult.StalePreview,
            generation = 5L,
        )
        assertEquals(AiDebugLabState.TextEditing, stale.state)
        assertEquals(listOf(AiChatMessage.UserText("Venta a María"), AiChatMessage.AssistantSaleStale), stale.messages)

        // Rejected
        val rejected = AiDebugLabMachine.onSaleExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiSaleExecutionResult.Rejected,
            generation = 5L,
        )
        assertEquals(AiDebugLabState.TextEditing, rejected.state)
        assertEquals(listOf(AiChatMessage.UserText("Venta a María"), AiChatMessage.AssistantSaleRejected), rejected.messages)

        // Unavailable
        val unavailable = AiDebugLabMachine.onSaleExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiSaleExecutionResult.Unavailable,
            generation = 5L,
        )
        assertEquals(AiDebugLabState.Unavailable, unavailable.state)
        assertEquals(listOf(AiChatMessage.UserText("Venta a María"), AiChatMessage.AssistantUnavailable), unavailable.messages)
    }

    @Test
    fun `onSaleExecutionResult ignores stale generation`() {
        val model = saleConfirmationModel(generation = 4L)
        val started = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started

        val afterStale = AiDebugLabMachine.onSaleExecutionResult(
            started.model,
            com.cuentivo.app.ai.AiSaleExecutionResult.Recorded("s", null),
            generation = 4L, // stale token
        )
        assertSame(started.model, afterStale)
    }

    @Test
    fun `onCancel during SaleConfirming increments generation and returns to TextEditing`() {
        val model = saleConfirmationModel(generation = 4L)
        val started = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started
        val cancelled = AiDebugLabMachine.onCancel(started.model)

        assertEquals(AiDebugLabState.TextEditing, cancelled.state)
        assertEquals(6L, cancelled.generation)
    }

    @Test
    fun `text editing is ignored while in SaleConfirming state`() {
        val model = saleConfirmationModel(generation = 4L)
        val started = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started
        val edited = AiDebugLabMachine.onTextChanged(started.model, "new typing")

        assertSame(started.model, edited)
    }

    @Test
    fun `onChooseCustomer with UseExisting produces Selected outcome with UseExisting choice and incremented generation`() {
        val candidate = com.cuentivo.app.ai.AiSaleCustomerCandidate("c1", "María G.")
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired(listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleCustomerSelection(listOf(candidate)),
            ),
        )

        val outcome = AiDebugLabMachine.onChooseCustomer(model, com.cuentivo.app.ai.AiEntityChoice.UseExisting("c1"))
        assertTrue("outcome must be Selected", outcome is AiSaleChoiceOutcome.Selected)
        val selected = outcome as AiSaleChoiceOutcome.Selected

        assertEquals(4L, selected.generation)
        assertEquals(com.cuentivo.app.ai.AiEntityChoice.UseExisting("c1"), selected.choices.customer)
        assertEquals(AiDebugLabState.ProposalPreview(validSaleProposal, selected.choices), selected.model.state)
    }

    @Test
    fun `onChooseCustomer with CreateNew produces Selected outcome with CreateNew choice and incremented generation`() {
        val candidate = com.cuentivo.app.ai.AiSaleCustomerCandidate("c1", "María G.")
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired(listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleCustomerSelection(listOf(candidate)),
            ),
        )

        val outcome = AiDebugLabMachine.onChooseCustomer(model, com.cuentivo.app.ai.AiEntityChoice.CreateNew)
        assertTrue("outcome must be Selected", outcome is AiSaleChoiceOutcome.Selected)
        val selected = outcome as AiSaleChoiceOutcome.Selected

        assertEquals(4L, selected.generation)
        assertEquals(com.cuentivo.app.ai.AiEntityChoice.CreateNew, selected.choices.customer)
    }

    @Test
    fun `onChooseCustomer ignores invalid candidate or wrong state`() {
        val candidate = com.cuentivo.app.ai.AiSaleCustomerCandidate("c1", "María G.")
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired(listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleCustomerSelection(listOf(candidate)),
            ),
        )

        // Non-existent candidate ID
        assertEquals(
            AiSaleChoiceOutcome.Ignored,
            AiDebugLabMachine.onChooseCustomer(model, com.cuentivo.app.ai.AiEntityChoice.UseExisting("non-existent")),
        )

        // Wrong state
        assertEquals(
            AiSaleChoiceOutcome.Ignored,
            AiDebugLabMachine.onChooseCustomer(AiDebugLabModel(), com.cuentivo.app.ai.AiEntityChoice.CreateNew),
        )
    }

    @Test
    fun `onChooseProduct with UseExisting and CreateNew produces Selected outcome with updated products map`() {
        val candidate = com.cuentivo.app.ai.AiSaleProductCandidate("p1", "Jabón Dove", 5000L)
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.ProductSelectionRequired("Jabón", listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleProductSelection("Jabón", listOf(candidate)),
            ),
        )

        // Choose UseExisting
        val outcomeExisting = AiDebugLabMachine.onChooseProduct(
            model,
            "Jabón",
            com.cuentivo.app.ai.AiEntityChoice.UseExisting("p1"),
        )
        assertTrue(outcomeExisting is AiSaleChoiceOutcome.Selected)
        val selectedExisting = outcomeExisting as AiSaleChoiceOutcome.Selected
        assertEquals(com.cuentivo.app.ai.AiEntityChoice.UseExisting("p1"), selectedExisting.choices.productsByNormalizedName["jabon"])

        // Choose CreateNew
        val outcomeNew = AiDebugLabMachine.onChooseProduct(
            model,
            "Jabón",
            com.cuentivo.app.ai.AiEntityChoice.CreateNew,
        )
        assertTrue(outcomeNew is AiSaleChoiceOutcome.Selected)
        val selectedNew = outcomeNew as AiSaleChoiceOutcome.Selected
        assertEquals(com.cuentivo.app.ai.AiEntityChoice.CreateNew, selectedNew.choices.productsByNormalizedName["jabon"])
    }

    @Test
    fun `onChooseProduct ignores invalid candidate, wrong product name, or wrong state`() {
        val candidate = com.cuentivo.app.ai.AiSaleProductCandidate("p1", "Jabón Dove", 5000L)
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.ProductSelectionRequired("Jabón", listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleProductSelection("Jabón", listOf(candidate)),
            ),
        )

        // Invalid candidate
        assertEquals(
            AiSaleChoiceOutcome.Ignored,
            AiDebugLabMachine.onChooseProduct(model, "Jabón", com.cuentivo.app.ai.AiEntityChoice.UseExisting("p99")),
        )

        // Mismatched product name
        assertEquals(
            AiSaleChoiceOutcome.Ignored,
            AiDebugLabMachine.onChooseProduct(model, "Shampoo", com.cuentivo.app.ai.AiEntityChoice.CreateNew),
        )
    }

    @Test
    fun `onCancel during SaleResolution returns to TextEditing with incremented generation`() {
        val candidate = com.cuentivo.app.ai.AiSaleCustomerCandidate("c1", "María G.")
        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleResolution(
                result = com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired(listOf(candidate)),
                proposal = validSaleProposal,
                choices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
            ),
            generation = 3L,
            messages = listOf(
                AiChatMessage.UserText("Venta a María"),
                AiChatMessage.AssistantSaleCustomerSelection(listOf(candidate)),
            ),
        )

        val cancelled = AiDebugLabMachine.onCancel(model)
        assertEquals(AiDebugLabState.TextEditing, cancelled.state)
        assertEquals(4L, cancelled.generation)
    }

    @Test
    fun `onConfirmSale with composite new entities maps newCustomer and newProducts specs`() {
        val compositePreview = com.cuentivo.app.ai.AiResolvedSalePreview(
            confirmationKey = "comp-key-ui-1",
            customer = com.cuentivo.app.ai.AiResolvedSaleCustomer(
                customerId = "cust-new-ui",
                displayName = "Don Alberto",
                normalizedName = "don alberto",
                mode = com.cuentivo.app.ai.AiEntityResolutionMode.NEW,
                sourceFingerprint = null,
                explicitCreateNew = true,
            ),
            rawLines = listOf(
                com.cuentivo.app.ai.AiResolvedSaleLine(
                    productId = "prod-new-ui",
                    productName = "Aceite de Oliva",
                    quantity = 2,
                    unitPriceCents = 15000L,
                    lineTotalCents = 30000L,
                    sourceDeclaredPriceCents = 15000L,
                    sourceFingerprint = null,
                    resolutionMode = com.cuentivo.app.ai.AiEntityResolutionMode.NEW,
                    explicitCreateNew = false,
                ),
            ),
            totalCents = 30000L,
            initialPaymentCents = 0L,
            remainderCents = 30000L,
        )

        val model = AiDebugLabModel(
            state = AiDebugLabState.SaleConfirmation(compositePreview),
            generation = 4L,
            messages = listOf(
                AiChatMessage.UserText("Venta a Don Alberto"),
                AiChatMessage.AssistantSalePreview(compositePreview),
            ),
        )

        val outcome = AiDebugLabMachine.onConfirmSale(model, "biz-123") as AiSaleConfirmOutcome.Started
        assertEquals(compositePreview.newCustomer, outcome.request.newCustomer)
        assertEquals(compositePreview.newProducts, outcome.request.newProducts)
        assertEquals("cust-new-ui", outcome.request.newCustomer?.customerId)
        assertEquals(true, outcome.request.newCustomer?.explicitCreateNew)
        assertEquals(1, outcome.request.newProducts.size)
        assertEquals("prod-new-ui", outcome.request.newProducts[0].productId)
        assertEquals(15000L, outcome.request.newProducts[0].salePriceCents)
        assertEquals(false, outcome.request.newProducts[0].explicitCreateNew)
    }

    @Test
    fun `double confirm tap on confirmation produces only one confirmation`() {
        val model = saleConfirmationModel(generation = 4L)

        val firstOutcome = AiDebugLabMachine.onConfirmSale(model, "biz-123")
        assertTrue(firstOutcome is AiSaleConfirmOutcome.Started)
        val startedModel = (firstOutcome as AiSaleConfirmOutcome.Started).model

        val secondOutcome = AiDebugLabMachine.onConfirmSale(startedModel, "biz-123")
        assertEquals(AiSaleConfirmOutcome.Ignored, secondOutcome)
    }

    @Test
    fun `onReset cleans up confirmation and choice state`() {
        val model = saleConfirmationModel(generation = 4L)
        val resetModel = AiDebugLabMachine.onReset(model)

        assertEquals(AiDebugLabState.TextEditing, resetModel.state)
        assertEquals(5L, resetModel.generation)
        assertEquals(emptyList<AiChatMessage>(), resetModel.messages)
        assertEquals("", resetModel.text)
    }
}
