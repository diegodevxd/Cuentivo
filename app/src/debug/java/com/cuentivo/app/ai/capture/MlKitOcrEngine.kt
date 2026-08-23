package com.cuentivo.app.ai.capture

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On-device ML Kit text recognition; no network, no Firebase, no upload. */
class MlKitOcrEngine(private val context: Context) : AiOcrEngine {

    override suspend fun recognize(imageFile: File): String? = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
            val result = Tasks.await(
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image),
            )
            result.text.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null // fail closed: empty means "no text", preserving existing editor content
        }
    }
}
