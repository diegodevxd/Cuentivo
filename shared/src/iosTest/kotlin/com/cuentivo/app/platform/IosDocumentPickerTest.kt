package com.cuentivo.app.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager

/**
 * El temporal del exportador y la lectura del archivo elegido.
 *
 * Es la parte del selector que sí se puede probar sin ventana: no presenta nada, sólo escribe, lee y
 * borra.
 *
 * Pendiente de CI: esta prueba sólo corre en `:shared:iosSimulatorArm64Test`.
 */
class IosDocumentPickerTest {

    @Test
    fun `escribe el temporal con el nombre exacto y lo borra al leerlo`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val url = writeDocumentTempFile("respaldo.cuentivo", bytes)
        val path = assertNotNull(url.path)
        assertTrue(path.endsWith("/respaldo.cuentivo"))

        val document = IosPickedDocument(url)
        assertContentEquals(bytes, document.readBytes())

        // La copia temporal no se queda: leerla es lo último que se hace con ella.
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
        assertNull(document.readBytes())
    }

    @Test
    fun `un nombre con diagonal no escribe nada`() {
        assertFails { writeDocumentTempFile("../fuera.cuentivo", byteArrayOf(1)) }
    }

    @Test
    fun `borrar quita la subcarpeta`() {
        val url = writeDocumentTempFile("respaldo.cuentivo", byteArrayOf(1))
        val path = assertNotNull(url.path)

        deleteDocumentTempFile(url)

        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path.substringBeforeLast('/')))
    }
}
