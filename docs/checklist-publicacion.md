# Checklist de publicación — Cuentivo

Pasos reproducibles para subir una versión a prueba cerrada/interna o producción.
Nada se declara listo sin la salida real de los comandos.

## 0. Antes de empezar

- Rama de trabajo `diegodevxd/feature-cuentivo-ai`, árbol comiteado o con los cambios aislados
  que se van a subir. (`codex/neo-fintech` fue la rama hasta agosto de 2026.)
- Un dispositivo para las instrumentadas. Hoy se usa el **teléfono físico A059P** conectado en
  depuración; el emulador `Medium_Phone` (API 37) sirve igual pero se degrada con el uso.
  Comprobar con `adb devices`, y si es emulador esperar además a que `adb shell dumpsys user` dé
  `0=RUNNING_UNLOCKED`: `adb devices` dice `device` con el sistema todavía a medias.
- **Si la app instalada en el teléfono es la debug, `installDebug` actualiza encima.** No
  desinstalar: eso borra la base local de Room y obliga a registrar otra vez el token de App Check.
- Entorno Gradle (Norton interfiere TLS; si se limpia `C:\tmp`, reexportar el cacerts):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
```

- Verificar que `app/google-services.json` existe y está fresco (`apps:sdkconfig` si cambió algo en consola Firebase).

## 1. Versión

- Subir `versionCode` en `app/build.gradle.kts` si ya se subió ese número a Play. Subir `versionName`
  si el cambio merece distinguirse (regla del proyecto: subir ambos cuando importe).
- Anotar en el mensaje del commit: `versionCode`, `versionName`, resumen de cambios.

## 2. Puertas locales

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat lintDebug
```

- `testDebugUnitTest`: anotar el conteo de suites/tests/fallos (leer los XML en
  `app/build/test-results/testDebugUnitTest/`).
- `compileDebugAndroidTestKotlin` SIEMPRE aparte: un cambio de firma puede pasar el build principal
  y reventar aquí (lección documentada).
- `lintDebug`: si falla, verificar que el error no sea nuevo respecto de la rama. A la fecha hay un
  error preexistente en `ui/NeoFintechShell.kt:206` (parámetro no usado en el `content` de
  `AnimatedContent`); no debe "arreglarse" a medias ni silenciarse sin decisión.
- Instrumentadas relevantes en el emulador:

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest'
```

- Paridad de idiomas (es/en) — deben ser iguales. El número envejece, así que cuenta, no lo copies:

```powershell
rg -c "<string name=" shared/src/commonMain/composeResources/values/strings.xml shared/src/commonMain/composeResources/values-en/strings.xml
```

- Puerta del idioma de `CLAUDE.md` (no literales visibles en `ui/`).

## 2b. Declaraciones de Play que viajan CON el AAB

No son código y **bloquean la publicación** si faltan. Van en el mismo envío que el binario, no
después.

- **Seguridad de los datos** (Play Console → Política y programas → Contenido de la app → Seguridad
  de los datos). Lo que la app hace hoy y cómo se declara:

| Dato | ¿Se recopila? | ¿Efímero? | ¿Obligatorio? |
|---|---|---|---|
| Audio (grabaciones de voz) | Sí | **Sí** | No, el usuario puede escribir |
| Fotos | Sí | **No** | No, sólo para importar libreta |

  El audio va al reconocimiento de voz de Android —prefiere el del dispositivo y cae al de Google si
  no está— y **Cuentivo nunca lo recibe, sólo texto**: por eso es efímero. La foto en cambio la
  transmite el propio backend a Gemini, **que retiene registros para supervisión de abuso**, así que
  ahí efímero es "No".

  Revisa además que el texto que CuentivoAI ya enviaba a Gemini —con **nombres de clientes** e
  **importes**— esté declarado como información personal y financiera.

- **Política de privacidad**: tiene que mencionar el micrófono y la foto. La frase correcta sobre la
  foto es **"sin archivo permanente de fotos en Cuentivo"**, nunca "no se guarda en ningún lado":
  existe un temporal local mientras dura la revisión y el proveedor retiene registros de abuso.
  Decir lo segundo sería falso en un documento legal. Subir también la fecha de actualización.

- **Permisos del manifiesto**: hoy `RECORD_AUDIO` y `POST_NOTIFICATIONS`. **No hay `CAMERA`** a
  propósito: la foto usa la cámara del sistema y el Photo Picker. Si algún día aparece `CAMERA`,
  es un error de diseño, no una mejora.

## 2c. Instrumentadas financieras, aparte

La suite instrumentada completa aborta en `ProductPhotoSourceTest`, y **ese aborto no cuenta como
verde**. Las clases que tocan dinero se corren una por una:

**`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` no es opcional en el telefono de
Diego.** Sin esa propiedad Gradle **desinstala la app al terminar**, y eso borra su base local de
Room y el token de App Check. Ya paso una vez, con perdida de datos reales.

```powershell
.gradlew.bat connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.AiSaleExecutionIsolationTest'
.gradlew.bat connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.LedgerBatchIsolationTest'
.gradlew.bat connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.LedgerGuidedReviewTest'
.gradlew.bat connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.data.local.SyncOperationReopenTest'
```

Los resultados se cuentan de los XML de `app/build/outputs/androidTest-results/connected/`, no del
texto de Gradle.

## 3. Firma y llave

- El AAB debe salir de un commit (regla del proyecto; ya falló una vez).
- Construir:

```powershell
.\gradlew.bat bundleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

- Verificar que el AAB está firmado con la llave de subida. Para un **AAB** es `jarsigner`, no
  `apksigner` (este último truena con `ApkFormatException` porque un bundle no es un APK):

```powershell
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
# debe decir "jar verified"; los avisos de autofirmado/sin timestamp son normales
```

- Comparar la llave con la última versión subida (obligatorio antes de un parche; Play rechaza para
  siempre un bundle con otra llave):

```powershell
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
# contrastar el SHA1 con la versión vigente
```

- Anotar `versionCode`, `versionName`, SHA-256 del AAB y ruta.

## 4. App Check y OAuth

- Paneles de Firebase → App Check → Apps → `com.cuentivo.app`: verificar que App Check no bloquea el
  tráfico nuevo (enforcement sigue APAGADO por decisión; revisar la barra del día).
- Authentication → Sign-in method: proveedores esperados (Email/Password + Google) activos.
- Si se cambió el sha-1/sha-256 de debug o release en consola, refrescar `google-services.json`
  (`apps:sdkconfig`) y verificar `default_web_client_id` único/no vacío:
  `rg -n "default_web_client_id" app/build/generated/res/processDebugGoogleServices/values/values.xml`.

## 5. Subida a Play

- Consola Play → Release → Probar seguimiento/producción → subir `app-release.aab`.
- Nombre de la versión = `versionName`; notas del release: qué se probó y qué requiere prueba física.
- Tras subir, verificar en la pestaña de la versión que el AAB cargó (no "firma no válida").

## 6. Prueba física de humo (teléfono de Terra o probador)

- Instalar desde Play (o Uso compartido interno / prueba interna según el canal).
- Abrir, iniciar sesión con una cuenta de prueba y confirmar que los datos se adoptan/restauran.
- Nunca usar datos reales de testers para pruebas destructivas; ver `prueba-restore-pro-sin-red.md`.
