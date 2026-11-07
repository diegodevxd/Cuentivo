package com.cuentivo.app.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiExperimentTest {

    @Test
    fun `is disabled by default`() {
        assertFalse(AiExperiment.isEnabled())
    }

    @Test
    fun `is enabled only with an explicit local override`() {
        assertFalse(AiExperiment.isEnabled(localOverride = false))
        assertTrue(AiExperiment.isEnabled(localOverride = true))
    }

    @Test
    fun `default stays disabled across repeated calls`() {
        repeat(3) { assertFalse(AiExperiment.isEnabled()) }
    }
}
