package com.cuentivo.app.ai

/**
 * El contrato del callable `interpretAiRequest`, en común.
 *
 * Vive aquí —y no en el gateway de Android— porque iOS manda exactamente el mismo payload y espera
 * exactamente la misma respuesta: dos copias del nombre de la función, la versión del contrato o la
 * acción serían dos formas de romper la compatibilidad sin que ninguna prueba lo note.
 */
const val AI_CALLABLE_NAME = "interpretAiRequest"

/** Versión del contrato tipado (v2). */
const val AI_CONTRACT_VERSION_FIELD = 2

/** La única acción que el servidor acepta hoy. */
const val AI_ACTION_VALUE = "interpret_text_proposal"

/** Región de las funciones: el servidor vive en `us-south1`. */
const val AI_FUNCTIONS_REGION = "us-south1"

/** Payload v2 exacto: contractVersion 2, requestId, action y el texto original. */
fun buildAiCallablePayload(request: AiInterpretRequest): Map<String, Any> = mapOf(
    "contractVersion" to AI_CONTRACT_VERSION_FIELD,
    "requestId" to request.requestId,
    "action" to AI_ACTION_VALUE,
    "text" to request.text,
)
