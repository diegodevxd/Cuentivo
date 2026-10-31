package com.cuentivo.app.domain

/**
 * Decides what a failed step of the account-erasure flow is allowed to do.
 *
 * This is the pure half of the failure contract that keeps local data, session, entitlement and the
 * Auth account alive unless the cloud copy is gone first. It returns an enum; the UI maps the enum
 * to the actual string, keeping `domain/` free of `Context` and `R.string` (see CLAUDE.md).
 */
object AccountDeletionFailure {

    enum class MessageKey {
        /** The remote erasure could not be completed, so nothing local was touched. Retry. */
        REMOTE_DELETION_FAILED,

        /** The account could not be deleted because the session is older than Firebase allows. */
        RECENT_LOGIN_REQUIRED,

        /** Auth refused to delete the account for a reason other than a stale session. */
        AUTH_DELETION_FAILED,
    }

    /**
     * Whether the local wipe may proceed. It may only after the remote copy is fully gone: clearing
     * the phone while the cloud still holds the business would strand the person's (and their
     * customers') data in Firestore with no way to ask again — the exact hole this flow exists to
     * close. A null business (nothing ever reached the cloud) counts as erased.
     */
    fun mayWipeLocal(remoteDeletion: Boolean): Boolean = remoteDeletion

    /**
     * Maps a Firebase Auth error code to the message the UI should show.
     *
     * En Android el código real es `ERROR_REQUIRES_RECENT_LOGIN` (el formato `requires-recent-login`
     * es el de la web). Comparar contra el de la web hacía que el aviso de "vuelve a iniciar sesión"
     * **nunca se mostrara**: el error caía en el genérico. Se aceptan los dos por si el SDK cambia,
     * pero el que importa es el de Android.
     */
    fun messageKeyForAuth(errorCode: String?): MessageKey =
        if (errorCode == ERROR_REQUIRES_RECENT_LOGIN || errorCode == "requires-recent-login") {
            MessageKey.RECENT_LOGIN_REQUIRED
        } else {
            MessageKey.AUTH_DELETION_FAILED
        }

    /** El código de error de Android para "hace falta un inicio de sesión reciente". */
    const val ERROR_REQUIRES_RECENT_LOGIN = "ERROR_REQUIRES_RECENT_LOGIN"
}
