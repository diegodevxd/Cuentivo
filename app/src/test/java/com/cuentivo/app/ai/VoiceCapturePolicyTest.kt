package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapturePolicyTest {

    @Test
    fun `initial state is Idle with generation zero`() {
        val model = VoiceCaptureModel()
        assertEquals(VoiceCaptureState.Idle, model.state)
        assertEquals(0L, model.generation)
    }

    @Test
    fun `onMicTapped when recognizer unavailable transitions to Unavailable NO_RECOGNIZER`() {
        val initial = VoiceCaptureModel()
        val next = VoiceCaptureMachine.onMicTapped(initial, hasPermission = true, isRecognizerAvailable = false)

        assertEquals(VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_RECOGNIZER), next.state)
        assertEquals(1L, next.generation)
    }

    @Test
    fun `onMicTapped when permission not granted transitions to PermissionRequired`() {
        val initial = VoiceCaptureModel()
        val next = VoiceCaptureMachine.onMicTapped(initial, hasPermission = false, isRecognizerAvailable = true)

        assertEquals(VoiceCaptureState.PermissionRequired, next.state)
        assertEquals(1L, next.generation)
    }

    @Test
    fun `onMicTapped when permission granted transitions to Listening with incremented generation`() {
        val initial = VoiceCaptureModel(generation = 3L)
        val next = VoiceCaptureMachine.onMicTapped(initial, hasPermission = true, isRecognizerAvailable = true)

        assertEquals(VoiceCaptureState.Listening(partialText = ""), next.state)
        assertEquals(4L, next.generation)
    }

    @Test
    fun `onMicTapped while already Listening is single-flight and does not restart or change generation`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(partialText = "hola"), generation = 5L)
        val next = VoiceCaptureMachine.onMicTapped(initial, hasPermission = true, isRecognizerAvailable = true)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Listening(partialText = "hola"), next.state)
        assertEquals(5L, next.generation)
    }

    @Test
    fun `onPermissionGranted from PermissionRequired transitions to Listening`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.PermissionRequired, generation = 2L)
        val next = VoiceCaptureMachine.onPermissionGranted(initial, isRecognizerAvailable = true)

        assertEquals(VoiceCaptureState.Listening(partialText = ""), next.state)
        assertEquals(3L, next.generation)
    }

    @Test
    fun `onPermissionGranted when recognizer unavailable transitions to Unavailable NO_RECOGNIZER`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.PermissionRequired, generation = 2L)
        val next = VoiceCaptureMachine.onPermissionGranted(initial, isRecognizerAvailable = false)

        assertEquals(VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_RECOGNIZER), next.state)
        assertEquals(3L, next.generation)
    }

    @Test
    fun `onPermissionGranted when not in PermissionRequired is no-op`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 1L)
        val next = VoiceCaptureMachine.onPermissionGranted(initial)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(1L, next.generation)
    }

    @Test
    fun `onPermissionDenied from PermissionRequired transitions to Unavailable PERMISSION_DENIED`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.PermissionRequired, generation = 1L)
        val next = VoiceCaptureMachine.onPermissionDenied(initial)

        assertEquals(VoiceCaptureState.Unavailable(VoiceUnavailableReason.PERMISSION_DENIED), next.state)
        assertEquals(2L, next.generation)
    }

    @Test
    fun `onPermissionDenied when not in PermissionRequired is no-op`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 1L)
        val next = VoiceCaptureMachine.onPermissionDenied(initial)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(1L, next.generation)
    }

    @Test
    fun `onPartialResult updates partialText during Listening when generation matches`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 4L)
        val next = VoiceCaptureMachine.onPartialResult(initial, partialText = "Carlos me pagó", generation = 4L)

        assertEquals(VoiceCaptureState.Listening(partialText = "Carlos me pagó"), next.state)
        assertEquals(4L, next.generation)
    }

    @Test
    fun `onPartialResult discards when generation does not match`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(partialText = "previo"), generation = 4L)
        val next = VoiceCaptureMachine.onPartialResult(initial, partialText = "nuevo", generation = 3L)

        assertSame(initial, next)
        assertEquals("previo", (next.state as VoiceCaptureState.Listening).partialText)
    }

    @Test
    fun `onPartialResult discards when state is not Listening`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 4L)
        val next = VoiceCaptureMachine.onPartialResult(initial, partialText = "algo", generation = 4L)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
    }

    @Test
    fun `onFinalResult with valid speech transitions to Transcribed with trimmed text`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 2L)
        val next = VoiceCaptureMachine.onFinalResult(initial, text = "  Carlos me pagó 92 pesos  ", generation = 2L)

        assertEquals(VoiceCaptureState.Transcribed(text = "Carlos me pagó 92 pesos"), next.state)
        assertEquals(2L, next.generation)
    }

    @Test
    fun `onFinalResult with empty string transitions to Unavailable NO_SPEECH, never Transcribed`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 2L)
        val next = VoiceCaptureMachine.onFinalResult(initial, text = "", generation = 2L)

        assertEquals(VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_SPEECH), next.state)
        assertEquals(2L, next.generation)
    }

    @Test
    fun `onFinalResult with only whitespace transitions to Unavailable NO_SPEECH, never Transcribed`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 2L)
        val next = VoiceCaptureMachine.onFinalResult(initial, text = "   \n\t  ", generation = 2L)

        assertEquals(VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_SPEECH), next.state)
        assertEquals(2L, next.generation)
    }

    @Test
    fun `onFinalResult discards when generation does not match`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 5L)
        val next = VoiceCaptureMachine.onFinalResult(initial, text = "texto", generation = 4L)

        assertSame(initial, next)
        assertTrue(next.state is VoiceCaptureState.Listening)
    }

    @Test
    fun `onFinalResult discards when state is not Listening`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 2L)
        val next = VoiceCaptureMachine.onFinalResult(initial, text = "texto", generation = 2L)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
    }

    @Test
    fun `onError transitions to Unavailable with specified reason when generation matches`() {
        for (reason in VoiceUnavailableReason.entries) {
            val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 3L)
            val next = VoiceCaptureMachine.onError(initial, reason = reason, generation = 3L)

            assertEquals(VoiceCaptureState.Unavailable(reason), next.state)
            assertEquals(3L, next.generation)
        }
    }

    @Test
    fun `onError discards when generation does not match`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 3L)
        val next = VoiceCaptureMachine.onError(initial, reason = VoiceUnavailableReason.RECOGNIZER_ERROR, generation = 2L)

        assertSame(initial, next)
        assertTrue(next.state is VoiceCaptureState.Listening)
    }

    @Test
    fun `onError discards when state is not Listening`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 3L)
        val next = VoiceCaptureMachine.onError(initial, reason = VoiceUnavailableReason.RECOGNIZER_ERROR, generation = 3L)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
    }

    @Test
    fun `onCancel while Listening transitions to Idle and increments generation`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(partialText = "parcial"), generation = 10L)
        val next = VoiceCaptureMachine.onCancel(initial)

        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(11L, next.generation)
    }

    @Test
    fun `onCancel discards stale results that arrive afterwards with previous generation`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Listening(), generation = 10L)
        val cancelled = VoiceCaptureMachine.onCancel(initial)

        assertEquals(VoiceCaptureState.Idle, cancelled.state)
        assertEquals(11L, cancelled.generation)

        // Stale partial result
        val afterPartial = VoiceCaptureMachine.onPartialResult(cancelled, partialText = "tarde", generation = 10L)
        assertSame(cancelled, afterPartial)

        // Stale final result
        val afterFinal = VoiceCaptureMachine.onFinalResult(cancelled, text = "tarde", generation = 10L)
        assertSame(cancelled, afterFinal)

        // Stale error
        val afterError = VoiceCaptureMachine.onError(cancelled, reason = VoiceUnavailableReason.RECOGNIZER_ERROR, generation = 10L)
        assertSame(cancelled, afterError)
    }

    @Test
    fun `onCancel from Transcribed transitions to Idle and increments generation`() {
        val initial = VoiceCaptureModel(
            state = VoiceCaptureState.Transcribed("texto consumido"),
            generation = 8L,
        )
        val next = VoiceCaptureMachine.onCancel(initial)

        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(9L, next.generation)
    }

    @Test
    fun `onCancel from Unavailable transitions to Idle and increments generation`() {
        val initial = VoiceCaptureModel(
            state = VoiceCaptureState.Unavailable(VoiceUnavailableReason.RECOGNIZER_ERROR),
            generation = 8L,
        )
        val next = VoiceCaptureMachine.onCancel(initial)

        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(9L, next.generation)
    }

    @Test
    fun `onCancel when already Idle is no-op and preserves generation`() {
        val initial = VoiceCaptureModel(state = VoiceCaptureState.Idle, generation = 7L)
        val next = VoiceCaptureMachine.onCancel(initial)

        assertSame(initial, next)
        assertEquals(VoiceCaptureState.Idle, next.state)
        assertEquals(7L, next.generation)
    }
}
