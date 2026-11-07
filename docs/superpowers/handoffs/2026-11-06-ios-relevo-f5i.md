# Relevo iOS: F5-H cerrada, siguen `ui/ai` y la raíz común

**La meta, confirmada por Diego el 2026-11-06:** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar la cuenta de Apple Developer. No te detengas al cerrar una tanda: sigue con la siguiente fase, y deja lo manual de Diego para el final.

**Lee primero** `docs/superpowers/handoffs/2026-11-06-ios-relevo-f5e.md` completo: sus secciones "Después", "Hallazgos", "OpenCode y permisos", "CI", "Decirle a Diego", "Lista manual" y "Reglas" siguen vigentes. **Sus secciones de estado y primer paso ya caducaron; manda este archivo.** También lo que ese relevo indica leer; de `2026-11-06-ios-relevo-f5f.md`, "Decisiones de F5-D", "Pendiente nuevo" y "Hallazgos"; y de `2026-11-06-ios-relevo-f5g.md`, "Decisiones" y "Hallazgos". Modelo de encargo: `docs/superpowers/encargos/2026-11-06-ios-f5g-fotos-productos-clientes.md`.

## Estado al 2026-11-06

- **F5-G cerrada en `6d32946`:** `ios-build` run 42315344436, 795 pruebas iOS en 112 suites, 0 fallos; `android-check` run 42315344635 verde. `ProductPhoto`, `ProductsScreen` y `CustomersScreen` en común; `AndroidImagePickerFactory` en `:app`.
- **F5-H cerrada en `c8270ab`:** `ios-build` run 42318530402 verde al primer intento, con **800 pruebas iOS en 113 suites y 0 fallos** (leídas por mí del artefacto; `FirstDebtPreferenceStoreTest` aparece), `xcodebuild` correcto y `.app` subido. `android-check` run 42318530254 verde. `FirstDebtPreferenceStore`, `AccountSheet`, `LanguageSheet` y `LanguageControl` en común.
- **Android local:** 805 + 777 = 1582, 0 fallos, leído por mí de los XML, en `96dd38c`. Lo posterior solo agrega el encargo. `assembleRelease` y `lintDebug` pasaron **según el reporte de OpenCode, sobre `96dd38c`**; no los repetí.
- `ResLocaleTest`, `CuentivoAppTest`, `ProductPhotoSourceTest`, `AuthenticationFlowTest`, `EmailVerificationScreenTest`, `SaleFlowTest`, `PaymentFlowTest`, `CustomerFlowTest` y `SubscriptionFlowTest` **siguen sin correr**. `adb devices` salió vacío al cerrar.
- **Esto no está probado:** el selector de fotos en Android (hasta correr `ProductPhotoSourceTest`), `IosLanguageControl`, y que `NSLocale.preferredLanguages` refleje la elección hecha en Ajustes.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima, y la `.app` nunca se ha abierto en un simulador en CI.** Dilo con esas palabras.

**Este archivo está en un commit local sin subir:** súbelo con la siguiente tanda, porque un push solo de docs gasta un run de iOS.

## Tu primer paso

**`ui/ai/*` (unas 6382 líneas, medición vieja): mide antes de escribir el encargo.** `LedgerReviewScreen`, `AiDebugLabScreen`, `LedgerCropHelper`, `AiDebugLabModel`, `LedgerReviewSaver`.

- `Bitmap` y `BitmapRegionDecoder` no existen en iOS: decide la interfaz de recorte antes del encargo y deja la decisión escrita.
- **Veinte archivos de `domain/` importan `ai.ledger`.** Revisa cuántos siguen en `app`, y decide cuándo mover `ai/ledger`, antes del encargo.
- **`AiDebugLabScreen` y `AiCameraCaptureStore`: comprueba si deben llegar a iOS.** `debugAiOcrEngine` da null en release. No muevas lo que solo sirve a depuración sin un motivo: la raíz común puede no necesitarlo.
- `LedgerReviewScreen` confirma movimientos desde la libreta y llega a `FinancialDao`: **audita leyendo.** Gemini solo propone; ver `CLAUDE.md`.
- Trocéalo en tandas. **Haz primero la que no toque dinero.**

## Después, en este orden

1. **`CuentivoApp.kt` y `MainActivity.onCreate` hacia una raíz común, con plan propio antes del encargo. Sin esto iOS no tiene app.** Lo que esa raíz debe conectar:
   - **Respaldo en iOS, decisión pendiente:** léela en "Pendiente nuevo" del relevo F5-F, y decide antes del plan. Contrasta si CommonCrypto da GCM.
   - `photoStore` con `IosProductPhotoStore` e `imagePickers` con `IosImagePickerFactory`.
   - `FirstDebtPreferenceStore(NsUserDefaultsFirstDebtFlagStore())`.
   - **`CuentivoApp.kt:547`, `onOpenLanguage`:** `if (language.opensSystemSettings) language.openSystemSettings() else showLanguage = true`. Hoy solo Android lo usa, así que nadie lee aún `opensSystemSettings`.
   - `onOpenPrivacyPolicy` con `Sharer.openUrl(PRIVACY_POLICY_URL)`. En Android hoy es un `Intent` en `CuentivoApp.kt`, sin `try/catch`.
   - `deleteAccountWithApple` si el proveedor es `apple.com`.
   - **`signOut()` en iOS lanza** con `check` en `IosAuthenticationGateway.kt:123`: atrápalo y no limpies datos locales.
   - `BackHandler` de `CuentivoApp.kt:466`: `PlatformBackHandler` en iOS no hace nada, así que comprueba que `NewSale` tenga un cierre visible.
   - `IosLocalReminderCenter.requestPermission`, tocar el aviso para abrir clientes vencidos, y `reminders.sync()` al abrir, al volver a primer plano y tras escrituras financieras.
2. **F6:** textos `subscription_*` por plataforma (Apple rechaza mencionar Google Play), StoreKit 2, `verifyAppleSubscription`, `handleAppStoreNotification`, `npm --prefix functions run release:check`, **sin deploy**.
3. **Resto de F7:** íconos, capturas, enlace de App Store en `StoreListing.ios.kt` y en `docs/ios-dia-de-pago.md`. Un arranque de la `.app` en simulador en CI, si cabe: nunca se ha abierto.
4. `PROJECT_MEMORY.md` al cerrar F5: su sección de iOS está vieja y el archivo tiene unas 824 líneas, muy por encima de las ~100 que pide `CLAUDE.md`.

## Decisiones de esta sesión (no las re-decidas sin motivo)

- **Los servicios de plataforma entran a las pantallas por parámetro, sin valor por defecto,** ni `CompositionLocal` ni `expect`: `photoStore: ProductPhotoStore`, `imagePickers: ImagePickerFactory`, `language: LanguageControl`, `onOpenPrivacyPolicy`. Los inyectará la raíz.
- **`ProductImageStore`, `CameraCaptureStore`, `AndroidImagePickerFactory`, `AndroidLanguageControl` y `LanguagePreference` se quedan en `:app`.** Dependen de `FileProvider`, `LocaleManager` y `attachBaseContext`.
- **`CuentivoApp` crea una sola `ProductImageStore` para las dos pantallas:** comparten su caché de 6 MiB. `MainActivity.kt:887` sigue con otra instancia, solo para borrar. La caché de esa instancia no es la de las pantallas; los nombres llevan marca de tiempo, así que no se ve una foto vieja.
- **Idioma en iOS:** no se muestra `LanguageSheet`; se abre Ajustes. Android usa el mismo archivo y las mismas llaves de siempre, también en `cuentivo_onboarding_preferences`.
- **Diferencias aceptadas en Android:** `working = true` se pone al arrancar la corrutina; una galería cuyo `save` lance ya no deja el botón bloqueado.

## Hallazgos (úsalos)

- **Dentro de una clase que implementa `ImagePickerFactory`, `remember` es el método de la interfaz,** y una lambda podría compilar como llamada recursiva. Importa `remember as composeRemember`.
- **Una prueba puede no poder cubrir dos guardias a la vez.** OpenCode partió en dos mi prueba de usuario en blanco: sembrar la llave escondía la fuga de escritura. Fue mejor que lo que pedí. Pide siempre los controles negativos con el nombre de la prueba.
- **Las dos tandas salieron verdes al primer intento** con encargos de líneas exactas y código ya escrito en los puntos delicados. Sigue así: mide con `grep`, y escribe tú el fragmento que decide.
- El encabezado del reporte F5-G daba como base `1b8af28`, que no era; la base real era `29ab9ed`. Sigue contrastando con `git log`.
- **Mi filtro de auditoría:** normalizar `R.string` y `Res.string`, y comparar líneas quitadas y agregadas, reduce un movimiento de 1000 líneas a lo que importa. No uses la resta de conjuntos sin normalizar el `-`/`+` y el prefijo `com.cuentivo.app.R.`, o sale todo como cambio.
- **GateGuard pide datos en el primer `Write` de cada archivo y en todo `rm -rf`.** Exponlos antes y reintenta idéntico. Una carpeta nueva evita el `rm`.

## OpenCode

Terminal `term_6951abe3-0d6d-482a-9c04-a5d81dd06cfa`, **cerca de 80% de contexto.** `ui/ai` es grande: **crea otra antes del siguiente encargo** con `orca terminal create`, espera a que cargue el TUI, y concede "Allow always" a `C:\tmp\ios-relevo\*` al primer aviso. La otra terminal `OC | ...` (`term_d8e7884b-...`) es vieja: no la uses. Monitor con `C:/tmp/ios-relevo/vigila_opencode.py <handle> <reporte> <worktree>`, más un bucle que busque `Permission required` en `orca terminal read --terminal <h> --screen`. Si pide un permiso fuera de `C:/tmp/ios-relevo`, recházalo: haz tú el contraste o pídeselo a Diego. Los símbolos nuevos los contrastas tú en `C:/tmp/ios-relevo/simbolos-<tanda>.txt`.

**Nunca mandes `git commit` o `push` en el mismo bloque que ediciones.**

## Relevo y costo

**Esta sesión cerró con ~$55.** Repite el relevo cerca de $50-$80 o antes de un tramo grande. **Abre cada sesión nueva con Remote Control** (`claude --model opus --remote-control "Cuentivo iOS <tanda>"`), comprueba que conectó y dile a Diego el nombre: la sigue desde su celular. Repite esta regla en tu relevo.

## Decirle a Diego al entregar

Lo de la sección homónima del relevo F5-E, más: la lista manual suma `SubscriptionFlowTest` y el selector de fotos, y las pruebas de flujo de venta, abono y clientes, que deben correr en su teléfono antes de publicar Android con estas pantallas.
