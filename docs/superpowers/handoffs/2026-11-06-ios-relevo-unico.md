# Relevo único del port a iOS

**Este archivo sustituye a todos los relevos anteriores: no los leas.** Solo consúltalos con `grep` si una duda concreta lo exige. Al relevar, **reescribe este archivo**, no agregues otro.

**Meta (Diego):** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar Apple Developer. No te detengas al cerrar una tanda; lo manual de Diego va al final. Español informal.

**Papeles:** tú planeas, escribes encargos y auditas. **OpenCode con DeepSeek v4.1 Flash implementa, sin delegar.** Stack: KMP + Compose Multiplatform; iOS compila en GitHub Actions (`macos-26`). Repo `diegodevxd/Cuentivo`, **público**. Rama `diegodevxd/cuentivo-ios`.

**Gasta poco, es orden de Diego:** salidas acotadas (`grep`, rangos, `cut`, `head`), nunca archivos enteros. Sin subagentes. Releva solo si el contexto creció demasiado o antes de un tramo grande; no por monto. Si `ECC_GATEGUARD` sigue activo, da sus datos antes de la primera edición de cada archivo y reintenta idéntico.

## Estado

- **F5-I cerrada:** `5b52021`, `ios-build` run 42323599053, **928 pruebas iOS en 125 suites, 0 fallos**, leídas del artefacto, `xcodebuild` correcto y `.app` subido. `android-check` run 42323598967 verde. Android local **680 + 904 = 1584**, 0 fallos, leído por mí de los XML, en `792c211`. `assembleRelease` y `lintDebug` pasaron según el reporte.
- **F5-J cerrada:** `3f8d5e5`, `ios-build` run 42329215778, **1168 pruebas iOS en 137 suites, 0 fallos**, leídas del artefacto, con las clases nuevas presentes, `xcodebuild` correcto y `.app` subido. `android-check` run 42329215761 verde. Android local **1144 + 446 = 1590**, 0 fallos, leído por mí de los XML, medido en `cea812c`; `3f8d5e5` solo renombra una prueba y no se recorrió localmente.
- **F5-K auditada y subida, sin veredicto de iOS al escribir esto:** encargo `docs/superpowers/encargos/2026-11-06-ios-f5k-pantallas-ia.md`, código hasta `202b3c4`. Android local **1163 + 446 = 1609**, 0 fallos, leído por mí de los XML. De sus controles negativos solo existe `control6.log`: los otros cinco constan únicamente en el reporte. `androidTest` solo compiló. **Es la primera vez que CI compila `IosLedgerCapture` y `PlatformForeground.ios.kt`: espera errores de Native, que Windows no ve.** Lectura en curso sin cierre visible: igual que Android (`onDismissRequest = { }`), decidido no agregar nada.
- **Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima, y la `.app` nunca se ha abierto en un simulador en CI.** Dilo con esas palabras al entregar.

## Tu primer paso

1. Lee el veredicto de `ios-build` sobre F5-K del artefacto, con `MathExactTest`, `LedgerScreenFormatTest` y `LedgerCropGeometryTest` presentes. Si Native falla, corrígelo antes de seguir.
2. Sigue con la raíz común: plan propio antes del encargo.

## Lo que sigue, en orden

2. **`CuentivoApp.kt` y `MainActivity.onCreate` (~2577 líneas) hacia una raíz común, con plan propio antes del encargo. Sin esto iOS no tiene app.** Debe conectar:
   - `IosLedgerCapture(files, IosImagePickerFactory)` para `ledgerPagePickerFactory` y `ledgerPageFiles`, `IosVoicePermissionGateFactory`, y `voiceRecognizerFactory` con `IosVoiceRecognizer`, en `AiDebugLabScreen`. `IosLedgerCapture` debe crearse una sola vez y **`ledgerCapture` y las factorías, recordarse dentro de la rama que abre la pantalla**, como `CuentivoApp.kt:726`.
   - `photoStore` con `IosProductPhotoStore`, `imagePickers` con `IosImagePickerFactory`, `FirstDebtPreferenceStore(NsUserDefaultsFirstDebtFlagStore())`.
   - Transportes ya escritos en `:iosKit`: `IosAiTransport`, `IosAiAvailabilityGateway`, `IosMexalmLedgerWarmGateway`, `IosLedgerExtractionGateway`.
   - `CuentivoApp.kt:547` `onOpenLanguage`: `if (language.opensSystemSettings) language.openSystemSettings() else showLanguage = true`. `onOpenPrivacyPolicy` con `Sharer.openUrl(PRIVACY_POLICY_URL)`.
   - `deleteAccountWithApple` si el proveedor es `apple.com`. **`signOut()` en iOS lanza** (`IosAuthenticationGateway.kt:123`): atrápalo y no limpies datos locales.
   - `BackHandler` de `CuentivoApp.kt:466`: en iOS no hace nada, así que comprueba que `NewSale` tenga cierre visible.
   - `IosLocalReminderCenter.requestPermission`, tocar el aviso para abrir clientes vencidos, `reminders.sync()` al abrir, al volver a primer plano y tras escrituras financieras; `PlannedReminderScheduler` con `iosNowLocalDateTime`.
3. **Respaldo en iOS, dentro de la raíz.** Decisión provisional, ciérrala en el plan:
   - **Mismo formato que Android,** así un respaldo de Android abre en iPhone. Prueba con un archivo real cifrado por `BackupCrypto` como fixture en común.
   - **CommonCrypto no expone GCM** (contrastado en los bindings: hay `CCCryptorCreateWithMode`, CTR, ECB y `CCKeyDerivationPBKDF`). PBKDF2 directo con `CCKeyDerivationPBKDF`. GCM con `cryptography-kotlin` (whyoleg) y su proveedor CryptoKit, solo en `iosMain`: un puente Swift no se puede probar en el simulador. **Sin verificar:** soporte de AES-GCM en la versión 0.5.0 (hecha con Kotlin 2.2.0; el proyecto usa 2.2.10). Contrástalo en su repo y justifica la dependencia.
   - **Restaurar:** iOS no reinicia el proceso, así que cierra y reabre la base en la raíz.
4. **F6:** textos `subscription_*` por plataforma (Apple rechaza mencionar Google Play), StoreKit 2, `verifyAppleSubscription`, `handleAppStoreNotification`, `npm --prefix functions run release:check`, **sin deploy**.
5. **Resto de F7:** íconos, capturas, enlace de App Store en `StoreListing.ios.kt` y en `docs/ios-dia-de-pago.md`. Un arranque de la `.app` en simulador en CI, si cabe. Un smoke test con emuladores de Firebase sería valioso.
6. `PROJECT_MEMORY.md` al cerrar F5: unas 824 líneas, ~100 es el tope.

## OpenCode

- Terminal `term_daeb805b-4614-4094-beff-579f8e7990ad`, al ~60% de contexto. **Tras rechazar un permiso OpenCode se queda detenido aunque parezca seguir: mándale un mensaje que lo redirija.** Si pasa de ~80%, crea otra: `orca terminal create --worktree "path:<wt>" --title "OpenCode iOS" --command "opencode -m opencode-go/deepseek-v4.1-flash" --json`; espera a que cargue el TUI antes de enviar. Otras terminales `OC | ...` son viejas.
- **Reporte y temporales van en `build/ios-relevo/`, dentro del repo e ignorados por git.** El clasificador me negó concederle "Allow always" a `C:\tmp\ios-relevo`: **no lo intentes otra vez ni le des la vuelta.** Si pide cualquier permiso externo, recházalo (flecha derecha dos veces y Enter) y redirígelo al repo. Solo Diego puede aprobarlo.
- **Cada envío lleva Monitor** (regla de Diego), hasta 30 min y se rearma:
  `H=<handle>; W=<wt>; (python -u C:/tmp/ios-relevo/vigila_opencode.py $H $W/build/ios-relevo/<reporte> $W 2>&1 | grep --line-buffered -vE ' COMMIT ') & while true; do if orca terminal read --terminal $H --screen 2>/dev/null | grep -q 'Permission required'; then echo "PERMISO"; sleep 300; fi; sleep 30; done`
  `ATORO` tras entregar es falsa alarma. Para cortar un bucle, Esc simple (`--text $'\e'`), sin `--interrupt`.
- **Encargos:** en `docs/superpowers/encargos/`; modelo, el de F5-J. Líneas exactas medidas con `grep` y el fragmento delicado ya escrito: así salen verdes al primer intento. Siempre control negativo con **el nombre de la prueba que falló y por qué**. Pide rama y commit sacados de `git`. No lances Gradle mientras compila.
- **Nunca mandes `git commit` o `push` en el mismo bloque que ediciones:** GateGuard niega la edición y el commit corre igual.

## Trampas

- **Símbolos de iOS sin Mac:** `C:/tmp/kn-prebuilt/kotlin-native-prebuilt-macos-aarch64-2.2.10/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.<Framework>/`, con `grep -rlaF --include=*.knm`. El contraste lo haces tú, en `C:/tmp/ios-relevo/simbolos-<tanda>.txt`. **Las categorías de Objective-C, incluidas las de compañero (`NSDate.dateWith...`), requieren `import platform.Foundation.<nombre>`.** `UNMutableNotificationContent.title` se asigna con `setTitle`; `NSDateComponents` con `setYear`. El `companion` de una subclase de `NSObject` no admite campos. Dentro de una clase que implementa `ImagePickerFactory`, importa `remember as composeRemember`.
- **Lee `iosMain` buscando lo que Windows no ve antes de subir:** `CPointer<ByteVar>` no entra donde se pide `UByteVar`; `private var` asignado desde afuera.
- **`commonMain` no nombra relojes ni `Instant`:** Android va con `kotlinx-datetime` 0.6.2 e iOS con 0.7.1. Usa `nowEpochMillis()`, `localDayIso()`, `todayEpochDay()` y `newUuid()`. `:shared:compileCommonMainKotlinMetadata` corre en Windows y caza esto.
- **Native rechaza en `commonTest`:** comas y `. ; : / \ < > [ ] ( )` en nombres entre backticks (los paréntesis tumbaron `ios-build` de F5-J y mi `grep` no los miraba), `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`, `java.*`, `Charsets`. `assertEquals(Int, Long)` no iguala. `:iosKit` no tiene pruebas: la lógica pura va a `:shared`. `NSNumber(longLong = x)`. `ARCHS=arm64`.
- **Solo Android:** `ProductImageStore`, `CameraCaptureStore`, `AndroidImagePickerFactory`, `AndroidLanguageControl`, `LanguagePreference`. Los servicios de plataforma entran a las pantallas por parámetro, sin valor por defecto, ni `CompositionLocal` ni `expect`. Al mover pantallas, pide `:app:compileDebugAndroidTestKotlin`.
- **Hacen menos de lo que parecen:** una guardia por directorio deja de mirar lo que se mueve, y `assertFails` puede fallar por el motivo equivocado. Compara el reporte contra `git log`; sus encabezados y bases ya han salido mal.

## Puertas y CI

- **Android** (entorno Norton de `CLAUDE.md`, con `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`): `:shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin`, y una vez `assembleRelease lintDebug`. La PC se queda sin memoria: no reintentes en bucle. Puertas de i18n en `CLAUDE.md`.
- **Veredicto:** `gh api "repos/diegodevxd/Cuentivo/actions/runs/<id>/jobs?filter=latest" --jq '.jobs[]|.steps[]|"\(.name): \(.conclusion)"'`. Errores: `gh run view <id> --repo diegodevxd/Cuentivo --log-failed`, filtrando `e: file:`, `: error:`, `Undefined symbol`. Pruebas: `gh run download <id> --repo diegodevxd/Cuentivo --name resultados-pruebas-ios-macos-26 --dir <carpeta nueva>`, cuenta XML. **Pasa el `<id>` siempre.**
- **Verde** es pruebas iOS leídas del artefacto, con las clases nuevas presentes, `xcodebuild` y `android-check`. `macos-26` a veces tarda horas en dar runner: no es fallo del código. Un push solo de docs gasta un run; no canceles un run en vuelo con otro push, porque el workflow tiene `cancel-in-progress`.

## Puerta abierta

Sin correr: `ResLocaleTest`, `CuentivoAppTest`, `ProductPhotoSourceTest`, `AuthenticationFlowTest`, `EmailVerificationScreenTest`, `SaleFlowTest`, `PaymentFlowTest`, `CustomerFlowTest`, `SubscriptionFlowTest`, y las de `androidTest` de IA (`AiPaymentExecutionIsolationTest`, `AiSaleExecutionIsolationTest`, `AiPaymentPreviewIsolationTest`, `LedgerReviewSaverBundleTest`, `LedgerRestoreConfirmSingleWriteTest`). **No relances el emulador en bucle:** una vez quedó offline por GPU y otra el sistema mató a Claude Code por falta de memoria. **Ruta: el teléfono de Diego** (A059P); mira `adb devices`, y si no está, sigue. Sin probar en iOS: selector de fotos y de documentos, voz, avisos, `IosLanguageControl`, y que `NSLocale.preferredLanguages` refleje Ajustes.

## Reglas

Dinero en `Long`; nada financiero se borra ni se edita. **Sin `firebase deploy` ni deploy de `functions` sin autorización aparte de Diego.** Revisa secretos antes de cada push. Nunca abras ni imprimas `keystore.properties`, `*.jks`, `google-services.json`, llaves ni el plist. No toques `feature-cuentivo-ai`. Diego autorizó que hagas push y secretos de GitHub. **Cada sesión nueva se abre con Remote Control** (`claude --model opus --remote-control "Cuentivo iOS <tanda>"`), compruebas que conectó, le dices a Diego el nombre, y confirmas antes de cerrar la anterior; si no identificas tu terminal con certeza, no cierres nada. Sugiere `ECC_GATEGUARD=off`.

## Decirle a Diego al entregar

- **Recordatorios en iOS:** si alguien paga desde otro teléfono y esta app no se abre, llega un aviso viejo; si no se abre en 7 días, dejan de llegar.
- **Voz en iOS:** los temporizadores (1.5 s, 6 s, 5 s) son valores elegidos sin probar con voz real.
- **Divergencia de `kotlinx-datetime`** entre Android e iOS.
- **Revisión de libreta en iOS:** si el sistema mata la app durante la revisión de una página, se pierde y hay que volver a leerla, y cada lectura cuesta.
- **Recorte en iOS:** sin reducir a 1600 px, porque la imagen ya viene limitada a 2400. Selector de páginas de libreta, cámara y recorte sin probar en iPhone.
- GitHub reporta vulnerabilidades de Dependabot en la rama por defecto; no las he revisado y no son de este trabajo.

## Lista manual de Diego, al final y paso a paso

1. Conectar el teléfono en depuración para correr la puerta abierta, antes de publicar Android con estas pantallas.
2. Bajar una imagen API 31 en Android Studio: debajo de API 33 nada se ha probado.
3. Pagar Apple cuando la app funcione: `docs/ios-dia-de-pago.md`.
