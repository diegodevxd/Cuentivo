# Encargo iOS — F5-D: selector de documentos y XlsxReader

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-D): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5d-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** que importar productos (`.xlsx`/CSV) y elegir archivos de respaldo tengan una versión de iOS, con **el mismo comportamiento en Android**. No muevas otras pantallas ni cambies textos. `DatabaseBackup` y `BackupCrypto` **se quedan en `app`**: usan `javax.crypto` y son otra tanda.

## Por qué así (ya decidido; no lo re-decidas)

**Leer ZIP en iOS.** `ZipInputStream` no existe en Native.
- **Android conserva `ZipInputStream`** tras un `expect`/`actual`. Es una función ya publicada: cero riesgo de regresión.
- **iOS usa un lector puro del directorio central (`ZipCentralDirectory`, en `commonMain`) y `platform.darwin.compression_decode_buffer` con `COMPRESSION_ZLIB`**, que es DEFLATE crudo (RFC 1951), el formato de ZIP. Viene con el sistema.
- **Sin dependencias nuevas.** okio y similares no se justifican para esto. Tampoco escribimos un DEFLATE a mano: es lo más fácil de hacer mal.
- El lector va en `commonMain` y recibe el inflador inyectado para poder **correrlo en Windows contra `java.util.zip.Inflater`** y compararlo con `ZipInputStream`. Eso caza errores sin gastar una vuelta de CI.
- Se lee el **directorio central**, no los encabezados locales: los archivos en streaming traen tamaño 0 en el encabezado local (bit 3).

**Selector de documentos.** `UIDocumentPickerViewController(forExportingURLs...)` necesita **el archivo ya hecho**, mientras que Android elige el destino primero y cifra después. Por eso el destino es un objeto con `write`: Android ya tiene el `Uri`; iOS escribe un temporal, presenta el exportador y suspende hasta que la persona decida. `write` devuelve `false` si canceló (en Android siempre `true`).

**`expect`/`actual` y no fábrica:** mismo patrón que `rememberMotionEnabled`, sin pasar parámetros por `CuentivoApp`. `androidx.activity:activity-compose` entra en `androidMain` de `:shared` porque ahí vive `rememberLauncherForActivityResult`. `:app` ya lo tiene: no agrega nada al APK.

## Tareas

### 1. `ZipCentralDirectory` (`shared/src/commonMain/kotlin/com/cuentivo/app/domain/`)

```kotlin
internal object ZipCentralDirectory {
    fun read(
        bytes: ByteArray,
        wanted: (String) -> Boolean,
        inflateRaw: (compressed: ByteArray, uncompressedSize: Int) -> ByteArray,
    ): Map<String, ByteArray>
}
```

Comportamiento exacto:
| Caso | Resultado |
|---|---|
| No empieza con `PK\x03\x04` y no tiene fin de directorio (EOCD) | `emptyMap()`, igual que `ZipInputStream` hoy |
| Empieza con `PK\x03\x04` y no tiene EOCD (truncado) | lanza |
| Campos ZIP64 (`0xFFFFFFFF`, `0xFFFF`), multidisco, cifrado (bit 0) o método distinto de 0 y 8 | lanza |
| Tamaño declarado mayor que `MAX_ENTRY_BYTES` (64 MiB), o suma de los **pedidos** mayor que `MAX_TOTAL_BYTES` (128 MiB) | lanza **antes** de reservar memoria |
| Entrada cuyo tamaño inflado o CRC-32 no coincide | lanza |
| Nombre que termina en `/` | se omite |
| Nombre repetido | gana el último, como hoy |

**Trampas que deben tener prueba:**
- El inicio de los datos sale del **encabezado local**: `30 + nombre + extra`, con los largos **del encabezado local**. El `extra` local suele medir distinto que el del directorio central.
- Busca el EOCD desde el final, en los últimos `22 + 65535` bytes, y comprueba que la longitud del comentario llegue justo al final.
- Lee enteros de 32 bits como `Long` y comprueba cada límite antes de usarlo. Un `Int` negativo no puede llegar a un índice ni a `ByteArray(n)`.
- Solo infla las entradas que pasan `wanted`. CRC-32 con tabla en Kotlin puro.
- `compressed` vacío con tamaño mayor que 0: lanza sin llamar al inflador. Tamaño 0: arreglo vacío, sin llamarlo, pero comprobando el CRC.

### 2. `expect`/`actual` y `XlsxReader`

- `commonMain/.../domain/ZipEntries.kt`: `internal expect fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray>`.
- `androidMain/.../domain/ZipEntries.android.kt`: el bucle de hoy, con `ZipInputStream` y `closeEntry`; guarda solo si no es directorio y pasa `wanted`. `closeEntry` sigue validando el CRC de las demás.
- `iosMain/.../domain/ZipEntries.ios.kt`: `ZipCentralDirectory.read(bytes, wanted, ::appleInflateRaw)` y `internal fun appleInflateRaw`. Usa `usePinned`, `reinterpret<UByteVar>()`, `convert()` y `scratch_buffer = null`. Lanza si el resultado no es exactamente `uncompressedSize`. **Anclar un arreglo vacío truena** (ver `IosDataBridge`): no llegues ahí con uno vacío.
- Mueve `XlsxReader` a `commonMain` en el mismo paquete. `unzip` se reemplaza por `readZipEntries`, con `wanted` = `SHARED_STRINGS` o `WORKSHEETS*.xml`, y `decodeToString()`. Conserva los comentarios y el resto idéntico.
- En `ProductSheetParser` agrega `fun parseFile(bytes: ByteArray, existingNames: Set<String>): SheetImport`, con la decisión por firma que hoy está en `MainActivity.kt:2385-2393`, incluido su comentario. `MainActivity` la llama. Sale de ahí para que iOS la herede y una prueba la invoque.

### 3. Pruebas

**Fixtures reales.** Script en `shared/src/commonTest/fixtures/generar_xlsx_fixtures.py`, con `date_time` fijo para que sea reproducible, que escribe `shared/src/commonTest/kotlin/com/cuentivo/app/domain/XlsxFixtures.kt` en Base64 (`kotlin.io.encoding.Base64`). **Los XML son los mismos de `ProductSheetImportTest` hoy.** Variantes: normal (DEFLATE), streaming (DEFLATE, bit 3: escribe a un flujo no posicionable), STORED, prefijo `x:`.

- **Mueve `app/src/test/.../domain/ProductSheetImportTest.kt` a `commonTest`** con `kotlin.test`. Conserva las 11 pruebas y su intención; las de xlsx leen los fixtures en lugar de `ZipOutputStream`. Quita comas de los nombres entre backticks.
- **`commonTest`, se ejecutan en los dos lados:**
  - Variante streaming y STORED dan las mismas filas que la normal.
  - Bytes que no son ZIP dan `emptyList()`.
  - Fixture truncado a la mitad de la primera entrada: `assertFails`.
  - Fixture con un byte volteado **dentro de los datos comprimidos**: `assertFails`.
  - `parseFile`: fixture xlsx; CSV; arreglo vacío; y `"PK,precio\nA,1"` (la firma lo manda a xlsx y no hay productos, sin lanzar).
- **`commonTest/.../ZipCentralDirectoryTest.kt`**, con entradas STORED armadas a mano y CRC fijos calculados con Python: `extra` local distinto del central; comentario en el EOCD; cada fila de la tabla de arriba; `wanted` falso **no llama al inflador**; tamaño declarado de 3 GiB lanza antes de llamarlo.
- **`shared/src/androidHostTest/.../ZipCentralDirectoryHostTest.kt`:** para cada fixture y para zips recién hechos con `ZipOutputStream`, `ZipCentralDirectory.read` con `Inflater(true)` debe ser igual a `readZipEntries`. Compara contenido, no solo claves.
- **`iosTest`** (solo de iOS, no se puede correr aquí): `appleInflateRaw` con un DEFLATE crudo conocido, generado con Python y fijo en la prueba; basura lanza; tamaño equivocado lanza.

**Control negativo obligatorio, en Windows.** Rompe temporalmente y **dime en el reporte qué prueba falló, con su nombre**:
1. Calcula el inicio de los datos con el `extra` del directorio central.
2. Quita la comprobación de CRC.
3. Quita el límite previo de tamaño.

Si alguno no hace fallar nada, falta una prueba. Restaura y confirma con `git diff`.

### 4. Selector de documentos

`shared/src/commonMain/kotlin/com/cuentivo/app/platform/DocumentPicker.kt`:

```kotlin
/** Un archivo elegido. En iOS es una copia temporal: se lee una vez y se borra. */
interface PickedDocument {
    /** `null` si no se pudo abrir. */
    suspend fun readBytes(): ByteArray?
}

interface DocumentSaveTarget {
    /** `false` si la persona canceló. Lanza si no se pudo escribir. */
    suspend fun write(bytes: ByteArray): Boolean
}

fun interface DocumentOpener { fun open() }
fun interface DocumentSaver { fun create(suggestedName: String) }

@Composable expect fun rememberDocumentOpener(onOpened: (PickedDocument) -> Unit): DocumentOpener

@Composable expect fun rememberDocumentSaver(
    mimeType: String,
    onTarget: (DocumentSaveTarget) -> Unit,
): DocumentSaver
```
Cancelar no llama a `onOpened` ni a `onTarget`. Pon el porqué de la asimetría en el KDoc.

**Android (`androidMain/.../platform/DocumentPicker.android.kt`):** `OpenDocument` con `arrayOf("*/*")` y `CreateDocument(mimeType)`. `readBytes` y `write` con `contentResolver` dentro de `withContext(Dispatchers.IO)`. `write` lanza con `error("El destino del respaldo no se pudo abrir")` si `openOutputStream` da `null`.

**iOS (`iosMain/.../platform/IosDocumentPicker.kt`):**
- `UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)` y `(forExportingURLs = listOf(url), asCopy = true)`. El selector se cierra solo: **no llames a `dismiss`.**
- **El delegado es referencia débil:** un host `NSObject` con `UIDocumentPickerDelegateProtocol`, en `remember`, con `release()` en `DisposableEffect`, como `IosPickerHost`. Si se libera con un exportador abierto, `release` reanuda la espera con `false`. **Sin campos en el `companion`.**
- `rememberDocumentSaver.create(nombre)` llama a `onTarget` de inmediato. `write`: escribe el temporal en un `Dispatchers.Default`, presenta en `Dispatchers.Main` con `suspendCancellableCoroutine` y borra el temporal en `finally`.
- Funciones `internal` que la UI llama y `iosTest` prueba: `IosPickedDocument(url)`, cuyo `readBytes` devuelve los bytes y borra el archivo, o `null` si no existe; `writeDocumentTempFile(name, bytes): NSURL`, en una subcarpeta única de `NSTemporaryDirectory()` con el nombre exacto, validado con `SafeFileName` (léelo); `deleteDocumentTempFile(url)`, que quita la subcarpeta. **Pruébalas en `iosTest`.**
- `topViewController()` nulo: `open` no hace nada; `write` lanza.
- Reusa `toNSData`/`toByteArray` y copia de `IosAppFiles` el manejo de `NSFileManager`.

Agrega `implementation(libs.androidx.activity.compose)` en un bloque `androidMain.dependencies` de `shared/build.gradle.kts`, con su comentario.

### 5. Usarlo

- **Mueve `app/.../backup/BackupSection.kt` a `shared/src/commonMain/kotlin/com/cuentivo/app/backup/`,** mismo paquete, con `Res.string` y `rememberDocumentOpener`/`rememberDocumentSaver`. Nuevas firmas: `onExport: (DocumentSaveTarget, String) -> Unit`, `onRestore: (PickedDocument, String) -> Unit` y `suggestedFileName: () -> String`. Desaparece `java.time`. **Conserva `testTag`, comentarios y la lógica de contraseña.** `mimeType = "application/octet-stream"`.
- **`MainActivity.kt:2631-2689`:** `suggestedFileName = { DatabaseBackup.suggestedFileName(LocalDate.now().toString()) }`. Exportar: `if (target.write(bytes)) uiMessage = exportDone`. Restaurar: `doc.readBytes() ?: error("El respaldo no se pudo abrir")`. El resto igual.
- **`ProductsScreen.kt` y `CuentivoApp.kt`:** `onImportProducts: (PickedDocument) -> Unit`, y `rememberDocumentOpener(onImportProducts)` en vez del lanzador. Quita los imports de `Uri` que queden sin uso (`ProductsScreen` y `CuentivoApp` pueden seguir usándolo por otra razón: revísalo). Conserva el comentario sobre el MIME abierto, ahora en el `actual` de Android.
- **`MainActivity.kt:2367-2393`:** `doc.readBytes() ?: error("No se pudo abrir el archivo")` y `ProductSheetParser.parseFile(bytes, existingNames)`. Quita los imports sin uso (`XlsxReader`).

## Fuente verificable de los símbolos

**No accedas a `C:/tmp/kn-prebuilt`.** Ya contrasté en `C:/tmp/ios-relevo/simbolos-f5d.txt`: `forOpeningContentTypes`, `forExportingURLs`, `asCopy`, `didPickDocumentsAtURLs`, `documentPickerWasCancelled`, `compression_decode_buffer` con `dst_buffer`, `dst_size`, `src_buffer`, `src_size`, `scratch_buffer`, `COMPRESSION_ZLIB`, `UTTypeItem`. **Si necesitas otro que no aparezca ahí ni en código que ya compila en `iosMain`, no lo adivines: anótalo en el reporte** y yo lo contrasto.

**Lo que Native rechaza en `commonTest`/`iosTest`:** comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`. `commonMain` **no nombra relojes** y no usa `Charsets`, `java.*` ni `String.format`.

## Puerta

Sin emulador ni pruebas instrumentadas: la PC se queda sin memoria. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`: como `BackupSection` entra a `shared/.../backup`, **lee si la primera puerta cubre esa ruta; si no, dímelo**, no la amplíes.

## Reporte

- Conteos reales de los XML. La base es 872 + 655 = 1527: explica el movimiento (11 pasan a `shared`) y cuántas son nuevas.
- Salida de las tareas, archivos tocados y resultado de los **tres controles negativos**, con el nombre de la prueba que falló.
- Símbolos que no pudiste contrastar, y cualquier cosa distinta a este encargo y por qué.

**No digas que iOS compila ni que el selector funciona: no puedes saberlo desde Windows.**
