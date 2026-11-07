package com.cuentivo.app.auth

import com.cuentivo.app.platform.KeyValueStore

/**
 * La marca durable de un borrado de cuenta que quedó a medias (v44).
 *
 * El problema que cierra: el borrado de Auth puede fallar **después** de haber borrado la nube y
 * Room (red, sesión caducada, cualquier error). Sin una marca, al volver a abrir la app no hay forma
 * de saber que esa cuenta ya perdió sus datos: la app crearía un negocio nuevo vacío y la persona
 * quedaría con una cuenta viva y sin datos, que es el estado que este flujo existe para impedir.
 *
 * La marca se escribe **antes** de empezar la parte destructiva y se limpia **sólo** cuando Auth se
 * borra. Si el proceso muere a mitad, la marca es lo que permite retomar al siguiente arranque.
 *
 * Guarda el uid, nunca el correo ni nada identificable: es un id opaco, igual que `LocalSessionGuard`.
 */
class PendingAccountDeletionStore(
    private val preferences: KeyValueStore,
) {
    /** Marca que el borrado de [userId] empezó y todavía no terminó. */
    fun mark(userId: String) {
        if (userId.isBlank()) return
        preferences.edit { putString(PENDING_USER_ID, userId) }
    }

    /** El uid con un borrado pendiente, o null si no hay ninguno. */
    fun pendingUserId(): String? = preferences.getString(PENDING_USER_ID)

    /** ¿Hay un borrado pendiente para exactamente [userId]? */
    fun isPendingFor(userId: String?): Boolean =
        userId != null && userId.isNotBlank() && pendingUserId() == userId

    /** Limpia la marca. Sólo cuando el usuario de Auth ya se borró. */
    fun clear() {
        preferences.edit { remove(PENDING_USER_ID) }
    }

    companion object {
        /** El archivo de preferencias donde vive. No cambia entre plataformas ni versiones. */
        const val PREFERENCES = "cuentivo.account_deletion"

        /** Un Firebase uid — opaco, nunca el correo. */
        private const val PENDING_USER_ID = "pendingDeletionUserId"
    }
}
