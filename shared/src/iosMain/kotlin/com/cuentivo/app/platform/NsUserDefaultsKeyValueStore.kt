package com.cuentivo.app.platform

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

class NsUserDefaultsKeyValueStore(
    private val name: String,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    private fun scoped(key: String) = "$name.$key"

    override fun getString(key: String): String? = defaults.stringForKey(scoped(key))

    override fun getLong(key: String, default: Long): Long =
        (defaults.objectForKey(scoped(key)) as? NSNumber)?.longLongValue ?: default

    override fun edit(block: KeyValueEditor.() -> Unit) {
        object : KeyValueEditor {
            override fun putString(key: String, value: String) =
                defaults.setObject(value, forKey = scoped(key))
            override fun putLong(key: String, value: Long) =
                defaults.setObject(NSNumber(longLong = value), forKey = scoped(key))
            override fun remove(key: String) = defaults.removeObjectForKey(scoped(key))
        }.block()
    }
}
