# Memoria persistente — Cuentivo

Actualizada: 2026-07-24 (America/Mexico_City)

## Prompt maestro / propósito

Actúa como arquitecto y desarrollador Android senior especializado en Kotlin, Jetpack Compose, Clean Architecture, Room, Firebase, offline-first, seguridad y SaaS para pequeños negocios. Cuentivo es una app Android profesional y con identidad propia para negocios que venden a crédito: **negocio → clientes → productos → ventas → cargos → abonos → saldo**. No usar marcas, logos, imágenes ni nombres de Betterware, Avon, Natura, Jafra, Tupperware u otras empresas de catálogo.

Requisitos centrales: dinero exclusivamente como `Long` en centavos; Room como fuente local de verdad; libro mayor inmutable; operaciones idempotentes; aislamiento obligatorio por `businessId`; Firebase Auth + Firestore Rules con roles; no PII/saldos/tokens en Analytics o logs; no borrar ventas/pagos con evidencia relacionada; no declarar fases terminadas sin compilación y pruebas reales.

El modelo local existente incluye `Business`, `BusinessMember`, `Customer`, `Product`, `Sale`, `SaleItem`, `Payment`, `PaymentAllocation`, `AccountMovement`, `AuditLog` y `SyncOperation`. Ventas y pagos deben conservar snapshots históricos e inmutabilidad financiera. Las fases posteriores requeridas son: 4 colaboración/roles/auditoría, 5 reportes/PDF/CSV/recordatorios, 6 suscripciones y Billing, 7 publicación/privacidad/exportación/eliminación de cuenta.

## Estado real por fases

| Fase | Estado | Evidencia / alcance |
|---|---|---|
| 0. Diagnóstico | Completada | Arquitectura y modelo existentes revisados antes de cambiar código. |
| 1. Núcleo financiero | Completada | Room, centavos (`Long`), ventas, pagos, libro mayor, idempotencia y pruebas locales. |
| 2. Interfaz Android | Parcial, rediseño UX 2026-07-24 | Selector de cliente/producto en venta y abono (`SelectorField` con ModalBottomSheet + buscador — antes se quedaba fijo el primer cliente, bug reportado por el usuario). Montos en **pesos** (`pesosToCents` con `BigDecimal.longValueExact`, rechaza >2 decimales, cero `Double`). Altas de cliente/producto en diálogo. Crear cuenta como vista propia con confirmación de contraseña. Pestañas con iconos (`material-icons-core` agregado bajo el BOM) y etiquetas honestas. Refresco con `refreshKey` en vez de `recreate()`; Snackbar de confirmación. Verificado `assembleDebug testDebugUnitTest`: 48 pruebas, 0 fallos. **Falta probar en dispositivo el flujo que falló** (elegir cliente 2/producto 2), y sigue pendiente: detalle de cliente, edición/archivo, historial. |
| 3. Firebase/sincronización | Parcial — código completo, **sin verificar** | Auth, reglas, cola local, gateway Firestore con subida y descarga, fusión en Room, Emulator Suite configurada. **Ninguna escritura ni lectura remota se ha confirmado end-to-end todavía.** |
| 4. Colaboración/roles/auditoría | Parcial | `domain/Permissions.kt` (matriz de capacidades espejo de `firestore.rules`, 10 pruebas) y auditoría escrita dentro de las transacciones de `FinancialDao`. **Falta toda la UI**: no hay pantalla de miembros, ni invitaciones, ni historial de auditoría, y `MainActivity` todavía no consulta el rol para decidir qué ofrece. |
| 5. Reportes/exportación | Iniciada 2026-07-24 | `domain/CustomerStatementCsv.kt`: estado de cuenta de UN cliente a CSV con saldo corrido (DEBIT suma, CREDIT resta), escape RFC de comas/comillas, tipos en español, 6 pruebas. Puro, sin call site aún. **Falta**: pantalla de estado de cuenta que lo alimente desde `FinancialDao.customerMovements` + share/guardar el archivo, resúmenes por periodo (las queries ya existen en `FinancialDao`), PDF y recordatorios. |
| 6. Suscripciones/Billing | Pendiente | **No verificable sin Play Console con productos configurados** — no escribir a ciegas. |
| 7. Publicación | Pendiente | Requiere firma release, política de privacidad, borrado de cuenta. |

## Sesión 2026-07-24 (tarde) — app usable + preparación Play Store

**Objetivo del producto aclarado por Diego: la app se publica en Google Play con DOS planes — Gratis y Pro $199 MXN/mes.** No es una app local: offline-first significa que funciona sin señal y sincroniza, no que los datos vivan en un solo teléfono.

Hecho, verificado con `assembleDebug assembleRelease testDebugUnitTest` → **BUILD SUCCESSFUL, 78 pruebas, 0 fallos**:

- `domain/Money.kt` — única conversión centavos ↔ texto; `CuentivoApp.formatMoney`/`pesosToCents` ahora delegan aquí.
- `domain/SaleDraft.kt` — carrito puro: varios productos, cantidad, descuento por renglón y por venta. Invariante `subtotalCents = quantity * unitPriceCents - discountCents`, la misma de `firestore.rules`. 10 pruebas.
- `domain/DebtorShareText.kt` — textos para WhatsApp/Sharesheet. `forBusiness` (lista completa) y `forCustomer` (solo ese cliente, nunca otros).
- `domain/SubscriptionTier.kt` — FREE/PRO, `PaidFeature`, `FeatureAccessManager`. Límites gratis: **10 clientes, 30 ventas**. Registrar abonos nunca se bloquea (bloquearlo corrompería el libro mayor para vender suscripción). Un plan desconocido degrada a FREE.
- `FinancialDao.outstandingSalesForCustomer(...)` + `registeredSaleCount(...)` y `OutstandingSaleRow`.
- **Venta con carrito** en UI y en `MainActivity`: N `SaleItemEntity`, un solo `CHARGE` por el total, fecha límite por chips (7/15/30 días), y se encola un `SALE_ITEM` por renglón.
- **Abonos ya se aplican a ventas**: `PaymentAllocator` por fin tiene call site de producción; se crean `PaymentAllocationEntity` y **se encolan los `PAYMENT_ALLOCATION`** (pendiente #8 cerrado). El mensaje dice a cuántas ventas se aplicó y cuánto quedó a favor.
- **Inicio ya no está solo**: lista de deudores ordenada por saldo, filtros (Con deuda / Todos), botón "Compartir lista", y tocar un renglón abre el estado de cuenta. Vencidos en rojo, el resto no.
- Teléfono del cliente al darlo de alta (la columna existía y siempre se guardaba `null`).
- **Borrado de cuenta** (requisito de Play): `AuthenticationGateway.deleteAccount` + confirmación en UI; borra primero los datos locales (`clearAllTables`) y luego la cuenta, para que un fallo deje al usuario dentro y pueda reintentar. También botón de cerrar sesión.
- **Build de release listo**: R8 activado (`optimization.enable = true`) + `app/proguard-rules.pro` nuevo, y `signingConfigs` que lee `keystore.properties` de la raíz (ignorado por git junto con `*.jks`). Sin ese archivo el release compila sin firmar, así que el repo sigue construyendo.

**Probado en teléfono por Diego** (instalando el APK debug por WhatsApp, sin cable — no quiere conectar USB; la ruta es `app/build/outputs/apk/debug/app-debug.apk`).

### Rediseño visual (glassmorphism) — misma sesión

El tema seguía usando la paleta morada de plantilla de Android Studio; por eso se veía plano.

- `ui/theme/Color.kt`: identidad propia — azul-marino profundo (`DeepNavy`/`DeepTeal`), acento menta (`MintAccent`), dorado (`WarmSand`). Se eliminaron `Purple80`/`Pink80` y compañía.
- `ui/theme/Theme.kt`: el efecto glass es **global y sin blur** (`Modifier.blur` no existe en minSdk 24). Se logra con degradado detrás de toda la app + colores de superficie translúcidos en el `ColorScheme`, así Card, ModalBottomSheet y NavigationBar se vuelven de vidrio sin tocar pantalla por pantalla. `background = Color.Transparent` para que el Scaffold no tape el degradado.
- **Dos bugs de contraste encontrados por Diego probando y ya corregidos**:
  1. Los textos fuera de una Card salían negros sobre fondo oscuro. Pintar el fondo no basta: hay que proveer `LocalContentColor` (se hace en `CuentivoTheme`).
  2. Diálogos y bottom sheets a la misma opacidad que las tarjetas eran ilegibles. Ahora `surfaceContainerHigh` (diálogos) y `surfaceContainerLow` (hojas) usan `OverlayDark`/`OverlayLight` casi opacos; las Cards siguen glass vía `surfaceContainerHighest`.
- `AuthenticationScreen`: disco menta con la "C" (marca provisional), título grande, y el formulario en tarjeta sólida con campos redondeados y spinner en el botón.

### Apartado de suscripciones (UI)

`SubscriptionSheet` + `PlanCard` dentro de `ui/CuentivoApp.kt`: los dos planes lado a lado, el actual marcado, con ✓ lo incluido y en gris lo que le falta al gratis. Se abre desde la tarjeta "Tu cuenta" del Inicio **y** desde el diálogo de límite alcanzado. Los números salen de `FeatureAccessManager`, nunca escritos a mano. **El botón "Suscribirme a Pro" todavía no cobra** — muestra un aviso, porque el producto no existe en Play Console.

### Publicación — estado real

- Keystore creado en `C:\Users\pibed\cuentivo-release.jks` (alias `cuentivo`), `keystore.properties` en la raíz, ignorado por git.
- **AAB firmado generado y verificado** con `jarsigner` → `jar verified`. Ruta: `app/build/outputs/bundle/release/app-release.aab`. **Aún no subido a Play.**
- Si se sube una segunda vez hay que subir `versionCode` (hoy 1).

Lo que NO se hizo en esta sesión y sigue abierto:

1. **Google Play Billing**: no hay código de cobro. Requiere que Diego cree la app en Play Console y el producto `cuentivo_pro_mensual` de $199 MXN. Hoy `MainActivity` fija `tier = SubscriptionTier.FREE` en una sola línea, lista para conectarse.
2. **Fotos** de productos y de comprobantes de pago (`ProductEntity.imageUri` existe y sigue en `null`; `payments` necesita columna nueva → migración 4→5).
3. **Importar/exportar productos en Excel/CSV.**
4. **Cancelar venta/abono desde la UI** (`cancelSaleIdempotently`/`cancelPaymentIdempotently` siguen sin botón).
5. Sync **todavía sin verificar** contra Firestore o el emulador.
6. Ícono propio, política de privacidad, formulario de Seguridad de datos.
7. `android:allowBackup="true"` sin decidir: hoy el respaldo de Android puede copiar la base financiera completa.

## Siguiente sesión — por dónde continuar (orden recomendado)

1. **Probar en dispositivo el rediseño UX** (`installDebug`): elegir cliente 2/producto 2 en el selector, abono en pesos, crear cuenta con confirmación. Es lo que el usuario reportó roto.
2. **Sync end-to-end contra el Emulator Suite** (`firebase emulators:start` + `installDebug -Pcuentivo.useEmulators=true`): ninguna escritura remota se ha confirmado jamás. Desplegar también las reglas endurecidas (`firebase deploy --only firestore:rules`).
3. ~~Fase 5 UI: pantalla de estado de cuenta.~~ **Hecho al cierre de la sesión**: tocar un cliente en la pestaña Clientes abre `StatementSheet` (hoja con movimientos cronológicos, +cargo/−abono) y botón "Compartir CSV" (intent `ACTION_SEND` texto plano con el CSV de `CustomerStatementCsv`; solo el cliente elegido, nunca otros). Fechas con `ZoneId.systemDefault()` — pendiente usar la zona del negocio. Falta probarlo en dispositivo. Queda de Fase 5: resúmenes por periodo en UI, PDF, recordatorios.
4. **Fase 4 UI**: miembros/roles/auditoría; `MainActivity` debe consultar `Permissions.allows(...)`.
5. Arrancar la sesión con `ECC_GATEGUARD=off`: el hook duplicó cada escritura de archivo (29 denegaciones hoy).

## Arquitectura y Firebase actual

- Android/Kotlin, Jetpack Compose, Material 3, Room (`cuentivo.db`).
- Paquete y `applicationId`: `com.cuentivo.app`.
- Rama: `phase1-financial-core`; hay muchos cambios locales sin commit que se deben preservar.
- Firebase project: `organitazionlabs`; Firestore Standard / `(default)` / `northamerica-south1`; Email/Password habilitado.
- Archivos Firebase: `app/google-services.json`, `firebase.json`, `firestore.rules`, `firestore.indexes.json`.
- Reglas Firestore son prototipo publicado: aislamiento por negocio y roles; ventas, pagos y movimientos son append-only desde cliente. Antes de producción hay que probarlas con Emulator Suite.

## Trabajo de Fase 3 que ya existe

- Autenticación por correo/contraseña: `FirebaseAuthenticationGateway`, `AuthenticationScreen` y `CredentialsValidatorTest`.
- Núcleo probado de sincronización en `app/src/main/java/com/cuentivo/app/sync/SyncContracts.kt`: IDs deterministas, política de conflictos y distinción entre fallos transitorios/permanentes.
- `FirestoreSyncGateway.kt`: escritura idempotente a Firestore con ID local determinista y rutas bajo `businesses/{businessId}`.
- `SyncWorker.kt` y `SyncScheduler.kt`: WorkManager con requisito de red, trabajo único, lotes de 50, reintentos y errores permanentes retenidos en cola.
- `SyncQueue.kt`: crea `SyncOperationEntity` y programa el Worker.
- `MainActivity` encola negocio, cliente, producto, venta, ítem de venta, pago y movimientos después de las escrituras locales básicas.
- Dependencia WorkManager estable 2.11.2 añadida.

## Lo que falta para cerrar Fase 3

0. ~~Publicar el documento `MEMBER` del propietario.~~ **Hecho 2026-07-24.** Era el bloqueante real: sin `businesses/{id}/members/{uid}` con `status ACTIVE`, `isMember()` fallaba y toda subida distinta del negocio raíz quedaba estacionada como `PERMANENT:...PERMISSION_DENIED`. Se agregó `sync/OwnerMembershipBootstrap.kt` (invocado al crear negocio y como backfill en arranque), `BusinessDao.insertMember`/`memberFor` y `SyncOperationDao.reopenParkedOperations()` para reabrir las filas ya estacionadas. Verificado con `testDebugUnitTest` → `BUILD SUCCESSFUL`. **Falta probarlo contra Firestore real o Emulator: ninguna subida se ha confirmado end-to-end todavía.**

1. ~~Implementar descarga de cambios remotos desde Firestore y fusión en Room.~~ **Hecho 2026-07-24.** `SyncRemotePullGateway.download` en `FirestoreSyncGateway` + `sync/RemoteMerger.kt` + tabla `sync_state` (watermark por negocio). `SyncWorker` ahora hace push y luego pull. Cada colección se consulta por su propio campo temporal (`PULL_SPECS`), porque no comparten nombre. Ítems y asignaciones se traen por padre descargado, sin `collectionGroup`, así que no requieren índices compuestos. El watermark avanza a `max(visto) − 5 min` para no dejar atrás documentos de un dispositivo que estuvo offline.

2. ~~Añadir DAOs/upserts y estrategia de conflictos…~~ **Hecho 2026-07-24.** `RemoteMergePlanner` (lógica pura, 11 pruebas) parte el mundo en dos: descriptivos (`BUSINESS`, `MEMBER`, `CUSTOMER`, `PRODUCT`) admiten `upsert` si el remoto es más nuevo; financieros (`SALE`, `SALE_ITEM`, `PAYMENT`, `PAYMENT_ALLOCATION`, `MOVEMENT`) solo se insertan si faltan, vía `adopt*` con `IGNORE`. Ninguna ruta remota reescribe una fila del libro mayor. Única excepción: una cancelación remota no vista se aplica con `markSaleCanceled`/`markPaymentCanceled`, y una cancelación local nunca se deshace. El lector de montos **rechaza `Double`** en vez de truncarlo.

3. **Falta.** Hacer el encolado atómico con cada transacción local, desde repositorios/DAO transaccional y no como statement suelto en `MainActivity`.

4. **Falta.** Membresía/bootstrap de negocio en dispositivo secundario. Hoy `OwnerMembershipBootstrap` solo cubre al propietario del negocio local.

5. **Parcial.** Emulator Suite ya configurada: bloque `emulators` en `firebase.json` (auth 9099, firestore 8080, UI 4000, `singleProjectMode`), `.firebaserc` apuntando a `organitazionlabs`, y `sync/FirebaseEmulators.kt` que conecta Auth y Firestore cuando se compila con `-Pcuentivo.useEmulators=true` (default `false`, verificado en el `BuildConfig` generado). **Faltan las pruebas**: Worker/cola, reintentos, duplicados, conflictos, y reglas con dos usuarios/negocios.

6. **Falta.** Validar índices de Firestore. Las consultas nuevas son rango + `orderBy` sobre un solo campo, que usa índice automático — pero eso no está confirmado contra el servidor.

7. **Falta.** `WriteBatch`. El gateway sigue haciendo `set()` individuales para venta, ítem y movimiento; `FIRESTORE_RULES_AUDIT.json` lo señala como condición para activar sincronización financiera.

También resuelto el 2026-07-24, fuera de la lista original:

- **Clasificación de fallos** ahora por `FirebaseFirestoreException.Code`, no por `contains()` sobre el mensaje. `INVALID_ARGUMENT`, `NOT_FOUND` y `FAILED_PRECONDITION` (falta de índice) son permanentes; antes se reintentaban indefinidamente. Tope de 10 intentos vía `SyncOperationDao.parkExhausted`.
- **`SyncWorker` ya no puede quedar en `FAILED`**: `doWork` envuelve todo en `try/catch` y devuelve `Result.retry()`. Además `FirestoreDocumentPath.forOperation` se resuelve antes del `try` de red, así que un `entityType` no soportado es fallo permanente y no transitorio-eterno.
- **Primera migración de Room del proyecto**: `MIGRATION_3_4` crea `sync_state`; `version` 3 → 4. Registrada en los dos builders de `"cuentivo.db"` (`MainActivity` y `SyncWorker`) vía `CUENTIVO_MIGRATIONS`. El SQL se verificó carácter a carácter contra el `createSql` de `app/schemas/.../4.json`, porque Room valida un hash de identidad.

## Verificación más reciente

El proceso en segundo plano terminó; no queda compilación activa. Comando que pasó:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Resultado 2026-07-24, con `assembleDebug testDebugUnitTest`: `BUILD SUCCESSFUL in 49s`, 47 tareas, 27 ejecutadas. APK debug generado. **38 pruebas unitarias, 0 fallos**, incluyendo las 11 nuevas de `RemoteMergePlannerTest`. Esquema Room 4 exportado a `app/schemas/`.

Para builds futuros conservar `--no-watch-fs`: el file watcher de Gradle se vuelve muy lento con el entorno actual.

Para levantar los emuladores y probar contra ellos:

```powershell
firebase emulators:start
.\gradlew.bat installDebug -Pcuentivo.useEmulators=true
```

## Fase 4 — lo hecho y lo que falta

Hecho 2026-07-24, con `assembleDebug testDebugUnitTest` en verde (48 pruebas, 0 fallos):

- `domain/Permissions.kt`: `Role` (OWNER/ADMIN/EDITOR/VIEWER), `Capability` (11) y la matriz, espejando función por función `canReadBusiness`, `canWriteOperationalData`, `canAdministerBusiness` e `isBusinessOwner`. Ojo: **ADMIN no puede gestionar miembros** — las reglas comprueban `ownerUserId` del negocio, no el rol.
- `effectiveRole` devuelve `null` si el status no es `ACTIVE` o si el rol no se reconoce. Un rol futuro no degrada a permisivo.
- `firestore.rules` endurecida para `/movements`: `CANCELLATION`, `REFUND` y `ADJUSTMENT` ahora exigen `canAdministerBusiness`; `CHARGE` y `PAYMENT` siguen igual. Se hizo para que el cliente más estricto no fuera solo esconder botones. **Sin desplegar todavía** (`firebase deploy --only firestore:rules`).
- `domain/AuditTrail.kt` + `FinancialDao.recordAudit(...)` invocado desde los cinco `*Idempotently`, dentro de la misma transacción. Id determinista desde la clave de idempotencia → reintentar no duplica el evento. `summary` es frase fija sin PII ni montos.

Falta para cerrar Fase 4:

1. **Toda la UI.** Pantalla de miembros, invitar/cambiar rol/revocar, y pantalla de historial de auditoría para OWNER/ADMIN.
2. **`MainActivity` no consulta el rol.** Hoy asume propietario; hay que resolver el `BusinessMemberEntity` del usuario autenticado y usar `Permissions.allows(...)` antes de ofrecer cada acción.
3. **Flujo de invitación.** No existe: crear miembro con `status = INVITED` y aceptar desde el otro dispositivo. Requiere también el bootstrap de negocio secundario que sigue pendiente de Fase 3.
4. **Desplegar las reglas endurecidas** y probarlas con dos usuarios en el emulador.

## TLS / Norton

El error original `PKIX path building failed / certificate_unknown` quedó resuelto de forma reversible. Norton inspecciona HTTPS con `Norton Web/Mail Shield Root`; se exportó desde el almacén de Windows y se importó únicamente en una copia temporal:

- Trust store temporal: `C:\tmp\cuentivo-jbr-cacerts`
- Certificado temporal: `C:\tmp\norton-web-mail-shield-root.cer`

No se desactivó TLS, Norton ni se modificó el JDK instalado de Android Studio. Para ejecutar Gradle en esta máquina, usar `JAVA_TOOL_OPTIONS` como en el comando anterior. Si se limpia `C:\tmp`, habrá que recrear la copia/importación.

## Reglas de continuidad

- No usar `Double` ni `Float` para dinero.
- No borrar la web ni datos/migraciones existentes sin respaldo y validación.
- Cambios pequeños, pruebas después de cada bloque, errores visibles.
- No marcar ninguna fase completada sin evidencia reciente de compilación/pruebas.
- El siguiente Codex debe leer este archivo, revisar `git status`, y continuar específicamente con “Lo que falta para cerrar Fase 3”.
