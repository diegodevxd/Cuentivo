# Relevo iOS: F5-C cerrada, sigue selector de documentos y pantallas

**La meta, confirmada por Diego el 2026-11-06:** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar la cuenta de Apple Developer. No te detengas al cerrar una tanda: sigue con la siguiente fase, y deja lo manual de Diego para el final.

**Lee primero** `docs/superpowers/handoffs/2026-11-05-ios-relevo.md` (papel, OpenCode, cómo auditar, trampas de iOS, reglas); de `2026-11-06-ios-relevo-f5c.md`, las secciones "Puerta abierta", "Tandas que siguen", "Pendientes que siguen vivos" y "Lista manual de Diego"; y de `2026-11-06-ios-relevo-f5d.md`, "Decisiones de F5-C" y "Reglas que no cambian". Sus secciones de estado y primer paso ya caducaron; manda este archivo. Lee también `CLAUDE.md` y la memoria `cuentivo-ios-sin-mac`, `cuentivo-reparto-de-agentes`, `relevo-de-sesiones-caras` y `working-agreements`. Modelo de encargo: `docs/superpowers/encargos/2026-11-06-ios-f5c-compila.md`.

## Estado al 2026-11-06

- **F5-C cerrada en `eef034c`:** `ios-build` run 42289930574 verde, con **675 pruebas iOS en 96 suites y 0 fallos** (leídas por mí del artefacto `resultados-pruebas-ios-macos-26`), `xcodebuild` correcto y `.app` subido. `android-check` verde en el mismo commit.
- **Android local:** 872 + 655 = 1527, 0 fallos, leído de los XML, en `e1a9407`. Lo posterior solo toca `iosMain` y docs.
- `ResLocaleTest` y `CuentivoAppTest` **siguen sin correr**; `adb devices` estaba vacío.
- Lo que compile y pase en simulador no es lo mismo que funcionar: **la `.app` nunca se ha abierto en un simulador en CI** y las interfaces de F5-C (voz, selector de imagen, avisos, compartir) **no tienen ejecución real**.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima.** Dilo con esas palabras.

**Este archivo está en un commit local sin subir:** súbelo junto con la siguiente tanda, porque un push solo de docs gasta un run de iOS.

## Tu primer paso

Escribe el encargo del **selector de documentos y `XlsxReader`**:

- `app/src/main/java/com/cuentivo/app/backup/BackupSection.kt` y `ui/product/ProductsScreen.kt` usan `OpenDocument`/`CreateDocument`. `AppFiles` **no** cubre eso: hace falta una interfaz nueva, con `actual` en Android que conserve el comportamiento de hoy y en iOS con `UIDocumentPickerViewController`.
- `app/src/main/java/com/cuentivo/app/domain/XlsxReader.kt` usa `ZipInputStream`, que no existe en Native. **Decide tú cómo leer ZIP en iOS antes del encargo y deja la decisión escrita.** No agregues una dependencia sin justificarla.
- `XlsxReader` debe llegar a `commonMain` con sus pruebas en `commonTest`, para que corran en iOS. Control negativo obligatorio.

Luego sigue el orden de abajo.

## Después

1. **Pantallas candidatas directas:** `AuthenticationScreen`, `EmailVerificationScreen`, `PaymentScreen`, `FirstDebtScreen`, `SubscriptionSheet`, `AiPaywallSheet`, `OpeningBalanceImportDialog`, y los `*Presentation`/`*Policy` de `customer`, `dashboard`, `product`, `account`, `onboarding`, `ai`. Al mover `ProductPhoto`, implementa `ImagePickerFactory` en Android conservando `rememberSaveable`, `deleteStale` y las dos carpetas. Decide cuándo mover `ai/ledger` y 20 archivos de `domain/`. **Hasta correr `ProductPhotoSourceTest` en el teléfono no está probado.**
2. **`MainActivity.onCreate` (~2577 líneas) hacia una raíz común:** plan propio antes del encargo. Debe conectar `IosLocalReminderCenter.requestPermission`, tocar el aviso para abrir clientes vencidos (`UNUserNotificationCenterDelegate`), `reminders.sync()` al abrir, al volver a primer plano y tras escrituras financieras, y construir `PlannedReminderScheduler` con `iosNowLocalDateTime`.
3. `deleteAccountWithApple` si el proveedor es `apple.com`; `signOut()` que puede lanzar (atrapar y no limpiar datos locales); selector de idioma y verificar `NSLocale.preferredLanguages`.
4. **F6:** textos `subscription_*` por plataforma (Apple rechaza mencionar Google Play), StoreKit 2, `verifyAppleSubscription`, `handleAppStoreNotification`, `npm --prefix functions run release:check`, **sin deploy**.
5. **Resto de F7:** íconos, capturas. Enlace de App Store en `StoreListing.ios.kt`, y agrégalo a `docs/ios-dia-de-pago.md`.
6. Al cerrar F5, actualiza `PROJECT_MEMORY.md`: su sección de iOS está vieja y el archivo tiene 824 líneas, muy por encima de las ~100 que pide `CLAUDE.md`.

## Hallazgos de esta sesión (úsalos)

- **Android compila contra `kotlinx-datetime` 0.6.2 e iOS contra 0.7.1,** por resolución de conflictos. Por eso `commonMain` **no debe nombrar ningún reloj**: se inyecta, y en iOS se usa `kotlin.time.Clock` (`IosClock.kt`). Es una divergencia viva: dile a Diego que queda como riesgo.
- **`:shared:compileCommonMainKotlinMetadata` corre en Windows** y compila `commonMain` contra la 0.7.1. Va en la puerta de todo encargo: caza errores sin gastar CI.
- **Símbolos reales de iOS sin Mac:** `C:/tmp/kn-prebuilt/kotlin-native-prebuilt-macos-aarch64-2.2.10/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.<Framework>/`. `grep -rlaF --include=*.knm "NOMBRE" <dir>` da el framework y `grep -a` da los selectores. **Contrasta tú y deja el resultado en un archivo dentro de `C:/tmp/ios-relevo/`, como `simbolos-f5c.txt`:** gracias a eso, 44 errores se resolvieron con dos vueltas de CI. Los `NO platform.CoreCrypto.*` son falso negativo.
- **Kotlin/Native:** las categorías de Objective-C requieren import explícito (`platform.UIKit.popoverPresentationController`). `UNMutableNotificationContent.title/body/sound` se ven como `val`: usa `setTitle/setBody/setSound`. **El `companion` de una subclase de `NSObject` no admite campos** (`Fields are not supported`): usa `private const val` de archivo. Este error salió en una capa posterior, que no aparece hasta corregir los de resolución de símbolos.
- `setValue(it, forKey = "subject")` en `IosSharer` es KVC y **nunca se ha ejecutado**.
- **Lee línea y columna exactas antes de escribir la pista.** Ya me equivoqué dos veces en encargos y relevos por suponer de qué trataba un error.

## OpenCode y permisos

- Terminal activa en Orca, título `OC | ...`; búscala con `orca terminal list`. Si reinició, crea otra y concede "Allow always" a `C:\tmp\ios-relevo\*` al primer aviso. Modelo `opencode-go/deepseek-v4.1-flash`, sin delegar. Cada envío lleva Monitor con `C:/tmp/ios-relevo/vigila_opencode.py <handle> <reporte> <worktree>` (expira a 30 min).
- Encargos: reporte en `C:/tmp/ios-relevo/` y **"no pidas acceso a nada más fuera del repo y C:/tmp/ios-relevo"**.
- **El clasificador de permisos me negó conceder a OpenCode acceso a `C:\tmp\kn-prebuilt`.** No lo sortees: rechaza el aviso, haz tú el contraste o pide a Diego que lo apruebe en su terminal.
- La terminal puede quedarse en aviso sin que el vigilante avise hasta 12 min: un Monitor que busque `Permission required` en `orca terminal read --terminal <h> --screen` lo caza antes. `ATORO` tras entregar el reporte es falsa alarma.
- **Nunca mandes `git commit`/`push` en el mismo bloque que ediciones:** GateGuard niega la primera edición de cada archivo y el commit correría igual.

## CI

- Espera con `C:/tmp/ios-relevo/espera_ios.sh <sha completo>` en segundo plano: reintenta sin ruido cuando no hay runner.
- **`macos-26` a veces tarda horas en conseguir runner:** `1e46efd` necesitó 7 intentos, cada uno cancelado a los 15 min sin runner. No es un fallo del código; no lo diagnostiques como tal. El siguiente commit lo consiguió al primero.
- Veredicto: `gh api repos/diegodevxd/Cuentivo/actions/runs/<id>/jobs?filter=latest --jq '.jobs[]|.steps[]|"\(.name): \(.conclusion)"'`. Errores: `gh run view <id> --repo diegodevxd/Cuentivo --log-failed`, filtrando `e: file:`, `: error:` (Kotlin también sale así en el segundo intento de Gradle), `.swift:N:N: error`, `Undefined symbol`. Pruebas: `gh run download <id> --repo diegodevxd/Cuentivo --name resultados-pruebas-ios-macos-26 --dir <carpeta nueva>`, y cuenta los XML.
- Verde significa pruebas iOS leídas del artefacto, `xcodebuild` y `android-check`. **Comprueba que las clases nuevas aparecen en el artefacto,** no solo el total.

## Decirle a Diego al entregar

- Recordatorios en iOS: si alguien paga desde otro teléfono y esta app no se abre, llega un aviso viejo; si no se abre en 7 días, dejan de llegar.
- Voz en iOS: los temporizadores son valores elegidos sin probar con voz real.
- Divergencia de `kotlinx-datetime` entre Android e iOS.
- GitHub reporta 2 vulnerabilidades de Dependabot en la rama por defecto del repo (1 alta, 1 moderada). No las he revisado y no son de este trabajo.

## Lista manual de Diego (para el final, paso a paso)

1. Conectar el teléfono en depuración para correr `ResLocaleTest`, `CuentivoAppTest` y `ProductPhotoSourceTest`.
2. Bajar una imagen API 31 en Android Studio antes de publicar Android con estas pantallas.
3. Pagar Apple cuando la app funcione: `docs/ios-dia-de-pago.md`.

## Reglas que no cambian

Dinero en `Long`; nada financiero se borra ni se edita; **sin `firebase deploy` ni deploy de `functions` sin autorización aparte de Diego**; repo público, revisa secretos antes de cada push. Nunca abras ni imprimas `keystore.properties`, `*.jks`, `google-services.json`, llaves ni el plist. No toques la rama `feature-cuentivo-ai`. Repite este relevo cerca de $50-$80 o antes de un tramo grande. Esta sesión cerró con ~$60.
