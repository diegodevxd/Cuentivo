# Encargo iOS — F5-H: primera deuda, cuenta e idioma en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-H): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5h-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** mover `FirstDebtPreferenceStore`, `AccountSheet` y `LanguageSheet` a `commonMain` **sin cambiar comportamiento en Android**. No cambies textos, `testTag` ni comentarios, salvo lo que esta hoja indica.

## Por qué así (ya decidido; no lo re-decidas)

- **Mismo paquete en cada archivo.** `internal` se quita **solo** de lo que `:app` o `androidTest` usan de verdad.
- **Patrón de preferencias:** el mismo de `ReminderPreferenceStore`: interfaz en común, `SharedPreferencesReminderStore` en `:app`, `NsUserDefaultsReminderStore` en `iosMain`.
- **En Android, el archivo y las llaves de preferencias no cambian:** `cuentivo_onboarding_preferences` y `first_debt_dismissed_<userId>`. Si cambian, a quien ya descartó la pregunta le vuelve a salir.
- **Idioma en iOS:** Apple administra el idioma por app en Ajustes, gracias a `CFBundleLocalizations`, ya declarado. Por eso iOS no muestra `LanguageSheet`: abre Ajustes. **Quien decide es la raíz común, en otra tanda:** aquí solo se deja la interfaz.
- **`LanguagePreference` se queda en `:app`**, con `LanguagePreferenceTest`: usa `LocaleManager`, `Build` y `attachBaseContext`.
- **Fuera de esta tanda, no los toques:** `MainActivity.kt` salvo la línea 1380, `ui/ai/*`, `backup/`. De `CuentivoApp.kt` solo lo que abajo se indica. `deleteAccountWithApple` y `signOut` **no entran:** se invocan desde `MainActivity` y van con la raíz común.

## Commit 1: `FirstDebtPreferenceStore`

**`shared/src/commonMain/kotlin/com/cuentivo/app/ui/onboarding/FirstDebtPreferenceStore.kt`**, mismo paquete. Conserva el KDoc de la clase:

```kotlin
/** Donde vive la respuesta, tras una interfaz para que las pruebas comunes la manejen. */
interface FirstDebtFlagStore {
    fun isSet(key: String): Boolean
    fun set(key: String)
}

class FirstDebtPreferenceStore(private val flags: FirstDebtFlagStore) {
    fun isDismissed(userId: String): Boolean {
        if (userId.isBlank()) return false
        return flags.isSet(keyFor(userId))
    }

    fun markDismissed(userId: String) {
        if (userId.isBlank()) return
        flags.set(keyFor(userId))
    }

    private fun keyFor(userId: String): String = "first_debt_dismissed_$userId"
}
```

- **`:app`, `ui/onboarding/SharedPreferencesFirstDebtFlagStore.kt`:** `getSharedPreferences("cuentivo_onboarding_preferences", MODE_PRIVATE)` sobre `context.applicationContext`; `getBoolean(key, false)`; `edit().putBoolean(key, true).apply()`. La constante se llama `PREFERENCES`, igual que hoy.
- **`iosMain`, `platform/NsUserDefaultsFirstDebtFlagStore.kt`:** como `NsUserDefaultsReminderStore`, con `boolForKey` y `setBool(true, forKey = key)`. No uses otro símbolo de `NSUserDefaults`.
- **`MainActivity.kt:1380`:** `FirstDebtPreferenceStore(SharedPreferencesFirstDebtFlagStore(this@MainActivity))`, y su `import`. Nada más.

**Prueba** en `commonTest/.../ui/onboarding/FirstDebtPreferenceStoreTest.kt`, con un doble en memoria (`mutableSetOf<String>`), que invoca la clase real:
- sin marcar da `false`; tras marcar, `true`;
- otro usuario sigue en `false`;
- un `userId` en blanco no escribe y da `false`, **incluso si la llave en blanco ya existe en el doble**;
- `markDismissed("u1")` escribe **exactamente** `first_debt_dismissed_u1`: esa llave ya está en los teléfonos.

## Commit 2: `AccountSheet`

Mueve `ui/account/AccountSheet.kt` a `shared/.../ui/account/`, mismo paquete.

- `Res.string.*` y `stringResource` de `org.jetbrains.compose.resources`, con `import com.cuentivo.shared.resources.*`.
- Parámetro nuevo, **sin valor por defecto:** `onOpenPrivacyPolicy: () -> Unit`, junto a `onDeleteAccount`. El botón `privacy-policy-button` lo llama. Quita `LocalContext`, `Intent` y `Uri` de ese archivo.
- `PRIVACY_POLICY_URL` se queda en el archivo movido, como `const val` público.
- **`CuentivoApp.kt:791`:** `onOpenPrivacyPolicy = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }`, usando el `context` que ya existe en la línea 324. **Sin `try/catch` nuevo:** es el comportamiento de hoy.

## Commit 3: `LanguageControl` y `LanguageSheet`

**`shared/src/commonMain/kotlin/com/cuentivo/app/platform/LanguageControl.kt`:**

```kotlin
/** Dónde se elige el idioma de la app. */
interface LanguageControl {
    /** `true` si esa elección vive en los Ajustes del sistema, y la app no muestra su hoja. */
    val opensSystemSettings: Boolean

    /** La elección vigente ("es", "en"), o null si sigue al sistema. */
    fun effectiveTag(): String?

    /** `tag` nulo significa seguir al sistema. */
    fun select(tag: String?)

    fun openSystemSettings()
}
```

**`:app`, `settings/AndroidLanguageControl.kt`:** `class AndroidLanguageControl(private val context: Context) : LanguageControl`.
- `opensSystemSettings = false`.
- `effectiveTag()`: `LanguagePreference.effectiveTag(context)`.
- `select(tag)`: el cuerpo actual, en el mismo orden: `LanguagePreference.set(context, tag)`, y `if (Build.VERSION.SDK_INT < 33) (context as? Activity)?.recreate()`.
- `openSystemSettings()`: no hace nada, con un comentario que diga por qué: Android elige dentro de la app.
- **Recibe el `context` de `LocalContext`, que es la `Activity`.** Sin eso, `recreate` no corre debajo de API 33. Por eso **no** llames `applicationContext` aquí.

**`iosMain`, `platform/IosLanguageControl.kt`:**
- `opensSystemSettings = true`.
- `effectiveTag()`: `null`.
- `select(tag)`: llama `openSystemSettings()`.
- `openSystemSettings()`: `val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return`, luego `UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)`, igual que `IosSharer.kt:52-55`.
- Importa explícito `platform.UIKit.UIApplicationOpenSettingsURLString`: es una constante de nivel superior.

**Mueve `ui/account/LanguageSheet.kt` a `shared/.../ui/account/`, mismo paquete:**
- Firma: `fun LanguageSheet(language: LanguageControl, onDismiss: () -> Unit)`.
- `labelRes` pasa a `StringResource`.
- El `when` inicial lee `language.effectiveTag()`; **conserva sus tres ramas**.
- `onClick`: `selected = option` y `language.select(option.tag)`. Quita `Activity`, `Build` y `LocalContext`.
- **`CuentivoApp.kt:830`:** `LanguageSheet(language = remember(context) { AndroidLanguageControl(context) }, onDismiss = { showLanguage = false })`.

## Lo que Native rechaza

Comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`, `java.*` y `Charsets` en común. **Revisa también lo que moviste.** Busca además `java.`, `android.`, `R.` y `Locale` en los archivos movidos.

## Símbolos

**No accedas a `C:/tmp/kn-prebuilt`.** Ya contrasté `UIApplicationOpenSettingsURLString` (UIKit). `boolForKey`, `setBool`, `URLWithString`, `sharedApplication` y `openURL` ya compilan en `iosMain`. Si necesitas otro, no lo adivines: anótalo en el reporte.

## Control negativo obligatorio, en Windows

Rompe, corre, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Restaura y confirma con `git diff`.

1. `markDismissed` sin el guardia de `isBlank`.
2. `isDismissed` sin el guardia de `isBlank`.
3. La llave con otro prefijo (`first_debt_$userId`).

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`. **Si una puerta da coincidencias nuevas, repórtalas: no la amplíes.**

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`, no de memoria.
- Conteos reales de los XML. La base es 805 + 772 = 1577: cuántas son nuevas.
- Salida de las tareas, archivos tocados y los **tres controles negativos**, con la prueba que falló.
- **Lista exacta de cada línea que no sea solo cambiar de recurso, de `internal` o de `import`.** La voy a auditar leyendo el diff.
- Qué perdió `internal`, y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que el idioma funciona en iOS: no puedes saberlo desde Windows.** Nadie ha comprobado que `NSLocale.preferredLanguages` refleje la elección hecha en Ajustes.
