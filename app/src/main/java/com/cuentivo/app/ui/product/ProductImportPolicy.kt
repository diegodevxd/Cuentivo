package com.cuentivo.app.ui.product

import com.cuentivo.app.domain.AccessDecision

/** What should happen when someone picks a spreadsheet to import. */
internal sealed interface ImportStart {
    /** Cleared to run, and the only outcome that may show progress. */
    data class Begin(val businessId: String) : ImportStart

    /** The plan does not include importing. */
    data class Denied(val decision: AccessDecision.Blocked) : ImportStart

    /** No active business, so there is nowhere to put the rows. */
    data object NoBusiness : ImportStart
}

/**
 * Decides whether an import actually starts, so the progress flag can be tied to that decision
 * rather than to a report arriving.
 *
 * The screen used to raise "Importando…" the moment a file was picked and only lower it when a
 * `SheetImport` came back. Three routes never produce one — no business, a blocked Free account,
 * and an unreadable file — so the button stayed on "Importando…" forever and the user could not try
 * again. Progress now belongs to the caller that actually launched the work.
 */
internal object ProductImportPolicy {
    fun start(businessId: String?, decision: AccessDecision): ImportStart = when {
        businessId == null -> ImportStart.NoBusiness
        decision is AccessDecision.Blocked -> ImportStart.Denied(decision)
        else -> ImportStart.Begin(businessId)
    }

    /** Only a started import may show progress; everything else has already finished. */
    fun showsProgress(start: ImportStart): Boolean = start is ImportStart.Begin
}
