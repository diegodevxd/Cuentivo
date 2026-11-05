# Día de pago — subir Cuentivo a TestFlight y luego a la App Store

Esta guía es la sección "Día de pago" del plan (`2026-11-04-cuentivo-ios-sin-mac.md`), paso a paso.
Todo lo que se puede dejar hecho **antes** de pagar está en la parte 1.

---

## 1. Lo que se puede hacer gratis, hoy

### 1.1 Registrar la app de iOS en Firebase

La app de iOS usa el mismo proyecto de Firebase que la de Android.

1. Consola de Firebase → **Configuración del proyecto** → pestaña **Tus apps** → **Agregar app** → iOS.
2. *Apple bundle ID*: **`com.cuentivo.app`**. El nombre y el App Store ID se pueden dejar vacíos.
3. Descargar **`GoogleService-Info.plist`** y **no** comitearlo: ya está en `.gitignore`.
4. Guardarlo como secreto en GitHub (paso 1.3).

> Mientras la Fase 4 no esté cableada en el target de iOS, este plist no lo lee todavía ningún
> código; se sube para que el día de pago no falte nada.

### 1.2 Subir la rama y correr `ios-build`

Los workflows existen pero nunca se han ejecutado en GitHub (no hubo push en toda la sesión).

1. `git push -u origin diegodevxd/cuentivo-ios`
2. GitHub → **Actions** → **ios-build** → **Run workflow**.
3. Qué debe pasar: `:shared:iosSimulatorArm64Test` en verde, `xcodebuild` sin firma en verde y el
   artifact **`Cuentivo-ios-simulator`** disponible al final.

Si algo falla ahí, es casi seguro en los `actual` de iOS (CommonCrypto, `NSString`,
`NSNumberFormatter`, Room con `BundledSQLiteDriver`), que **nunca se compilaron en Windows**.

### 1.3 Guardar el plist como secreto

1. En Windows, generar el base64:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\ruta\GoogleService-Info.plist")) | Set-Clipboard
   ```

   `Set-Clipboard` lo deja listo para pegar. Si no existe, quitar ese tramo y copiar la salida a mano.

2. GitHub → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:
   - Nombre: **`GOOGLE_SERVICE_INFO_PLIST`**
   - Valor: el base64 del paso anterior.

---

## 2. Lo que exige pagar

### 2.1 Apple Developer Program

1. <https://developer.apple.com/programs/> → **Enroll** → cuenta de Apple de Diego → US$99 al año.
2. Valorar también el **App Store Small Business Program** (<https://developer.apple.com/app-store/small-business-program/>):
   comisión del 15 % en vez del 30 % si se factura menos de US$1 M al año.

### 2.2 Bundle id y capacidades

1. <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles** → **Identifiers** → **+**.
2. Tipo **App IDs** → **App** → *Bundle ID (explicit)*: **`com.cuentivo.app`**.
3. Marcar las capacidades:
   - **Sign in with Apple**
   - **In-App Purchase**
   - **Push Notifications**
   - **App Attest**
4. **Continue** → **Register**.

### 2.3 App Store Connect API key (la que usa fastlane)

1. <https://appstoreconnect.apple.com> → **Users and Access** → pestaña **Integrations** →
   **App Store Connect API** → **Team Keys** → **+**.
2. Nombre: `github-actions`. Acceso: **App Manager**.
3. Anotar el **Key ID** y el **Issuer ID** que muestra la página.
4. Descargar el **`.p8`**. **Sólo se puede descargar una vez**; si se pierde, hay que revocar la llave
   y crear otra.

### 2.4 Los cuatro secretos de Apple en GitHub

**Settings** → **Secrets and variables** → **Actions** → **New repository secret**, uno por uno:

| Secreto | De dónde sale |
|---|---|
| `ASC_KEY_ID` | El *Key ID* de la llave del paso 2.3 |
| `ASC_ISSUER_ID` | El *Issuer ID* de la misma página |
| `ASC_KEY_P8` | El contenido del `.p8` en base64 (comando de abajo) |
| `APPLE_TEAM_ID` | <https://developer.apple.com/account> → *Membership details* → **Team ID** (10 caracteres) |

El `.p8` en base64, en Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\ruta\AuthKey_XXXXXXXXXX.p8")) | Set-Clipboard
```

El equipo en GitHub distingue el secreto `APPLE_TEAM_ID` (lo lee fastlane) del `DEVELOPMENT_TEAM`
que espera `xcodebuild`; el `Fastfile` los conecta con
`xcargs "-allowProvisioningUpdates DEVELOPMENT_TEAM=#{ENV['APPLE_TEAM_ID']}"`.

### 2.5 La app en App Store Connect

1. <https://appstoreconnect.apple.com> → **My Apps** → **+** → **New App**.
2. Plataforma **iOS**, nombre **Cuentivo**, idioma principal **Spanish (Mexico)**, *Bundle ID*
   `com.cuentivo.app`, SKU `cuentivo-ios`, acceso **Full Access**.
3. **App Information** → categoría: *Business* (Finanzas si se prefiere). Contenido: 4+.
4. **Subscriptions** → crear el **grupo de suscripción** y los dos productos con los IDs de la Fase 6
   (los mismos que usa el código de StoreKit). Sin productos cargados, TestFlight funciona igual, pero
   la compra no.

### 2.6 Sign in with Apple y las notificaciones de la App Store

1. Consola de Firebase → **Authentication** → **Sign-in method** → **Apple** → configurar con la
   *Services ID* y la llave `.p8` que genera Apple (distinta de la de App Store Connect).
2. App Store Server API: generar una llave en **Users and Access → Integrations → In-App Purchase**
   y guardarla en Secret Manager. La URL de *Server Notifications V2* apunta a
   `handleAppStoreNotification` de `functions/`. **El deploy de funciones lo autoriza Diego.**

---

## 3. Subir a TestFlight

1. GitHub → **Actions** → **ios-release** → **Run workflow**.
2. El primer paso comprueba los cinco secretos. Si falta alguno, el run termina con:
   `::error::Falta la cuenta de Apple o un secreto: ver docs/ios-dia-de-pago.md`
3. Si están todos: decodifica `GoogleService-Info.plist`, instala Ruby + fastlane, compila con
   `build_app` (firma automática con `-allowProvisioningUpdates`) y sube con
   `upload_to_testflight(skip_waiting_for_build_processing: true)`.
4. En App Store Connect → **TestFlight** la build aparece en unos minutos. La primera vez Apple pide
   completar **Export Compliance** (aquí ya va contestado con `ITSAppUsesNonExemptEncryption = false`
   en el `Info.plist`).

## 4. Enviar a revisión

1. **App Store** → completar ficha: nombre, subtítulo, descripción, palabras clave, soporte, política
   de privacidad, capturas de 6.9" y 6.5".
2. **App Privacy**: las respuestas deben coincidir con `iosApp/iosApp/PrivacyInfo.xcprivacy` y con la
   política pública:
   - Sin *tracking*.
   - Datos ligados al usuario: identificador de usuario, contenido del negocio (nombres y montos de
     clientes), información financiera de la libreta, y fotos cuando se usa la libreta por foto.
   - Todo con propósito **App Functionality**.
3. Seleccionar la build de TestFlight → **Submit for Review**.

---

## 5. Si algo falla

| Síntoma | Causa probable |
|---|---|
| `Permission denied` al correr `./gradlew` | El bit de ejecución se perdió en el commit; `git ls-files -s gradlew` debe decir `100755` |
| `No signing certificate "iOS Distribution" found` | Falta `APPLE_TEAM_ID` o la llave no tiene rol App Manager |
| `Invalid API Key` / `401` | `ASC_KEY_ID` o `ASC_ISSUER_ID` no corresponden a la llave, o el `.p8` no está en base64 |
| La build sube pero no aparece en TestFlight | Faltan los *Export Compliance* o la app no tiene bundle id registrado |
| `ios-build` falla en `linkPodDebugFrameworkIosSimulatorArm64` | Es el KSP/Room de iOS: mirar el log del runner macOS, no Windows |
