package com.cuentivo.app.ai.ledger

/**
 * `Math.addExact`, `subtractExact` y `multiplyExact` de Java, que no existen en código común.
 *
 * Los tres lanzan `ArithmeticException` si el resultado no cabe en un `Long`, igual que Java: aquí
 * se suman y multiplican centavos y una cuenta que se sale de rango es un error que hay que ver, no
 * un número negativo silencioso.
 */
internal fun addExact(a: Long, b: Long): Long {
    val result = a + b
    if (((a xor result) and (b xor result)) < 0) throw ArithmeticException("long overflow")
    return result
}

internal fun subtractExact(a: Long, b: Long): Long {
    val result = a - b
    if (((a xor b) and (a xor result)) < 0) throw ArithmeticException("long overflow")
    return result
}

internal fun multiplyExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val result = a * b
    if (result / a != b) throw ArithmeticException("long overflow")
    return result
}
