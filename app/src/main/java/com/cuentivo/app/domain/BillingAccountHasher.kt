package com.cuentivo.app.domain

import java.security.MessageDigest

/**
 * Derives a stable, deterministic, and irreversible obfuscated account identifier for Google Play Billing.
 *
 * Google Play requires `obfuscatedAccountId` to be at most 64 characters. Using a SHA-256 hex digest
 * of the Firebase UID guarantees:
 * 1. Fixed length of 64 hex characters (within Play's 64-char limit).
 * 2. Irreversibility (the raw UID is never exposed to Play in cleartext, preventing PII leaks).
 * 3. Determinism (the same Firebase user always maps to the same obfuscated account identifier).
 */
object BillingAccountHasher {

    /**
     * Obfuscates the given [userId] into a 64-character SHA-256 hex string.
     *
     * @param userId the raw Firebase UID, or null/blank.
     * @return 64-character lowercase hex string, or null if [userId] is null or blank.
     */
    fun obfuscate(userId: String?): String? {
        if (userId.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(userId.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
