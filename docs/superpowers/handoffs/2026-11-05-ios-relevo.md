# Relevo iOS: de la sesión 1 a la siguiente mente

**Tu papel:** eres la mente: planeas, escribes encargos y auditas. **OpenCode con DeepSeek v4.1 Flash implementa, sin delegar.**
Diego habla español informal; dile paso a paso solo lo que tenga que hacer a mano, y déjalo para el final. Objetivo: Cuentivo en iOS
sin Mac, con **pagar Apple Developer como único muro**. Diego pidió seguir hasta acabar todas las fases.

**Lee también:** `CLAUDE.md`, el plan `docs/superpowers/plans/2026-11-04-cuentivo-ios-sin-mac.md` (Fases 4 a 8 y "Día de pago"), la
memoria `cuentivo-ios-sin-mac`, `cuentivo-reparto-de-agentes` y `relevo-de-sesiones-caras`, y los encargos en
`docs/superpowers/encargos/` como modelo de formato. **Regla de relevo:** cuando esta sesión se encarezca (cerca de $50-$80 o antes
de un tramo grande), repite este relevo: MD nuevo, sesión Opus nueva en Orca, confirma que entendió y cierra la tuya.

## Estado al 2026-11-05 20:50

Worktree `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`, rama `diegodevxd/cuentivo-ios`. Repo
`diegodevxd/Cuentivo` **público**: revisa secretos antes de cada push. Yo hago push y secretos, con autorización de Diego.

Hechas y en verde: F1, F2, F3, dominio, preparación de F7, F4-A, F4-B, F4-C (`d7c6599`, run 42145751471: 566/566 en simulador iOS, `.app`
generado, `android-check` verde). Secreto `GOOGLE_SERVICE_INFO_PLIST` guardado.

**F4-D en vuelo:** `6a9b2f8` subido, **sin veredicto todavía**. Compila por primera vez `IosAuthenticationGateway`, los proveedores en Swift
(Google con `GoogleSignIn-iOS`, Apple), App Check, `IosCallableAiGateway`, `IosFirestoreDeletionGateway`, y `CloudRestore` en común.
Android local: 881 + 603 = 1484, 0 fallos (el total no bajó).

**Hay un commit local sin subir** con `if: !cancelled()` en el paso del URL scheme y este handoff. **No lo subas hasta tener el veredicto**:
el workflow tiene `cancel-in-progress: true` y cancelaría el build.

## Tu primer paso

```bash
bash C:/tmp/ios-relevo/espera_ios.sh 6a9b2f8$(git -C <worktree> rev-parse 6a9b2f8 | cut -c8-)
```
Con `run_in_background`. Luego lee pasos, XML y errores (ver "Veredicto"), corrige lo que salga y sube el commit local.

**Posibles fallos:**
- `GoogleService-Info.plist sin REVERSED_CLIENT_ID`: es manual de Diego. Dile, paso a paso: Firebase Console → Authentication → Sign-in
  method → Google → activar → Configuración del proyecto → app iOS → volver a bajar el plist. Luego lo guardas tú con
  `base64 -w0 archivo | gh secret set GOOGLE_SERVICE_INFO_PLIST --repo diegodevxd/Cuentivo`. **Nunca abras ni imprimas el plist.**
- Errores de Swift: un `Boolean` de una lambda Kotlin es `KotlinBoolean` (`.boolValue`, `KotlinBoolean(bool:)`). Revisa `AppleNonce` si
  no es visible desde Swift, y que la app importe los productos SPM correctos.
- Errores de cinterop: GitLive usa `cocoapods.FirebaseAuth` etc. Confírmalo en su fuente v2.8.0 (`firebase-*/src/appleMain`).

## Lo que sigue

1. **Cerrar F4-D.** Pendiente para F5: **nadie llama todavía a `deleteAccountWithApple`**. La pantalla de borrar cuenta debe usarla si el
   proveedor es `apple.com`, porque App Store exige revocar el token. `deleteAccount` normal no revoca.
2. **F5, UI y strings.** Es lo más grande. Trocéala: primero strings a `composeResources` de una vez (702 claves, con puertas i18n y
   `strings-parity.sh` en el mismo commit), luego interfaces (`Sharer`, `PhotoPicker`, `TextRecognizer` con Vision,
   `ReminderScheduler`, `AppFiles`), luego pantallas en tandas. **Empieza con una sesión nueva de OpenCode:** el terminal actual
   (`term_d0a04595-16e3-483c-9ff1-74bb843689f5`) tiene el contexto al 91%; ciérralo y crea otro.
3. **F6:** StoreKit 2, `verifyAppleSubscription`, `handleAppStoreNotification`, gate `npm --prefix functions run release:check`, **sin deploy**.
4. **Resto de F7:** íconos, capturas. F8 es opcional.
5. **Al final, Diego:** pagar Apple cuando la app funcione, siguiendo `docs/ios-dia-de-pago.md`.

Actualiza `PROJECT_MEMORY.md`: su sección "iOS sin Mac" está vieja, y el archivo tiene 824 líneas aunque debería tener unas 100.

## Cómo trabajar con OpenCode

```bash
orca terminal create --worktree "path:<wt>" --title "OpenCode iOS" --command "opencode -m opencode-go/deepseek-v4.1-flash" --json
orca terminal send --terminal <h> --text "Lee y ejecuta <encargo>... Reporte en C:/tmp/oc-ios-X-reporte.md." --enter --json
```
Espera a que cargue el TUI antes del primer envío, o se pierde. **Cada envío lleva un Monitor** (regla de Diego):
```bash
python C:/tmp/ios-relevo/vigila_opencode.py <handle> C:/tmp/oc-ios-X-reporte.md <wt> | grep --line-buffered -vE ' COMMIT '
```
Expira a los 30 min: si no hay reporte, vuelve a armarlo. Si OpenCode ya entregó, `ATORO` es falsa alarma.
- `agent_prompt_blocked` significa que espera un permiso. Una sesión nueva de OpenCode vuelve a pedir `C:\tmp\*`: mira la pantalla, Flecha
  derecha (`$'\e[C'`) + Enter + Enter da "Allow always" a ese patrón. Solo concede lo que el encargo requiere.
- Atorado de verdad: Esc (`--text $'\e'`) y un mensaje.

## Cómo auditar: lo que ya cazó esta auditoría

No aceptes el reporte sin mirar. Revisa: `git diff --stat`, `git status`, sensibles en el diff, `app/schemas` intacto, `Double` con dinero y
conteos reales del XML. **Lee el código que toca dinero, tokens o concurrencia.** Ejemplos reales:
- F4-D: revocar Apple con `client_secret` vacío, sin esperar antes de borrar. El reporte lo presentaba como "diferencia inevitable" y era falso.
- F4-C: una prueba aseguraba algo que el DAO no hace. La producción estaba bien; se corrigió la prueba, porque `markSaleCanceled` sí sella `updatedAt`.
- Nunca subas un límite de prueba a ciegas: mide primero, con el artefacto `resultados-pruebas-ios-*`.

**Puerta Android local** (entorno Norton en `CLAUDE.md`; `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`):
`testDebugUnitTest :shared:testAndroidHostTest assembleDebug assembleRelease lintDebug`. **La PC se queda sin memoria** y mi corrida con
`--rerun-tasks` la mató el sistema; Diego usa la PC para otras cosas. Si no cabe, `android-check` en CI sustituye `testDebugUnitTest` +
`testAndroidHostTest`, pero no release ni lint: corre esos antes de dar una fase por cerrada.

**Veredicto de CI:**
```bash
gh api repos/diegodevxd/Cuentivo/actions/runs/<id>/jobs?filter=latest --jq '.jobs[]|.steps[]|"\(.name): \(.conclusion)"'
gh run view <id> --repo diegodevxd/Cuentivo --log-failed   # filtra ^e: , .kt:N:N: error , .swift:N:N: error , Undefined symbol
gh run download <id> --repo diegodevxd/Cuentivo --name resultados-pruebas-ios-macos-26 --dir <carpeta nueva>   # aquí están los mensajes de aserción
```

## Trampas de iOS sin Mac

- Solo `macos-26` consigue runner. Windows no compila iOS: cada error se descubre en CI (unos 7-15 min).
- `commonTest` en Native rechaza: comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`.
- `ARCHS=arm64` es obligatorio en el build de simulador. `:shared` no depende de Firebase (si no, se rompen sus pruebas): lo de Firebase va en `:iosKit`.
- `:iosKit` no tiene pruebas: la lógica pura va en `:shared` para que corra en el simulador.
- `NSNumber(longLong = x)`, no `numberWithLongLong`. `immediateTransaction { block() }`. `NSJSONSerialization` pierde precisión: usa `StrictJson`.
- **Nada se ha probado contra Firestore, Auth o Apple reales**, ni en iPhone: compilar y correr en simulador no prueba eso. Díselo a Diego
  con esas palabras al dar una fase por lista. Un smoke test en simulador con emuladores de Firebase sería valioso antes del Día de pago.

## Reglas que no cambian

Dinero en `Long`; nada financiero se borra ni se edita; **sin `firebase deploy` ni deploy de `functions` sin autorización aparte de Diego**.
Nunca imprimas `keystore.properties`, `*.jks`, `google-services.json`, llaves ni el plist. Commit con los trailers que te indique tu sesión.
La rama `feature-cuentivo-ai` tiene trabajo sin confirmar de Diego: no la toques.
