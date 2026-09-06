package com.cuentivo.app.ai.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLedgerImagePolicyTest {

    @Test fun `the binary cap always encodes within the server limit`() {
        val encoded = AiLedgerImagePolicy.base64LengthFor(AiLedgerImagePolicy.MAX_IMAGE_BYTES)
        assertTrue(
            "A file at the client cap must still fit the callable: " + encoded,
            encoded <= AiLedgerImagePolicy.MAX_BASE64_LENGTH,
        )
    }

    @Test fun `one byte over the cap is refused before the upload`() {
        assertTrue(AiLedgerImagePolicy.isWithinCallableCap(AiLedgerImagePolicy.MAX_IMAGE_BYTES))
        assertFalse(AiLedgerImagePolicy.isWithinCallableCap(AiLedgerImagePolicy.MAX_IMAGE_BYTES + 1))
    }

    @Test fun `an empty file is not a photo`() {
        assertFalse(AiLedgerImagePolicy.isWithinCallableCap(0))
    }
}
