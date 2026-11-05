# Cuentivo en iOS sin Mac

**Fecha:** 2026-11-04 · **Rama:** `diegodevxd/cuentivo-ios` (sale de `release/v49-mexalm`, 595ab0d)
**Orquesta:** Claude · **Implementa:** OpenCode con DeepSeek v4.1 Flash · **Audita:** Codex

## Objetivo

Dejar Cuentivo listo para iOS de forma que **el único muro sea pagar la cuenta de Apple Developer
(US$99/año)**. El día que se pague, solo faltan los pasos de la sección "Día de pago": cero
programación nueva.

Restricción: **Diego no tiene Mac** y no quiere comprarla ni rentarla.

## Decisión de arquitectura (no se discute en la implementación)

| Opción | Veredicto |
|---|---|
| **xtool** (compila iOS desde Windows/WSL) | **Descartado como ruta principal.** Solo compila apps Swift (SwiftPM), no Kotlin. Usarlo = reescribir las ~37,700 líneas en Swift y mantener dos apps para siempre. Tampoco sube a App Store/TestFlight. Queda como herramienta opcional (Fase 8). |
| Reescribir en SwiftUI | Descartado por lo mismo: doble mantenimiento de la lógica financiera, que es donde están las reglas innegociables. |
| **Kotlin Multiplatform + Compose Multiplatform + GitHub Actions macOS** | **Elegido.** Se reutiliza `domain/`, Room y la UI de Compose. El compilador de Kotlin para iOS exige macOS, pero **la Mac la pone GitHub Actions** (runner `macos-latest`) por minutos, sin comprar ni rentar nada. El mismo runner firma y sube a TestFlight cuando haya cuenta. |

Consecuencia práctica: en Windows se compila y prueba **todo el código común y Android**. Lo
específico de iOS solo se compila en CI. Por eso CI entra temprano (Fase 2), no al final.

## Reglas para el implementador (leer antes de cada tarea)

1. **Una tarea a la vez.** Cada tarea termina con su **puerta** (comandos exactos). Si la puerta no
   pasa, no se sigue: se reporta la salida real.
2. **Android no se puede romper.** Después de cada tarea corre (PowerShell, con el entorno Norton de
   `CLAUDE.md`):
   ```powershell
   $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
   $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
   .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
   ```
3. Reglas innegociables de `CLAUDE.md` siguen vigentes: dinero `Long` en centavos, nada de
   `Double`/`Float` con dinero, nada financiero se edita ni borra, todo filtra por `businessId`,
   nada de PII en logs.
4. **No cambiar comportamiento.** Mover código ≠ reescribirlo. Si un archivo necesita cambios de
   lógica para moverse, se deja donde está y se anota en "Pendientes" al final de este archivo.
5. **No subir `version` de Room, no tocar `app/schemas/`, no tocar `versionCode`.**
6. **Las pruebas no se reescriben para que pasen.** Si se mueven, deben seguir llamando al código
   de producción (ver `.claude/agents/test-honesty-reviewer.md`).
7. **Nada de deploy** (`firebase deploy`, Play, App Store). Nada de `git push` sin que Diego lo pida.
8. Commits pequeños, uno por tarea, mensaje en español: `ios(Fx.y): <qué>`.
9. Si algo de este plan no coincide con el código real (archivo, clase, versión), **para y
   reporta**; no inventes nombres.
10. Versiones de librerías: verifica en la documentación oficial la versión compatible con
    **Kotlin 2.2.10 y AGP 9.3.0** antes de agregarla. Si hace falta subir Kotlin, es una tarea
    aparte que se reporta primero.

---

## Fase 1 — Módulo `:shared` con el dominio puro

**Por qué primero:** es lo de menor riesgo y lo más valioso. `domain/` tiene ~7,000 líneas, solo
un archivo importa Android (`LegacyMigrationRepository.kt`, usa Room) y 12 usan APIs de Java.

**F1.1 — Crear el módulo vacío.**
- `shared/` con plugin Kotlin Multiplatform. Con AGP 9 el plugin Android para librerías KMP es
  `com.android.kotlin.multiplatform.library` (no `com.android.library`); verifícalo en la doc de
  AGP 9.
- Targets: `androidTarget` (o el bloque `androidLibrary {}` de ese plugin), `iosArm64`,
  `iosSimulatorArm64`. `namespace = "com.cuentivo.shared"`.
- En `gradle.properties`: `kotlin.native.ignoreDisabledTargets=true` (en Windows los targets iOS
  se desactivan solos; esto evita el aviso).
- `include(":shared")` en `settings.gradle.kts`; `app` depende de `project(":shared")`.
- Puerta: regla 2 + `.\gradlew.bat :shared:compileCommonMainKotlinMetadata ...`

**F1.2 — Mover los `object` sin imports de Java ni Android.**
- Lista: `grep -L "^import java\|^import android" app/src/main/java/com/cuentivo/app/domain/*.kt`.
  Excluye los `*Repository.kt` (dependen de DAOs; van en Fase 3).
- Se mueven a `shared/src/commonMain/kotlin/com/cuentivo/app/domain/` **conservando el paquete**
  `com.cuentivo.app.domain`, para que ningún import de `app` cambie.
- Mover en tandas de ~10 archivos con `git mv` (conserva historia). Las pruebas se quedan en
  `app/src/test` por ahora: siguen ejecutando el código movido.
- Si un archivo usa `R.string` o `Context`, no se mueve (no debería haber ninguno; ver regla de
  `CLAUDE.md` sobre `domain/`).
- Puerta: regla 2 + compileCommonMainKotlinMetadata. El número de pruebas de
  `testDebugUnitTest` debe ser **el mismo** que antes de empezar (anótalo en F1.1).

**F1.3 — Reemplazar APIs de Java por equivalentes multiplataforma.**
Para cada import, en este orden de preferencia:

| API Java | Reemplazo |
|---|---|
| `java.time.LocalDate/Instant/ZoneId` | `kotlinx-datetime` |
| `java.util.UUID` | `kotlin.uuid.Uuid` (opt-in `ExperimentalUuidApi`) |
| `java.security.MessageDigest` (SHA-256) | `expect fun sha256(bytes: ByteArray): ByteArray`; `actual` Android = MessageDigest, iOS = CommonCrypto `CC_SHA256` |
| `java.text.Normalizer` (`CustomerNameNormalizer`) | `expect/actual`; iOS = `NSString.decomposedStringWithCanonicalMapping` |
| `java.math.BigDecimal`, `NumberFormat`, `Currency`, `Locale` | Solo se usan para **mostrar** dinero. `expect fun formatMoney(cents: Long): String`; nunca convertir a `Double`. |
| `ZipInputStream`, `ByteArrayInputStream` (`XlsxReader`) | **Se queda en `app`** por ahora. Pendiente para iOS. |

- `CustomerNameNormalizer` alimenta `customers.normalizedName`: el `actual` de iOS debe dar
  **exactamente** la misma salida. Prueba en `commonTest` con casos con acentos, ñ, espacios dobles
  y mayúsculas, que corra en Android (`:shared:testAndroidHostTest` o el nombre que dé el plugin) y
  en CI en iOS.
- El hash de `SyncQueue` (`SHA-256("businessId|entityType|entityId|revision")`) debe ser idéntico
  en ambas plataformas: prueba con un vector fijo en `commonTest`.
- Un archivo por commit. Puerta: regla 2.

**F1.4 — Mover las pruebas del dominio a `shared/src/commonTest`** con `kotlin.test`, solo las de
archivos ya movidos. Puerta: mismas pruebas, mismo conteo total.

## Fase 2 — App iOS mínima y CI en macOS (prueba que la ruta sin Mac funciona)

**F2.1 — `iosApp/`** con proyecto Xcode mínimo (bundle id `com.cuentivo.app`, iOS 16+) que muestra
un `ComposeUIViewController` con un texto y el resultado de una función de `:shared` (por ejemplo,
formatear `12345` centavos). Generarlo a partir de la plantilla oficial de Kotlin Multiplatform
(kmp.jetbrains.com) y adaptar nombres; **no escribir el `.pbxproj` a mano**.
- Agregar Compose Multiplatform (`org.jetbrains.compose`) solo en `shared` para esta pantalla.

**F2.2 — `.github/workflows/ios-build.yml`:**
- `runs-on: macos-latest`, disparo **`workflow_dispatch` y push a `diegodevxd/cuentivo-ios`**
  (no en cada rama: los minutos de macOS cuestan 10× en repos privados).
- Pasos: checkout → JDK 21 → caché de Gradle y `~/.konan` → `./gradlew :shared:iosSimulatorArm64Test`
  → `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`.
- Subir el `.app` del simulador como artifact.
- **Sin firma y sin secretos.** Esto funciona hoy, sin cuenta de Apple.

**F2.3 — Workflow Android mínimo** (`android-check.yml`, `ubuntu-latest`): `testDebugUnitTest`.
Opcional; da la CI que el proyecto nunca tuvo.

Puerta de la fase: Diego hace push de la rama, el workflow pasa en verde y el artifact existe.
El implementador **no** puede verificar esto localmente: lo reporta como "pendiente de CI".

## Fase 3 — Room multiplataforma

Room 2.7.x ya soporta KMP. Plan:

**F3.1** — Mover entidades, DAOs y `CuentivoDatabase` a `shared/src/commonMain`, mismo paquete
`com.cuentivo.app.data.local`. KSP por target (`kspAndroid`, `kspIosArm64`,
`kspIosSimulatorArm64`). `@ConstructedBy` + `expect object CuentivoDatabaseConstructor`.
- `room { schemaDirectory("$projectDir/schemas") }` se mueve a `shared` y **apunta a la misma
  carpeta** `app/schemas` (o se mueve la carpeta con `git mv`). El JSON de la versión 8 no debe
  cambiar: `git diff --stat app/schemas` vacío después de compilar.

**F3.2 — Migraciones: se quedan en Android.** iOS siempre instala desde cero en la versión 8, así
que `CUENTIVO_MIGRATIONS` y sus pruebas (`Migration5To6Test`, `Migration6To7Test`) viven en
`app` (o `androidMain`). Los tres builders de `"cuentivo.db"` siguen registrándolas.
iOS: builder propio con `BundledSQLiteDriver` y sin migraciones.

**F3.3** — `withTransaction` (de `room-ktx`, solo Android) se usa en `MainActivity` y en
`LegacyMigrationRepository`. En código común se usa `useWriterConnection { it.immediateTransaction { } }`.
Cambiar solo donde el código se mueva a común; lo que se quede en `app` no se toca.

**F3.4** — Repositorios (`CustomerRepository`, `SaleRepository`, `PaymentRepository`,
`ProductRepository`, `DashboardRepository`) a `commonMain`.

Puerta: regla 2 **más** pruebas instrumentadas de Room en emulador
(`connectedDebugAndroidTest`, al menos `OperationDaoTest` y las de migración) y CI iOS en verde.
Esta fase es la de mayor riesgo para Android: si `OperationDaoTest` falla, se revierte la tarea.

## Fase 4 — Firebase multiplataforma

**Decisión:** interfaces en `commonMain`, implementación Android = el código actual sin cambios,
implementación iOS = **GitLive `firebase-kotlin-sdk`** (`dev.gitlive:firebase-auth`,
`firebase-firestore`, `firebase-functions`). Verificar versión compatible con Kotlin 2.2.10.

- `FirestoreSyncGateway`, `RemoteMerger`, `CloudRestore`, `FirestoreDeletionGateway`,
  `FirebaseCallableAiGateway`: extraer la interfaz que ya usan (`SyncContracts.kt`) y dejar las
  clases actuales como `actual` Android.
- `RemoteMerger` **rechaza `Double`** en dinero; la versión iOS debe rechazarlo igual, con prueba.
- Auth: Google Sign-In en iOS y **Sign in with Apple obligatorio** (guía 4.8 de App Store) cuando
  hay login con Google. Dejar el botón y el flujo de Firebase Auth con el proveedor `apple.com`;
  la configuración de la llave es del "Día de pago".
- App Check: en iOS, proveedor App Attest.
- Crashlytics/Analytics en iOS: vía SDK nativo desde `iosApp` (Swift Package Manager en Xcode).
- **Se puede hacer hoy sin cuenta de Apple:** registrar la app iOS en la consola de Firebase con el
  bundle id `com.cuentivo.app` y bajar `GoogleService-Info.plist`. **No va al repo** (igual que
  `google-services.json`): en CI se inyecta desde un secreto `GOOGLE_SERVICE_INFO_PLIST` (base64).
- Sincronización en segundo plano: WorkManager → `BGTaskScheduler` en iOS. Además sincronizar al
  abrir la app y al volver a primer plano (iOS no garantiza las tareas de fondo).

## Fase 5 — UI con Compose Multiplatform

- Mover pantallas de `ui/` a `shared/src/commonMain` **una pantalla por tarea**, empezando por las
  sin dependencias de plataforma (dashboard, clientes, productos, detalle de cliente).
- Strings: `res/values/strings.xml` y `values-en/strings.xml` → `composeResources/values/` y
  `composeResources/values-en/`. `stringResource(R.string.x)` → `stringResource(Res.string.x)`.
  **Se mueve el archivo completo de una vez** (702 claves en v49) para no partir la paridad, y se
  actualizan las rutas de las dos puertas de i18n y del hook `strings-parity.sh` en el mismo commit.
- Lo que depende de Android se aísla con interfaces inyectadas desde cada plataforma:

| Hoy (Android) | Interfaz común | iOS |
|---|---|---|
| Compartir (Intent) | `Sharer` | `UIActivityViewController` |
| Cámara/foto de libreta | `PhotoPicker` | `PHPickerViewController` / cámara |
| ML Kit text recognition | `TextRecognizer` | Vision `VNRecognizeTextRequest` |
| Notificaciones de cobro | `ReminderScheduler` | `UNUserNotificationCenter` |
| `Context`/archivos | `AppFiles` | `NSFileManager` |

- `MainActivity` sigue siendo el punto de entrada Android; en iOS el equivalente es
  `MainViewController.kt` + `iosApp`.
- Puerta por pantalla: regla 2, `CuentivoAppTest` (instrumentada) y CI iOS en verde.

## Fase 6 — Suscripciones con StoreKit

- `billing/` ya tiene `BillingGateway`: hacerlo interfaz común. Android = Play Billing actual.
  iOS = **StoreKit 2** en Swift dentro de `iosApp`, expuesto a Kotlin por la interfaz.
- Mismos productos que Play: Pro $199 MXN/mes y CuentivoAI (ver `cuentivo-playstore-planes`).
  IDs de producto iOS: `com.cuentivo.app.pro.monthly`, `com.cuentivo.app.ai.monthly` (definir aquí
  y usar los mismos el Día de pago).
- **Guía 3.1.1:** en iOS no puede haber enlaces ni textos que manden a pagar fuera de Apple.
- `functions/`: nueva función `verifyAppleSubscription` (App Store Server API, JWS firmado) y
  `handleAppStoreNotification` (Server Notifications v2), con pruebas Vitest con fixtures
  firmados de prueba. **Sin deploy.** El servidor sigue siendo la fuente de verdad del plan.
  Las llaves de la App Store Server API son del Día de pago; el código las lee de Secret Manager.
- Puerta: `npm --prefix functions run release:check` en verde.

## Fase 7 — Todo listo para subir

- `iosApp/Info.plist`: `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription` en español,
  `ITSAppUsesNonExemptEncryption = false`, `CFBundleDevelopmentRegion = es`.
- `PrivacyInfo.xcprivacy` (obligatorio): APIs de razón requerida que usen Firebase/SQLite y datos
  recolectados (coherente con la política de privacidad de `2026-08-30-cuentivoai-privacy.md`).
- Íconos 1024×1024 sin transparencia en `Assets.xcassets` (derivados del ícono Android actual).
- Capturas: workflow que corre la app en simulador y saca capturas para iPhone 6.9" y 6.5".
- `fastlane/` con `Fastfile` (`lane :beta` → `build_app` + `upload_to_testflight`) que usa la
  **App Store Connect API key** (sin usuario/contraseña ni 2FA).
- `.github/workflows/ios-release.yml`: `workflow_dispatch`; **si faltan los secretos, termina con
  un mensaje claro** ("Falta la cuenta de Apple: ver Día de pago"), no con un error críptico.
  Firma automática de `xcodebuild` con `-allowProvisioningUpdates` y la API key.
- `docs/ios-dia-de-pago.md`: copia de la sección de abajo, con capturas o enlaces.

## Fase 8 (opcional) — Probar en un iPhone real antes de pagar

Solo si Diego tiene iPhone. CI genera un `.ipa` sin firmar para dispositivo (`iosArm64`), y
**xtool** desde WSL lo firma con un Apple ID gratuito (cuenta desechable, usa APIs privadas) y lo
instala por USB. Las firmas gratuitas caducan a los 7 días. Verificar primero que `xtool install`
acepte un `.ipa` externo; si no, esta fase se descarta.

---

## Día de pago (lo único que queda después del plan)

1. Inscribirse en el Apple Developer Program (US$99/año). Valorar inscribirse también al **App
   Store Small Business Program** (comisión 15% en vez de 30%).
2. Certificates, IDs & Profiles: registrar el bundle id `com.cuentivo.app` con las capacidades
   *Sign in with Apple*, *In-App Purchase*, *Push Notifications* y *App Attest*.
3. App Store Connect → Users and Access → Integrations → crear **API key** (rol App Manager).
   Guardar en GitHub Secrets: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (base64),
   `APPLE_TEAM_ID`.
4. Crear la app en App Store Connect, el grupo de suscripción y los dos productos con los IDs de la
   Fase 6.
5. Llave de Sign in with Apple → consola de Firebase (proveedor Apple).
6. Llaves de la App Store Server API → Secret Manager; URL de Server Notifications v2 apuntando a
   `handleAppStoreNotification`. El deploy de funciones lo autoriza Diego.
7. GitHub → Actions → `ios-release` → Run. Sube a TestFlight.
8. Llenar ficha, privacidad y capturas; enviar a revisión.

## Costos de la ruta sin Mac

- Cuenta Apple: US$99/año.
- GitHub Actions: en repo privado, el plan gratis da 2,000 minutos/mes y macOS cuenta ×10
  (≈200 minutos de Mac). Un build iOS de KMP tarda ~15–25 min, así que alcanzan ~8–12 builds al
  mes. Por eso los workflows de iOS son manuales o solo de esta rama. Verificar precios vigentes.

## Pendientes (los llena el implementador)

- `XlsxReader` (usa `ZipInputStream`): pendiente para iOS.
- **Los `actual` de iOS no se compilan en Windows.** `Hashing.ios.kt` (CommonCrypto), `Normalization.ios.kt`
  (`decomposedStringWithCanonicalMapping`) y `Money.ios.kt` (`NSNumberFormatter`) sólo los valida el
  runner macOS de `ios-build.yml`. Cualquier error de interop ahí aparece en CI, no aquí.
- **20 archivos de `domain/` siguen en `app` porque importan `com.cuentivo.app.ai.ledger`**, que no
  está en `:shared`: `AiLedgerBatchPlanner`, `LedgerBatchCoordinator`, `LedgerCentPatternDetector`,
  `LedgerCorrectionCounter`, `LedgerCustomerMatching`, `LedgerDeterministicObjections`,
  `LedgerDueDatePolicy`, `LedgerDuplicateBlocking`, `LedgerDuplicateDetector`, `LedgerEffectiveDate`,
  `LedgerFieldHealth`, `LedgerImportMetricRecorder`, `LedgerImportSummaryCalculator`,
  `LedgerItemsDescription`, `LedgerObjectionConfirmation`, `LedgerPageCompleteness`,
  `LedgerPageResolution`, `LedgerPendingInDoubt`, `LedgerProductMatching`, `LedgerYearCorrection`.
  Moverlos exige decidir antes si `ai/ledger` va a `:shared` (no está en este plan).
- **4 archivos esperan a la Fase 3** porque usan entidades de Room: `LegacyMigrationPlanner`,
  `OpeningBalance`, `OpeningBalancePlanner`, `StatementCustomerRefresh`. Con ellos se desbloquean
  seis más que dependen de ellos: `BusinessDeletionPlanner`, `LedgerConfirmAndRefresh`,
  `LedgerConfirmRefreshDecision`, `LedgerOldDatesBulkCorrection`, `NewCustomerDraft`,
  `ProductSheetParser`.
- **Los 6 repositorios y `LegacyMigrationRepository`** son F3.4 por definición.
- **~120 pruebas siguen en `app/src/test`**, no en `commonTest`, porque prueban archivos que aún no
  se movieron (`Ledger*`, `OpeningBalance*`, `Migration*`, los DAO, la UI). `LedgerEntityMatcherTest`
  es el único caso de un archivo ya movido cuya prueba no pudo seguir: usa tipos de `ai.ledger`.
- `android-check.yml` genera un `app/google-services.json` de relleno porque el real no está en git
  y el plugin de Google Services lo exige para compilar. Sólo sirve para pruebas unitarias.

