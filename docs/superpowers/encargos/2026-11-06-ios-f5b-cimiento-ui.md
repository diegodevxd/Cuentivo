# Encargo iOS — F5-B: cimiento de UI en `:shared`, idioma y primeras piezas con `Res.string`

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-B): ...`, uno por tarea, **y cada commit debe compilar**. Reporte en `C:/tmp/oc-ios-f5b-reporte.md`.

**Objetivo:** el tema, los componentes base y la barra de navegación viven en `shared/src/commonMain`, conservan su paquete y compilan
para Android e iOS. Es la primera vez que pantallas reales de Android leen textos con `Res.string`, por eso aquí también se arregla el idioma.

**Fuera de este encargo:** `MainActivity` (salvo B1), `CuentivoApp.kt`, pantallas con cámara o `Intent`, y cambiar textos o diseño. Mover ≠ reescribir.

## Por qué así (ya decidido; no lo re-decidas)

- `stringResource(Res.string.x)` resuelve el idioma con `Locale.current` de Compose, que en Android sale del `Locale` por defecto del proceso.
  `R.string` sale de la configuración del `Context`. Debajo de API 33, `attachBaseContext` solo envuelve el `Context`, así que **las dos fuentes
  contestarían en idiomas distintos** dentro de una misma pantalla. Por eso B1 va antes de mover nada.
- Las pruebas no se reescriben para que pasen. Si una prueba compara `R.string.x`, pasa a `Res.string.x`, la misma clave. Si lee un archivo fuente,
  cambia solo la ruta; debe seguir leyendo el archivo real y fallar si la regla se rompe.

## Tareas

**B1. Idioma del proceso en Android.** En `LanguagePreference` agrega una función pura:
```kotlin
/** El `Locale` que debe ser el default del proceso. `tag` nulo significa seguir al sistema. */
fun processLocale(tag: String?, systemLocale: Locale): Locale =
    if (tag == null) systemLocale else Locale.forLanguageTag(tag)
```
En las dos ramas de `MainActivity.attachBaseContext`, antes de `super.attachBaseContext`, llama a
`Locale.setDefault(LanguagePreference.processLocale(tagElegido, Resources.getSystem().configuration.locales[0]))`. En la rama de API 33+,
`tagElegido` es `locales[0].toLanguageTag()` si `locales` no es nulo ni vacío, y `null` si no.
**El caso `null` importa:** quien vuelve a "seguir al sistema" no puede quedarse con el idioma anterior hasta reiniciar el proceso.
`Resources.getSystem()` da la configuración del sistema, no la de la app. `CollectionReminderWorker` y `SyncWorker` no se tocan.
Prueba JVM de `processLocale` en `app/src/test` (los dos casos), llamando a la función real.

**B2. Dependencias.** En `commonMain` de `shared/build.gradle.kts`, lo que pidan las piezas de B3 y B4. Los íconos: confirma en la doc oficial de
Compose Multiplatform 1.9 la coordenada de `material-icons-core`, porque `compose.materialIconsExtended` y similares cambiaron; no adivines.
`compose.ui`, `foundation`, `material3` y `runtime` ya están en `:shared`, pero revisa si están como `implementation`: los tipos de Compose aparecen
en la firma pública de lo que `:app` va a llamar, así que deben ser `api` o `:app` debe tenerlos ya (los tiene por su BOM). Anota qué hiciste y por qué.
Android tiene BOM `2026.02.01` e iOS Compose 1.9.3: **si algo existe en Android y no en 1.9.3, no subas versiones**; para y repórtalo.

**B3. Cimiento.** Con `git mv` a `shared/src/commonMain/kotlin/` **conservando el paquete**:
- `com/example/organizationlabs/ui/theme/{Color,CuentivoPalette,Theme,Type}.kt` a `com/cuentivo/app/ui/theme/`.
- `com/cuentivo/app/ui/`: `NeoFintechTokens`, `NeoFintechLayoutPolicy`, `NavigationLayoutPolicy`, `UiModels`, `NeoFormFields`, `NeoFintechComponents`, `NeoFintechShell`.
- `ui/transaction/NeoFormComponents.kt`.

En `NeoFintechComponents.kt` hay una lectura de `Settings.Global.ANIMATOR_DURATION_SCALE` con `LocalContext`. Es lo único de plataforma:
sácala a `expect`/`actual` sin cambiar el valor en Android. En iOS, `UIAccessibilityIsReduceMotionEnabled()`, con el mismo significado.
Reemplaza `R.string`/`R.plurals` por `Res.string`/`Res.plurals` y `stringResource`/`pluralStringResource` de `org.jetbrains.compose.resources`.
Ojo con el orden de argumentos de `pluralStringResource`, con `@StringRes Int` en firmas (pasa a `StringResource`) y con `stringResource` llamado fuera
de Composable. Las firmas cambian hacia quien llama: ajusta a los llamadores de `:app` con lo mínimo.
Si una pieza depende de algo que no está en `:shared`, déjala donde está y anótalo en el reporte; no muevas dependencias nuevas para forzarlo.

**B4. Primera pantalla.** `ui/AlertsSheet.kt` a `commonMain`, igual que B3. Si depende de algo fuera de `:shared`, para y dilo.

**B5. Pruebas.** Actualiza las que se rompan según la regla de arriba. `NeoFintechShellSourceContractTest` y `AiDebugLabGuardsTest` leen archivos fuente: revisa cada ruta.
Una prueba de `app/src/test` no puede invocar un `stringResource`; si alguna lo necesitaba, repórtalo en vez de borrarla.

## Puerta final (entorno Norton de `CLAUDE.md`)

```powershell
.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug assembleRelease lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```
**Sin `--rerun-tasks`.** Anota en el reporte: pruebas leídas de los XML (hoy 881 + 603; solo pueden bajar si explicas cuál y por qué, y B1 agrega), `git diff --stat HEAD~N -- app/schemas` (vacío),
paridad, y la salida de las dos puertas de i18n de `CLAUDE.md`: **actualiza sus rutas para cubrir también `shared/src/commonMain/kotlin/com/cuentivo/app/ui`**, porque un
literal movido ahí dejaría de verse. Confirma que `R.string` no quedó en `commonMain`: `grep -rn "R\.string\|R\.plurals\|import android\." shared/src/commonMain` vacío.

**No corras pruebas instrumentadas ni abras emulador:** esa puerta la hago yo. iOS no se compila en Windows: la compilación de `commonMain` para iOS queda "pendiente de CI"; dilo así.

Si algo de esto no coincide con el código real, para y repórtalo. **Nunca abras ni imprimas `google-services.json`, `GoogleService-Info.plist` ni llaves.**
