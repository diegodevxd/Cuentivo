package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiProposalSaleLine
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.BusinessQueryType
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.ClarificationReason
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.RequiresClarificationProposal
import com.cuentivo.app.ai.UnresolvedSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `text editing is only possible in the editing state`() {
        val editing = AiDebugLabModel(text = "a")
        val submitted = AiDebugLabMachine.onSubmit(editing) as AiDebugLabSubmitOutcome.Submitted
        val changed = AiDebugLabMachine.onTextChanged(submitted.model, "otro")
        assertEquals("a", changed.text) // ignored while Sending
        assertEquals(AiDebugLabState.Sending, changed.state)

        val editable = AiDebugLabMachine.onTextChanged(editing, "otro")
        assertEquals("otro", editable.text)
    }
}
