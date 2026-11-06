package com.cuentivo.app.sync

/**
 * Las reglas de inmutabilidad remota, sin `org.json`: una sola copia para Android e iOS.
 *
 * Android las usaba dentro de `SyncRemoteImmutability` mezcladas con el parseo del payload; iOS las
 * necesita con el payload ya convertido a mapa. Si estuvieran dos veces, un cambio en una plataforma
 * dejaría a la otra estacionando (o perdiendo) filas de la cola con un criterio distinto.
 *
 * El "por qué" completo está en `app/.../sync/SyncRemoteImmutability.kt`: las colecciones financieras
 * son create-only en `firestore.rules`, así que para ellas **existir en el id correcto es el estado
 * final**, y una denegación sólo se resuelve leyendo el servidor.
 */
object SyncRemoteImmutabilityRules {

    /** Tipos que este cliente nunca puede reescribir: si el documento ya está, no queda nada por hacer. */
    private val ALWAYS_IMMUTABLE = setOf(
        "SALE",
        "SALE_ITEM",
        "PAYMENT",
        "PAYMENT_ALLOCATION",
        "MOVEMENT",
    )

    /**
     * ¿Un documento que ya existe deja esta operación sin nada que hacer?
     *
     * [role] es el `role` del payload para los `MEMBER`: sólo la fila del OWNER es inmutable (las
     * reglas permiten crear la membresía y nunca cambiarla); un miembro que el dueño todavía puede
     * promover o desactivar sí acepta `update`, así que una denegación ahí es un rechazo de verdad.
     */
    fun isSettledByExistence(entityType: String, role: String?): Boolean = when (entityType) {
        in ALWAYS_IMMUTABLE -> true
        "MEMBER" -> role == "OWNER"
        else -> false
    }

    /** Sólo tiene sentido preguntar al servidor tras una denegación y sobre un tipo inmutable. */
    fun shouldCheckWhetherAlreadyPublished(
        entityType: String,
        role: String?,
        isPermissionDenied: Boolean,
    ): Boolean = isPermissionDenied && isSettledByExistence(entityType, role)

    /**
     * ¿El documento que ya está arriba es exactamente el que esta fila quería publicar?
     *
     * Existir no es lo mismo que coincidir: si la copia remota tiene otra cosa bajo el mismo id, la
     * fila no está hecha, es una divergencia — y como estos documentos no se pueden reescribir, nadie
     * volvería a verla si se descartara en silencio.
     *
     * Se comparan los valores ya normalizados: los mismos centavos llegan como `Int` desde el JSON
     * encolado y como `Long` desde Firestore, y un monto que coincide no puede leerse como conflicto.
     */
    fun isAlreadyTheSameDocument(payload: Map<*, *>, remote: Map<*, *>): Boolean {
        if (payload.keys != remote.keys) return false
        return payload.keys.all { key -> normalizedValue(payload[key]) == normalizedValue(remote[key]) }
    }

    /**
     * Un `Double` que en realidad es entero se compara como entero; cualquier otro número, por su
     * valor. Un decimal que no es entero se queda `Double` y por lo tanto **no** coincide con un
     * centavo, que es lo que se quiere.
     */
    private fun normalizedValue(value: Any?): Any? = when (value) {
        null -> null
        is Double -> if (value == kotlin.math.floor(value) && !value.isInfinite()) value.toLong() else value
        is Float -> normalizedValue(value.toDouble())
        is Number -> value.toLong()
        is Boolean, is String -> value
        else -> value.toString()
    }
}
