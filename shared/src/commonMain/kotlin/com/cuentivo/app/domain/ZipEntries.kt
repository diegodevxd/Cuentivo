package com.cuentivo.app.domain

/**
 * Devuelve el contenido de las entradas del zip que [wanted] acepta.
 *
 * Android usa `ZipInputStream`, que es lo que ya leía estos archivos; iOS no lo tiene, así que lee el
 * directorio central con [ZipCentralDirectory]. El contrato es el mismo en los dos lados: mapa por
 * nombre, contenido ya descomprimido, y solo las entradas pedidas.
 *
 * Un archivo que no es un zip devuelve un mapa vacío, igual que hacía `ZipInputStream`.
 */
internal expect fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray>
