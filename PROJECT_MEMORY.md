# Memoria persistente — Cuentivo

Actualizada: 2026-07-27 (America/Mexico_City)

## FASE A CERRADA — 2026-07-27

Verificado con las tareas por separado, todas con código de salida 0:

| Tarea | Resultado |
|---|---|
| `testDebugUnitTest --rerun-tasks` | **114 pruebas, 0 fallos, 0 errores** |
| `lintDebug` | **0 errores**, 30 advertencias, 1 sugerencia |
| `assembleRelease` | exit 0; APK `apksigner verify` → *Verifies*, esquema v2, `CN=Diego Fernando Mancera Gomez, O=Capilabs` |
| `bundleRelease` | exit 0; AAB `jarsigner -verify` → *jar verified* |

**Sobre lint, con precisión:** medí el estado **posterior** al desugaring → 0 errores. El conteo de
"30 errores" previo viene de la revisión externa; no lo medí yo antes del arreglo, así que no afirmo
cuántos eran exactamente `NewApi`. Lo verificable es que hoy no queda ninguno. **No se creó baseline
ni se ocultó nada.**

Cambios de esta fase:

1. **`.kotlin/` a `.gitignore`** y `.kotlin/errors/errors-1785181423124.log` sacado del índice con
   `git rm --cached`. Sin reescribir historia ni force-push.
2. **BOM del CSV.** El literal crudo (`EF BB BF`, invisible en el editor) se sustituyó por
   `Char(0xFEFF).toString()` en una constante con nombre. ⚠️ **Desviación deliberada:** se pidió la
   secuencia de escape `﻿`, pero la herramienta de escritura la normalizaba de vuelta al
   carácter crudo. El punto de código explícito cumple el mismo objetivo y además es greppable.
   Prueba nueva `a csv saved by Excel with a byte order mark still finds its headers`, corrida
   aislada antes de seguir: 9/9 en `ProductSheetImportTest`.
3. **Auto Backup apagado.** `android:allowBackup="false"`, confirmado en el **manifest fusionado de
   release**. `backup_rules.xml` (API ≤30) y `data_extraction_rules.xml` (API 31+) excluyen
   `database`, `sharedpref`, `file`, `root` y `external` en `<cloud-backup>` **y** en
   `<device-transfer>`. Se dejan configurados aunque el flag esté en false: si alguien lo reactiva,
   el default es respaldar todo. Motivo: `LocalSessionGuard` ata la base a un uid, y una restauración
   corre antes de eso y mezclaría los libros de dos negocios.
4. **Aislamiento de alertas.** `FinancialDao.salesWithDueDate` ahora exige
   `customer.businessId = sale.businessId` además del id. Sin migración, sin tocar el esquema.
   ⚠️ **`DueSaleIsolationTest` (androidTest, 3 casos) está escrita pero NO ejecutada**: requiere
   emulador o dispositivo conectado y no hay ninguno. Pendiente correrla.

**Permisos `AD_ID` siguen en el manifest fusionado** (`com.google.android.gms.permission.AD_ID`,
`ACCESS_ADSERVICES_AD_ID`), confirmando el diagnóstico: los mete `firebase-analytics`. Se quitan en
**Fase D**, fuera del alcance de hoy.

`versionCode` sigue en **4**; el plan indica subir como 5 cuando toque publicar. No se tocó aquí.

**Siguiente: Fase B, y empieza por verificar la sync contra el emulador ANTES de reescribirla.**

## PARA RETOMAR — 2026-07-27

Plan vigente: `C:\Users\pibed\.claude\plans\ok-amigo-mira-este-dynamic-ritchie.md`
("cerrar bloqueantes antes de los 12 testers"). Verificado: **BUILD SUCCESSFUL, 113 pruebas, 0 fallos.**

### Rediseño — bloques 1 a 5 hechos

1. **Tokens.** `Type.kt` era la plantilla del IDE (sólo `bodyLarge`); ahora escala completa,
   `displaySmall` 40sp para el monto heroico. `Color.kt` con rampa neón y **la regla que evita
   repetir los bugs de contraste: el neón es sólo para rellenos** (menta sobre blanco da 1.7:1);
   cada tono tiene su versión "tinta" para texto, medidas 5.4–5.9:1. `Theme.kt` invertido a claro,
   decidido porque la app se usa en la calle a plena luz.
2. **Fondo vivo.** Degradado de 5 colores, ciclo de 20 s, dentro de `CuentivoTheme`. Respeta el
   ajuste de "quitar animaciones" del sistema.
3. **Barra ovalada + FAB.** Píldora flotante; cada pestaña con `weight(1f)` porque con 5 destinos
   las etiquetas desbordan a 360 dp. FAB contextual, ausente en Vender y Abonos que ya son
   formularios. ⚠️ **Bug hallado en dispositivo y corregido**: la barra del sistema tapaba la navbar
   — Material la descontaba sola, un `Surface` propio no. Arreglado con `navigationBarsPadding()`.
4. **`domain/BusinessInsights.kt`** (7 pruebas). Compara **el mismo tramo del mes anterior**, no el
   mes completo: en día 3, comparar 3 días contra 30 daría una caída falsa. Primer mes → dice que no
   hay con qué comparar en vez de dividir entre cero. Cambios <5% se llaman "parejo".
5. **`domain/AppAlerts.kt`** (10 pruebas) + `ui/AlertsSheet.kt` + `FinancialDao.salesWithDueDate`
   (sólo lectura, **sin migración**). Horizonte hoy/mañana/vencidos; el aviso de plan siempre va
   después del dinero; tocar un aviso abre el estado de cuenta.

**Bloque 6 (perfil con foto): aplazado**, no bloquea el test cerrado.

### Bloqueante corregido hoy

**Faltaba core-library desugaring.** `java.time` se usa en `DashboardRepository`, `CuentivoApp` y
`MainActivity` con `minSdk 24`: en Android 7.0/7.1 la app **no abría**, tronaba con
`NoClassDefFoundError`. Nadie lo notó porque siempre se probó en teléfono nuevo. Activado
`isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs:2.1.5`, conservando `minSdk 24`.

### Lo que sigue, en orden

1. **`lintDebug` — 30 errores reportados, todavía sin re-verificar** tras el desugaring. Es el
   siguiente paso inmediato.
2. Resto de Fase A: BOM del CSV a `"\uFEFF"`, apagar Auto Backup, `.kotlin/` a `.gitignore`.
3. Fase B: **verificar la sync contra el emulador ANTES de reescribirla** — nunca se ha confirmado
   una sola escritura remota. Luego atomicidad, `groupId` (migración 4→5), `WriteBatch`, y que el
   Worker vacíe la cola en vez de rendirse tras 50.
4. Fase C: borrado de cuenta remoto, política de privacidad, Data Safety.
5. Fase D: precio localizado desde `ProductDetails`, quitar `firebase-analytics`, Crashlytics.

**Decisiones vigentes:** se cierra todo antes de invitar a los 12 testers (yo recomendaba arrancar
el reloj de 14 días en paralelo; Diego decidió lo contrario).

### La nube es EXCLUSIVA de Pro — decisión definitiva (2026-07-27)

`PaidFeature.CLOUD_BACKUP` y toda la sincronización con Firestore son beneficio de Pro. **Cualquier
anotación anterior que dijera que la sincronización básica estaba disponible en Gratis queda
anulada.** Hubo un ida y vuelta ese día; esta es la versión final y no hay ambigüedad.

**Implementado y verificado**, sin reescribir la arquitectura de sync:

- **`domain/SyncEntitlement.kt`** — lógica pura, 9 pruebas. `tierFor(storedUserId, storedTier,
  currentUserId)` **falla cerrado en todas las direcciones**: sin sesión, sin nada guardado, plan
  desconocido, o entitlement de **otro uid** → FREE. El riesgo que cubre no es "a alguien de Pro le
  tardó la sync", es que el libro mayor de una persona viaje bajo el entitlement de otra.
- **`billing/EntitlementStore.kt`** — persiste el plan en SharedPreferences `cuentivo.entitlement`
  (`entitlementUserId`, `entitlementTier`) **junto al uid al que pertenece**. El plan vive en estado
  Compose mientras la app está abierta, pero WorkManager puede correr en un proceso donde
  `MainActivity` nunca arrancó; un tier en memoria es invisible justo cuando ocurre la sync.
- **Dos compuertas, no una.** `SyncScheduler.enqueue` no programa nada para Gratis, y **`SyncWorker`
  vuelve a preguntar dentro del `doWork`**, porque el trabajo programado mientras había Pro
  sobrevive al fin de la suscripción y WorkManager lo correría días después.
- **Nada se pierde.** Gratis sigue escribiendo sus filas en `sync_operations`; simplemente no se
  envían. El Worker devuelve `success` sin borrar ni marcar nada, así que al recuperar Pro la cola
  drena sola. `MainActivity` reprograma la sync en cuanto el tier vuelve a PRO.
- **Eliminar la cuenta también olvida el entitlement**, para que quien se registre después en ese
  teléfono no herede Pro.

Verificado: **123 pruebas, 0 fallos**; `lintDebug` **0 errores**; `assembleRelease` y `bundleRelease`
exit 0; AAB `jar verified`; APK reporta `versionCode='5' versionName='1.2'`.

### Estabilización 2026-07-27 (tarde)

- **Carrera Firebase Auth ↔ Play Billing, cerrada.** Billing podía responder PRO con
  `currentUser == null`: `remember(null, PRO)` no guardaba nada pero la UI quedaba en Pro mientras la
  compuerta de sync decía FREE. Ahora `BillingGateway` **arrastra el uid de la sesión que preguntó**
  y `domain/EntitlementReconciler.kt` (puro, 7 pruebas) decide: sin uid actual, o si el uid de la
  sesión no es el actual → publica FREE y no persiste. Cubre el callback tardío de otra cuenta.
  Al cambiar de uid, `MainActivity` **primero baja `tierState` a FREE** y luego vuelve a preguntar a
  Play; ese mismo camino es el de después de iniciar sesión.
- **Entitlement con caducidad de 24 h.** Se persiste `entitlementVerifiedAt` (epoch millis). La nube
  exige uid coincidente **+** PRO **+** confirmación reciente. Una confirmación con fecha futura se
  rechaza: aceptarla haría de atrasar el reloj una forma de conservar Pro para siempre. `onResume`
  reconfirma, así que un suscriptor que abra la app nunca lo nota. Nunca se borra cola ni datos.
- **`CuentivoAppTest` corregida**: el rediseño quitó el botón duplicado a propósito; ahora exige
  **exactamente una** acción accesible "Nueva venta" (por `contentDescription`, que es lo que
  anuncia un lector de pantalla), y sigue verificando `$125.00`.

**Verificado, todo exit 0:** `testDebugUnitTest --rerun-tasks` **137 pruebas, 0 fallos**;
`connectedDebugAndroidTest` en el AVD `Medium_Phone` **17/17, 0 fallos**; `lintDebug` **0 errores**
(32 advertencias, 1 sugerencia, sin baseline); `assembleRelease` y `bundleRelease`; AAB
`jar verified`; APK `versionCode='5' versionName='1.2'`, firmado por `CN=Diego Fernando Mancera
Gomez, O=Capilabs`.

⚠️ **La sincronización sigue SIN verificarse end-to-end.** Lo cerrado es **quién puede** sincronizar,
no que sincronizar funcione. Corrección importante: **el Emulator Suite sí arranca en esta máquina**
— `npx firebase-tools@15.24.0 emulators:start --only auth,firestore` descargó el jar y llegó a
"All emulators ready" (auth 9099, firestore 8080). Ya no es un bloqueante de infraestructura.
Lo que falta para el E2E de la nube: instalar con `-Pcuentivo.useEmulators=true`, conducir la app en
el AVD hasta crear negocio/cliente/venta, **forzar el entitlement sin Play** (Billing no tiene
emulador; habría que escribir `cuentivo.entitlement` por `adb shell run-as`), y comprobar en
Firestore los cinco escenarios: Gratis no sube, Pro sube y drena, downgrade retiene, upgrade reanuda,
y ningún dato cruza entre negocios. Es trabajo de una sesión propia.

**Sin probar todavía: la compra de Billing en el teléfono**, y el rediseño completo en dispositivo.

## Sesión 2026-07-26 — bugs de MVP tras probar el APK

Diego probó el APK en su teléfono al volver del viaje. Verificado con
`assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL, 89 pruebas, 0 fallos** (eran 80).
Plan aprobado: `C:\Users\pibed\.claude\plans\ok-amigo-mira-este-dynamic-ritchie.md`.

1. **BUG GRAVE ARREGLADO — dos cuentas compartían los datos.** `prueba3@gmail.com` veía los clientes
   y ventas de `prueba2@gmail.com`. Causa: `BusinessDao.first()` era
   `SELECT * FROM businesses ORDER BY createdAtEpochMillis LIMIT 1`, **sin filtrar por usuario**, y
   `DashboardRepository.load()` lo usaba tal cual; cerrar sesión sólo borraba el uid en memoria.
   Nada que ver con Firestore.
   - `BusinessDao.firstOwnedBy(userId)` nuevo; `DashboardRepository.load(userId)` lo usa. `first()`
     se conserva **sólo** para `SyncWorker`, con KDoc que dice por qué.
   - `auth/LocalSessionGuard.kt` nuevo (SharedPreferences `cuentivo.session` → `lastUserId`):
     `adopt(uid)` hace `clearAllTables()` si el teléfono traía datos de otra persona. Si no hay
     marcador (instalación previa) compara contra `businesses.ownerUserId`, **lo que repara solo el
     teléfono de Diego en el primer arranque**. `forget()` se llama al eliminar la cuenta.
   - `MainActivity`: la rama autenticada se envuelve en `sessionReady`; hasta que `adopt` termina se
     muestra `CuentivoLoadingScreen()`, porque los `produceState` alcanzaban a leer la base anterior.
   - Cerrar sesión ahora pide confirmación y **avisa** que lo no sincronizado se borra si entra otra
     cuenta. Es la contrapartida honesta de la decisión tomada.
2. **Plan gratis a 5 clientes.** Una línea en `FeatureAccessManager.FREE_CUSTOMER_LIMIT`. Las pruebas
   se reescribieron **contra la constante**, no contra el número, más una que fija el 5.
3. **Eliminar clientes y productos.** Borrado suave con los `archive(...)` que ya existían en los DAO;
   se reencola la fila completa como `update` (ningún `delete` es posible desde cliente en
   `firestore.rules`). Producto: siempre, porque `sale_items` guarda el snapshot. Cliente: **sólo con
   saldo en cero**, releído del DAO, no del renglón que pintó la pantalla; si debe, el diálogo manda
   a su estado de cuenta.
4. **Fotos de producto, locales.** `data/local/ProductImageStore.kt` + Photo Picker (sin permisos,
   sin Coil). Se reescala a 1024 px y se guarda en `filesDir/product-images/`; `imageUri` guarda
   **sólo el nombre de archivo** (una ruta absoluta se rompe en un restore). Miniatura de 56 dp en la
   lista, cuadro de 96 dp en los diálogos. Un producto llegado por sync muestra el marcador: subirlas
   exige Firebase Storage y plan Blaze, y eso queda fuera.
5. **Importar productos de Excel/CSV, sin dependencias nuevas.** `domain/ProductSheetParser.kt` (puro,
   importación **parcial** a propósito: reporta renglón y motivo de cada rechazo) y
   `domain/XlsxReader.kt` (el .xlsx es un zip de XML → `java.util.zip` + escáner de etiquetas propio,
   nada de Apache POI ni `XmlPullParser`, por eso corre en `testDebugUnitTest`). Precios sucios
   (`$1,234.50`, `1234,50`) se limpian pero siguen pasando por `Money.positiveCentsFromPesos`, que
   rechaza el tercer decimal. Botón "Plantilla" que comparte un CSV de ejemplo.
   **8 pruebas nuevas** en `ProductSheetImportTest`, incluidas dos que arman un .xlsx con `ZipOutputStream`.
   ⚠️ Está detrás de `PaidFeature.SPREADSHEET`, y `MainActivity` fija `val tier = SubscriptionTier.FREE`
   hasta que exista Billing: **para probarlo hay que cambiar esa línea a `PRO`** y regresarla antes del AAB.
6. **`docs/DESIGN_BRIEF.md`** nuevo: brief copiable a Claude Design con las 5 pestañas, las hojas y
   diálogos reales, la paleta, las restricciones (minSdk 24 → sin `Modifier.blur`, sin dependencias)
   y los 8 problemas concretos del diseño actual.

**Falta probar todo esto en el teléfono.** APK: `app/build/outputs/apk/debug/app-debug.apk`.
La prueba principal es el ciclo prueba2 → prueba3 → prueba2.

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

### Sesión 2026-07-24 (noche) — corregir errores y editar catálogo

Verificado con `assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL, 80 pruebas, 0 fallos**.

- **Cancelar venta / abono desde la pantalla** (pendiente #4 cerrado). En la hoja de estado de cuenta hay una sección "Corregir" con las ventas que aún deben algo y los abonos vigentes; cada una pide confirmación que dice explícitamente qué le pasa al saldo y que **nada se borra**. Llama a `cancelSaleIdempotently` / `cancelPaymentIdempotently` con clave `cancel-sale-{id}` / `cancel-payment-{id}`.
  - El monto se **relee del DAO** antes de cancelar (`totalCents - activeAllocatedPaymentCents`), no se confía en lo que mostró la pantalla: el DAO exige que la compensación sea exactamente el remanente y un `require` fallido tiraría la app. Los errores se atrapan con `runCatching` y salen como Snackbar.
  - Solo se ofrecen ventas con remanente > 0 (`FinancialDao.cancelableSalesForCustomer`): una venta ya cobrada produciría un movimiento de cero. Abonos vigentes vía `activePaymentsForCustomer`.
  - **Sincronización**: se encola únicamente el `MOVEMENT` compensatorio, porque ventas y pagos son create-only en `firestore.rules` y la bandera no puede viajar como edición. Por eso `RemoteMerger` ahora, al adoptar un movimiento `CANCELLATION`/`REFUND`, aplica `markSaleCanceled`/`markPaymentCanceled` sobre el padre. Depende de que las ventas se descarguen antes que los movimientos, que es el orden de `PULL_SPECS`.
- **Editar cliente y producto** (descriptivos, no financieros). Botón "Editar" en cada renglón de Clientes y Productos.
  - Cliente: recalcula `normalizedName` con el nombre nuevo — dejarlo viejo abriría la puerta a un duplicado pese al índice único. Si choca con otro cliente, el mensaje lo dice en vez de tronar.
  - Producto: el precio nuevo solo afecta ventas futuras; las pasadas guardan su snapshot en `sale_items`. La pantalla lo dice.
  - `Money.pesosText(cents)` nuevo para prellenar el campo de precio sin símbolo ni separadores, con prueba de ida y vuelta contra `centsFromPesos` (2 pruebas).

Lo que NO se hizo en esta sesión y sigue abierto:

1. **Google Play Billing**: no hay código de cobro. Requiere que Diego cree la app en Play Console y el producto `cuentivo_pro_mensual` de $199 MXN. Hoy `MainActivity` fija `tier = SubscriptionTier.FREE` en una sola línea, lista para conectarse.
2. **Fotos** de productos y de comprobantes de pago (`ProductEntity.imageUri` existe y sigue en `null`; `payments` necesita columna nueva → migración 4→5).
3. **Importar/exportar productos en Excel/CSV.**
4. ~~**Cancelar venta/abono desde la UI**~~ **Hecho la misma noche** (ver arriba). Falta probarlo en el teléfono.
5. Sync **todavía sin verificar** contra Firestore o el emulador.
6. Ícono propio, política de privacidad, formulario de Seguridad de datos.
7. `android:allowBackup="true"` sin decidir: hoy el respaldo de Android puede copiar la base financiera completa.

## PARA RETOMAR — cierre de sesión 2026-07-24 (noche)

Diego se va de viaje. Estado exacto al cerrar:

- **Compilando en verde**: `assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, **80 pruebas, 0 fallos**. El APK debug de `app/build/outputs/apk/debug/app-debug.apk` ya incluye cancelar venta/abono y editar cliente/producto, pero **Diego todavía no lo probó en el teléfono**.
- **Git**: existe el commit `43182a5` (hecho desde otra sesión, a media edición: se llevó `FinancialDao.kt` pero dejó fuera el resto). Sin commitear quedan `CuentivoApp.kt`, `MainActivity.kt`, `RemoteMerger.kt`, `Money.kt`, `PROJECT_MEMORY.md` y `MoneyPesosTextTest.kt`. **No hay remoto configurado: nada está respaldado fuera de esta máquina.** `google-services.json` y `keystore.properties` siguen fuera del control de versiones, como debe ser.
- **Plan de publicación aprobado**: `C:\Users\pibed\.claude\plans\fuzzy-baking-whale.md`. Ahí está la lista completa y ordenada de lo que falta para Play.

**Decisión de producto que cambia el plan: el Pro de $199 MXN se queda.** Diego quiere monetizar, así que NO se ocultan los precios. Consecuencia directa: **Google Play Billing pasa a ser requisito para publicar** — mostrar el precio obliga a cobrar por Play. Bloqueado hasta que Diego cree la app en Play Console y dentro de ella la suscripción `cuentivo_pro_mensual` de $199 MXN. Hoy `MainActivity` fija `val tier = SubscriptionTier.FREE` en una línea, lista para conectarse.

Otras decisiones tomadas esta sesión: cuenta de Play Console **personal** (aplica el requisito de 12 testers inscritos 14 días seguidos antes de producción), y **la sincronización se verifica antes de publicar**, no después.

Primeros tres pasos al volver, en orden:

1. Probar en el teléfono lo de esta noche (cancelar venta, cancelar abono, editar cliente duplicado, editar precio).
2. Verificar sync end-to-end contra el emulador y desplegar las reglas endurecidas. Es el bloqueante real.
3. Play Console: crear la app y la suscripción, para poder empezar Billing.

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
