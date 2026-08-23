package com.cuentivo.app.ai.capture

import java.io.File

/** Seam for on-device text recognition; implementations live in source-set-specific files. */
fun interface AiOcrEngine {
    suspend fun recognize(imageFile: File): String?
}
