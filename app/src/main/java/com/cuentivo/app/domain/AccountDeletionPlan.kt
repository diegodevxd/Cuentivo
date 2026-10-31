package com.cuentivo.app.domain

/**
 * El orden y las paradas del borrado de cuenta (v44).
 *
 * El defecto que esto cierra: la app borraba la nube, luego Room, luego el entitlement y **al final**
 * intentaba borrar el usuario de Auth. Firebase exige un inicio de sesión reciente para borrar un
 * usuario, así que con una sesión de días atrás el borrado de Auth fallaba **después** de haber
 * borrado todo lo demás: la persona quedaba con sesión abierta y sin datos —el estado raro que vio
 * Diego— y la pantalla se quedaba colgada.
 *
 * El orden correcto es: **re-autenticar primero**, y sólo con eso hecho, nube → local → Auth. Si la
 * re-autenticación se cancela o falla, no se borra nada y se le dice. La cuenta invitada (anónima)
 * no tiene Google que re-autenticar: sigue funcionando sin ese paso.
 *
 * Puro: cero Android, cero Firebase, cero Context. La Activity ejecuta los pasos; aquí sólo se
 * decide qué toca y qué se hace cuando cada uno falla.
 */
object AccountDeletionPlan {

    /** Qué paso toca ejecutar a continuación. */
    enum class Step {
        /** Pedir la credencial de Google y `reauthenticate`. Sólo para cuentas con Google. */
        REAUTHENTICATE,

        /** Borrar la copia remota (nube). */
        ERASE_REMOTE,

        /** Borrar la copia local (Room) y olvidar entitlement/sesión. */
        WIPE_LOCAL,

        /** Borrar el usuario de Auth. */
        DELETE_AUTH,

        /** Terminado: fuera de la sesión y a la pantalla de inicio de sesión. */
        DONE,
    }

    /** Qué hacer cuando un paso falla. */
    enum class FailureAction {
        /**
         * El paso falló **antes** de tocar nada destructivo: no se borró nada, se le dice qué pasó y
         * se puede reintentar con todo intacto.
         */
        ABORT_WITH_MESSAGE,

        /** La sesión es vieja: se le pide volver a iniciar sesión y repetir. */
        ASK_RECENT_LOGIN,

        /**
         * El borrado de Auth falló **después** de borrar la nube y Room: los datos ya no están, pero
         * la cuenta sigue viva. Nunca se puede quedar la app con sesión abierta y sin datos, así que
         * se cierra la sesión igualmente y se le dice la verdad: sus datos se borraron, pero no se
         * pudo cerrar la cuenta; al volver a entrar se termina.
         */
        SIGN_OUT_AND_MESSAGE,
    }

    /**
     * El primer paso de la secuencia.
     *
     * Una cuenta anónima no tiene Google que re-autenticar, así que arranca directo en la nube. Una
     * cuenta con Google (o con cualquier proveedor que exija re-auth) empieza pidiendo la credencial.
     */
    fun firstStep(isAnonymous: Boolean): Step =
        if (isAnonymous) Step.ERASE_REMOTE else Step.REAUTHENTICATE

    /**
     * El paso siguiente a uno que salió bien.
     *
     * `REAUTHENTICATE` → `ERASE_REMOTE` → `WIPE_LOCAL` → `DELETE_AUTH` → `DONE`. Es una cadena fija:
     * cada paso depende de que el anterior haya borrado lo suyo, y por eso no se puede reordenar.
     */
    fun nextStepAfterSuccess(step: Step): Step = when (step) {
        Step.REAUTHENTICATE -> Step.ERASE_REMOTE
        Step.ERASE_REMOTE -> Step.WIPE_LOCAL
        Step.WIPE_LOCAL -> Step.DELETE_AUTH
        Step.DELETE_AUTH -> Step.DONE
        Step.DONE -> Step.DONE
    }

    /**
     * Qué se hace cuando un paso falla.
     *
     * Sólo el borrado de Auth puede pedir un inicio de sesión reciente; cualquier otro fallo —la
     * re-autenticación cancelada, la nube caída, el disco lleno— aborta sin borrar nada más y con un
     * mensaje. Nunca se sigue adelante tras un fallo: seguir es exactamente cómo se llegó al estado
     * "sesión abierta y sin datos".
     *
     * **`DELETE_AUTH` es el único paso que falla con los datos ya borrados.** Ahí `ABORT_WITH_MESSAGE`
     * no significa "no se borró nada": la nube y Room ya no están. Por eso devuelve
     * [FailureAction.SIGN_OUT_AND_MESSAGE] (o [FailureAction.ASK_RECENT_LOGIN] si el motivo es la
     * sesión vieja): en los dos casos hay que salir de la sesión igualmente, porque quedarse dentro
     * con la cuenta viva y sin datos es el estado que este flujo existe para impedir.
     */
    fun onFailure(step: Step, authErrorCode: String?): FailureAction = when (step) {
        Step.DELETE_AUTH ->
            if (AccountDeletionFailure.messageKeyForAuth(authErrorCode) == AccountDeletionFailure.MessageKey.RECENT_LOGIN_REQUIRED) {
                FailureAction.ASK_RECENT_LOGIN
            } else {
                FailureAction.SIGN_OUT_AND_MESSAGE
            }
        else -> FailureAction.ABORT_WITH_MESSAGE
    }

    /**
     * El paso con el que se retoma un borrado que quedó a medias.
     *
     * Si hay una marca durable de borrado pendiente para el uid que acaba de entrar, la parte
     * destructiva ya ocurrió (o el proceso murió a mitad): lo único que falta es borrar el usuario de
     * Auth. Se reintenta eso —re-autenticando si hace falta— y **no** se crea ni se adopta ningún
     * negocio. Sin marca, no hay nada que retomar.
     */
    fun resumeStep(hasPendingDeletion: Boolean): Step? =
        if (hasPendingDeletion) Step.DELETE_AUTH else null
}
