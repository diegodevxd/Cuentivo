package com.cuentivo.app.domain

import com.cuentivo.app.domain.AccountDeletionFailure.MessageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure contract of the account-erasure flow: local data, session, entitlement and the Auth
 * account survive every remote failure. Only a fully erased cloud copy lets the phone wipe itself.
 */
class AccountDeletionFailureTest {

    @Test
    fun `failed remote deletion never wipes local`() {
        assertFalse(AccountDeletionFailure.mayWipeLocal(remoteDeletion = false))
    }

    @Test
    fun `successful remote deletion wipes local`() {
        assertTrue(AccountDeletionFailure.mayWipeLocal(remoteDeletion = true))
    }

    @Test
    fun `no business to erase counts as erased`() {
        // Nothing ever reached the cloud; there is no remote copy to protect by staying put.
        assertTrue(AccountDeletionFailure.mayWipeLocal(remoteDeletion = true))
    }

    @Test
    fun `the real Android code for a stale session gets its own message`() {
        // En Android el código es ERROR_REQUIRES_RECENT_LOGIN; `requires-recent-login` es el de la
        // web. Comparar contra el de la web hacía que este aviso NUNCA se mostrara.
        assertEquals(
            MessageKey.RECENT_LOGIN_REQUIRED,
            AccountDeletionFailure.messageKeyForAuth(AccountDeletionFailure.ERROR_REQUIRES_RECENT_LOGIN),
        )
        assertEquals(
            "el código de Android es el que importa",
            "ERROR_REQUIRES_RECENT_LOGIN",
            AccountDeletionFailure.ERROR_REQUIRES_RECENT_LOGIN,
        )
    }

    @Test
    fun `the web spelling still maps, for safety`() {
        assertEquals(
            MessageKey.RECENT_LOGIN_REQUIRED,
            AccountDeletionFailure.messageKeyForAuth("requires-recent-login"),
        )
    }

    @Test
    fun `any other auth failure is a plain try again`() {
        assertEquals(MessageKey.AUTH_DELETION_FAILED, AccountDeletionFailure.messageKeyForAuth(null))
        assertEquals(MessageKey.AUTH_DELETION_FAILED, AccountDeletionFailure.messageKeyForAuth("network-error"))
        assertEquals(MessageKey.AUTH_DELETION_FAILED, AccountDeletionFailure.messageKeyForAuth("user-not-found"))
    }
}
