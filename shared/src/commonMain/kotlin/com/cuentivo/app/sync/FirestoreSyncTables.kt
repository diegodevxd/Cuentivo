package com.cuentivo.app.sync

/**
 * Lo que Android y iOS tienen que decidir igual sobre Firestore.
 *
 * Son tablas y reglas puras: no conocen `org.json`, ni Firebase, ni el SDK de ninguna plataforma. La
 * idea es que exista **una sola** tabla de rutas, una sola de colecciones a traer y una sola
 * clasificación de errores; cada gateway pone lo suyo (cómo lee el JSON, cómo habla con Firestore).
 */

/**
 * La ruta del documento remoto de una operación.
 *
 * [parentId] es el `saleId` o el `paymentId` que el payload lleva dentro; lo resuelve cada gateway
 * porque el payload es JSON y cada plataforma lo parsea con su herramienta.
 */
object FirestorePaths {
    fun pathFor(
        entityType: String,
        businessId: String,
        entityId: String,
        parentId: String? = null,
    ): String = when (entityType) {
        "BUSINESS" -> "businesses/$entityId"
        "MEMBER" -> "businesses/$businessId/members/$entityId"
        "CUSTOMER" -> "businesses/$businessId/customers/$entityId"
        "PRODUCT" -> "businesses/$businessId/products/$entityId"
        "SALE" -> "businesses/$businessId/sales/$entityId"
        "SALE_ITEM" -> "businesses/$businessId/sales/${requireParent(parentId)}/items/$entityId"
        "PAYMENT" -> "businesses/$businessId/payments/$entityId"
        "PAYMENT_ALLOCATION" ->
            "businesses/$businessId/payments/${requireParent(parentId)}/allocations/$entityId"
        "MOVEMENT" -> "businesses/$businessId/movements/$entityId"
        else -> throw IllegalArgumentException("Unsupported sync entity type: $entityType")
    }

    private fun requireParent(parentId: String?): String =
        requireNotNull(parentId) { "The payload is missing the parent id" }
}

/**
 * Por qué campo temporal se ordena cada colección. No comparten nombre, así que esta tabla es la que
 * permite que un solo watermark sirva para todas y que cada consulta filtre por el campo correcto.
 */
data class PullSpec(val collection: String, val entityType: String, val watermarkField: String)

val PULL_SPECS: List<PullSpec> = listOf(
    PullSpec("customers", "CUSTOMER", "updatedAtEpochMillis"),
    PullSpec("products", "PRODUCT", "updatedAtEpochMillis"),
    PullSpec("sales", "SALE", "updatedAtEpochMillis"),
    PullSpec("payments", "PAYMENT", "createdAtEpochMillis"),
    PullSpec("movements", "MOVEMENT", "occurredAtEpochMillis"),
)

/** Cuántos documentos como máximo trae cada consulta del pull. */
const val SYNC_PAGE_SIZE = 200L

/**
 * Clasifica un fallo por el **código** de Firestore, no por el texto del mensaje.
 *
 * De esto depende que una fila de la cola se reintente o se estacione para siempre: derivarlo de un
 * `substring` era un riesgo real (INVALID_ARGUMENT y NOT_FOUND se reintentaban sin fin), y
 * FAILED_PRECONDITION es lo que reporta un índice compuesto que falta, que no va a aparecer solo.
 *
 * [codeName] es el nombre del código (`PERMISSION_DENIED`, …) o `null` si la excepción no vino de
 * Firestore. Android lo saca de `FirebaseFirestoreException.Code.name`; iOS, del `NSError`.
 */
fun classifyFirestoreFailure(codeName: String?, message: String): SyncFailure = when (codeName) {
    "PERMISSION_DENIED",
    "INVALID_ARGUMENT",
    "NOT_FOUND",
    "ALREADY_EXISTS",
    "FAILED_PRECONDITION",
    "OUT_OF_RANGE",
    "UNIMPLEMENTED",
    "DATA_LOSS",
    -> SyncFailure.Permanent("$codeName: $message")

    // Sin código en la cadena: el fallo vino de otro sitio, así que se decide por el mensaje, y sólo
    // por la única frase que el servidor usa para decir "las reglas dijeron no".
    null -> if (message.contains("PERMISSION_DENIED", ignoreCase = true)) {
        SyncFailure.Permanent(message)
    } else {
        SyncFailure.Transient(message)
    }

    else -> SyncFailure.Transient("$codeName: $message")
}
