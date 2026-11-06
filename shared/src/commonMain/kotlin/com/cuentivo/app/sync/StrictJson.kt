package com.cuentivo.app.sync

/**
 * Parser JSON mínimo y exacto, para los payloads que la app ya escribe con `JSONObject`.
 *
 * Existe porque el gateway de iOS necesita convertir el texto del payload a un mapa de Firestore y
 * `NSJSONSerialization` no garantiza conservar enteros de 64 bits: un id o un monto por encima de
 * 2^53 puede volver como `Double` y perder precisión. Aquí un literal entero se convierte con
 * `toLongOrNull()`, que falla en vez de redondear; si un entero no cabe en un `Long` el documento
 * entero se rechaza.
 *
 * Vive en común —y no en `iosMain`— para que la prueba corra en la JVM del host y no sólo en el
 * simulador: el parser es lo único de esta capa que se puede verificar sin una Mac.
 */
internal object StrictJson {

    /** Parsea un objeto JSON de nivel superior. Lanza [IllegalArgumentException] si no lo es. */
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing content after the JSON object" }
        require(value is Map<*, *>) { "The payload must be a JSON object" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val c = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("Unexpected '$c' at $index")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Invalid literal at $index" }
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(index < text.length && text[index] == ':') { "Expected ':' at $index" }
                index++
                result[key] = readValue()
                skipWhitespace()
                require(index < text.length) { "Unterminated object" }
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                require(index < text.length) { "Unterminated array" }
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $index")
                }
            }
        }

        private fun readString(): String {
            require(index < text.length && text[index] == '"') { "Expected a string at $index" }
            index++
            val out = StringBuilder()
            while (true) {
                require(index < text.length) { "Unterminated string" }
                when (val c = text[index]) {
                    '"' -> { index++; return out.toString() }
                    '\\' -> {
                        index++
                        require(index < text.length) { "Unterminated escape" }
                        when (val escaped = text[index]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 < text.length) { "Short unicode escape" }
                                val code = text.substring(index + 1, index + 5).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape at $index")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$escaped' at $index")
                        }
                        index++
                    }
                    else -> { out.append(c); index++ }
                }
            }
        }

        /**
         * Entero sin punto ni exponente → `Long` exacto. Cualquier otra forma → `Double`, que es lo
         * que un monto decimal es de todos modos (y el lector de dinero lo rechaza más adelante).
         */
        private fun readNumber(): Any {
            val start = index
            if (index < text.length && text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var isFloating = false
            if (index < text.length && text[index] == '.') {
                isFloating = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isFloating = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val literal = text.substring(start, index)
            require(literal.isNotEmpty() && literal != "-") { "Invalid number at $start" }
            return if (isFloating) {
                literal.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid decimal '$literal'")
            } else {
                // Un entero que no cabe en Long se rechaza en vez de redondearse a Double: es la
                // misma frontera que impide que entren decimales, aplicada a la precisión.
                literal.toLongOrNull() ?: throw IllegalArgumentException("Integer '$literal' does not fit in a Long")
            }
        }
    }
}
