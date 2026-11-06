package com.cuentivo.app.platform

import com.cuentivo.app.domain.StatementImageContent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationState
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene

/**
 * [Sharer] de iOS: la hoja de compartir de UIKit y el marcador del sistema.
 *
 * La imagen temporal vive en [AppFiles] (área `CACHE`, directorio `statement-share`): se borra
 * cuando la hoja termina, y se limpia antes de escribir la siguiente.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSharer(
    private val files: AppFiles,
) : Sharer {

    override fun shareText(subject: String, body: String) {
        present(items = listOf(body), subject = subject)
    }

    override fun shareStatementImage(
        subject: String,
        shortText: String,
        fullText: String,
        content: StatementImageContent,
    ) {
        StatementShareFlow.share(
            subject = subject,
            shortText = shortText,
            fullText = fullText,
            shareImage = { text -> shareImageNow(subject, text, content) },
            shareText = { text -> shareText(subject, text) },
        )
    }

    /**
     * `TelUri` normaliza el número y `NSURL.URLWithString` puede devolver `null` con un espacio: no
     * se fuerza con `!!`. Sin URL, no se abre nada.
     */
    override fun dial(phone: String) {
        val uri = TelUri.from(phone)?.let { NSURL.URLWithString("tel:$it") } ?: return
        UIApplication.sharedApplication.openURL(uri, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun openUrl(url: String) {
        val uri = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(uri, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun shareImageNow(subject: String, text: String, content: StatementImageContent): Boolean {
        val bytes = IosStatementImageRenderer.renderPng(content) ?: return false
        files.clear(FileArea.CACHE, DIRECTORY)
        val name = "statement-${Clock.System.now().toEpochMilliseconds()}.png"
        if (!files.writeBytes(FileArea.CACHE, DIRECTORY, name, bytes)) return false
        val path = files.absolutePath(FileArea.CACHE, DIRECTORY, name) ?: return false
        val presented = present(
            items = listOf(NSURL.fileURLWithPath(path), text),
            subject = subject,
            onComplete = { files.delete(FileArea.CACHE, DIRECTORY, name) },
        )
        // Sin ventana no hay hoja que avise al terminar: el estado de cuenta no se queda en caché.
        if (!presented) files.delete(FileArea.CACHE, DIRECTORY, name)
        return presented
    }

    /**
     * Presenta la hoja sobre el controlador que esté arriba del todo.
     *
     * En iPad, `UIActivityViewController` es un popover y sin `sourceView`/`sourceRect` la app truena.
     * Sin ventana no se comparte nada: devuelve `false` en vez de reventar.
     */
    private fun present(
        items: List<Any?>,
        subject: String?,
        onComplete: (() -> Unit)? = null,
    ): Boolean {
        val controller = topViewController() ?: return false
        val activity = UIActivityViewController(activityItems = items, applicationActivities = null)
        subject?.let { activity.setValue(it, forKey = "subject") }
        activity.popoverPresentationController?.let { popover ->
            popover.sourceView = controller.view
            popover.sourceRect = controller.view.bounds
        }
        activity.completionWithItemsHandler = { _, _, _, _ -> onComplete?.invoke() }
        controller.presentViewController(activity, animated = true, completion = null)
        return true
    }

    private companion object {
        const val DIRECTORY = "statement-share"
    }
}
