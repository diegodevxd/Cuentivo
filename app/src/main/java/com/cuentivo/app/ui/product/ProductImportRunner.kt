package com.cuentivo.app.ui.product

import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the progress flag for the duration of an import.
 *
 * Separated from `MainActivity` so the transition itself can be tested. `ProductImportPolicy` only
 * decides whether an import starts; this is what guarantees the flag comes back down afterwards,
 * including when the file turns out to be unreadable — the route that used to leave the button on
 * "Importando…" with no way to retry.
 */
internal object ProductImportRunner {
    /**
     * Runs [block] with progress raised, and lowers it in a `finally` so a thrown exception cannot
     * strand it. Returns null when [start] never began, in which case progress is never touched.
     */
    suspend fun <T> runIfStarted(
        start: ImportStart,
        setProgress: (Boolean) -> Unit,
        block: suspend (businessId: String) -> T,
    ): Result<T>? {
        if (start !is ImportStart.Begin) return null
        setProgress(true)
        return try {
            Result.success(block(start.businessId))
        } catch (cancellation: CancellationException) {
            // Deliberately not folded into the failure branch. `runCatching` would have swallowed
            // this, turning a cancelled scope into an ordinary error result: the screen would show
            // "No fue posible leer ese archivo" for a coroutine nobody wanted to finish, and the
            // cancellation would never reach the parent job.
            throw cancellation
        } catch (error: Exception) {
            // Ordinary failures only — an unreadable file, a malformed sheet. `Error` keeps
            // travelling, since swallowing an OutOfMemoryError helps no one.
            Result.failure(error)
        } finally {
            // Runs on every exit, cancellation included, so the button always comes back.
            setProgress(false)
        }
    }
}
