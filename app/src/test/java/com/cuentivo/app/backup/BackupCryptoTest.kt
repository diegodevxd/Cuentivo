package com.cuentivo.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupCryptoTest {
    private val ledger = "una base de datos con nombres, telefonos y saldos".toByteArray()

    @Test fun a_backup_comes_back_exactly_as_it_went_in() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 4)

        assertArrayEquals(ledger, BackupCrypto.decrypt(file, "mi clave".toCharArray(), 4))
    }

    /** The file leaves the phone; the ledger inside it must not be readable by looking at it. */
    @Test fun the_ledger_is_not_sitting_in_the_file_in_the_clear() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 4)

        assertEquals(-1, String(file, Charsets.ISO_8859_1).indexOf("telefonos"))
    }

    @Test fun the_wrong_password_is_refused_rather_than_returning_garbage() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 4)

        assertEquals(
            BackupFailure.WRONG_PASSWORD_OR_DAMAGED,
            failureOf { BackupCrypto.decrypt(file, "otra clave".toCharArray(), 4) },
        )
    }

    /** AES-GCM authenticates: an edited backup fails to open instead of restoring doctored data. */
    @Test fun a_tampered_file_does_not_open() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 4)
        file[file.size - 1] = (file[file.size - 1] + 1).toByte()

        assertEquals(
            BackupFailure.WRONG_PASSWORD_OR_DAMAGED,
            failureOf { BackupCrypto.decrypt(file, "mi clave".toCharArray(), 4) },
        )
    }

    /** The header is authenticated too, so the stored version cannot be edited to slip one through. */
    @Test fun editing_the_stored_version_breaks_the_file() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 3)
        file[16 + 3] = 4

        assertEquals(
            BackupFailure.WRONG_PASSWORD_OR_DAMAGED,
            failureOf { BackupCrypto.decrypt(file, "mi clave".toCharArray(), 4) },
        )
    }

    /**
     * Room migrates forward and never back, so a backup from a newer app would leave this one
     * unable to open its own database. Refused before it can do that.
     */
    @Test fun a_backup_from_a_newer_app_is_refused() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 9)

        assertEquals(
            BackupFailure.NEWER_APP,
            failureOf { BackupCrypto.decrypt(file, "mi clave".toCharArray(), 4) },
        )
    }

    /** An older backup is fine: Room migrates it on open. */
    @Test fun an_older_backup_still_restores() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 3)

        assertArrayEquals(ledger, BackupCrypto.decrypt(file, "mi clave".toCharArray(), 4))
    }

    @Test fun some_other_file_is_recognised_as_not_a_backup() {
        val notOurs = "esto es una foto, no un respaldo".toByteArray()

        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failureOf { BackupCrypto.decrypt(notOurs, "mi clave".toCharArray(), 4) },
        )
        assertNull(BackupCrypto.schemaVersionOf(notOurs))
    }

    @Test fun the_version_can_be_read_without_the_password() {
        val file = BackupCrypto.encrypt(ledger, "mi clave".toCharArray(), schemaVersion = 4)

        assertEquals(4, BackupCrypto.schemaVersionOf(file))
    }

    private fun failureOf(block: () -> Unit): BackupFailure? =
        runCatching(block).exceptionOrNull().let { it as? BackupException }?.failure
}
