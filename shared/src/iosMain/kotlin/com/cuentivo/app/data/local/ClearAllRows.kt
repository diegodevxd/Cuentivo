package com.cuentivo.app.data.local

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.cuentivo.app.auth.LocalSessionGuard
import com.cuentivo.app.platform.NsUserDefaultsKeyValueStore

suspend fun CuentivoDatabase.clearAllRows() {
    useWriterConnection { transactor ->
        val tables = transactor.usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata')",
        ) { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        transactor.immediateTransaction {
            tables.forEach { table -> usePrepared("DELETE FROM `$table`") { it.step() } }
        }
    }
}

fun iosLocalSessionGuard(database: CuentivoDatabase): LocalSessionGuard =
    LocalSessionGuard(
        preferences = NsUserDefaultsKeyValueStore(LocalSessionGuard.PREFERENCES),
        firstBusiness = { database.businessDao().first() },
        clearAllTables = { database.clearAllRows() },
    )
