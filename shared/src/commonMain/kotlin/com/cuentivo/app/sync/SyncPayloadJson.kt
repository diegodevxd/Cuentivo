package com.cuentivo.app.sync

internal object SyncPayloadJson {

    fun encode(fields: Map<String, Any?>): String = buildString {
        append('{')
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendJsonString(key)
            append(':')
            when (value) {
                null -> append("null")
                is String -> appendJsonString(value)
                is Long, is Int, is Boolean -> append(value.toString())
                // Double/Float incluidos: por aquí entraría dinero fraccionario. Nunca en silencio.
                else -> throw IllegalArgumentException("Tipo no admitido en payload: ${value::class.simpleName}")
            }
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
}
