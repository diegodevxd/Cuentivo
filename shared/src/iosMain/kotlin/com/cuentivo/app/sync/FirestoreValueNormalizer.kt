package com.cuentivo.app.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSArray
import platform.Foundation.NSDictionary
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.NSString

/**
 * Convierte lo que el SDK nativo de Firestore entrega a tipos Kotlin, sin perder información.
 *
 * Es la mitad iOS de la frontera de dinero: `RemoteMerger` sólo acepta `Long` para los centavos y
 * rechaza todo lo demás, así que lo importante aquí es **no truncar**. Un `NSNumber` de `10.5` tiene
 * que llegar como `Double` 10.5 —nunca como `Long` 10— para que el lector de dinero lo rechace en vez
 * de aceptar un libro con decimales.
 *
 * La clasificación del `NSNumber` se hace por su codificación ObjC (`objCType`), que es el tipo real
 * del valor: `"c"`/`"B"` es un booleano, `"f"`/`"d"` es flotante, cualquier otro es entero. Un `Long`
 * que viaja como `1` llega como `"q"`, así que no se confunde con `true`. (La alternativa con
 * `CFBooleanGetTypeID` exige pasar el objeto ObjC a CoreFoundation, algo que no se puede compilar
 * fuera de macOS; el resultado es el mismo y las pruebas lo fijan.)
 */
@OptIn(ExperimentalForeignApi::class)
fun normalizeFirestoreValue(value: Any?): Any? = when (value) {
    null -> null
    is NSNull -> null
    is NSNumber -> normalizeNumber(value)
    // Por si el puente de ObjC ya los convirtió: se respetan tal cual, sin pasar por Long.
    is Boolean -> value
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.toDouble()
    is Double -> value
    is String -> value
    is NSString -> value.toString()
    is Map<*, *> -> value.entries.associate { (key, item) ->
        normalizeFirestoreValue(key) to normalizeFirestoreValue(item)
    }
    is NSDictionary -> (value as Map<*, *>).entries.associate { (key, item) ->
        normalizeFirestoreValue(key) to normalizeFirestoreValue(item)
    }
    is List<*> -> value.map { normalizeFirestoreValue(it) }
    is NSArray -> (value as List<*>).map { normalizeFirestoreValue(it) }
    // Cualquier otra cosa (FIRTimestamp, FIRGeoPoint, referencias) se conserva tal cual: el lector
    // de dinero la rechazará, que es justo lo que tiene que pasar.
    else -> value
}

@OptIn(ExperimentalForeignApi::class)
private fun normalizeNumber(number: NSNumber): Any = when (number.objCType?.toKString()) {
    "c", "B" -> number.boolValue
    "f", "d" -> number.doubleValue
    else -> number.longLongValue
}

/**
 * El payload de una operación encolada, como mapa de Firestore.
 *
 * Usa [StrictJson] y no `NSJSONSerialization`: el parser de Apple puede devolver un entero grande
 * como `Double` y perder precisión en un monto o en un id. `StrictJson` convierte un literal entero
 * con `toLongOrNull()` y **rechaza** el que no cabe, que es la misma regla que impide que entren
 * decimales.
 */
fun parseJsonToFirestoreMap(json: String): Map<Any?, Any?> =
    buildMap { putAll(StrictJson.parseObject(json)) }
