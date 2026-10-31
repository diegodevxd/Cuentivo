package com.cuentivo.app.auth

sealed interface CredentialsValidation {
    data object Valid : CredentialsValidation
    data object InvalidEmail : CredentialsValidation
    data object PasswordTooShort : CredentialsValidation
}

object CredentialsValidator {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validate(email: String, password: String): CredentialsValidation = when {
        !emailPattern.matches(email.trim()) -> CredentialsValidation.InvalidEmail
        password.length < 8 -> CredentialsValidation.PasswordTooShort
        else -> CredentialsValidation.Valid
    }
}
