# Plan: raíz común de Cuentivo (iOS con app de verdad)

**Por qué:** iOS sólo muestra una pantalla mínima (`MainViewController.kt:32`). Lo que falta está en `CuentivoApp.kt` (853 líneas) y en `MainActivity.kt` (3020 líneas, de las que `setContent` ocupa ~2440: 530 a 2970).

## Medido el 2026-11-06, en `9ef8766`

- `CuentivoApp.kt` sólo depende de Android en: `LocalContext`, ciclo de vida (`ON_RESUME`), `BackHandler`, `Intent`, `ProductImageStore`, `AndroidImagePickerFactory`, `AndroidLedgerCapture`, `AndroidVoiceRecognizer`, `AndroidVoicePermissionGateFactory`, `AndroidLanguageControl`, `debugAiTransport()` y 14 `R.string`. Lo demás ya está en común.
- `setContent` tiene 57 estados `remember`, 95 `R.string`, 55 `getString`, 21 `this@MainActivity`, 12 `Log`, `Firebase*Exception`, Google, Billing y respaldo.
- Faltan en común: `EntitlementStore` (87), `SyncQueue` (62), `OutboxBackfill` (144), `OwnerMembershipBootstrap` (62), `LocalSessionGuard` (72), `CreditLimitSuggestionStore` (30). `CollectionReminderScheduler`, `SyncScheduler`, `BillingGateway` y `DatabaseBackup` se quedan en Android, detrás de una costura.
- `AiDebugLabGuardsTest` y `MexalmLedgerWarmGatewayTest` leen `CuentivoApp.kt` por ruta: si se mueve sin actualizarlas, truenan; si se actualizan mal, dejan de vigilar.
- `NewSaleScreen` ya tiene cierre visible en sus dos llamadas (`onExit`, `CuentivoApp.kt:594` y `:639`; `NewSaleScreen.kt:197`). No hace falta agregar nada.

## Decisiones

1. **Mover, no reescribir.** Android conserva su comportamiento en cada commit; cada tanda pasa la puerta de Android.
2. **Los servicios de plataforma entran por parámetro**, sin valor por defecto, ni `CompositionLocal`, ni `expect`, salvo efectos composables pequeños como `PlatformBackHandler`. Un solo objeto por raíz: `CuentivoAppPlatform` y, en F5-N, `CuentivoRootPlatform`.
3. **`setContent` no se parte en piezas lógicas todavía.** Se mueve por secciones hacia `CuentivoRoot` en común, porque reorganizar 57 estados y mover a la vez ocultaría regresiones. Refactorizar queda fuera.
4. **Textos dentro de lambdas:** `getString` de Compose Resources es `suspend`. Los lambdas sin corrutina resuelven el texto con `stringResource` afuera, en composición, o lanzan en el `scope` si ya lanzaban. No se traduce nada al persistir.
5. **Errores de Firebase:** la raíz común no nombra `Firebase*Exception`. Los gateways exponen una clasificación común (colisión de correo, sesión vieja, red, código), porque en iOS las excepciones son otras. **Esto es lo más delicado de F5-N y lleva pruebas con los dos gateways.**
6. **Respaldo (cerrado):** mismo formato que Android, con `cryptography-kotlin` **0.5.0** y su proveedor CryptoKit, sólo en `iosMain`. Verificado en su repo: `CryptoKitAesGcm.kt` existe en la etiqueta 0.5.0, hecha con Kotlin 2.2.0; el proyecto usa 2.2.10. **La 0.6.0 no:** está hecha con Kotlin 2.3.20, posterior al compilador del proyecto. PBKDF2 con `CCKeyDerivationPBKDF`, como dice el relevo. **Sin verificar hasta que lo diga CI:** que enlace en `:iosKit` con `macos-26`. Si no enlaza, se reporta antes de buscar otra biblioteca. La prueba usa como fixture un archivo real cifrado por `BackupCrypto`.
7. **Restaurar en iOS:** no hay reinicio de proceso, así que `CuentivoRootPlatform.onRestored` cierra y reabre la base en la raíz. En Android sigue siendo `restartAfterRestore()`.

## Tandas

| Tanda | Contenido | Cierra cuando |
|---|---|---|
| **F5-L** | `CuentivoApp.kt` a común con `CuentivoAppPlatform`; `PlatformResumeEffect`; entrada de idioma; 52 llamadas de `androidTest` en 18 archivos. Encargo: `encargos/2026-11-06-ios-f5l-cuentivoapp-comun.md`. | Puerta Android y `ios-build` verdes |
| **F5-M** | Almacenes y cola a común: `SyncQueue`, `OutboxBackfill`, `OwnerMembershipBootstrap`, `LocalSessionGuard`, `CreditLimitSuggestionStore`, `EntitlementStore`, con su implementación iOS en `NSUserDefaults`. Clasificación común de errores de autenticación. Encargo: `encargos/2026-11-06-ios-f5m-almacenes-cola.md`. | Igual |
| **F5-N** | `setContent` a `CuentivoRoot` en común, por secciones, con la costura `ReminderControl` (`sync`/`cancel`): sesión y cuenta, datos y mensajes, lambdas de `CuentivoApp`, respaldo y borrado. `MainActivity` queda en crear dependencias, `CuentivoRootPlatform` y `setContent { CuentivoRoot(...) }`. | Igual, con `androidTest` compilado |
| **F5-O** | Raíz de iOS: `MainViewController` crea la base, los gateways y `CuentivoRootPlatform`, con todo lo que lista el relevo en su punto 2. Pantalla real en vez de la mínima. Arranque de la `.app` en simulador en CI, si cabe. | `ios-build` verde y, si cabe, arranque en simulador |
| **F5-P** | Respaldo en iOS según la decisión 6. | Fixture de Android abierto en la prueba iOS |

Después: F6 (StoreKit, **sin deploy**), resto de F7 y recorte de `PROJECT_MEMORY.md`.

## Riesgos

- **Autenticación:** F5-N cambia la forma de detectar errores en Android. Se cubre con pruebas sobre `FirebaseAuthenticationGateway` y `AuthenticationFlowTest`, que sigue en la puerta abierta hasta que se conecte el teléfono.
- **Memoria de la PC:** la puerta de Android no se reintenta en bucle.
- **Tamaño de F5-N:** si OpenCode pasa de ~80% de contexto, se divide por secciones con terminal nueva. Cada sección es un commit que compila.
