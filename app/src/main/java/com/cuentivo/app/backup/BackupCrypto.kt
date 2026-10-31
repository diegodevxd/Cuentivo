package com.cuentivo.app.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a restore was refused, in terms the screen can turn into one sentence. */
enum class BackupFailure {
    /** Not a Cuentivo backup at all, or truncated. */
    NOT_A_BACKUP,

    /** Made by a newer version of the app; this one would not understand the database. */
    NEWER_APP,

    /** Wrong password, or the file was altered after it was written. */
    WRONG_PASSWORD_OR_DAMAGED,
}

class BackupException(val failure: BackupFailure) : Exception(failure.name)

/**
 * Password-encrypted container for a Cuentivo backup.
 *
 * A backup is the whole ledger of a business — names, phone numbers, who owes what. It leaves the
 * app as a file the person can put anywhere, so it is encrypted before it ever touches storage, not
 * after. AES-GCM is used rather than plain AES because it also detects tampering: a file edited by
 * anyone else fails to open instead of restoring a doctored ledger.
 *
 * Everything here is plain `javax.crypto`, so it runs on the JVM and is covered by unit tests.
 */
object BackupCrypto {
    /** Recognises our own files before trying to decrypt anything. */
    private val MAGIC = "CUENTIVO-BACKUP1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /**
     * High enough to make guessing a short password expensive, low enough that a cheap phone still
     * finishes in about a second. Stored in the header so an older file keeps opening if this ever
     * changes.
     */
    private const val ITERATIONS = 120_000

    fun encrypt(payload: ByteArray, password: CharArray, schemaVersion: Int): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(schemaVersion, ITERATIONS, salt, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
            // The header is authenticated but not encrypted: altering the stored schema version to
            // trick an old app into restoring a new database now breaks the tag.
            updateAAD(header)
        }
        return header + cipher.doFinal(payload)
    }

    /**
     * @param currentSchemaVersion the database version this build of the app understands. A backup
     *   from a newer version is refused: Room migrates forward, never back, so restoring it would
     *   leave the app unable to open its own database.
     */
    fun decrypt(file: ByteArray, password: CharArray, currentSchemaVersion: Int): ByteArray {
        val headerSize = MAGIC.size + 4 + 4 + SALT_BYTES + IV_BYTES
        if (file.size <= headerSize || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException(BackupFailure.NOT_A_BACKUP)
        }
        val header = file.copyOfRange(0, headerSize)
        var offset = MAGIC.size
        val schemaVersion = readInt(file, offset); offset += 4
        val iterations = readInt(file, offset); offset += 4
        val salt = file.copyOfRange(offset, offset + SALT_BYTES); offset += SALT_BYTES
        val iv = file.copyOfRange(offset, offset + IV_BYTES)
        if (schemaVersion > currentSchemaVersion) throw BackupException(BackupFailure.NEWER_APP)
        if (iterations !in 1..1_000_000) throw BackupException(BackupFailure.NOT_A_BACKUP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(header)
        }
        return try {
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (error: Exception) {
            // GCM cannot tell a wrong password from an edited file, and neither can the person:
            // both mean "this will not restore".
            throw BackupException(BackupFailure.WRONG_PASSWORD_OR_DAMAGED)
        }
    }

    /** The database version a file was written with, without needing the password. */
    fun schemaVersionOf(file: ByteArray): Int? {
        if (file.size < MAGIC.size + 4 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return readInt(file, MAGIC.size)
    }

    private fun header(schemaVersion: Int, iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        MAGIC + intBytes(schemaVersion) + intBytes(iterations) + salt + iv

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
