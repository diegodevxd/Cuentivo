package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingAccountHasherTest {

    @Test
    fun null_or_blank_user_id_returns_null() {
        assertNull(BillingAccountHasher.obfuscate(null))
        assertNull(BillingAccountHasher.obfuscate(""))
        assertNull(BillingAccountHasher.obfuscate("   "))
    }

    @Test
    fun obfuscated_account_id_is_exactly_64_hex_characters() {
        val hashed = BillingAccountHasher.obfuscate("user_12345_firebase_uid")
        org.junit.Assert.assertNotNull(hashed)
        assertEquals(64, hashed?.length)
        assertTrue(hashed?.all { it in '0'..'9' || it in 'a'..'f' } == true)
    }

    @Test
    fun hashing_is_deterministic_and_stable() {
        val hash1 = BillingAccountHasher.obfuscate("uid_ana_123")
        val hash2 = BillingAccountHasher.obfuscate("uid_ana_123")
        assertEquals(hash1, hash2)
    }

    @Test
    fun different_users_yield_different_hashes() {
        val hashAna = BillingAccountHasher.obfuscate("uid_ana")
        val hashBeto = BillingAccountHasher.obfuscate("uid_beto")
        assertNotEquals(hashAna, hashBeto)
    }
}
