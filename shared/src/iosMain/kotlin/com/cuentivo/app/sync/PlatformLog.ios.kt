package com.cuentivo.app.sync

import platform.Foundation.NSLog

internal actual object PlatformLog {
    actual fun warn(tag: String, message: String) {
        NSLog("%@: %@", tag, message)
    }
}
