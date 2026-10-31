package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerNameNormalizerTest {
    @Test
    fun `normalizes accents casing and repeated whitespace for migration matching`() {
        assertEquals("maria lopez", CustomerNameNormalizer.key("  María   LÓPEZ  "))
    }
}
