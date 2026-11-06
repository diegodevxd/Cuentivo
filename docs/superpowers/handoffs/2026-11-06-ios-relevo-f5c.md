# Relevo iOS: de F5-B a F5-C

**Lee primero** `docs/superpowers/handoffs/2026-11-05-ios-relevo.md` (papel, OpenCode, cómo auditar, trampas de iOS, reglas) y las "Trampas de interop Swift" y "Pendientes" de
`2026-11-06-ios-relevo-f5.md`. Sus secciones de estado y primer paso ya caducaron; manda este archivo. Lee también `CLAUDE.md`, el plan
`docs/superpowers/plans/2026-11-04-cuentivo-ios-sin-mac.md` (F5 a F7) y la memoria `cuentivo-ios-sin-mac`, `cuentivo-reparto-de-agentes` y `relevo-de-sesiones-caras`.

## Estado al 2026-11-06

- **F5-A cerrada:** `08a391b`, run 42162316878. Textos en `shared/src/commonMain/composeResources`; `:app` genera sus `strings.xml` (`GenerateAndroidStringsTask`). El `.app` lleva los recursos.
- **F5-B en verde en CI, puerta instrumentada abierta:** `815f20b`, run 42168852334, `ios-build` y `android-check` verdes, **621/621 en simulador iOS**. Android local según el reporte de OpenCode: 883 + 603 = 1486, 0 fallos, con `assembleRelease` y `lintDebug` (no los repetí yo; leí reporte y diff).
  Movidos: tema, `NeoFintechTokens`, `NeoFintechLayoutPolicy`, `NavigationLayoutPolicy`, `UiModels`, `NeoFormFields`, `NeoFintechComponents`, `NeoFintechShell`, `NeoFormComponents`, `AlertsSheet`, `rememberMotionEnabled` como `expect`/`actual`.
- Sin subir: solo el commit local con este archivo y `ResLocaleTest`. Súbelo junto con lo siguiente: un push solo de docs gasta un run de iOS.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima: lo movido compila, pero nadie lo muestra aún.** Dilo con esas palabras.

## Puerta abierta: `Res.string` nunca ha corrido en Android

`app/src/androidTest/.../ui/ResLocaleTest.kt` compara `Res.string` contra `R.string` con el mismo `Locale`, en los dos idiomas, leyendo los `.cvr` reales. **Compila, pero nunca se ha ejecutado.** `CuentivoAppTest` tampoco se ha corrido tras mover la barra, que ya usa `Res.string`.

- El emulador quedó `offline` en el primer arranque (GPU: `UpdateLayeredWindowIndirect failed`). En el segundo, con `-gpu swiftshader_indirect`, **Claude Code lo mató por falta de memoria en la PC**, junto con la prueba. No sé si ese arranque habría funcionado. **No lo relances por tu cuenta en bucle.**
- **Ruta correcta: el teléfono de Diego** (A059P, Android 16), mucho más ligero. Cuando esté conectado:
  `.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.ResLocaleTest,com.cuentivo.app.ui.CuentivoAppTest"` con el entorno Norton y las banderas de `CLAUDE.md`. Revisa antes `adb devices`; si no está, déjalo en la lista manual de Diego y sigue.
- **Debajo de API 33 no se ha probado nada:** solo hay imagen API 37 y no hay `sdkmanager`. La rama usa el mismo `Locale.setDefault` y `processLocale` tiene prueba JVM, pero esa rama no ha corrido. **Android no debe publicarse con pantallas en `Res.string` sin probarlo**: va a la lista manual (Android Studio → SDK Manager → una imagen API 31).

## Tu primer paso

1. Terminal de OpenCode `term_c2b793b3-78cb-45e2-b857-d2d200590bc8`, al 44-50% de contexto. Si pasa de ~80%, otra (Flecha derecha + Enter + Enter al primer aviso de `C:\tmp\*`). Cada envío lleva Monitor, y al expirar a los 30 min se rearma.
2. **Escribe el encargo F5-C** con el formato de `docs/superpowers/encargos/2026-11-06-ios-f5b-cimiento-ui.md`: "por qué así", tareas con puerta, sin re-decidir. **Pide que la puerta corra sin emulador:** la PC se queda sin memoria.
3. Audita leyendo el diff, sube, lee veredicto.

## Tandas que siguen (ya medidas, no vuelvas a barrer)

Quedan unas 15,000 líneas en `app/.../ui`. Pantallas **sin imports de plataforma** (candidatas directas): `AuthenticationScreen`, `EmailVerificationScreen`, `PaymentScreen`, `FirstDebtScreen`, `SubscriptionSheet`, `AiPaywallSheet`, `OpeningBalanceImportDialog`, y las `*Presentation`/`*Policy` de `customer`, `dashboard`, `product`, `account`, `onboarding`, `ai`. Algunas dependen de `billing/` o `ai/`, que no están en `:shared`: revísalo.

**F5-C, interfaces.** Del plan, corregido contra el código:
| Interfaz | Hoy en Android | iOS |
|---|---|---|
| `Sharer` | `MainActivity.share`, `shareStatementImage`, `ui/share/StatementImageShareStore`, `AccountSheet` (`Intent`), `dial` | `UIActivityViewController`, `tel:` |
| `PhotoPicker` | `ProductPhoto`, `ProductsScreen`, `CuentivoApp`, `AiDebugLabScreen`, `CameraCaptureStore`, `AiCameraCaptureStore` | `PHPickerViewController` y cámara |
| `ReminderScheduler` | `notifications/CollectionReminderScheduler`, `Worker`, `settings/CollectionReminderPreferences` | `UNUserNotificationCenter` |
| `AppFiles` | `ProductImageStore`, `AiCaptureStore`, `StatementImageShareStore`, `BackupSection` | `NSFileManager` |
| **Voz (no estaba en el plan)** | `ai/VoiceRecognizer.kt` (273 líneas) | `SFSpeechRecognizer`, más `NSSpeechRecognitionUsageDescription` y `NSMicrophoneUsageDescription` en `Info.plist` |

- **`TextRecognizer` con Vision NO hace falta.** ML Kit solo se usa en `app/src/debug`; en release `debugAiOcrEngine` devuelve null. La libreta se lee con Cloud Functions.
- También hay que resolver: `BackHandler` y `LocalLifecycleOwner` (`CuentivoApp`, `CustomerDetailSheet`, `AiDebugLabScreen`), `java.time`, `UUID`, `BigDecimal`, `DateFormat` en pantallas, `Bitmap`/`BitmapRegionDecoder` (`LedgerCropHelper`, `LedgerReviewScreen`, `StatementImageRenderer`), `FirstDebtPreferenceStore` (`SharedPreferences`), `XlsxReader` (`ZipInputStream`).
- 20 archivos de `domain/` siguen en `app` porque importan `ai.ledger`, y las pantallas de IA dependen de eso. Decide cuándo mover `ai/ledger` antes de llegar a `ui/ai` (6382 líneas).

**La tanda de más riesgo: `MainActivity.onCreate`.** Son ~2577 líneas (líneas 380-2957) con toda la lógica de estado, y de ahí sale `CuentivoApp(...)` (línea ~1721, más `CuentivoApp.kt`, 827 líneas). **Sin moverla, iOS no tiene app.** Extrae una raíz común con servicios de plataforma inyectados, por pasos pequeños; `CuentivoAppTest` debe correr en cada paso. Hazle su propio plan antes del encargo. Toca dinero y flujos financieros: audita leyendo.

**Pendientes que siguen vivos:**
- `deleteAccountWithApple`: nadie la llama. La pantalla de borrar cuenta debe usarla si el proveedor es `apple.com`.
- `signOut()` en iOS puede lanzar: atraparlo y **no** limpiar datos locales.
- **Idioma en iOS (propuesta, decídela al mover la cuenta):** declarar `CFBundleLocalizations` (`es`, `en`) en `Info.plist` y que el selector abra Ajustes de la app con `UIApplicationOpenSettingsURLString`. Es el camino de Apple y evita reiniciar el proceso. **No lo he verificado:** confirma que `NSLocale.preferredLanguages` refleja esa elección.
- `PROJECT_MEMORY.md` tiene 824 líneas y su sección "iOS sin Mac" está vieja. Actualízala al cerrar F5.
- Luego F6 (StoreKit 2, `verifyAppleSubscription`, `handleAppStoreNotification`, `release:check`, **sin deploy**) y el resto de F7.

## Hallazgos de esta auditoría

- **Comprueba tú lo que el reporte afirma sobre artefactos.** En F5-A dijo "0 `compose-resources` en la APK" por buscar el nombre equivocado: en Android es `composeResources`, sin guion. Si le hubiera creído, habría concluido que `Res` truena en Android.
- El reporte de F5-B dice que la APK release lleva `composeResources` en `assets/` y en la raíz del zip. Son unos 120 KB repetidos: sin efecto funcional, y se resuelve de raíz cuando desaparezca `R`.
- `Res` vive en `com.cuentivo.shared.resources`, y cada clave es una propiedad de extensión: importa `...resources.*` o la clave, no solo `Res`.
- Las puertas de i18n ya cubren `shared/src/commonMain/kotlin/com/cuentivo/app/ui`.

## Lista manual de Diego (para el final, paso a paso)

1. Conectar el teléfono en depuración para correr `ResLocaleTest` y `CuentivoAppTest`.
2. Bajar una imagen API 31 en Android Studio antes de publicar Android con estas pantallas.
3. Pagar Apple cuando la app funcione: `docs/ios-dia-de-pago.md`.

## Reglas que no cambian

Dinero en `Long`; nada financiero se borra ni se edita; **sin `firebase deploy` ni deploy de `functions` sin autorización aparte de Diego**; repo público, revisa secretos antes de cada push. Nunca abras ni imprimas `keystore.properties`, `*.jks`, `google-services.json`, llaves ni el plist. No toques la rama `feature-cuentivo-ai`. Esta sesión cerró con ~$50: repite el relevo cerca de $50-$80 o antes de un tramo grande.
