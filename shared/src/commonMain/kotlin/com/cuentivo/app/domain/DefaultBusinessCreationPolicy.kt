package com.cuentivo.app.domain

/**
 * Qué hacer cuando hay sesión, la cuenta ya resolvió su [BusinessClaim] y **no** hay negocio.
 *
 * El defecto que cierra (v46): una cuenta nueva sin negocio se quedaba atorada. "Guardar" en Nuevo
 * cliente no hacía nada, "Tomar foto de mi libreta" abría el chat sin cámara ni importación —el atajo
 * sólo existe con negocio activo— y la única salida era ir a Inicio y pulsar "Crear mi negocio", cosa
 * que nadie hace sola. La decisión de Diego es que el negocio se cree solo.
 *
 * La decisión vive aquí, pura y sin Android, porque la parte que importa es **cuándo no crear**:
 * crear mientras el claim todavía consulta la nube, o después de que la consulta falló, es exactamente
 * cómo una cuenta terminó con varios negocios. `MainActivity` sólo llama a [decide] y ejecuta.
 */
sealed interface DefaultBusinessDecision {
    /** Todavía no se sabe si la cuenta ya tiene negocio: no se crea nada aún. */
    data object Wait : DefaultBusinessDecision

    /** Es seguro crear el negocio por defecto: la cuenta no tiene ninguno, en ningún lado. */
    data object Create : DefaultBusinessDecision

    /** Nada que hacer: ya hay negocio, o el claim no autoriza crear (adoptado o bloqueado). */
    data object DoNothing : DefaultBusinessDecision
}

object DefaultBusinessCreationPolicy {

    /**
     * @param claim el resultado del claim, o `null` mientras sigue consultando la nube.
     * @param hasLocalBusiness si Room ya guarda un negocio para el dueño de la sesión.
     * @param waitingForEntitlement si Play todavía no respondió y el teléfono está vacío. Es la misma
     *   espera que frena la oferta manual de "Crear mi negocio": sin ella, un dispositivo vacío
     *   ofrecería crear antes de saber si la cuenta ya tiene un negocio en la nube.
     *
     * Precedencia, y por qué:
     * 1. [hasLocalBusiness] primero: es la garantía de idempotencia. Un segundo arranque no crea otro.
     * 2. `claim == null` → esperar. Es la consulta en curso; crear aquí duplicaría.
     * 3. [waitingForEntitlement] → esperar. Misma espera que la ruta manual.
     * 4. `CreateNew` → crear. Es el único claim que significa "la nube respondió y no hay nada".
     * 5. Todo lo demás → nada. `UseLocal` ya tiene negocio; `AdoptRemote` lo está adoptando;
     *    `Blocked` es una consulta que falló y **jamás** debe crear.
     */
    fun decide(
        claim: BusinessClaim?,
        hasLocalBusiness: Boolean,
        waitingForEntitlement: Boolean,
    ): DefaultBusinessDecision = when {
        hasLocalBusiness -> DefaultBusinessDecision.DoNothing
        claim == null -> DefaultBusinessDecision.Wait
        waitingForEntitlement -> DefaultBusinessDecision.Wait
        claim == BusinessClaim.CreateNew -> DefaultBusinessDecision.Create
        else -> DefaultBusinessDecision.DoNothing
    }
}
