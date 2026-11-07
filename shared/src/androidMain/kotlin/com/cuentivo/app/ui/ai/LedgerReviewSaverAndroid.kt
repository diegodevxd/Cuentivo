package com.cuentivo.app.ui.ai

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver

/**
 * Saves and restores the review under way across Activity recreation.
 *
 * The saved value is always a Bundle, never null: a null save would make the state itself vanish
 * from the registry, and the screen needs the slot to exist so an ended import writes "empty"
 * over a previously saved one. An empty Bundle restores as null — no review to bring back.
 *
 * The heavy, derivable half of the review — balances, candidates, registered rows — is
 * deliberately not here: it is reloaded from Room by the restore effect, and a Bundle big
 * enough to hold it would exceed the transaction limit and kill the app.
 */
@Suppress("DEPRECATION")
actual fun ledgerReviewStateSaver(): Saver<LedgerReviewSaver.SavedLedgerReview?, *> =
    Saver<LedgerReviewSaver.SavedLedgerReview?, Bundle>(
        save = { it?.let(LedgerReviewBundle::toBundle) ?: Bundle() },
        restore = { bundle -> if (bundle.isEmpty) null else LedgerReviewBundle.fromBundle(bundle) },
    )
