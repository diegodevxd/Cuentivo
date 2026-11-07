package com.cuentivo.app.ui.ai

import androidx.compose.runtime.saveable.Saver

// En iOS esa revisión se pierde si el sistema mata la app: no hay Bundle que la sostenga.
actual fun ledgerReviewStateSaver(): Saver<LedgerReviewSaver.SavedLedgerReview?, *> =
    Saver(save = { "" }, restore = { null })
