package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Abre la base en iOS.
 *
 * Sin migraciones a propósito: iOS instala la app nueva, así que la base siempre nace en la versión
 * 8 de `CuentivoDatabase`. Las cinco migraciones son de Android, donde sí existe una base vieja en
 * el teléfono de alguien; viven en `app/.../CuentivoMigrations.kt` y no cruzan a común.
 *
 * SQLite va empaquetado (`BundledSQLiteDriver`): iOS no trae un driver utilizable desde Room, y el
 * empaquetado garantiza la misma versión de SQLite en todos los dispositivos, incluidos los que
 * actualizan el sistema.
 */
fun createCuentivoDatabase(): CuentivoDatabase =
    Room.databaseBuilder<CuentivoDatabase>(name = "cuentivo.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
