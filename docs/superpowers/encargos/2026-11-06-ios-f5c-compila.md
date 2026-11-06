# Encargo iOS — F5-C: que compile en iOS

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-C): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5c2-reporte.md`.

**Objetivo:** corregir los 44 errores de `:shared:compileKotlinIosSimulatorArm64` (log: `C:/tmp/ios-relevo/log-f5c-1.txt`; búscalos con `grep -E '^.*e: file:'`). Solo eso: sin mover pantallas ni cambiar comportamiento en Android ni textos.

## Por qué así (ya decidido; no lo re-decidas)

- **Android compila contra `kotlinx-datetime` 0.6.2 e iOS contra 0.7.1** (lo medí con `dependencyInsight`; la 0.7.1 llega por resolución de conflictos). En 0.7.1 `kotlinx.datetime.Clock` ya no da `Clock.System`, y `kotlin.time.Clock` no sirve en Android con 0.6.2, porque su `Instant` es otro tipo. **Por eso `commonMain` no debe nombrar ningún reloj.** No cambies versiones de dependencias: anótalo en el reporte y sigue.
- **`LinkedHashMap` es final en Native y no tiene el constructor de orden de acceso.** La recencia se hace a mano: sacar y volver a meter.
- **Windows no compila iOS**, y cada vuelta de CI cuesta de 7 a 15 minutos. Por eso los símbolos se contrastan antes contra las librerías reales (abajo), no a prueba y error.

## Fuente verificable de los símbolos

**No accedas a `C:/tmp/kn-prebuilt`.** El contraste ya está hecho contra las librerías que usa el runner (Kotlin 2.2.10), en `C:/tmp/ios-relevo/simbolos-f5c.txt`: cada import de iOS y los selectores clave. `OK` = está donde se importa; `NO` = indica dónde está de verdad. Los `NO` de `platform.CoreCrypto` son falso negativo: ese código ya compilaba. **Si necesitas un símbolo que no aparece ahí, no lo adivines: anótalo en el reporte** y yo lo contrasto.

## Tareas

**1. `BoundedImageCache` (`shared/src/commonMain/.../data/local/BoundedImageCache.kt`).** Campo `private val entries = LinkedHashMap<String, V>()` (orden de inserción), misma API pública, misma semántica:
- `get`: si existe, `remove` y vuelve a insertar; devuelve el valor. Debe refrescar recencia.
- **`put` sobre una clave existente también debe refrescar recencia.** `LinkedHashMap` por inserción conserva la posición vieja al reemplazar, así que hay que quitar primero (y restar sus bytes). Esta es la trampa: el código de hoy lo hace gratis por el orden de acceso.
- `V` puede ser cualquier tipo no nulo en el uso real; no añadas lógica para valores nulos.

Mueve `class BoundedImageCacheTest` de `app/src/test/.../data/local/ProductImageStoreTest.kt` a `shared/src/commonTest/kotlin/com/cuentivo/app/data/local/BoundedImageCacheTest.kt` con `kotlin.test`. No toques `ProductImageSamplingTest`. Las 5 pruebas se conservan tal cual; añade una sexta: reemplazar una clave la vuelve la más reciente (`put a`, `put b`, `put a` otra vez, `put c` que obliga a desalojar → sale `b`). **Comprueba que esa prueba falla sin el `remove` de `put`** y dilo en el reporte.

**2. Reloj.**
- `PlannedReminderScheduler.kt`: `now` pasa a ser obligatorio, sin valor por defecto; quita `import kotlinx.datetime.Clock`, y también `toLocalDateTime` y `TimeZone` si quedan sin uso. Las pruebas ya pasan su reloj: si alguna dependía del valor por defecto, dale uno fijo.
- Crea en `shared/src/iosMain/kotlin/com/cuentivo/app/platform/IosClock.kt`, con `@OptIn(kotlin.time.ExperimentalTime::class)` y `kotlin.time.Clock`: `internal fun iosNowEpochMillis(): Long` y `fun iosNowLocalDateTime(): LocalDateTime` (`toLocalDateTime(TimeZone.currentSystemDefault())`). Úsalas en `IosProductPhotoStore.kt:38` e `IosSharer.kt:60`, quitando el import viejo. `iosNowLocalDateTime` queda para quien construya el programador en iOS.

**3. Imports y llamadas de iOS** (ya contrastado contra las librerías salvo lo marcado):
| Archivo | Arreglo |
|---|---|
| `IosVoiceRecognizer.kt:182` | `domain = error.domain ?: ""`. Revisa que `mapIosSpeechError` caiga en su rama genérica con `""`. |
| `IosDataBridge.kt:32` | `import platform.Foundation.create` |
| `IosAppFiles.kt:44` | `writeToFile` es de `NSData` y falla en cascada por lo anterior; si sigue, contrástalo. `:104`: añade `@OptIn(ExperimentalForeignApi::class)` donde falte. |
| `IosImagePickerFactory.kt` | El método `remember` de la clase tapa al de Compose: `import androidx.compose.runtime.remember as composeRemember` y úsalo en las líneas 50 y 53. Etiqueta los lambdas: `pickerHost.onImage = onImage@{ data -> ... }` (líneas 75 y 97). |
| `IosLocalReminderCenter.kt:48-50` | `components.setYear(...)`, `setMonth`, `setDay`, `setHour`. Línea 53: `triggerWithDateMatchingComponents(dateComponents = components, repeats = false)`. |
| `IosSharer.kt:86-89` | **El constructor está bien, no lo toques.** Faltan imports de categorías: `platform.UIKit.popoverPresentationController` y `platform.Foundation.setValue` (no contrastado: busca `setValue:forKey:`). |
| `IosStatementImageRenderer.kt` | Cambia los imports de `platform.Foundation` a `platform.UIKit`: `NSFontAttributeName`, `NSForegroundColorAttributeName`, `NSStringDrawingUsesLineFragmentOrigin`. Añade `import platform.UIKit.boundingRectWithSize` y `import platform.UIKit.drawWithRect`. Si `value as NSString` no resuelve el receptor, usa `NSString.create(string = value)` con su import. |
| `IosTopViewController.kt:21` | `import platform.UIKit.UISceneActivationStateForegroundActive` y compara con la constante. Quita el import de `UISceneActivationState` si queda sin uso. |

Revisa en cada archivo si otro símbolo de categoría del mismo tipo fallará al pasar esta capa, porque el compilador puede estar ocultándolo. **Lee `shared/src/iosTest` buscando lo que Native rechaza:** comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`.

## Puerta

Sin emulador ni pruebas instrumentadas: la PC se queda sin memoria. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:
```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug
```
`compileCommonMainKotlinMetadata` compila `commonMain` contra la 0.7.1, así que puede cazar en Windows los errores de esta clase. Si esa tarea no existe o no corre en Windows, dilo en el reporte y corre el resto.

## Reporte

Conteos reales de los XML (el total esperado es 1526 + 1, más cualquier movimiento explicado), tareas corridas y salida, archivos tocados, símbolos que no pudiste contrastar, y cualquier cosa que hayas hecho distinto a este encargo y por qué. **No digas que iOS compila: no puedes saberlo desde Windows.**
