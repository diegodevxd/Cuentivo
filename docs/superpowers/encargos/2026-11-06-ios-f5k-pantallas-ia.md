# Encargo iOS — F5-K: pantallas de CuentivoAI en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-K): ...`, **y cada commit debe compilar en Android**. Reporte y temporales en `build/ios-relevo/`; reporte en `build/ios-relevo/oc-ios-f5k-reporte.md`. **No pidas permisos fuera del repo.** No toques este encargo ni `docs/superpowers/handoffs/`.

**Objetivo:** mover `AiDebugLabScreen` (2271 líneas) y `LedgerReviewScreen` (2083) a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai/`, con captura, voz y recorte tras servicios inyectados, **sin cambiar comportamiento en Android**.

## Por qué así (ya decidido; no lo re-decidas)

- **`AiDebugLabScreen` es la pantalla real de CuentivoAI:** `CuentivoApp.kt:724` la abre en release. **No la renombres.** Su `ocrEngine` ya no existe: quita solo el `import ...capture.AiOcrEngine` sin uso.
- **Mueve, no reescribas.** Ni limpieza, ni renombres, ni comentarios nuevos. Conserva los comentarios existentes aunque suenen a Android, salvo los que acompañan código que se va a la factoría: esos se van con su código.
- **Se quedan en `:app`:** `AiCaptureStore`, `AiCameraCaptureStore`, `LedgerImagePreparer`, `LedgerCropHelper`, `AndroidVoiceRecognizer`, `AiOcrEngine`.
- **Servicios de plataforma por parámetro, sin valor por defecto,** ni `CompositionLocal`. Solo `ledgerReviewStateSaver` y el efecto de primer plano son `expect`, igual que `PlatformBackHandler`.
- `LedgerReviewScreen` llega a `FinancialDao` solo por la lambda `confirmLedgerBatch`: no cambies nada de esa ruta.

## Commit 1: `multiplyExact` igual a Java

`shared/.../ai/ledger/MathExact.kt` deja pasar `multiplyExact(-1L, Long.MIN_VALUE)`, donde `Math.multiplyExact` lanza. Añade como primera línea útil, antes de calcular:

```kotlin
if (a == -1L && b == Long.MIN_VALUE) throw ArithmeticException("long overflow")
```

Prueba en `commonTest` `.../ai/ledger/MathExactTest.kt`: ese caso lanza, y `(Long.MIN_VALUE, -1L)` también; `(3L, 4L)` da `12L`.

## Commit 2: costuras comunes con sus dos implementaciones

La pantalla **sigue en `:app`** al terminar este commit, ya usando las costuras.

**Nuevo `shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai/LedgerCapture.kt`:**

```kotlin
package com.cuentivo.app.ui.ai

/** [path] es un identificador opaco para [LedgerPageFiles]: la pantalla no lo interpreta. */
data class PreparedLedgerPage(val path: String, val mimeType: String)

interface LedgerPagePicker {
    fun pickFromGallery()
    /** Falso si la cámara no pudo abrirse; entonces no llegará ninguna respuesta. */
    fun takePhoto(): Boolean
}

fun interface LedgerPagePickerFactory {
    @Composable
    fun remember(
        onPrepared: (PreparedLedgerPage) -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit,
    ): LedgerPagePicker
}

interface LedgerPageFiles {
    fun readBytes(path: String): ByteArray?
    fun delete(path: String)
    /** Equivale a `captureStore.cleanupAll()` más `cameraStore.deleteStale(except = null)`. */
    fun cleanupAll()
    /** Equivale solo a `cameraStore.deleteStale(except = null)`. */
    fun deleteStaleCaptures()
    fun decodeCrop(path: String, region: LedgerRegion): ImageBitmap?
}

/** Lo que recibe `LedgerReviewScreen` en vez de `java.io.File`. */
data class LedgerCaptureRef(val path: String, val files: LedgerPageFiles)

interface VoicePermissionGate {
    fun isGranted(): Boolean
    fun request()
}

fun interface VoicePermissionGateFactory {
    @Composable
    fun remember(onResult: (Boolean) -> Unit): VoicePermissionGate
}
```

Tres respuestas, no dos, porque hoy cancelar y fallar se comportan distinto: cancelar descarta en silencio; fallar descarta **y** agrega `AiChatMessage.AssistantLedgerFailed`.

**Nuevo `app/src/main/java/com/cuentivo/app/ui/ai/AndroidLedgerCapture.kt`:** `class AndroidLedgerCapture(context: Context) : LedgerPagePickerFactory, LedgerPageFiles`.

- Sus almacenes: `AiCaptureStore(File(context.cacheDir, "ai-capture"))` y `AiCameraCaptureStore(context)`, como en `AiDebugLabScreen.kt:235-238`.
- Dentro de `remember` (usa `import androidx.compose.runtime.remember as composeRemember`), **copiados de `AiDebugLabScreen`:**
  - `CaptureUriSaver` (146) y `var pendingLedgerUri by rememberSaveable(stateSaver = CaptureUriSaver)` (248).
  - El `LaunchedEffect(Unit)` con `cameraStore.deleteStaleExcept(listOf(pendingLedgerUri))`, con su comentario (424-429).
  - `val ledgerCameraLauncher` (696-719) y `val ledgerPickLauncher` (721-756), conservando esos nombres y `Dispatchers.IO`.
- Sustituciones dentro de los lanzadores:
  - `discardLedgerCapture()` en cámara sin éxito y `uri == null` en galería → `onCancelled()`.
  - `discardLedgerCapture()` + `AssistantLedgerFailed` → `onFailed()`.
  - `runLedgerExtraction(file, mimeType)` → `onPrepared(PreparedLedgerPage(file.absolutePath, mimeType))`.
- `takePhoto()`, de las líneas 1468-1479:

```kotlin
cameraStore.deleteStaleExcept(listOf(pendingLedgerUri))
val attempt = runCatching {
    val uri = cameraStore.createCaptureUri()
    pendingLedgerUri = uri
    ledgerCameraLauncher.launch(uri)
}
if (attempt.isFailure) {
    pendingLedgerUri?.let(cameraStore::delete)
    pendingLedgerUri = null
}
return attempt.isSuccess
```

- `pickFromGallery()`: `ledgerPickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))`.
- `readBytes(path)`: `runCatching { File(path).readBytes() }.getOrNull()`. `delete(path)`: `File(path).delete()`.
- `decodeCrop`: `LedgerCropHelper.decodeCrop(File(path), region)?.asImageBitmap()`.

**Nuevo en `:app`:** `class AndroidVoicePermissionGateFactory(context)`, con `audioPermissionLauncher` y `ContextCompat.checkSelfPermission(... RECORD_AUDIO)` de las líneas 518-539. El cuerpo del `granted ->` se queda en la pantalla, como `onResult`.

**Nuevo `shared/src/iosMain/kotlin/com/cuentivo/app/ui/ai/IosLedgerCapture.kt`:** `class IosLedgerCapture(files: AppFiles, pickers: IosImagePickerFactory) : LedgerPagePickerFactory, LedgerPageFiles`.

- Área `FileArea.CACHE`, carpeta `"ledger-capture"`. `path` es el nombre de archivo: `"${newUuid()}.jpg"`. No guardes rutas absolutas: el contenedor de iOS puede cambiar entre lanzamientos.
- `remember`: `LaunchedEffect(Unit) { files.clear(CACHE, "ledger-capture") }` para los huérfanos, porque en iOS el proceso no se recrea con la pantalla abierta. Luego, envuelve el selector.
- `IosImagePickerFactory` hoy calla al cancelar y si `data == null`. Añade en ese archivo una función `internal` nueva que reciba `onPicked` y `onNoImage`, llamado en esos tres puntos (selección vacía o datos nulos en galería, datos nulos en cámara, cancelación de cámara). **`remember` de la factoría pública delega en ella con `onNoImage = {}`,** así que fotos de producto no cambian.
- Preparación, fuera del hilo principal, **siempre redibujada** aunque ya sea pequeña, porque esto normaliza la orientación. No uses `downscaled`: esa regresa la misma imagen si ya cabe.

```kotlin
private fun prepare(source: NSData): ByteArray? = autoreleasepool {
    val image = UIImage(data = source) ?: return@autoreleasepool null
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return@autoreleasepool null
    val factor = min(MAX_EDGE / width, MAX_EDGE / height).coerceAtMost(1.0)
    val format = UIGraphicsImageRendererFormat.defaultFormat()
    format.scale = 1.0
    format.opaque = true
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(width * factor, height * factor), format = format)
    val drawn = renderer.imageWithActions {
        image.drawInRect(CGRectMake(0.0, 0.0, width * factor, height * factor))
    }
    val bytes = UIImageJPEGRepresentation(drawn, 0.85)?.toByteArray() ?: return@autoreleasepool null
    if (AiLedgerImagePolicy.isWithinCallableCap(bytes.size.toLong())) bytes else null
}
private const val MAX_EDGE = 2400.0
```

- Si `writeBytes` falla o `prepare` da null: `onFailed()`. `onNoImage`: `onCancelled()`. Éxito: `onPrepared(PreparedLedgerPage(name, "image/jpeg"))`.
- `cleanupAll()`: `files.clear(CACHE, "ledger-capture")`. `deleteStaleCaptures()`: **no hace nada** (en iOS la cámara no deja archivos propios) con un comentario de una línea que lo diga.
- `decodeCrop`, con `LedgerCropGeometry` (commit 3). `CGImage` se libera en `finally`:

```kotlin
override fun decodeCrop(path: String, region: LedgerRegion): ImageBitmap? = autoreleasepool {
    val bytes = files.readBytes(FileArea.CACHE, DIRECTORY, path) ?: return@autoreleasepool null
    val image = UIImage(data = bytes.toNSData()) ?: return@autoreleasepool null
    val whole = image.CGImage ?: return@autoreleasepool null
    val rect = LedgerCropGeometry.pixelRect(
        region, CGImageGetWidth(whole).toInt(), CGImageGetHeight(whole).toInt(),
    ) ?: return@autoreleasepool null
    val cropped = CGImageCreateWithImageInRect(
        whole,
        CGRectMake(rect.left.toDouble(), rect.top.toDouble(), rect.width.toDouble(), rect.height.toDouble()),
    ) ?: return@autoreleasepool null
    try {
        val png = UIImagePNGRepresentation(UIImage(cGImage = cropped)) ?: return@autoreleasepool null
        runCatching { png.toByteArray().decodeToImageBitmap() }.getOrNull()
    } finally {
        CGImageRelease(cropped)
    }
}
```

  El recorte de iOS no se reduce a `MAX_CROP_EDGE_PIXELS`: la imagen ya viene limitada a 2400. Dilo en el reporte; no agregues más.

**Nuevo `IosVoicePermissionGateFactory` en `iosMain`:** `isGranted() = true`, `request()` vacío, con una línea que explique que `IosVoiceRecognizer` pide el permiso solo y avisa con `PERMISSION_DENIED`.

**`expect` del primer plano:** nuevo `shared/.../platform/PlatformForeground.kt`.

```kotlin
/** Corre [onLeave] cuando la app deja el primer plano. Un diálogo de permiso NO cuenta en iOS. */
@Composable
expect fun PlatformLeaveForegroundEffect(key: Any?, onLeave: () -> Unit)
```

- **Android:** el observador de `AiDebugLabScreen.kt:285-300`, con `ON_PAUSE` y `ON_STOP` tal cual, y `rememberUpdatedState(onLeave)`. Si `androidMain` de `:shared` no ve `LocalLifecycleOwner`, usa la dependencia que ya exista; si agregas una, justifícala en el reporte.
- **iOS:** `NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, ...)` y `removeObserver` en `onDispose`. **Usa `DidEnterBackground`, nunca `WillResignActive`:** esa también se dispara con el diálogo de permiso de voz, y cancelaría la escucha justo al pedir el permiso.

**`expect` del estado guardado:** en `shared/.../ui/ai/LedgerReviewSaver.kt` declara `expect fun ledgerReviewStateSaver(): Saver<LedgerReviewSaver.SavedLedgerReview?, *>`. Android: el cuerpo de `LedgerReviewStateSaver` (`AiDebugLabScreen.kt:162`) en `androidMain`, sobre `LedgerReviewBundle`. iOS: `Saver(save = { "" }, restore = { null })`, con un comentario breve de que en iOS esa revisión se pierde si el sistema mata la app.

**Cambios en `AiDebugLabScreen`:**

- **Parámetros nuevos, requeridos, sin valor por defecto,** después de `onClose`: `ledgerPagePickerFactory: LedgerPagePickerFactory`, `ledgerPageFiles: LedgerPageFiles`, `voiceRecognizerFactory: () -> VoiceRecognizer`, `voicePermissionGateFactory: VoicePermissionGateFactory`.
- **Quita `voiceRecognizer: VoiceRecognizer? = null`.** `val effectiveVoiceRecognizer = remember(voiceRecognizerFactory) { voiceRecognizerFactory() }`. Va como factoría **porque la pantalla destruye el reconocedor** al salir (`DisposableEffect`): recordarlo afuera dejaría uno destruido al reabrirla.
- `val ledgerPagePicker = ledgerPagePickerFactory.remember(onPrepared = { runLedgerExtraction(it.path, it.mimeType) }, onCancelled = { discardLedgerCapture() }, onFailed = { discardLedgerCapture(); model = model.copy(messages = model.messages + AiChatMessage.AssistantLedgerFailed) })`, en el lugar de los lanzadores, **incondicional**: si queda dentro de un `if`, Android pierde el `rememberSaveable`.
- `runLedgerExtraction: (String, String) -> Unit`. `file.delete()` → `ledgerPageFiles.delete(path)`. `ledgerCaptureFilePath = path`. Lectura: `ledgerPageFiles.readBytes(path)`.
- `val ledgerCaptureFile: LedgerCaptureRef? = ledgerCaptureFilePath?.let { LedgerCaptureRef(it, ledgerPageFiles) }`, envuelto en `remember(ledgerCaptureFilePath, ledgerPageFiles)` para que no recargue recortes. `discardLedgerCapture`: `ledgerCaptureFilePath?.let(ledgerPageFiles::delete)`.
- `closeLab`: `ledgerPageFiles.cleanupAll()` (sustituye las líneas 471-472). Línea 1511: `ledgerPageFiles.deleteStaleCaptures()`.
- Botón de cámara:

```kotlin
showLedgerSourceSelector = false
cameraUnavailable = false
if (!ledgerPagePicker.takePhoto()) {
    discardLedgerCapture()
    cameraUnavailable = true
}
```

- Galería: `ledgerPagePicker.pickFromGallery()`.
- Micrófono: `voicePermissionGate.isGranted()` y `voicePermissionGate.request()`. El `granted ->` pasa a `onResult`.
- `LocalLifecycleOwner` y su efecto → `PlatformLeaveForegroundEffect(effectiveVoiceRecognizer) { ... }`, con su comentario.
- `BackHandler(onBack = closeLab)` → `PlatformBackHandler(enabled = true, onBack = closeLab)` (ajusta a la firma real).

**`LedgerReviewScreen`:** `captureFile: File?` → `captureFile: LedgerCaptureRef?` en todos sus niveles, sin renombrar. En 1442-1486: `mutableStateOf<ImageBitmap?>(null)`; `LedgerCropHelper.decodeCrop(captureFile, operation.region)` → `captureFile.files.decodeCrop(captureFile.path, operation.region)`; `bitmap = currentBitmap`.

**Llamadores** (`CuentivoApp.kt:724`, `LedgerLabHostActivity.kt:38`), con `context = LocalContext.current`:

```kotlin
val ledgerCapture = remember(context) { AndroidLedgerCapture(context) }
val voiceFactory = remember(context) { { AndroidVoiceRecognizer(context) } }
val voiceGate = remember(context) { AndroidVoicePermissionGateFactory(context) }
```

**Guardias de `AiDebugLabGuardsTest`:** agrega un ayudante y úsalo en **todas** las lecturas de `AiDebugLabScreen.kt`:

```kotlin
private fun labScreenSource(): String =
    File("src/main/java/com/cuentivo/app/ui/ai/AndroidLedgerCapture.kt").readText() + "\n" +
        File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
```

La factoría va **primero**, porque `cameraBlock` corta de `val ledgerCameraLauncher` hasta el primer `Dialog(`. **Ninguna afirmación se borra, se invierte ni se afloja.** Si una falla porque su texto se fue a otro archivo real, añade ese archivo al ayudante o a esa prueba, y lista el caso en el reporte.

## Commit 3: lo que no compila en común

Todavía en `:app`. Lógica pura en `shared/.../ui/ai/LedgerScreenFormat.kt` y `.../LedgerCropGeometry.kt`, con pruebas en `commonTest`.

| Hoy | Cambia a |
|---|---|
| `java.util.UUID.randomUUID().toString()`, `UUID.randomUUID().toString()` | `newUuid()` |
| `System.currentTimeMillis()` | `nowEpochMillis()` |
| `java.time.LocalDate.now().toEpochDay()` | `todayEpochDay()` |
| `android.util.Base64.encodeToString(bytes, NO_WRAP)` | `encodeLedgerImage(bytes)` |
| `LocalDate.ofEpochDay(x).toString()` | `LocalDate.fromEpochDays(x.toInt()).toString()` (kotlinx; mismo ISO) |
| `String.format("%04d-%02d-%02d", y, m, d)` | `ledgerIsoDate(y, m, d)` |
| 665: `String.format(...)` sobre `localDate` | `LocalDate.fromEpochDays(it.toInt()).toString()` |
| `java.time.LocalDate.of(a, m, d).toEpochDay()` | `LocalDate(a, m, d).toEpochDays().toLong()` |
| `formatCents` con `BigDecimal` | el de abajo |

No uses `.monthNumber`, `.dayOfMonth`, `.day` ni `Instant`: Android e iOS llevan versiones distintas de `kotlinx-datetime`. Si una fecha ya se atrapa con `runCatching` o `catch (_: Exception)`, se queda igual; si encuentras otra forma de atraparla, anótala en el reporte. **`.toEpochDays().toLong()` compila en ambas versiones; no quites el `.toLong()`.**

```kotlin
fun encodeLedgerImage(bytes: ByteArray): String = Base64.Default.encode(bytes)

fun ledgerIsoDate(year: Int, month: Int, day: Int): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

fun formatCents(cents: Long, absolute: Boolean = false): String {
    val digits = cents.toString().removePrefix("-").padStart(3, '0')
    val sign = if (cents < 0 && !absolute) "-" else ""
    return "$" + sign + digits.dropLast(2) + "." + digits.takeLast(2)
}
```

`formatCents` se vuelve público porque `AiSalePreviewContentTest` lo usa desde `:app`. Trabaja con texto, sin `abs`, para que `Long.MIN_VALUE` no se desborde. Pruebas: `0→$0.00`, `5→$0.05`, `-5→$-0.05`, `-5 absoluto→$0.05`, `123456→$1234.56`, `Long.MIN_VALUE absoluto→$92233720368547758.08`, `Long.MIN_VALUE→$-92233720368547758.08`.

`encodeLedgerImage`: `"Man"→"TWFu"`, `"Ma"→"TWE="`, y **120 bytes no contienen `\n`**. Agrega el `@OptIn` solo si el compilador lo pide.

`ledgerIsoDate`: `(2026, 1, 5)→"2026-01-05"`, `(2026, 12, 31)→"2026-12-31"`.

`LedgerCropGeometry`, con la aritmética de `LedgerCropHelper` tal cual, y `LedgerCropHelper` debe llamarla:

```kotlin
data class LedgerCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object LedgerCropGeometry {
    const val MAX_CROP_EDGE_PIXELS = 1600

    fun pixelRect(region: LedgerRegion, imageWidth: Int, imageHeight: Int): LedgerCropRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = (region.x * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val top = (region.y * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val right = ((region.x + region.width) * imageWidth).toInt().coerceIn(left + 1, imageWidth)
        val bottom = ((region.y + region.height) * imageHeight).toInt().coerceIn(top + 1, imageHeight)
        return LedgerCropRect(left, top, right, bottom)
    }
}
```

`LedgerCropHelper.MAX_CROP_EDGE_PIXELS` queda como `= LedgerCropGeometry.MAX_CROP_EDGE_PIXELS`. Pruebas: `(0.1, 0.2, 0.5, 0.5)` en 1000×1000 da `100, 200, 600, 700`; ancho y alto 0 siguen dando 1 píxel; región que se sale se recorta a la imagen; imagen de 0 da null.

## Commit 4: mover las pantallas

- `git mv` de las dos a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai/`, **mismo paquete**.
- `R.string.` → `Res.string.`, `R.plurals.` → `Res.plurals.`, `R.drawable.` → `Res.drawable.`. Imports: `com.cuentivo.shared.resources.*` y `org.jetbrains.compose.resources.{stringResource,pluralStringResource,painterResource}`. Quita los de `androidx.compose.ui.res`.
- `git mv` de `ic_mic.xml`, `ic_receipt_long.xml` e `ic_delete.xml` de `app/src/main/res/drawable/` a `shared/src/commonMain/composeResources/drawable/`, tras confirmar con `grep` en `app/src` que nadie más las usa; si alguien las usa, cópialas.
- `Dispatchers.IO` en común requiere `import kotlinx.coroutines.IO`.
- `internal` se quita **solo** de lo que `:app` o `androidTest` usan de verdad. Mira `LedgerReviewContext`, `parseLedgerDateInput` y `formatCents`. Lístalo.
- `ledgerReviewStateSaver()` sustituye a `LedgerReviewStateSaver`.
- **Guardias:** `labScreenSource()` lee `../shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`, y las referencias a `LedgerReviewScreen.kt` apuntan a `shared`. `AiSalePreviewContentTest` y el KDoc de `LedgerConfirmRefreshDecision.kt` también, si leen la ruta. Lo mismo para las guardias que escanean `mainDir`: ya suman `commonDir`.
- **Si algo sigue sin compilar en común, no le des la vuelta en silencio:** anótalo en el reporte.

## Pruebas: a `commonTest`, para que corran en iOS

Las nuevas van a `shared/src/commonTest/kotlin/com/cuentivo/app/...`. Native rechaza en `commonTest`: comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `java.*`, `runBlocking`. `assertEquals(Int, Long)` no iguala.

**Antes de escribir código iOS:** los nombres de `platform.*` del encargo ya están contrastados contra los bindings, pero **las categorías de Objective-C requieren `import platform.<Framework>.<nombre>`**. `CPointer<ByteVar>` no entra donde se pide `UByteVar`. Dentro de una clase que implementa una factoría, importa `remember as composeRemember`. Windows no compila `iosMain`: revísalo leyendo.

## Control negativo obligatorio, en Windows

Rompe, corre, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. **Si ninguna falla, escribe la prueba que faltaba y dilo.** Restaura tras cada uno.

1. `multiplyExact` sin la línea nueva.
2. `formatCents` con `cents.toString()` sin `removePrefix`.
3. `LedgerCropGeometry`: `coerceIn(left + 1, imageWidth)` → `coerceIn(left, imageWidth)`.
4. `encodeLedgerImage` con `Base64.Mime`.
5. `AndroidLedgerCapture`: quita `deleteStaleExcept(listOf(pendingLedgerUri))` del `LaunchedEffect` **y** de `takePhoto()`. Debe fallar una guardia.
6. En la pantalla ya movida a `shared`, agrega el comentario `// FirebaseCallableAiGateway`. Debe fallar `main ai lab sources never reference Firebase or the debug gateway`: prueba que la guardia ve la nueva ubicación.

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de idioma de `CLAUDE.md`: deben salir vacías. Si salen etiquetas de animación nuevas, añádelas a la exclusión en `CLAUDE.md` y dilo. Las claves deben seguir en **702 + 702**.

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`, y la lista de `git log`.
- **Pruebas leídas de los XML** (`app/build/test-results/testDebugUnitTest`, `shared/build/test-results/testAndroidHostTest`). La base es **1590**, sin contar las nuevas: el total no debe bajar.
- Salida de las tareas, archivos tocados y los **seis controles negativos**.
- **Lista exacta de cada línea de las dos pantallas que no sea solo mover, `R.`→`Res.`, `internal` o `import`.**
- **Cierre visible en iOS:** en iOS no hay botón atrás. Para cada estado que hoy se cierra con `BackHandler` (lectura en curso, selector de fuente, revisión de libreta, chat), di qué control visible lo cierra, con su línea. **Si alguno no tiene, repórtalo y no agregues nada:** eso lo decido yo.
- Qué perdió `internal`, las guardias cuyo archivo cambió, y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que esto funciona en iOS: no puedes saberlo desde Windows.** Las pruebas de `androidTest` que tocan estos archivos no corren aquí: dilo. Nada de esto se prueba contra Firebase real.
