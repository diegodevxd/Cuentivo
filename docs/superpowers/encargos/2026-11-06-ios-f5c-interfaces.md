# Encargo iOS — F5-C: interfaces de plataforma

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-C): ...`, uno por tarea, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/oc-ios-f5c-reporte.md`.

**Objetivo:** archivos, compartir, fotos, recordatorios y voz tienen un contrato común en `:shared` y una implementación de iOS que pasa por CI. Las pantallas se mueven en otro encargo.

**Fuera de este encargo:** mover pantallas, `MainActivity.onCreate`, `CuentivoApp.kt`, `AccountSheet`, `BackupSection`, `XlsxReader`, StoreKit y los textos `subscription_*`. Mover ≠ reescribir: en Android no cambia ningún comportamiento ni ningún texto.

## Por qué así (ya decidido; no lo re-decidas)

- **Toda la lógica pura va en `commonMain`, para que corra en Android y en el simulador.** Lo de `iosMain` es un adaptador delgado. `:iosKit` no tiene pruebas y lleva Firebase; todo esto va en `:shared`, y nada de aquí depende de Firebase.
- **Sin emulador.** La puerta local no abre emulador ni corre pruebas instrumentadas: la PC se queda sin memoria. Por eso `ProductPhoto`, `AiDebugLabScreen` y `CuentivoApp` **no se migran todavía**: sus flujos de cámara solo se prueban en un dispositivo. Se migran junto con su pantalla, cuando se pueda correr `ProductPhotoSourceTest`.
- **En iOS no hay trabajo diario fiable.** `BGTaskScheduler` no garantiza hora, así que el recordatorio no puede funcionar como el `Worker` de Android. Se dejan programadas notificaciones de los próximos 7 días con la política real, y se reprograman cada vez que `sync()` corre.
- **El reintento de voz de Android no se copia.** En iOS, `supportsOnDeviceRecognition` lo dice de antemano: se pide on-device solo si está soportado.
- **En iOS no hay Google Play.** Decirle "Gratis en Google Play" a un cliente desde la app de iOS es falso y motivo de rechazo. El enlace de App Store no existe hasta el Día de pago, así que por ahora iOS firma sin enlace.

## Tareas

**C0. Dependencias.** En `:shared`, declara `kotlinx-coroutines-core` en `commonMain` y `kotlinx-coroutines-test` en `commonTest`, en `gradle/libs.versions.toml`. Usa exactamente la versión que ya resuelve el grafo: revísala con `:shared:dependencyInsight --dependency kotlinx-coroutines-core --configuration androidMainImplementationDependenciesMetadata` (si esa configuración no existe, lista las disponibles con `:shared:dependencies`). No subas versiones. Anota cuál fue.

**C1. `AppFiles`.** En `shared/src/commonMain/kotlin/com/cuentivo/app/platform/AppFiles.kt`:
```kotlin
enum class FileArea { CACHE, PERSISTENT }

/** Archivos propios de la app. `directory` y `name` son un solo segmento: nunca una ruta. */
interface AppFiles {
    fun readBytes(area: FileArea, directory: String, name: String): ByteArray?
    fun writeBytes(area: FileArea, directory: String, name: String, bytes: ByteArray): Boolean
    fun delete(area: FileArea, directory: String, name: String): Boolean
    fun clear(area: FileArea, directory: String)
    fun list(area: FileArea, directory: String): List<String>
    fun absolutePath(area: FileArea, directory: String, name: String): String?
}

object SafeFileName { fun isValid(segment: String): Boolean }
```
`isValid` rechaza vacío o en blanco, `/`, `\`, `.`, `..` y NUL. Cualquier método con un segmento inválido devuelve `null`, `false` o vacío, **sin tocar el disco**: un nombre puede venir de un documento remoto.
- Android: `FileAppFiles(cacheRoot: File, filesRoot: File)` en `shared/src/androidMain`. No se conecta a nada todavía.
- iOS: `IosAppFiles(cacheRoot: String, filesRoot: String)` en `shared/src/iosMain` con `NSFileManager`, y `fun defaultIosAppFiles()` con `NSCachesDirectory` y `NSApplicationSupportDirectory` (créalo si falta). **`usePinned` + `addressOf(0)` truena con un arreglo vacío:** contempla cero bytes en los dos sentidos.
- Prueba de contrato: `abstract class AppFilesContractTest` en `commonTest` con `abstract fun newFiles(): AppFiles`, y una subclase en `androidHostTest` y otra en `iosTest`, cada una sobre un directorio temporal nuevo. Casos: escribir y leer, cero bytes, sobrescribir, borrar, `clear` no toca otro directorio, las áreas no se mezclan, `list`, leer lo que no existe y cada segmento inválido. **Con un segmento inválido comprueba además que no se creó nada fuera.**

**C2. `Sharer`, imagen del estado de cuenta y pie de tienda.**
1. `expect object StoreListing { val footerLine: String; val url: String? }` en `com.cuentivo.app.platform`. Android: `"Enviado con Cuentivo · Gratis en Google Play"` y la URL actual de Play. iOS: `"Enviado con Cuentivo"` y `null`.
   `DebtorShareText.footer` los usa, sin línea de URL si es `null`. **El texto en Android debe quedar byte a byte igual.**
   Las pruebas de `commonTest` que fijan ese pie no se debilitan: la comparación con el literal pasa a `androidHostTest`, y en `iosTest` una prueba afirma que el pie **no** contiene `Google` ni `play.google`.
2. Extrae a `commonMain`, en `com.cuentivo.app.ui.share`, lo que hoy decide `buildPieces`: `StatementImageLayout` con `WIDTH_PX`, los márgenes y tamaños, `StatementImagePalette`, y `pieces(content, palette): List<StatementImagePiece>` (`Gap(height)`, `Divider(color)`, `Text(value, size, color, bold)`). `STAMP_TEXT` sale de `StoreListing.footerLine`.
   `StatementImageRenderer` de Android sigue en `app` con el mismo `render` y solo mide y pinta esas piezas: **misma imagen, mismos números**. Mantén `WIDTH_PX` accesible para `StatementImageRendererTest` y confirma que esa prueba instrumentada aún compila. Prueba en `commonTest`: orden de piezas, el sello solo con `showsStamp`, `moreLine` y `balanceText` opcionales.
3. En `com.cuentivo.app.platform`:
```kotlin
interface Sharer {
    fun shareText(subject: String, body: String)
    fun shareStatementImage(subject: String, shortText: String, fullText: String, content: StatementImageContent)
    fun dial(phone: String)
    fun openUrl(url: String)
}
```
   Y dos piezas puras con prueba en `commonTest`:
   - `StatementShareFlow.share(subject, shortText, fullText, shareImage: (String) -> Boolean, shareText: (String) -> Unit)`: las dos ramas pasan por `StatementShareTextChoice.choose`, igual que hoy. Si `shareImage` devuelve `false` o lanza, se comparte el texto completo. **`CancellationException` se relanza.**
   - `TelUri.from(phone): String?`: conserva dígitos y un `+` inicial; `null` si no queda ningún dígito.
4. Android: `AndroidSharer(activity, statementStore, requestCode)` en `app`, con el mismo código que hoy tienen `share`, `shareStatementImage` y `dial`. Conserva `ACTION_DIAL` tal cual, sin `TelUri`. `MainActivity` usa `sharer` en sus llamadas y `onActivityResult` sigue limpiando el PNG. `openUrl` usa `ACTION_VIEW`.
5. iOS: `IosSharer(files: AppFiles)` en `shared/src/iosMain/.../platform/`, más `IosStatementImageRenderer` que pinta las mismas piezas:
   - `UIGraphicsImageRenderer` con **`scale = 1`** y `opaque = true`, fondo sólido (WhatsApp pinta de negro lo transparente), ancho `WIDTH_PX`. Mide con `boundingRectWithSize` (`NSStringDrawingUsesLineFragmentOrigin`) y pinta el mismo rectángulo, para que la altura no discrepe.
   - Presenta `UIActivityViewController` sobre el controlador superior: escena `foregroundActive` → `keyWindow` → sube por `presentedViewController`. **En iPad, sin `popoverPresentationController.sourceView` y `sourceRect` la app truena.** Si no hay ventana, no comparte nada y no truena.
   - Imagen: PNG temporal en `CACHE`, directorio `statement-share`. Se borra en `completionWithItemsHandler`, y `clear` antes de escribir otro. El asunto va con `setValue(subject, forKey = "subject")`.
   - `dial`: `TelUri.from` y luego `openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)`. Si es `null`, no hace nada. **`NSURL.URLWithString` con espacios devuelve `null`: no fuerces `!!`.**
   - Prueba en `iosTest`: el renderizador da un PNG de ancho 1080, alto positivo y sin alfa. Sin prueba de presentación; no hay ventana en el simulador de pruebas.

**C3. Selector de fotos y almacén de fotos.** En `com.cuentivo.app.platform`:
```kotlin
expect class PickedImage   // Android: actual typealias = android.net.Uri. iOS: actual typealias = NSData. Mismo patrón que PlatformAuthHost.

interface ImagePicker {
    fun pickFromGallery()
    /** `false` si este dispositivo no puede tomar fotos; quien llama muestra su aviso. */
    fun takePhoto(): Boolean
}

/** El selector corre `onPicked` en su propio scope y limpia lo temporal en `finally`. Cancelar no llama a `onPicked`. */
fun interface ImagePickerFactory {
    @Composable fun remember(onPicked: suspend (PickedImage) -> Unit): ImagePicker
}

interface ProductPhotoStore {
    fun save(source: PickedImage, productId: String): String?
    fun loadBitmap(fileName: String): ImageBitmap?
    fun delete(fileName: String)
}
```
- `BoundedImageCache` pasa a `commonMain` con `git mv` y paquete intacto.
- Android: `ProductImageStore : ProductPhotoStore`; `loadBitmap` es `load(fileName)?.asImageBitmap()`. **Nada más cambia.** `ImagePickerFactory` no se implementa en Android en este encargo.
- iOS:
  - `IosProductPhotoStore(files, now: () -> Long)` sobre `AppFiles`, área `PERSISTENT`, directorio `product-images`, nombre `"$productId-$now.jpg"`. Aristas: máximo 1024 al guardar y 512 al cargar, redibujando con `UIGraphicsImageRenderer` (respeta la orientación). JPEG 0.85. Caché `BoundedImageCache` de 6 MB, con `sizeOf = width * height * 4`. Decodifica con `decodeToImageBitmap` de Compose Resources. `save` y `loadBitmap` devuelven `null` ante cualquier fallo o nombre inválido, sin tronar. Envuelve el trabajo con `autoreleasepool`. Un `productId` inválido devuelve `null`.
  - `IosImagePickerFactory` con `PHPickerViewController` (`selectionLimit = 1`, solo imágenes, `loadDataRepresentationForTypeIdentifier("public.image")`) y `UIImagePickerController` para cámara. `takePhoto` devuelve `false` si `isSourceTypeAvailable` dice que no. **Los delegados son referencias débiles:** guárdalos en un campo fuerte hasta terminar o el recolector los borra y no llega el resultado. Vuelve al hilo principal antes de tocar UI.
- Prueba en `iosTest` de `IosProductPhotoStore`: genera una imagen de 2000×1000 con `UIGraphicsImageRenderer`, guarda, comprueba que el archivo existe y que `loadBitmap` da un ancho ≤ 512, que `delete` lo quita, y que un nombre con `../` da `null`.

**C4. Recordatorios.**
- `git mv` a `commonMain`, paquete `com.cuentivo.app.settings`: `ReminderPreferenceStore` y `CollectionReminderPreferences`, sin el constructor de `Context`. `SharedPreferencesReminderStore` se queda en `app`, y junto a él una función `fun CollectionReminderPreferences(context: Context) = CollectionReminderPreferences(SharedPreferencesReminderStore(context))` para que **ninguna llamada de Android cambie**. `CollectionReminderPreferencesTest` pasa a `commonTest` si no usa `Context`.
- `interface ReminderScheduler { fun sync(); fun cancel() }` en `platform`. `CollectionReminderScheduler` la implementa con `override`. Nada más cambia en Android.
- `ReminderPlanner.plan(dueSales, now: LocalDateTime, hourOfDay, days = 7): List<PlannedReminder>` en `commonMain`, con `epochDay`, `hourOfDay` y `OverdueReminder`. Llama a `OverdueReminderPolicy.summarize` por cada día, la real y no una copia. Hoy cuenta solo si la hora no ha pasado: **misma regla que `nextDelayMillis`**, a las 09:00 exactas sí entra y un instante después no. Si `summarize` da `null`, ese día no lleva recordatorio. Usa `validatedHour`.
- `PlannedReminderScheduler(preferences, center: LocalReminderCenter, loadDueSales: suspend () -> List<DueSale>?, now, texts: suspend (OverdueReminder) -> ReminderText, scope) : ReminderScheduler`, también común. `LocalReminderCenter` tiene `suspend fun replaceAll(reminders)` y `fun removeAll()`.
  **`sync` y `cancel` no se pueden pisar.** Una `sync` lenta que termina después de un `cancel` le recordaría al siguiente dueño las deudas del anterior. Usa un contador atómico de generación, incrementado en cada llamada, y un `Mutex` adquirido con `start = UNDISPATCHED`. Una `sync` vuelve a comparar la generación **justo antes** de `replaceAll` y, si ya no es la última, no escribe. `cancel` siempre llama a `removeAll`. Si `sync` falla, atrápalo y deja lo programado (`CancellationException` se relanza), igual que el `Worker`, que nunca reintenta en bucle. `enabled == false` o `loadDueSales == null` llaman a `removeAll`.
  Pruebas con `runTest` y un centro falso: desactivado, sin sesión, reemplazo, **`cancel` durante una `sync` suspendida no deja nada programado**, un error no borra lo anterior.
- iOS: `IosLocalReminderCenter` con `UNUserNotificationCenter`, identificadores `cuentivo-overdue-<epochDay>`, `UNCalendarNotificationTrigger` con año, mes, día y hora sin repetir. `removeAll` borra pendientes y entregadas. `suspend fun requestPermission(): Boolean` queda ahí; lo llamará la raíz. `NsUserDefaultsReminderStore` con los mismos valores por defecto, y una función que da `texts` con `getString(Res.string.reminder_notification_*)` y `Money.format`.
  **No crees `UNUserNotificationCenter.currentNotificationCenter()` en una prueba:** sin bundle truena. Por eso la lógica está en común.

**C5. Voz.**
- A `commonMain`, paquete `com.cuentivo.app.ai`: `VoiceCapturePolicy.kt` completo con `git mv` y la interfaz `VoiceRecognizer`. `AndroidVoiceRecognizer` y sus funciones de códigos de error se quedan en `app` sin cambios. `VoiceRecognizerTest` no se debilita.
- iOS: `IosVoiceRecognizer` en `shared/src/iosMain/.../ai/`, `SFSpeechRecognizer` con `es-MX`, `SFSpeechAudioBufferRecognitionRequest`, `shouldReportPartialResults = true`, `AVAudioEngine`.
  - `requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition`. **Nunca se guarda ni se sube audio.**
  - `startListening` pide primero `SFSpeechRecognizer.requestAuthorization` y `AVAudioSession.requestRecordPermission`. Si niegan, `onError(PERMISSION_DENIED)`. Sin reconocedor o con `isAvailable == false`, `NO_RECOGNIZER`.
  - **Contador de sesión igual que Android:** un callback de una sesión vieja se ignora. Los callbacks llegan en otra cola: pásalos al hilo principal antes de entregar.
  - **`outputFormatForBus(0)` con `sampleRate == 0` hace que `installTapOnBus` lance un `NSException`, que mata el proceso.** Pasa en el simulador sin micrófono. Compruébalo antes y da `NO_RECOGNIZER`.
  - `cancel` y `destroy`: `removeTapOnBus(0)`, `stop`, `endAudio`, `recognitionTask.cancel()`, desactiva la sesión de audio. Son idempotentes.
  - `onRmsChanged`: calcula el RMS de `floatChannelData` y conviértelo a la escala de Android (-2 a 10 dB) que espera `normalizeRmsDb`, con una función pura `rmsToAndroidScale` en `commonMain` y prueba.
  - Errores con función pura `mapIosSpeechError(domain, code)` y prueba: `kAFAssistantErrorDomain` 1110 da `NO_SPEECH`, 1700 da `PERMISSION_DENIED`, y lo demás `RECOGNIZER_ERROR`.
- `iosApp/iosApp/Info.plist`: agrega `NSSpeechRecognitionUsageDescription` y `NSMicrophoneUsageDescription` en español, y cambia `NSCameraUsageDescription` y `NSPhotoLibraryUsageDescription` para que mencionen la libreta **y** los productos: Apple revisa que el motivo sea cierto.

## Puerta final (entorno Norton de `CLAUDE.md`)

```powershell
.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug assembleRelease lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```
**Sin `--rerun-tasks`, sin emulador y sin pruebas instrumentadas.** iOS no compila en Windows: todo `iosMain` e `iosTest` queda "pendiente de CI". **Dilo así, y no digas que funciona.** Escríbelo con cuidado: cada error se descubre 10 minutos después. Comprueba firmas de interop con los ejemplos de `shared/src/iosMain` e `iosKit` antes de inventarlas. En `iosTest` y `commonTest` valen las reglas de Native: sin comas ni `. ; : / \ < > [ ]` en nombres entre backticks, sin `@Test(expected=)`, `org.junit`, `String.format` ni `measureTimeMillis`.

Anota en el reporte: pruebas leídas de los XML (hoy 883 + 603; solo pueden bajar si explicas cuál y por qué), `git diff --stat HEAD~N -- app/schemas` (vacío), la paridad de strings, las dos puertas de i18n de `CLAUDE.md` y `grep -rn "R\.string\|R\.plurals\|import android\." shared/src/commonMain` (vacío). Si un símbolo faltó al enlazar es asunto de CI: no toques `project.pbxproj`.

Las pruebas deben llamar al código real y fallar si se rompe: ver `.claude/agents/test-honesty-reviewer.md`. Sin `Double` ni `Float` para dinero (`onRmsChanged` es volumen de audio, no dinero). Sin nombres, teléfonos ni montos en logs.

Si algo de esto no coincide con el código real, para y repórtalo. **Nunca abras ni imprimas `google-services.json`, `GoogleService-Info.plist` ni llaves.**
