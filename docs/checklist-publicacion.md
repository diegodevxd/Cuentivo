# Checklist de publicación — Cuentivo

Pasos reproducibles para subir una versión a prueba cerrada/interna o producción.
Nada se declara listo sin la salida real de los comandos.

## 0. Antes de empezar

- Rama de trabajo `codex/neo-fintech`, árbol comiteado o con los cambios aislados que se van a subir.
- Emulador `Medium_Phone` (API 37) disponible para las instrumentadas: `adb devices`.
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
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
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
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest'
```

- Paridad de idiomas (es/en) — deben ser iguales, hoy 363 + plurals:

```powershell
rg -c "<string name=" app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
```

- Puerta del idioma de `CLAUDE.md` (no literales visibles en `ui/`).

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
