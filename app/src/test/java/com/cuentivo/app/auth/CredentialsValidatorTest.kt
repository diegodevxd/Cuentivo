package com.cuentivo.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialsValidatorTest {
    @Test
    fun `accepts a valid email and password`() {
        assertEquals(CredentialsValidation.Valid, CredentialsValidator.validate("ana@ejemplo.com", "segura123"))
    }

    @Test
    fun `rejects an invalid email`() {
        assertEquals(CredentialsValidation.InvalidEmail, CredentialsValidator.validate("ana", "segura123"))
    }

    @Test
    fun `rejects a short password`() {
        assertEquals(CredentialsValidation.PasswordTooShort, CredentialsValidator.validate("ana@ejemplo.com", "1234567"))
    }
}
