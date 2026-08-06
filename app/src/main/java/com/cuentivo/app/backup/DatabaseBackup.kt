package com.cuentivo.app.backup

import android.content.Context
import com.cuentivo.app.data.local.CuentivoDatabase
import java.io.File

/**
 * The whole ledger, in and out of one encrypted file.
 *
 * The database file is copied byte for byte instead of exporting each table as JSON. Copying is
 * shorter, but the real reason is that it cannot forget anything: a table added later is included
 * without anybody remembering to add it here, and a backup that silently omits a table is worse
 * than no backup at all.
 *
 * This exists because the ledger lives only on the phone — `allowBackup` is off on purpose, and
 * cloud sync is a paid feature — so losing the phone means losing every customer and balance.
 */
object DatabaseBackup {
    const val DATABASE_NAME = "cuentivo.db"

    /** Suggested to the file picker; the person can rename it. */
    fun suggestedFileName(todayIso: String) = "cuentivo-respaldo-$todayIso.cuentivo"

    /**
     * Reads the database as it stands right now.
     *
     * The checkpoint is not optional: recent writes live in the `-wal` sidecar until SQLite folds
     * them in, so copying the main file without it produces a backup that is quietly missing the
     * last sales somebody registered.
     */
    fun export(context: Context, database: CuentivoDatabase, password: CharArray): ByteArray {
        val schemaVersion = database.openHelper.readableDatabase.version
        database.query("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { it.moveToFirst() }
        return BackupCrypto.encrypt(databaseFile(context).readBytes(), password, schemaVersion)
    }

    /**
     * Replaces this device's ledger with the one in [file].
     *
     * Destructive by nature, so the caller must have confirmed it. The database is closed first and
     * **the process must be restarted afterwards**: anything still holding the old connection would
     * be reading a file that no longer exists.
     */
    fun restore(context: Context, database: CuentivoDatabase, file: ByteArray, password: CharArray) {
        val currentVersion = database.openHelper.readableDatabase.version
        // Decrypted before anything is destroyed: a wrong password must leave the ledger untouched.
        val payload = BackupCrypto.decrypt(file, password, currentVersion)
        database.close()
        val target = databaseFile(context)
        // The sidecars belong to the database being replaced. Left behind, SQLite would replay
        // those pending writes on top of the restored file and corrupt it.
        File("${target.path}-wal").delete()
        File("${target.path}-shm").delete()
        target.writeBytes(payload)
    }

    private fun databaseFile(context: Context): File = context.getDatabasePath(DATABASE_NAME)
}
