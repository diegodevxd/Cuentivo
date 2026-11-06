package com.cuentivo.app.ui.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.cuentivo.app.domain.StatementImageContent
import com.cuentivo.app.platform.Sharer
import com.cuentivo.app.platform.StatementShareFlow

/**
 * [Sharer] de Android: la misma hoja de compartir y el mismo marcador de siempre.
 *
 * Necesita la `Activity` y el `requestCode` porque la imagen temporal se borra cuando la hoja
 * regresa; eso es un detalle de Android y por eso no vive en el contrato común.
 */
class AndroidSharer(
    private val activity: Activity,
    private val statementStore: StatementImageShareStore,
    private val requestCode: Int,
) : Sharer {

    /** Hands text to the Android Sharesheet, where WhatsApp shows up on its own. */
    override fun shareText(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        activity.startActivity(Intent.createChooser(intent, subject))
    }

    override fun shareStatementImage(
        subject: String,
        shortText: String,
        fullText: String,
        content: StatementImageContent,
    ) {
        // Las dos ramas pasan por `StatementShareFlow`, que a su vez pasa por
        // `StatementShareTextChoice`: con imagen, texto corto; sin imagen, texto completo.
        StatementShareFlow.share(
            subject = subject,
            shortText = shortText,
            fullText = fullText,
            shareImage = { text -> startStatementImageShare(subject, text, content) },
            shareText = { text -> this.shareText(subject, text) },
        )
    }

    /**
     * Escribe el PNG temporal y abre la hoja. Devuelve `false` si el PNG no se pudo escribir (sin
     * espacio, error de disco), que es lo que hace que se comparta el texto completo.
     */
    private fun startStatementImageShare(
        subject: String,
        text: String,
        content: StatementImageContent,
    ): Boolean {
        val intent = runCatching { statementStore.createShareIntent(subject, text, content) }.getOrNull()
            ?: return false
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Intent.createChooser(intent, subject), requestCode)
        return true
    }

    /**
     * Opens the dialer with the number filled in. Deliberately ACTION_DIAL, not ACTION_CALL: the
     * latter needs the CALL_PHONE permission and would place the call without the owner seeing it.
     *
     * Igual que hasta hoy: el número va tal cual lo escribió la persona, sin pasar por `TelUri`.
     */
    override fun dial(phone: String) {
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
    }

    override fun openUrl(url: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
