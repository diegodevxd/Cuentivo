package com.cuentivo.app.platform

import android.content.Context

class SharedPreferencesKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)

    override fun edit(block: KeyValueEditor.() -> Unit) {
        val editor = preferences.edit()
        object : KeyValueEditor {
            override fun putString(key: String, value: String) {
                editor.putString(key, value)
            }

            override fun putLong(key: String, value: Long) {
                editor.putLong(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }.block()
        editor.apply()
    }
}
