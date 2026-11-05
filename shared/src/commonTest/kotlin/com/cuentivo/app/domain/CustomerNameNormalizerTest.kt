package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CustomerNameNormalizerTest {
    @Test
    fun `normalizes accents casing and repeated whitespace for migration matching`() {
        assertEquals("maria lopez", CustomerNameNormalizer.key("  María   LÓPEZ  "))
    }
}
