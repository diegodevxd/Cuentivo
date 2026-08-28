# Brief para AGY — N5 / T9: gate final y AAB

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `dead369` · **Plan:**
`docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md` §T9

T1 a T8 están cerradas, todas verificadas reejecutando. T9 es la última, y es distinta de las
demás: **casi no se escribe código, se demuestra.** Lo único que se edita es la versión.

## 1. La versión — esto bloquea todo lo demás

`app/build.gradle.kts:31` dice `versionCode = 32`, **que es exactamente el que ya está publicado en
producción**. Play rechaza un AAB cuyo `versionCode` ya se subió; el propio comentario del archivo
lo advierte.

- `versionCode` → **33**
- `versionName` → **1.18.0** (la 1.17.0 fue la de retención; ésta trae CuentivoAI, que es una
  función nueva, no un parche)

Si Diego decide otro `versionName`, es suyo y se cambia. El `versionCode` no es opcional.

## 2. El gate completo, sin caché

`--rerun-tasks` **no es adorno aquí**: reejecuta todo desde cero e impide que un `UP-TO-DATE` te haga
reportar como verde algo que en realidad no corrió en este árbol.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

En `functions/`, los **tres** comandos, reportados por separado — ya está en `CLAUDE.md`:

```
npm test
npm run test:firestore-emulator
npm run lint
```

Más: paridad de claves contada, puerta del idioma del `CLAUDE.md` ejecutada y vacía, y
`git diff --check`.

**Líneas base: 930 pruebas JVM en 104 clases · functions 266 · gate del emulador 6.** Ninguna baja.

## 3. La prueba física

**El A059P está conectado ahora mismo** (`adb devices` lo muestra como `00170157R000160`), así que
esto se puede correr sin esperar a nadie:

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Reporta el conteo real del XML de `app/build/outputs/androidTest-results/`, no un resumen. Si aborta
con `INSTRUMENTATION_ABORTED` o `RootViewWithoutFocusException`, **no lo reportes como verde ni lo
reintentes en bucle**: eso suele ser el diálogo ANR del sistema robándose el foco, y `CLAUDE.md`
tiene el procedimiento. Dilo tal cual pasó.

## 4. El AAB

```powershell
.\gradlew.bat bundleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

`keystore.properties` existe en el árbol, así que el bundle sale firmado con la llave de subida.

**Y ahora la verificación que no se puede saltar.** Un AAB **no** es un APK:

- `apksigner` **truena** contra un bundle con `ApkFormatException: Missing AndroidManifest.xml`. No
  lo uses aquí.
- Usa **`jarsigner -verify`**; debe decir `jar verified`. Los avisos de certificado autofirmado y
  sin marca de tiempo son normales con una llave de subida.
- **Y lo más importante:** compara la llave contra el AAB anterior con
  `keytool -printcert -jarfile` sobre **los dos** —el nuevo y `dist/Cuentivo-v32-1.17.0.aab`— y
  confirma que el **SHA1 es idéntico**. **Play rechaza para siempre un bundle firmado con otra
  llave.** Ese contraste es obligatorio y va en el reporte con los dos valores.

Copia el AAB a `dist/` con el nombre `Cuentivo-v33-1.18.0.aab`. `dist/` está en `.gitignore`: **el
AAB no se comitea**.

## 5. Lo que NO haces

- **No subes nada a Play.** Eso lo hace Diego, a **prueba cerrada**, nunca a producción. Es decisión
  suya del 2026-08-26 y no se adelanta.
- **No despliegas functions.**
- **No imprimes ni copias** el contenido de `keystore.properties`, contraseñas, alias ni el JSON de
  la cuenta de servicio. Ni en el reporte, ni en un log, ni en un commit.
- No entra la función que consume las notificaciones de Play: tiene tópico creado y verificado, pero
  es su propio incremento.

## Invariantes que se comprueban una última vez

- **`RECORD_AUDIO` sólo en debug**, verificado con `aapt2` sobre el APK de release empaquetado:
  release `=0`, debug `=1`. Igual que al cerrar N3.
- **Registrar un pago o abono nunca se bloquea.**
- Nada de PII, saldos ni tokens en logs, Analytics o Crashlytics.
- Dinero en `Long` centavos; nada financiero se borra ni se edita.

## Reporte

Un commit de cierre con el cambio de versión y este brief. En el reporte: cada comando que
realmente ejecutaste con su resultado, los conteos reales, los dos SHA1 de la comparación de llave, y
la ruta del AAB. **Si algo no corrió, dilo** — un hueco declarado vale; uno tapado, no.
