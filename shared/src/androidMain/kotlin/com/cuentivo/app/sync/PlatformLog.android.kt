package com.cuentivo.app.sync

import android.util.Log

internal actual object PlatformLog {
    actual fun warn(tag: String, message: String) {
        Log.w(tag, message)
    }
}
