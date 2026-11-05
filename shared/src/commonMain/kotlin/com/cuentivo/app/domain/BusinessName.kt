package com.cuentivo.app.domain

/**
 * El nombre con el que nace un negocio, y cómo saber si alguien lo configuró de verdad.
 *
 * El valor por defecto **no es un nombre: es la ausencia de uno**. Se escribe al crear el negocio, en
 * el contrato de sincronización y al leer de Firestore, y **nunca debe llegarle a un cliente** dentro
 * del mensaje: "te comparto tu estado de cuenta con Mi negocio" es una fuga de un detalle interno a la
 * persona que menos tiene por qué verlo.
 *
 * Vive aparte para que los seis sitios que lo escribían a mano compartan una sola definición: cambiar
 * el nombre por defecto tiene que ser un cambio de una línea, no una cacería.
 */
object BusinessName {

    const val DEFAULT = "Mi negocio"

    /**
     * True cuando hay un nombre escrito por la persona.
     *
     * Compara ignorando los espacios de los extremos y las mayúsculas: `"  mi NEGOCIO "` es el valor
     * por defecto disfrazado, y enseñarlo sería el mismo defecto con otro espaciado.
     */
    fun isConfigured(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.isNotEmpty() && !trimmed.equals(DEFAULT, ignoreCase = true)
    }
}
