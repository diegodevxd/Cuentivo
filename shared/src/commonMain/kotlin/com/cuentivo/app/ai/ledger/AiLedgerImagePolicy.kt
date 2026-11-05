package com.cuentivo.app.ai.ledger

/**
 * What the callable will actually accept for a photographed page.
 *
 * The screen used to check the generic capture cap, which is larger than what the server takes:
 * a photo between the two limits was copied, encoded and uploaded only to be rejected, and the
 * person saw an unexplained failure after the slowest part of the flow.
 */
object AiLedgerImagePolicy {

    /** Server side cap, in UTF-16 code units of base64. */
    const val MAX_BASE64_LENGTH: Long = 8_000_000

    /** Base64 costs four characters for every three bytes, so the binary cap is three quarters. */
    const val MAX_IMAGE_BYTES: Long = MAX_BASE64_LENGTH / 4 * 3

    fun isWithinCallableCap(bytes: Long): Boolean = bytes in 1..MAX_IMAGE_BYTES

    /** Length of the base64 encoding of [bytes] bytes, padding included. */
    fun base64LengthFor(bytes: Long): Long = (bytes + 2) / 3 * 4
}
