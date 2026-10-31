package com.cuentivo.app.ai.capture

/**
 * Pure, fail-closed policy for AI capture files (notebook/ticket images).
 *
 * MIME allow-list, byte cap, dimension cap and deterministic file-name generation from a UUID.
 * No user input ever reaches a file name; the extension is derived only from the allow-listed
 * MIME type. No Firebase, no network, no Room, no logging.
 */
object AiCapturePolicy {

    val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

    /** 8 MB hard cap for any single capture file. */
    const val MAX_BYTES: Long = 8L * 1024 * 1024

    /** Maximum width or height in pixels. */
    const val MAX_DIMENSION: Int = 4096

    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val MIME_TO_EXTENSION = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
    )

    fun isAllowedMime(mime: String?): Boolean = mime != null && mime in ALLOWED_MIME_TYPES

    fun isWithinByteCap(bytes: Long): Boolean = bytes in 1..MAX_BYTES

    fun isWithinDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION

    /**
     * Deterministic file name from a valid UUID and an allow-listed MIME type. Returns null for
     * any input that is not exactly a lowercase UUID or not in the MIME map — this is the path
     * traversal defense: no user-controlled string ever reaches a file name.
     */
    fun fileNameFor(uuid: String, mime: String): String? {
        if (!UUID_REGEX.matches(uuid)) return null
        val ext = MIME_TO_EXTENSION[mime] ?: return null
        return "capture-$uuid.$ext"
    }
}
