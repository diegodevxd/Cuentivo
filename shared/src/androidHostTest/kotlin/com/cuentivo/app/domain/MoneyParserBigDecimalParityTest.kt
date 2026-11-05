package com.cuentivo.app.domain

import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Prueba diferencial del parser de pesos contra el comportamiento que tenía antes de moverlo a
 * común: `BigDecimal(texto.trim()).movePointRight(2).longValueExact()`.
 *
 * Aquí sí se copia la regla vieja, porque no es un sustituto de la producción: es el oráculo contra
 * el que se compara `Money.centsFromPesos`, que es el código que de verdad corre. Si el parser nuevo
 * diverge en un solo caso, esta prueba lo dice; no hay expectativas escritas a mano que se puedan
 * "ajustar" para tapar una diferencia.
 *
 * Vive en `androidHostTest` y no en `commonTest` porque el oráculo es `java.math.BigDecimal`, que
 * sólo existe en la JVM. Corre con `:shared:testAndroidHostTest`.
 */
class MoneyParserBigDecimalParityTest {

    /** La implementación original, palabra por palabra. Este es el oráculo. */
    private fun oracle(t: String): Long? = try {
        BigDecimal(t.trim()).movePointRight(2).longValueExact()
    } catch (e: Exception) {
        null
    }

    private fun assertSameBehaviour(text: String) {
        assertEquals(oracle(text), Money.centsFromPesos(text), "diverge en '${text.replace("\n", "\\n")}'")
    }

    @Test
    fun fixed_cases_match_the_big_decimal_oracle() {
        val cases = listOf(
            // Enteros y sin decimales
            "0", "1", "5", "10", "100", "1234", "  42  ", "+7", "-7", "-0", "+0",
            // Uno y dos decimales
            "0.01", "0.10", "1.5", "150.50", "-150.50", "0.05", "-0.05", "10.", ".5",
            // Más decimales de los que caben: sólo pasan los ceros
            "1.230", "10.005", "0.001", "1.000", "1.2340", "0.000",
            // Ceros a la izquierda
            "000123", "000.50", "-000.01",
            // Exponentes, positivos y negativos
            "1E3", "1e+3", "1e-2", "1.5e2", "15e-1", "1e0", "0e10", "0e-10", "-1e3",
            // Exponentes gigantes: aquí sí caben como casos fijos. El aleatorio no los genera
            // porque `BigDecimal` tarda minutos en resolverlos (ver el generador de abajo).
            "0e999999999", "1e999999999", "-1e999999999", "0e-999999999", "1e-999999999",
            "0e2147483647", "0e2147483648", "0e2147483649", "0e-2147483647", "0e-2147483648",
            "1e2147483647", "1e-2147483647",
            // Extremos de Long
            "92233720368547758.07", "92233720368547758.08", "-92233720368547758.08",
            "-92233720368547758.09", "9223372036854775807",
            // Basura y casi-números
            "", "   ", "abc", "1,5", "1.2.3", "--1", "+-1", "1 2", "1e", "1e+", "1e-", "e5",
            ".", "+", "-", "1..2", "1e1e1", "  ", "\t10\t",
        )
        cases.forEach { text -> assertSameBehaviour(text) }
    }

    @Test
    fun random_strings_match_the_big_decimal_oracle() {
        val random = Random(seed = 20261104)
        repeat(RANDOM_CASES) { assertSameBehaviour(randomText(random)) }
    }

    /**
     * Cadena aleatoria del alfabeto pedido, con una poda: si cayó una `e`/`E`, el exponente se
     * recorta a tres dígitos.
     *
     * No es por gusto. `BigDecimal` con exponentes enormes (`"5e-999999999"`) entra en una
     * operación interna que tarda decenas de milisegundos por caso; con 20,000 cadenas la prueba se
     * iba a 16 minutos. Los exponentes gigantes siguen cubiertos, pero como casos fijos arriba,
     * donde cuestan milisegundos. La comparación no se relaja: cada cadena que se genera se compara
     * contra el oráculo.
     */
    private fun randomText(random: Random): String {
        val length = random.nextInt(0, 13)
        val raw = buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        val marker = raw.indexOfFirst { it == 'e' || it == 'E' }
        if (marker < 0) return raw

        val head = raw.substring(0, marker + 1)
        var index = marker + 1
        val sign = if (index < raw.length && (raw[index] == '+' || raw[index] == '-')) {
            raw[index++].toString()
        } else {
            ""
        }
        val exponentDigits = raw.substring(index).takeWhile { it in '0'..'9' }.take(MAX_RANDOM_EXPONENT_DIGITS)
        return head + sign + exponentDigits
    }

    /**
     * Diferencia permitida y a propósito: `BigDecimal` acepta dígitos que no son ASCII (por
     * `Character.digit`) y el parser nuevo sólo acepta `0`–`9`. Rechazar "١٢" es más seguro que
     * interpretarlo como doce pesos, y el campo de la app sólo produce dígitos ASCII.
     */
    @Test
    fun non_ascii_digits_are_the_one_documented_difference() {
        assertEquals(1200L, oracle("١٢"))
        assertNull(Money.centsFromPesos("١٢"))
        assertEquals(300L, oracle("٣"))
        assertNull(Money.centsFromPesos("٣"))
    }

    private companion object {
        const val RANDOM_CASES = 20_000
        const val MAX_RANDOM_EXPONENT_DIGITS = 3
        const val ALPHABET = "0123456789.+-eE "
    }
}
