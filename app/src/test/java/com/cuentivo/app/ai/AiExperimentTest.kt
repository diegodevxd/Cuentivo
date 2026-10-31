package com.cuentivo.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
