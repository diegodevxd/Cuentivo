# Encargo iOS — F4-D: login, App Check, Functions, borrado de cuenta y restauración

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F4-D): ...`, uno por tarea.

Windows no compila iOS: lo verifica el runner. **No adivines nombres:** confírmalos en la documentación oficial o en el
fuente de GitLive v2.8.0 (`firebase-auth/src/appleMain`, `firebase-functions/src/appleMain`), en crudo en
`raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/`. El interop es `cocoapods.FirebaseAuth`, etc. Al llamar
desde Swift, un `Boolean` de Kotlin dentro de una lambda llega como `KotlinBoolean`: usa `.boolValue`. Código común sin
JVM, y en nombres entre backticks ninguno de `, . ; : / \ < > [ ]`. `:shared` no depende de Firebase.

**Fuera de este encargo:** pantallas. Tampoco se configura nada en consolas ni se despliega nada.

## Tareas

**D1. Contrato común de auth.** Mueve a `commonMain`, mismo paquete, `AuthenticationGateway`, `GoogleAuthenticationResult` y
lo que sea puro de `app/.../auth/` (`AuthenticationFlowPolicy`, `GuestAccountSwitchPolicy`, `LocalSessionGuard` si no usan
Android), con sus pruebas. `Activity` se sustituye por `expect class PlatformAuthHost`, con
`actual typealias PlatformAuthHost = android.app.Activity` en Android, **sin cambios de comportamiento ni de firmas efectivas**,
y `UIViewController` en iOS. Lo que use Android de verdad se queda en `app`.

**D2. Nonce de Apple, con pruebas en iOS.** En `shared/src/iosMain`, un generador de nonce aleatorio seguro
(`SecRandomCopyBytes`, **nunca** `kotlin.random`) y su SHA-256 en hexadecimal, reutilizando el `sha256` existente. Pruebas en
`shared/src/iosTest`: longitud, caracteres, dos nonces distintos y un vector SHA-256 conocido.

**D3. `iosKit/.../auth/IosAuthenticationGateway.kt`**: implementa todo `AuthenticationGateway`, con la misma semántica que la
versión Android, incluidos los casos de cuenta invitada, vinculación y fallo. Lee la clase Android completa.
- Google y Apple requieren SDK de Swift: define en Kotlin `IosGoogleSignInProvider` e `IosAppleSignInProvider`, implementados
  en Swift en `iosApp/` y registrados al arrancar. Kotlin recibe `idToken`, `accessToken` y `rawNonce`, y construye la credencial.
- Google: `GoogleSignIn-iOS` por SPM (versión exacta estable; anótala), con el `CLIENT_ID` leído de `FirebaseApp.app()?.options.clientID`.
- Apple: `AuthenticationServices`, con el nonce de D2, `credential` del proveedor `apple.com`, y flujo de vinculación equivalente al de Google.
- **Borrado de cuenta con Apple:** reautentica y revoca con `revokeToken(withAuthorizationCode:)` antes de borrar. App Store lo exige.

**D4. Proyecto y permisos.**
- `iosApp/iosApp/iosApp.entitlements` con `com.apple.developer.applesignin = [Default]` y
  `com.apple.developer.devicecheck.appattest-environment = production`, y `CODE_SIGN_ENTITLEMENTS` en Debug y Release.
- URL scheme de Google: es el `REVERSED_CLIENT_ID` del plist, que **no va al repo**. En `ios-build.yml` e `ios-release.yml`,
  después de decodificar el plist, un paso con `PlistBuddy` que lo copie a `CFBundleURLTypes` del `Info.plist` durante la corrida.
  Sin imprimir el valor. Si la clave no existe en el plist, falla con
  `::error::GoogleService-Info.plist sin REVERSED_CLIENT_ID: activar Google para iOS en Firebase y volver a descargarlo`.
- **Nunca abras ni imprimas `GoogleService-Info.plist`.** Si necesitas su estructura, usa la documentación de Firebase.

**D5. App Check en Swift**, en `AppDelegate`, **antes** de `FirebaseApp.configure()`: `AppCheckDebugProviderFactory` en
`#if DEBUG` o simulador, y en lo demás una factory con `AppAttestProvider`. Lee cómo Android elige proveedor (`AppCheckInstaller`).

**D6. `iosKit/.../ai/IosCallableAiGateway.kt`**: traduce `FirebaseCallableAiGateway` con la API de funciones de GitLive o nativa:
mismos nombres de función, misma región, mismo mapeo de errores, y los enteros del payload no pueden pasar a double. Usa
`normalizeFirestoreValue` y `StrictJson` donde aplique. Extrae primero a común el contrato si aún no está. **Haz lo mismo** con
`FirestoreDeletionGateway` → `IosFirestoreDeletionGateway`. Y `CloudRestore` → pásalo a común cambiando `android.util.Log` por una
interfaz o `expect` mínimo, con Android sin cambios; si no se puede sin alterar su comportamiento, repórtalo.

**D7. Documenta en `docs/ios-dia-de-pago.md`** lo manual que crea este encargo, paso a paso y con rutas exactas: Apple Developer
(Sign in with Apple y App Attest en el App ID), Firebase Auth (proveedor Apple, Service ID, llave), Firebase App Check (registrar
App Attest), y volver a descargar el plist si hace falta.

**D8. Puerta Android**, con el entorno de Norton, **una sola corrida**, porque la PC tiene poca memoria:
`.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain`.
Cuenta del XML, 0 fallos, y el total de `app` + `:shared` no baja.

## Reglas

- Nada de PII, tokens ni saldos en logs (regla 4). Un `idToken` o `rawNonce` no se imprime ni en errores.
- Nada de `Double`/`Float` con dinero.
- Si algo no se puede hacer igual, para esa parte, repórtala y sigue con lo demás en commits limpios.
- Sin dependencias nuevas fuera de `GoogleSignIn-iOS`. Antes de cada commit: `git status --short` sin plist, `google-services.json` ni `local.properties`.

## Reporte

`C:/tmp/oc-ios-f4d-reporte.md`: archivos, tabla Android → iOS por método, diferencias, cuenta del XML, hashes y lo no verificado.
Al terminar: `ENCARGO-IOS-F4D-TERMINADO`.
