# Relevo: Codex pasa a orquestar la v44 (2026-09-23)

Claude orquestaba y se le acaban los tokens. **Desde ahora Codex (gpt-6-luna) orquesta**: escribe
encargos, verifica y decide lo técnico. **OpenCode con DeepSeek v4.1 Flash implementa.** Diego decide
lo de producto y todo lo irreversible (subir a Play, deploys, borrar cuentas).
Lee `CLAUDE.md` y el encabezado de `PROJECT_MEMORY.md` antes de nada.

## Dónde está todo

- **Rama** `diegodevxd/v44-libreta` (worktree `feature-cuentivo-ai`). **Commit local `7a6dadc`** con
  A-F de la v44 (auditado por ti ayer, D probado en el teléfono). **No está en GitHub.**
- **Ahora mismo trabajando:** OpenCode/DeepSeek en la terminal Orca "OC v44 tanda4"
  (`term_a854a3ff-12f7-4454-86c8-ecd8a250e7fc`) con
  `docs/superpowers/encargos/2026-09-23-v44-tanda4-recorrido-dinero.md`. Reportará en
  `C:/tmp/oc-v44-tanda4-reporte.md`.
- **Siguiente encargo ya escrito:** `docs/superpowers/encargos/2026-09-23-v44-tanda5-navegacion-lenguaje.md`.
- **Por qué existen las tandas 4 y 5:** `docs/superpowers/reportes/2026-09-23-recorrido-dona-mary.md`.
- Play: v41 en producción, v43 (`1.22.1`, versionCode 43, etiqueta `v1.22.1-43`) subida. **La v44 será
  versionCode 44 / `1.22.2`** (o `1.23.0` si Diego lo prefiere: pregúntale).

## Qué sigue, en orden

1. Cuando DeepSeek termine la tanda 4: lee su reporte, **corre tú el gate** (abajo) y cuenta desde los
   XML; revisa las pruebas nuevas contra `.claude/agents/test-honesty-reviewer.md` (una prueba que lee
   el código fuente o reimplementa la regla no vale). Commit local de la tanda 4.
2. **Sesión nueva** de OpenCode (`orca terminal create --worktree active --title "OC v44 tanda5"
   --command "opencode -m opencode-go/deepseek-v4.1-flash"`) con la tanda 5. Verifica y commit local.
3. Auditoría de las tandas 4 y 5 (puedes hacerla tú mismo, sólo lectura, o pedirle a Diego otro
   modelo). Arreglos que salgan → otra tanda de DeepSeek.
4. Subir versión a 44, **gate completo con todas las instrumentadas**, `bundleRelease`, verificar
   firma y llave (`jarsigner -verify` y `keytool -printcert -jarfile`: SHA1 debe ser
   `25:05:8A:9C:F3:1F:D7:B4:1E:8E:B3:2D:9A:AB:8F:9F:7A:E8:1D:BF`), copiar a
   `dist/Cuentivo-v44-<versión>.aab`, **push de la rama y etiqueta** sólo cuando Diego diga.
5. Actualiza `PROJECT_MEMORY.md` **sustituyendo** el encabezado (menos de ~100 líneas).

## Gate (PowerShell)

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Referencia: **1334** pruebas JVM, 0 fallos (cuenta sumando `tests=` de
`app/build/test-results/testDebugUnitTest/*.xml`; revisa la hora de los XML: si no cambió, no corrió).
Paridad ES/EN **673/673** (compara también que las **claves** sean las mismas). Las dos puertas de
idioma de `CLAUDE.md` deben salir vacías.

## Trampas que ya nos mordieron

- **No lances Gradle mientras OpenCode compila en la misma carpeta** (corrompe `build/intermediates`).
- **Teléfono de Diego** `00170157R000160` (A059P) tiene la app **debug** con token App Check registrado.
  `connectedDebugAndroidTest` **desinstala** la app al terminar y el token cambia: siempre
  `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Si cambia, el token nuevo sale de
  `adb logcat | grep DebugAppCheckProvider` y Diego lo registra en Firebase Console → App Check.
  Instalar encima (`installDebug`) lo conserva.
- Git Bash con `adb shell`: `export MSYS_NO_PATHCONV=1` o las rutas del teléfono se rompen.
- **OpenCode**: pedir siempre "sin delegar" (sus subagentes usan modelos de pago de OpenAI) y reporte en
  archivo. Mientras trabaja, lo que le mandes queda **QUEUED**. Para cortar un bucle: **Esc simple dos
  veces** (`orca terminal send --text $'\e'` sin `--interrupt`; con `--interrupt` se cierra entero).
  `orca terminal wait --for tui-idle` da falsos positivos: vigila que desaparezca `esc interrupt` en 3
  lecturas seguidas. Si pasa del ~80% de contexto, que corra el gate, reporte y sesión nueva.
- La memoria del sistema se agota con emulador + Laya + varias sesiones: el emulador y Laya están
  **apagados**; usa el teléfono de Diego.
- **Laya no decide nada de Cuentivo** (lo pidió Diego).
- Nada de datos reales de las libretas en el repo, pruebas o capturas.
- Commits: nunca `--no-verify`; mensaje en archivo y `git commit -F`; no incluir
  `docs/superpowers/handoffs/` (es de Diego).
