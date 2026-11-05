package com.cuentivo.app.ai.ledger

/**
 * La fecha que el servidor leyó de la hoja, con su confianza. Nunca se aplica sola.
 *
 * Vive en su propio archivo porque es común y `LedgerExtractionGateway` —que la usa— depende de
 * Firebase y se queda en Android. Mover la declaración es lo que permite que el resto del dominio
 * la use sin arrastrar el cliente.
 */
data class LedgerDetectedPageDate(
    val date: SafeDateCandidate,
    val confidence: Double,
)
