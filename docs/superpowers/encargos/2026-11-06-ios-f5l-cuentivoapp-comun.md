# Encargo iOS — F5-L: `CuentivoApp` en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-L): ...`, **y cada commit debe compilar en Android**. Temporales en `build/ios-relevo/`; reporte en `build/ios-relevo/f5l-reporte.md`. **No pidas permisos fuera del repo.**

**Objetivo:** mover `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt` a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/CuentivoApp.kt`, **sin cambiar comportamiento en Android**.

## Por qué así (ya decidido; no lo re-decidas)

- **Mueve, no reescribas.** Ni limpieza, ni renombres, ni comentarios nuevos. El paquete sigue siendo `com.cuentivo.app.ui`.
- **Los servicios de plataforma entran por el parámetro `platform`, sin valor por defecto**, ni `CompositionLocal`, ni `expect`. La excepción son los efectos composables de `platform/`, que ya siguen ese patrón.
- `MainActivity` queda fuera, salvo agregar `platform = ...` a su llamada (`MainActivity.kt:1738`).

## Commit 1: `PlatformResumeEffect`

En `shared/src/commonMain/kotlin/com/cuentivo/app/platform/PlatformForeground.kt`:

```kotlin
/** Corre [onResume] al entrar en composición y cada vez que la app vuelve al primer plano. */
@Composable
expect fun PlatformResumeEffect(key: Any?, onResume: () -> Unit)
```

En `shared/src/androidMain/.../platform/PlatformForeground.android.kt`, el mismo observador que hoy está en `CuentivoApp.kt:418-429`. `addObserver` entrega `ON_RESUME` al registrarse si la pantalla ya está activa, y de ahí sale la llamada inicial:

```kotlin
@Composable
actual fun PlatformResumeEffect(key: Any?, onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResume = rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner, key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnResume.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
```

En `shared/src/iosMain/.../platform/PlatformForeground.ios.kt`. `WillEnterForeground` no se dispara en el arranque en frío, así que la llamada inicial no se duplica. **Agrega el import** `platform.UIKit.UIApplicationWillEnterForegroundNotification`:

```kotlin
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformResumeEffect(key: Any?, onResume: () -> Unit) {
    val currentOnResume = rememberUpdatedState(onResume)
    DisposableEffect(key) {
        currentOnResume.value()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            currentOnResume.value()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
}
```

## Commit 2: entrada de idioma, con prueba

Nuevo `shared/src/commonMain/kotlin/com/cuentivo/app/ui/LanguageEntry.kt`:

```kotlin
package com.cuentivo.app.ui

import com.cuentivo.app.platform.LanguageControl

/** iOS cambia el idioma en Ajustes; Android abre su hoja. */
internal fun openLanguageEntry(language: LanguageControl, showSheet: () -> Unit) {
    if (language.opensSystemSettings) language.openSystemSettings() else showSheet()
}
```

Nuevo `shared/src/commonTest/kotlin/com/cuentivo/app/ui/LanguageEntryTest.kt`, con un `LanguageControl` falso y dos pruebas: si `opensSystemSettings` es `true`, abre Ajustes y no la hoja; si es `false`, abre la hoja y no Ajustes. **Nombres entre backticks sin comas ni `. ; : / \ < > [ ] ( )`.**

Todavía no conectes nada en `CuentivoApp`.

## Commit 3: `CuentivoAppPlatform`, aún en `:app`

Nuevo `shared/src/commonMain/kotlin/com/cuentivo/app/ui/CuentivoAppPlatform.kt`, paquete `com.cuentivo.app.ui`. Los tipos ya existen en común; sus imports son los que usa hoy `CuentivoApp.kt` más `com.cuentivo.app.platform.*` que mida `grep`:

```kotlin
class CuentivoAppPlatform(
    val photoStore: ProductPhotoStore,
    val imagePickers: ImagePickerFactory,
    val language: LanguageControl,
    val openUrl: (String) -> Unit,
    val aiLabTransport: AiDebugTransport,
    /** Se llama sólo dentro de la rama que abre el laboratorio: ahí se crean y recuerdan. */
    val rememberAiLabServices: @Composable () -> AiLabServices,
)

class AiLabServices(
    val ledgerPagePickerFactory: LedgerPagePickerFactory,
    val ledgerPageFiles: LedgerPageFiles,
    val voiceRecognizerFactory: () -> VoiceRecognizer,
    val voicePermissionGateFactory: VoicePermissionGateFactory,
)
```

Nuevo `app/src/main/java/com/cuentivo/app/ui/AndroidCuentivoAppPlatform.kt`, paquete `com.cuentivo.app.ui`:

```kotlin
@Composable
fun rememberAndroidCuentivoAppPlatform(): CuentivoAppPlatform {
    val context = LocalContext.current
    return remember(context) {
        CuentivoAppPlatform(
            photoStore = ProductImageStore(context),
            imagePickers = AndroidImagePickerFactory,
            language = AndroidLanguageControl(context),
            openUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            aiLabTransport = debugAiTransport(),
            rememberAiLabServices = {
                val current = LocalContext.current
                remember(current) {
                    val ledgerCapture = AndroidLedgerCapture(current)
                    AiLabServices(
                        ledgerPagePickerFactory = ledgerCapture,
                        ledgerPageFiles = ledgerCapture,
                        voiceRecognizerFactory = { AndroidVoiceRecognizer(current) },
                        voicePermissionGateFactory = AndroidVoicePermissionGateFactory(current),
                    )
                }
            },
        )
    }
}
```

En `CuentivoApp.kt`, agrega `platform: CuentivoAppPlatform,` como **primer parámetro**, y cambia exactamente esto. Líneas medidas en `9ef8766`:

| Línea | Hoy | Queda |
|---|---|---|
| 331-332 | `context` y `photoStore = remember(context) {...}` | `val photoStore = platform.photoStore`. Conserva el comentario de la línea 330. |
| 353 | `remember { debugAiTransport() }` | `val debugAiLabTransport = platform.aiLabTransport` **con ese nombre**: una guardia lo busca |
| 382 | `val lifecycleOwner = ...` | se borra |
| 418-429 | `DisposableEffect` con observador | `PlatformResumeEffect(key = tier to mexalmLedgerWarmGateway) { refreshAiAvailability(); warmMexalmLedger() }` |
| 477 | `BackHandler(enabled = showNewSale) {...}` | `PlatformBackHandler(enabled = showNewSale) {...}`, conserva el cuerpo y el comentario |
| 554 | `onOpenLanguage = { showLanguage = true }` | `onOpenLanguage = { openLanguageEntry(platform.language) { showLanguage = true } }` |
| 658, 702 | `imagePickers = AndroidImagePickerFactory` | `imagePickers = platform.imagePickers` |
| 726-729 | `context`, `ledgerCapture`, `voiceFactory`, `voiceGate` | `val aiLab = platform.rememberAiLabServices()` **en esa misma rama** |
| 735-738 | los cuatro argumentos | `aiLab.ledgerPagePickerFactory`, `aiLab.ledgerPageFiles`, `aiLab.voiceRecognizerFactory`, `aiLab.voicePermissionGateFactory` |
| 819-821 | `context.startActivity(...)` | `onOpenPrivacyPolicy = { platform.openUrl(PRIVACY_POLICY_URL) },` |
| 849 | `remember(context) { AndroidLanguageControl(context) }` | `language = platform.language,` |

Quita los imports que queden sin uso, incluidos `android.graphics.Bitmap` (l. 3), `asImageBitmap` (l. 56) y `java.util.UUID` (l. 97), si `grep` confirma que nadie los usa.

**Llamadas:**
- `MainActivity.kt:1738`: `platform = rememberAndroidCuentivoAppPlatform(),` como primer argumento, con su import.
- `app/src/androidTest` (18 archivos, 52 llamadas; compruébalo con `git grep -c '\bCuentivoApp('`): lo mismo, en cada llamada. Agrega el import donde el paquete no sea `com.cuentivo.app.ui`. Todas corren en `setContent`; si alguna no, repórtala en vez de improvisar.

## Commit 4: mover

`git mv` de `CuentivoApp.kt` a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/CuentivoApp.kt`. En el archivo:

- Quita `import com.cuentivo.app.R` y `import androidx.compose.ui.res.stringResource`.
- Agrega `import org.jetbrains.compose.resources.stringResource` e `import com.cuentivo.shared.resources.*`.
- Las 14 `R.string.x` pasan a `Res.string.x`. **Con `sed` no cambies otra cosa.**
- Agrega los imports de `PlatformBackHandler` y `PlatformResumeEffect`.
- Quita `Intent`, `Uri`, `LocalContext` y los `Android*` que aún queden.

Guardias, **sólo cambia lo que está aquí**:

- `AiDebugLabGuardsTest.kt:193, 203, 600` y `MexalmLedgerWarmGatewayTest.kt:39`: la ruta pasa a `../shared/src/commonMain/kotlin/com/cuentivo/app/ui/CuentivoApp.kt`.
- `MexalmLedgerWarmGatewayTest`: `assertTrue(source.contains("Lifecycle.Event.ON_RESUME"))` pasa a `assertTrue(source.contains("PlatformResumeEffect"))`, y agrega otra lectura de `../shared/src/androidMain/kotlin/com/cuentivo/app/platform/PlatformForeground.android.kt` que afirme `contains("Lifecycle.Event.ON_RESUME")`. El resto de esa prueba y su `warmMexalmLedger()` se quedan igual.

**Si otra guardia falla, el error está en tu cambio:** corrige el código, no la guardia. Si no se puede, déjala fallando y repórtalo.

## Antes de la puerta: lo que Windows no ve

Compara el `iosMain` que tocaste contra el `import` real de cada símbolo. `UIApplicationWillEnterForegroundNotification` ya está contrastado en los bindings.

## Puerta

Entorno de `CLAUDE.md` (Norton), con `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`. **Una Gradle a la vez; no reintentes en bucle.**

```text
:shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
assembleRelease lintDebug
```

Corre las dos puertas de i18n de `CLAUDE.md`. Si `CuentivoApp.kt` introduce algo nuevo en la salida, no lo agregues a la exclusión sin reportarlo.

## Controles negativos

Haz cada uno, corre lo mínimo, anota **el nombre de lo que falló y por qué**, y revierte:

1. En `LanguageEntry.kt`, llama siempre `showSheet()`: debe fallar la prueba de Ajustes de `LanguageEntryTest`.
2. En el `actual` de Android, cambia `ON_RESUME` por `ON_START`: debe fallar la guardia de `MexalmLedgerWarmGatewayTest`.
3. En el común, quita `warmMexalmLedger()` del `PlatformResumeEffect`: debe fallar la misma guardia. Prueba que ya lee el archivo nuevo.
4. En el común, agrega `import android.net.Uri`: debe fallar `:shared:compileCommonMainKotlinMetadata`.

## Reporte (`build/ios-relevo/f5l-reporte.md`)

- Rama y commits **sacados de `git`**, no de memoria.
- Comandos ejecutados; pruebas Android contadas de los XML (total y fallos).
- Los 4 controles, con prueba que falló y motivo.
- Salida de las puertas de i18n.
- Lo que no pudiste hacer, y cualquier desvío de este encargo, con su razón.
- Sin push. Termina la última línea con `REPORTE LISTO`.
