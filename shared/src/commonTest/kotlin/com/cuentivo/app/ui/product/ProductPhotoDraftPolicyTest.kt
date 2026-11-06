package com.cuentivo.app.ui.product

import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * The six transitions that decide whether a product ends up pointing at a file that no longer
 * exists. `ProductImageStore.save` mints a new timestamped name every call, so replacing a photo
 * really does leave the previous file behind unless something cleans it up.
 */
class ProductPhotoDraftPolicyTest {
    @Test
    fun changing_the_photo_then_cancelling_keeps_the_original() {
        val start = ProductPhotoDraftPolicy.start(ORIGINAL)

        val picked = ProductPhotoDraftPolicy.picked(start, NEW)

        // Nothing is deleted while the dialog is open: the row still points at the original.
        assertEquals(emptyList<String>(), picked.deleteNow)
        assertEquals(NEW, picked.draft.current)
        assertEquals(ORIGINAL, picked.draft.original)
        // Backing out throws away only the file this dialog created.
        assertEquals(listOf(NEW), ProductPhotoDraftPolicy.onCancel(picked.draft))
    }

    @Test
    fun removing_the_photo_then_cancelling_keeps_the_original() {
        val start = ProductPhotoDraftPolicy.start(ORIGINAL)

        val cleared = ProductPhotoDraftPolicy.cleared(start)

        assertEquals(emptyList<String>(), cleared.deleteNow)
        assertEquals(null, cleared.draft.current)
        assertEquals(emptyList<String>(), ProductPhotoDraftPolicy.onCancel(cleared.draft))
    }

    @Test
    fun changing_the_photo_then_saving_hands_over_the_new_name_and_retires_the_old_one() {
        val picked = ProductPhotoDraftPolicy.picked(ProductPhotoDraftPolicy.start(ORIGINAL), NEW)

        assertEquals(NEW, picked.draft.current)
        // Only after the write succeeds, and only because the row stopped referencing it.
        assertEquals(listOf(ORIGINAL), ProductPhotoDraftPolicy.onCommit(picked.draft))
    }

    @Test
    fun removing_the_photo_then_saving_stores_null_and_retires_the_old_one() {
        val cleared = ProductPhotoDraftPolicy.cleared(ProductPhotoDraftPolicy.start(ORIGINAL))

        assertEquals(null, cleared.draft.current)
        assertEquals(listOf(ORIGINAL), ProductPhotoDraftPolicy.onCommit(cleared.draft))
    }

    @Test
    fun picking_twice_discards_the_first_provisional_file() {
        val first = ProductPhotoDraftPolicy.picked(ProductPhotoDraftPolicy.start(ORIGINAL), NEW)

        val second = ProductPhotoDraftPolicy.picked(first.draft, NEWER)

        // The first pick was never persisted, so it can go immediately.
        assertEquals(listOf(NEW), second.deleteNow)
        assertEquals(NEWER, second.draft.current)
        assertEquals(setOf(NEWER), second.draft.provisional)
        // The original is still untouched until the write lands.
        assertEquals(listOf(ORIGINAL), ProductPhotoDraftPolicy.onCommit(second.draft))
    }

    @Test
    fun cancelling_a_brand_new_product_leaves_no_orphan() {
        val picked = ProductPhotoDraftPolicy.picked(ProductPhotoDraftPolicy.start(null), NEW)

        assertEquals(listOf(NEW), ProductPhotoDraftPolicy.onCancel(picked.draft))
        // Nothing to retire: there was never an original.
        assertEquals(emptyList<String>(), ProductPhotoDraftPolicy.onCommit(picked.draft))
    }

    @Test
    fun saving_without_touching_the_photo_retires_nothing() {
        val untouched = ProductPhotoDraftPolicy.start(ORIGINAL)

        assertEquals(emptyList<String>(), ProductPhotoDraftPolicy.onCommit(untouched))
        assertEquals(emptyList<String>(), ProductPhotoDraftPolicy.onCancel(untouched))
    }

    @Test
    fun removing_a_provisional_photo_deletes_it_at_once() {
        val picked = ProductPhotoDraftPolicy.picked(ProductPhotoDraftPolicy.start(null), NEW)

        val cleared = ProductPhotoDraftPolicy.cleared(picked.draft)

        assertEquals(listOf(NEW), cleared.deleteNow)
        assertEquals(emptySet<String>(), cleared.draft.provisional)
    }

    @Test
    fun a_failed_save_returns_null_and_deletes_nothing() {
        val draft = ProductPhotoDraftPolicy.start(ORIGINAL)
        val deleted = mutableListOf<String>()

        val next = ProductPhotoDraftPolicy.applySaved(draft, saved = null, deleteFile = { deleted += it })

        assertEquals(null, next)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun applying_a_saved_photo_deletes_the_provisional_it_replaces() {
        // Two picks in one dialog: the first file is provisional, so it goes now and nothing else.
        val first = ProductPhotoDraftPolicy.picked(ProductPhotoDraftPolicy.start(ORIGINAL), NEW)
        val deleted = mutableListOf<String>()

        val next = ProductPhotoDraftPolicy.applySaved(first.draft, saved = NEWER, deleteFile = { deleted += it })

        assertEquals(listOf(NEW), deleted)
        assertEquals(NEWER, next?.current)
        assertEquals(ORIGINAL, next?.original)
    }

    @Test
    fun applying_a_saved_photo_never_deletes_the_persisted_original() {
        // The dialog's first pick: `current` is still the row's original, which the row points at.
        val draft = ProductPhotoDraftPolicy.start(ORIGINAL)
        val deleted = mutableListOf<String>()

        val next = ProductPhotoDraftPolicy.applySaved(draft, saved = NEW, deleteFile = { deleted += it })

        assertEquals(emptyList<String>(), deleted)
        assertEquals(NEW, next?.current)
    }

    @Test
    fun the_returned_draft_points_at_the_new_photo() {
        val next = ProductPhotoDraftPolicy.applySaved(
            ProductPhotoDraftPolicy.start(ORIGINAL),
            saved = NEW,
            deleteFile = { },
        )

        assertEquals(NEW, next?.current)
        assertEquals(setOf(NEW), next?.provisional)
    }

    private companion object {
        const val ORIGINAL = "p1-1753900000000.jpg"
        const val NEW = "p1-1753900111111.jpg"
        const val NEWER = "p1-1753900222222.jpg"
    }
}
