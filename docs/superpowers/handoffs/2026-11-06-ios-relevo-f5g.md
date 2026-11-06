# Relevo iOS: F5-F cerrada, siguen fotos, clientes y cuenta

**La meta, confirmada por Diego el 2026-11-06:** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar la cuenta de Apple Developer. No te detengas al cerrar una tanda: sigue con la siguiente fase, y deja lo manual de Diego para el final.

**Lee primero** `docs/superpowers/handoffs/2026-11-06-ios-relevo-f5e.md` completo: sus secciones "Después", "Hallazgos", "OpenCode y permisos", "CI", "Decirle a Diego", "Lista manual" y "Reglas" siguen vigentes. **Sus secciones de estado y primer paso ya caducaron; manda este archivo.** También lo que ese relevo indica leer, y de `2026-11-06-ios-relevo-f5f.md` "Decisiones de F5-D", "Pendiente nuevo" y "Hallazgos". Modelo de encargo: `docs/superpowers/encargos/2026-11-06-ios-f5f-pantallas-2.md`.

## Estado al 2026-11-06

- **F5-E cerrada en `fd27074`:** run 42303998659, 780 pruebas iOS. Login, verificación de correo, suscripción, aviso de pago de IA y 13 presentaciones y políticas en común.
- **F5-F cerrada en `0d01331`:** `ios-build` run 42311197134 verde, con **791 pruebas iOS en 112 suites y 0 fallos** (leídas por mí del artefacto; `PlatformDatesTest` y `PlatformDatesIosTest` aparecen), `xcodebuild` correcto y `.app` subido. `android-check` verde en el mismo commit (run 42311196813).
- **Android local:** 802 + 768 = 1570, 0 fallos, leído por mí de los XML, en `e140aba`. Lo posterior solo agrega imports en `iosMain` e `iosTest`. `assembleRelease` y `lintDebug` pasaron **según el reporte de OpenCode, sobre `e140aba`**; no los repetí.
- En común desde F5-F: `FirstDebtScreen`, `OpeningBalanceImportDialog`, `PaymentScreen`, `NewSaleScreen`, `DashboardScreen`, `CustomerDetailSheet` y `platform/PlatformDates.kt`.
- `ResLocaleTest`, `CuentivoAppTest`, `ProductPhotoSourceTest`, `AuthenticationFlowTest` y `EmailVerificationScreenTest` **siguen sin correr**. También las de flujo de venta y abono (`SaleFlowTest`, `PaymentFlowTest`, `CustomerFlowTest`), que ahora pasan por `Res`. `adb devices` salió vacío.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima, y la `.app` nunca se ha abierto en un simulador en CI.** Dilo con esas palabras.

**Este archivo está en un commit local sin subir:** súbelo con la siguiente tanda, porque un push solo de docs gasta un run de iOS.

## Tu primer paso

**Fotos de producto y las pantallas que dependen de ellas.** Ya medido:

- `ProductPhoto.kt` (303 líneas) usa `Bitmap`, `Uri`, `rememberLauncherForActivityResult`, `PickVisualMediaRequest`, `Dispatchers.IO`, `LocalContext`, y `CaptureUriSaver`. Implementa `ImagePickerFactory` en Android conservando `rememberSaveable`, `deleteStale` y las dos carpetas separadas. Lee `IosImagePickerFactory` antes del encargo: su forma ya compila en iOS.
- `ProductsScreen.kt` (559): `LocalContext` en la línea 134 y `UUID` en la 287. Hoy usa `ProductPhoto`.
- `CustomersScreen.kt` (1005): `LocalContext` en la línea 465, `UUID` y `LocalDate`. Depende de `PhotoDraft` y de la foto.
- `PlatformDates` ya da `newUuid()` y `todayEpochDay()`. Úsalos.
- **Hasta correr `ProductPhotoSourceTest` en el teléfono, no está probado.** Dilo así.

## Después, en este orden

1. **`FirstDebtPreferenceStore`** (`SharedPreferences`), **`AccountSheet`** (`Intent` a la política de privacidad; hay `Sharer`/URL en iOS, revísalo) y **`LanguageSheet`** (`Activity`, `Build`). Conecta aquí la propuesta de idioma en iOS y `deleteAccountWithApple`, y que un `signOut()` que lance no limpie datos locales.
2. **`ui/ai/*`** (6382 líneas): `LedgerReviewScreen`, `AiDebugLabScreen`, `LedgerCropHelper`, `AiDebugLabModel`, `LedgerReviewSaver`. `Bitmap`/`BitmapRegionDecoder` no existen en iOS. Mide primero.
3. **`CuentivoApp.kt` (824) y `MainActivity.onCreate` hacia una raíz común, con plan propio antes del encargo,** incluido el respaldo (abajo). **Sin esto iOS no tiene app.**
4. Orden restante del relevo F5-E: F6 y resto de F7. `PROJECT_MEMORY.md` al cerrar F5.

**El respaldo sigue sin funcionar en iOS.** Decisión pendiente, léela en el relevo F5-F, antes de la raíz común.

## Decisiones de esta sesión (no las re-decidas sin motivo)

- **Fechas:** `commonMain` habla en `Long` y `String` con `expect`/`actual`. Las fechas de negocio se formatean en **UTC** en iOS, para que `1 ene` no salga `31 dic` en México. Android conserva `DateTimeFormatter` y `DateFormat`.
- **`PlatformBackHandler` en iOS no hace nada:** no hay botón atrás. Por eso, al mover una hoja, **comprueba que tenga un cierre visible** (`CustomerDetailSheet` tiene `DetailTopBar`). `CuentivoApp.kt:466` también usa `BackHandler` para cerrar `NewSale`: revísalo al moverla.
- **Logo:** `Res.drawable.ic_cuentivo_logo`, copia del `mipmap-xxxhdpi`. Los `mipmap` siguen siendo el icono de Android.
- **`labelRes`:** las cadenas son `StringResource`; `CriticalStatus` pasó a `sealed interface` porque `StringResource` y `PluralStringResource` ya no caben en un `Int`.
- **`String.format` con el formato crudo no existe en común.** `PaymentScreen` y `NewSaleScreen` resuelven un mapa `balanceDetails` por cliente. Es una llamada de `stringResource` por cliente en cada composición: sin efecto notable con pocos clientes, pero no lo he medido con miles.
- **Puerta 2 de i18n:** `CLAUDE.md` ya excluye, por nombre, el `require` de `BalancePresentation` y el `"?"` de iniciales.

## Hallazgos (úsalos)

- **Contrastar que un símbolo existe no basta: hay que saber si es categoría.** `simbolos-f5f.txt` listaba todos, y aun así seis fallaron por falta de `import platform.Foundation.<nombre>` (`currentLocale`, `dateWithTimeIntervalSince1970`, `localTimeZone`, `localeIdentifier`, `preferredLanguages`, `timeZoneForSecondsFromGMT`). Me costó una vuelta de CI. **Regla para los encargos: todo lo que no sea miembro directo de la clase se importa explícito, incluidos los de compañero (`NSDate.dateWith...`, `NSTimeZone.local...`).** Los `set...` de `NSDateFormatter` sí son miembros.
- **Lee `iosMain` buscando eso antes de subir.** Yo lo leí y no lo vi, porque el reporte lo daba por miembros y yo no contrasté cuál de las dos cosas era.
- Mi encargo F5-E afirmó que no había nombres problemáticos, y había una coma en `FirstDebtPolicyTest`. OpenCode la cazó. Mide con `grep` lo que afirmes en un encargo.
- `kotlin.test.assertEquals(Int, Long)` no iguala aunque el número coincida. En pruebas movidas de JUnit hay que escribir `12_500L`.
- El encabezado del reporte traía la rama y el commit bien esta vez, porque el encargo pidió sacarlos de `git`. Sigue pidiéndolo.

## OpenCode

Terminal activa `term_6951abe3-0d6d-482a-9c04-a5d81dd06cfa`, al ~70% de contexto. **Si pasa de ~80%, crea otra** con `orca terminal create` y concede "Allow always" a `C:\tmp\ios-relevo\*` al primer aviso. La otra terminal `OC | ...` (`term_d8e7884b-...`) es vieja: no la uses. Monitor con `C:/tmp/ios-relevo/vigila_opencode.py <handle> <reporte> <worktree>`, como en el relevo F5-E. Los símbolos nuevos los contrastas tú en `C:/tmp/ios-relevo/simbolos-<tanda>.txt`.

## Relevo y costo

**Esta sesión cerró con ~$65.** Repite el relevo cerca de $50-$80 o antes de un tramo grande. **Abre cada sesión nueva con Remote Control** (`claude --model opus --remote-control "Cuentivo iOS <tanda>"`), comprueba que conectó y dile a Diego el nombre: la sigue desde su celular. Repite esta regla en tu relevo.

## Decirle a Diego al entregar

Lo de la sección homónima del relevo F5-E, más: la lista manual suma las pruebas de flujo de venta, abono y clientes, que deben correr en su teléfono antes de publicar Android con estas pantallas.
