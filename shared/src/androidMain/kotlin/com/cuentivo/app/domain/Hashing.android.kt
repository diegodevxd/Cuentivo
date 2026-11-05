package com.cuentivo.app.domain

import java.security.MessageDigest

internal actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun md5(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("MD5").digest(bytes)
