package com.cuentivo.app.platform

interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    /** Las escrituras del bloque se aplican juntas. */
    fun edit(block: KeyValueEditor.() -> Unit)
}

interface KeyValueEditor {
    fun putString(key: String, value: String)
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}
