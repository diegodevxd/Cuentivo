package com.cuentivo.app.platform

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

    override fun edit(block: KeyValueEditor.() -> Unit) {
        val staged = mutableMapOf<String, Any?>()
        object : KeyValueEditor {
            override fun putString(key: String, value: String) {
                staged[key] = value
            }

            override fun putLong(key: String, value: Long) {
                staged[key] = value
            }

            override fun remove(key: String) {
                staged[key] = null
            }
        }.block()
        staged.forEach { (key, value) ->
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
