package com.cuentivo.app.ui.product

/**
 * The photo a product form is working on, separate from the one already saved.
 *
 * [original] is what `ProductEntity.imageUri` pointed at when the dialog opened. [current] is what
 * the form would save right now. [provisional] are files this dialog itself created — they exist on
 * disk but no row references them yet, so they are the only ones it may delete.
 */
internal data class PhotoDraft(
    val original: String?,
    val current: String?,
    val provisional: Set<String> = emptySet(),
)

/** A new draft plus the files that became garbage at that exact moment. */
internal data class PhotoDraftUpdate(
    val draft: PhotoDraft,
    val deleteNow: List<String>,
)

/**
 * Keeps photo files and product rows from drifting apart.
 *
 * The picker used to delete the previous photo the instant a new one was chosen. Cancelling the
 * dialog then left `imageUri` pointing at a file that no longer existed, and the product showed the
 * placeholder forever. The rule here is simply that nothing referenced by a saved row is deleted
 * until a write has succeeded and the row no longer points at it.
 *
 * `ProductImageStore.save` mints a fresh timestamped name on every call, so picking twice really
 * does create two files; the replaced one has to be cleaned up rather than left behind.
 */
internal object ProductPhotoDraftPolicy {
    fun start(original: String?): PhotoDraft = PhotoDraft(original = original, current = original)

    /** Choosing a photo. Only a file this dialog created can be thrown away here. */
    fun picked(draft: PhotoDraft, fileName: String): PhotoDraftUpdate {
        val replaced = draft.current?.takeIf { it in draft.provisional }
        return PhotoDraftUpdate(
            draft = draft.copy(
                current = fileName,
                provisional = draft.provisional - setOfNotNull(replaced) + fileName,
            ),
            deleteNow = listOfNotNull(replaced),
        )
    }

    /** "Quitar foto". The persisted original survives until the write succeeds. */
    fun cleared(draft: PhotoDraft): PhotoDraftUpdate {
        val replaced = draft.current?.takeIf { it in draft.provisional }
        return PhotoDraftUpdate(
            draft = draft.copy(
                current = null,
                provisional = draft.provisional - setOfNotNull(replaced),
            ),
            deleteNow = listOfNotNull(replaced),
        )
    }

    /** Cancelling leaves the saved product exactly as it was; only new files go. */
    fun onCancel(draft: PhotoDraft): List<String> = draft.provisional.toList()

    /**
     * What the owner may delete *after* the update succeeded — the original, and only when the row
     * has stopped pointing at it.
     */
    fun onCommit(draft: PhotoDraft): List<String> =
        listOfNotNull(draft.original?.takeIf { it != draft.current })
}
