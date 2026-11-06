# Encargo iOS — F5-A: strings a `composeResources`, sin tocar pantallas

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-A): ...`, uno por tarea. Reporte en `C:/tmp/oc-ios-f5a-reporte.md`.

**Objetivo:** las 702 claves ES/EN viven en un solo lugar, `composeResources`, y `:shared` puede usar `Res.string`. **Android se
comporta igual, byte por byte:** los 768 `R.string` y 17 `R.plurals` siguen compilando y dando el mismo texto, porque Android
recibe sus `strings.xml` generados en el build.

**Fuera de este encargo:** mover pantallas o cambiar llamadas de `R.string` a `Res.string`. Tampoco cambiar textos ni agregar claves.

## Por qué así (ya verificado en el fuente de Compose Multiplatform 1.9.3; no lo re-decidas)

- `Res.getString` fuera de un Composable es `suspend`; `MainActivity`, `BillingGateway`, `BackupSection`, `CollectionReminderWorker` y
  `AuthenticationGateway` usan `R.string` de forma síncrona, y el `android:label` del manifest usa `@string/app_name`. Por eso `R` se queda.
- `handleSpecialCharacters` de Compose **solo** entiende `\n`, `\t`, `\uXXXX` y `\\`. Un `\"` o `\'` se vería con la diagonal en
  pantalla. Android, al revés, **exige** ese escape. Por eso la fuente lleva comillas y apóstrofos sin diagonal, y el generador se la pone.
- `replaceWithArgs` solo reconoce `%N$s` y `%N$d`. `%1$02d` saldría literal.

## Tareas

**A1. Mover el archivo.** Con `git mv`:
`app/src/main/res/values/strings.xml` → `shared/src/commonMain/composeResources/values/strings.xml` y
`app/src/main/res/values-en/strings.xml` → `shared/src/commonMain/composeResources/values-en/strings.xml`.
En esos dos archivos, reemplaza `\"` por `"` y `\'` por `'`. Son 34 `\"` en cada uno y 6 `\'` en inglés; confirma los conteos antes.
No cambies nada más. `colors.xml` y `themes.xml` se quedan.

**A2. `reminder_hour_option`.** Cambia en ambos idiomas `%1$02d:00` por `%1$s:00`, y en `AccountSheet.kt:176` pasa
`reminderHour.toString().padStart(2, '0')`. El texto final debe ser idéntico (`07:00`).

**A3. Generador para Android.** Tarea de Gradle en `app/build.gradle.kts`, registrada con
`androidComponents.onVariants { it.sources.res?.addGeneratedSourceDirectory(tarea, ...::outputDir) }` (confírmalo en la doc de AGP 9.3):
lee ambos XML de `shared/src/commonMain/composeResources` (`@InputDirectory`/`@InputFiles`), escribe `values/strings.xml` y
`values-en/strings.xml` en su directorio de salida, y solo cambia `"` por `\"` y `'` por `\'` **dentro del texto**. Los `"` de los atributos
`name="..."` y `quantity="..."` no se tocan. Lee y escribe en UTF-8 explícito. Compatible con `--no-configuration-cache` y con la
caché de configuración; sin dependencias nuevas.

**Puerta de A3, obligatoria:** compara el generado contra la versión anterior del repo:
`git show HEAD~N:app/src/main/res/values/strings.xml` frente a `app/build/.../values/strings.xml`, ignorando solo finales de línea. **La
única diferencia permitida es la línea `reminder_hour_option`**, en cada idioma. Pega la salida real de ambos `diff` en el reporte.

**A4. `Res` en `:shared`.** En `shared/build.gradle.kts`, `implementation(compose.components.resources)` en `commonMain` y:
```kotlin
compose.resources {
    publicResClass = true
    packageOfResClass = "com.cuentivo.shared.resources"
    generateResClass = always
}
```
Con el plugin `com.android.kotlin.multiplatform.library` puede hacer falta activar recursos Android. Confirma en la doc oficial de
Compose 1.9 cuál es la forma exacta; no adivines. La APK no debe perder ni duplicar recursos.

**A5. Prueba en iOS, que ejerce la lectura real.** `shared/src/iosTest/.../ComposeStringsTest.kt`, con `getString(Res.string...)`
(no en `commonTest`: en el host de Android `Resources.getSystem()` no existe):
- `app_name` da `Cuentivo`.
- `ai_sale_preview_product_selection_prompt` con argumento `x` contiene `"x"` y **no** contiene `\`.
- `reminder_hour_option` con `07` da `07:00`.
- un plural con `getPluralString` sustituye su argumento, sin dejar `%`.
Sin comas ni los demás caracteres prohibidos en los nombres entre backticks.

**A6. El `.app` lleva los recursos.** En `.github/workflows/ios-build.yml`, después de `xcodebuild`, un paso que falle con `::error::` si dentro del `.app` no
hay `compose-resources` con un `strings` para `values-en`. Compilar no prueba esto: sin ese directorio la app truena al abrir.
Mira primero qué ruta genera `:iosKit:embedAndSignAppleFrameworkForXcode` en la doc oficial, porque `:iosKit` es quien arma el framework y el `Res` vive en `:shared`.
Si para que se copien hace falta algo en `iosKit/build.gradle.kts`, hazlo y anótalo.

**A7. Rutas que apuntan al archivo viejo.** Actualiza en el mismo commit:
`.claude/hooks/strings-parity.sh` (variables `ES`, `EN` y el `case`, incluida la forma con `\`), `.claude/skills/gate/SKILL.md`,
`CLAUDE.md` (conteo de paridad y notas de escape de `values-en/`: ahora la regla es **sin diagonales en la fuente**) y
`app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`. En esta prueba cambia solo la ruta: debe seguir leyendo el archivo real y fallar si la regla se rompe.
Prueba el hook a mano: agrega temporalmente una clave en un solo idioma, confirma el aviso, y revierte.

## Puerta final (entorno Norton de `CLAUDE.md`)

```powershell
.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug assembleRelease lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```
**Sin `--rerun-tasks`:** la PC se queda sin memoria. Anota en el reporte: total de pruebas leído de los XML (hoy 881 + 603; no puede bajar),
`git diff --stat HEAD~N -- app/schemas` (vacío), paridad `grep -c "<string name="` en los dos archivos nuevos (702 y 702), y salida de las
dos puertas de i18n de `CLAUDE.md`. iOS no se compila en Windows: A5 y A6 quedan "pendientes de CI"; dilo así.

Si algo de esto no coincide con el código real, para y repórtalo. **Nunca abras ni imprimas `google-services.json`, `GoogleService-Info.plist` ni llaves.**
