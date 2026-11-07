package com.cuentivo.app.sync

import platform.Foundation.NSLog

actual object PlatformLog {
    actual fun warn(tag: String, message: String) {
        NSLog("%@: %@", tag, message)
    }

    actual fun info(tag: String, message: String) {
        NSLog("%@: %@", tag, message)
    }
}
