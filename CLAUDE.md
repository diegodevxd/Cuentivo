# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

**Cuentivo** — app Android offline-first para pequeños negocios que venden a crédito o fiado.
Modelo: negocio → clientes → productos → ventas → cargos → abonos → saldo.

El proyecto lo inició Codex siguiendo un prompt maestro por fases. `PROJECT_MEMORY.md` es el archivo de continuidad compartido: **léelo al empezar y actualízalo al terminar trabajo relevante.**

## Comandos

Esta máquina tiene Norton interceptando TLS, así que **todo comando de Gradle** necesita el trust store temporal o falla con `PKIX path building failed`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

- `--no-watch-fs` no es opcional: el file watcher de Gradle se vuelve extremadamente lento en este entorno.
- Si se limpia `C:\tmp`, hay que reexportar `norton-web-mail-shield-root.cer` del almacén de Windows y reimportarlo en la copia del cacerts. **No se modifica el JDK instalado ni se desactiva TLS.**

| Tarea | Comando |
|---|---|
| Pruebas unitarias (JVM) | `.\gradlew.bat testDebugUnitTest` |
| Una sola clase de prueba | `.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.domain.PaymentAllocatorTest"` |
| Compilar | `.\gradlew.bat assembleDebug` |
| Pruebas instrumentadas | `.\gradlew.bat connectedDebugAndroidTest` |
| Emuladores Firebase | `firebase emulators:start` |
| Instalar apuntando a emuladores | `.\gradlew.bat installDebug -Pcuentivo.useEmulators=true` |

`cuentivo.useEmulators` **default `false`**: sin esa propiedad la app habla con Firestore de producción. El flag llega al código como `BuildConfig.USE_FIREBASE_EMULATORS` y lo consume `sync/FirebaseEmulators.kt`, que debe correr antes de cualquier uso de Firestore o Auth — `useEmulator` lanza si la instancia ya se usó. Por eso se invoca al inicio de `MainActivity.onCreate` **y** de `SyncWorker.doWork`: WorkManager puede correr en un proceso donde la Activity nunca arrancó.

Los tests de `androidTest/` **requieren emulador o dispositivo conectado**: no hay Robolectric ni `unitTests.isIncludeAndroidResources`. Ahí viven las pruebas de Room (`OperationDaoTest`, 11 casos) y de Compose (`CuentivoAppTest`).

No hay CI. Nada valida el código salvo lo que ejecutes localmente.

## Reglas del proyecto

Innegociables. Vienen del prompt maestro y están codificadas en el esquema:

1. **Dinero siempre `Long` en centavos.** Nunca `Double` ni `Float`. Hoy el repo tiene cero ocurrencias; mantenlo así.
2. **Nada financiero se borra ni se edita.** Ventas, pagos y movimientos se corrigen con un movimiento compensatorio, jamás con `UPDATE`/`DELETE`.
3. **Toda consulta filtra por `businessId`.** El aislamiento entre negocios se aplica en repositorio *y* en reglas de Firestore, no ocultando botones.
4. **Nada de PII, saldos ni tokens** en logs, Analytics o Crashlytics.
5. **Sin marcas de terceros** (Betterware, Avon, Natura, Jafra, Tupperware): identidad propia.
6. **No declarar una fase completa sin compilación y pruebas reales ejecutadas.** No inventes resultados de build.

## Arquitectura

### Room es la fuente de verdad

`data/local/CuentivoDatabase.kt` — `version = 4`, `exportSchema = true` → `app/schemas/`. 13 entidades, 8 DAOs.

**No hay `fallbackToDestructiveMigration()` y no debe haberlo.** Existe una sola migración, `MIGRATION_3_4` (crea `sync_state`), exportada junto con las demás en el arreglo `CUENTIVO_MIGRATIONS`. Si subes `version`:

1. Escribe la `Migration` y agrégala a `CUENTIVO_MIGRATIONS`.
2. Registra el arreglo en **todos** los builders de `"cuentivo.db"` — hoy son dos, `MainActivity.onCreate` y `SyncWorker.doWork`. Olvidar uno hace que solo truene esa ruta.
3. Verifica el SQL contra `createSql` del esquema recién exportado en `app/schemas/.../{version}.json`. Room valida un hash de identidad: si tu `CREATE TABLE` difiere aunque sea en un backtick, la migración corre y *aun así* falla al abrir.

Tampoco hay `@ForeignKey` en ninguna entidad: la integridad referencial se aplica con `require(...)` dentro de las transacciones de `FinancialDao`.

### Dinero y fechas

- Columnas monetarias llevan sufijo `Cents` y son `Long`. La única conversión a decimal está en la frontera de UI: `CuentivoApp.kt:155-156`, `BigDecimal.valueOf(cents, 2)` con `Locale("es","MX")`.
- **Dos precisiones de fecha, no las mezcles**: fechas de negocio en `...EpochDay` (`saleDateEpochDay`, `dueDateEpochDay`, `paymentDateEpochDay`); auditoría y sincronización en `...EpochMillis`.

### El saldo se deriva, nunca se almacena

`account_movements` es el libro mayor append-only. `amountCents` es **siempre positivo**; el signo lo da la columna `direction` (`DEBIT`/`CREDIT`), resuelto en SQL:

```sql
CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END
```

El saldo sale exclusivamente de `FinancialDao.customerBalanceCents` / `businessReceivableCents`. Nunca de un campo mutable ni de texto de la UI.

Igual con el estado de venta: **no hay columna de status en `sales`**. `SaleStatusCalculator.status(...)` lo deriva con precedencia total `CANCELED > PAID > OVERDUE > PARTIALLY_PAID > PENDING`, así que una venta pagada nunca aparece vencida.

### La lógica de negocio vive en FinancialDao

`data/local/FinancialDao.kt` es el archivo más importante del proyecto. Sus siete métodos `@Transaction` son donde se aplican las invariantes reales — no en los repositorios, que son envoltorios delgados:

- **Idempotencia durable**: cada `*Idempotently` abre con `if (reserveOperation(operation) == -1L) return requireNotNull(operationResultId(key))`. La reserva de la clave y la escritura financiera ocurren en **una sola transacción SQLite**, así que un reintento devuelve el `resultId` original en vez de duplicar el cargo.
- **Cancelar venta** (`cancelSaleIdempotently`): movimiento `CANCELLATION/CREDIT` por **solo el remanente no pagado** (`totalCents - activeAllocatedPaymentCents`). Un pago devuelto es un evento de libro mayor aparte.
- **Cancelar pago** (`cancelPaymentIdempotently`): movimiento `REFUND/DEBIT` por el monto completo + sellar `payments.canceledAtEpochMillis`. Nunca se borra la evidencia del cobro.
- **Sin sobre-asignación**: una asignación no puede exceder `sale.totalCents - activeAllocatedPaymentCents(sale.id)`.
- **Todo ajuste exige motivo escrito**: `require(!notes.isNullOrBlank())`.

`domain/IdempotencyRegistry.kt` es una implementación **en memoria, de referencia, no thread-safe y sin uso en producción** — solo la tocan sus tests. La idempotencia real es la tabla `operations`.

### Capa de dominio

Todo en `com.cuentivo.app.domain`, plano. Los calculadores son `object` de Kotlin puro sin dependencias de Android, por eso corren en `testDebugUnitTest`:

| Archivo | Qué garantiza |
|---|---|
| `FinancialCalculator` | Suma de movimientos con signo por `MovementType`. Aritmética entera pura. |
| `SaleStatusCalculator` | Estado derivado de la venta. |
| `PaymentAllocator` | Asignación greedy por vencimiento más antiguo, con desempate determinista `dueDate → saleDate → id`. El remanente se conserva como `creditCents`, nunca se descarta. |
| `CustomerNameNormalizer` | NFD → quita acentos → minúsculas → colapsa espacios. Alimenta `customers.normalizedName`, mitad del índice único `(businessId, normalizedName)` que impide clientes duplicados. |
| `LegacyMigrationPlanner` | Planifica la importación desde la web original. **Puro: planifica pero nunca escribe.** IDs deterministas (`legacy-movimiento-{id}`) para que replanificar sea idempotente. |

`LegacyMigrationRepository` es el único de la capa que toca la API de Room (`withTransaction`) y el único que exige `plan.rejected.isEmpty()` — la importación es todo-o-nada.

⚠️ **Desajuste conocido**: `FinancialCalculator.MovementType` tiene `ADJUSTMENT_DEBIT`/`ADJUSTMENT_CREDIT`, pero la BD guarda `type = "ADJUSTMENT"` + columna `direction` separada. No son 1:1 y no existe un mapper entre ambas representaciones.

### Sin inyección de dependencias

Cero Hilt/Dagger/Koin. Cableado manual en `MainActivity.onCreate`. Consecuencias que importan:

- Los repositorios son **clases concretas, no interfaces**. Los seams de test son las interfaces DAO de Room, `DashboardRepository.todayProvider` (reloj inyectable) y `DashboardViewModel`, que recibe `suspend () -> DashboardSnapshot?` en vez del repositorio.
- `DashboardViewModel` **no es un `androidx.lifecycle.ViewModel`** — es una clase plana sin `viewModelScope` ni `StateFlow`.
- `SyncWorker` construye su **propia instancia de Room** (`"cuentivo.db"`), independiente de la de `MainActivity`. No hay singleton de aplicación.

### Sincronización: outbox de subida + pull con watermark

`SyncWorker.doWork` hace **push y luego pull**, en ese orden a propósito: un dispositivo que acaba de escribir debe publicar antes de adoptar, para que la fusión vea sus propias filas como ya presentes en vez de competir con ellas.

**Subida** — patrón outbox sobre `sync_operations`:

1. `SyncQueue.enqueue(...)` crea una fila con `id = SHA-256("businessId|entityType|entityId|revision")` e `@Insert(IGNORE)` → reencolar es idempotente.
2. `SyncScheduler` lanza trabajo único `cuentivo-sync` en WorkManager con constraint de red conectada.
3. `SyncWorker.push` toma lotes de 50 de `SyncOperationDao.pending(...)` y los pasa a `SyncCoordinator`.
4. `FirestoreSyncGateway` escribe en `businesses/{businessId}/...` usando el UUID local como ID de documento remoto.
5. Éxito → `remove(id)`. Fallo permanente → `markFailure(id, "PERMANENT:$error")`, y el prefijo `PERMANENT:` excluye la fila de `pending` para siempre. `parkExhausted(10)` estaciona además lo que agotó 10 intentos — estacionar no es borrar, la fila queda para inspección y `reopenParkedOperations()` la reactiva.

**Descarga** — `SyncWorker.pull` → `FirestoreSyncGateway.download` → `RemoteMerger`:

- El watermark vive en `sync_state.lastPulledAtEpochMillis`, uno por negocio.
- **Cada colección se ordena por su propio campo temporal**, porque no comparten nombre: `updatedAtEpochMillis` para clientes/productos/ventas, `createdAtEpochMillis` para pagos, `occurredAtEpochMillis` para movimientos. Esa tabla está en `PULL_SPECS`.
- El negocio y los miembros se traen completos, no por watermark: son pocos documentos y condicionan todo lo demás.
- Ítems de venta y asignaciones se traen **por padre recién descargado**, no con `collectionGroup`. Así no hacen falta índices compuestos.
- Al terminar, el watermark avanza a `max(visto) - 5 min`. El retroceso es deliberado: un dispositivo que estuvo offline sube documentos con fecha anterior a otros ya descargados, y sin ese solape quedarían atrás para siempre. Toda fusión es idempotente, así que releer el solape no cambia nada.

**Qué puede hacer un documento remoto** lo decide `RemoteMergePlanner` (lógica pura, 11 pruebas en `RemoteMergePlannerTest`):

| Tipo | Regla |
|---|---|
| `BUSINESS`, `MEMBER`, `CUSTOMER`, `PRODUCT` | Descriptivos: el remoto **sobrescribe** si es estrictamente más nuevo (`RemoteMergePolicy`). Empate con contenido distinto → gana el local, que sigue encolado para subir; ambos dispositivos reconvergen ahí. |
| `SALE`, `SALE_ITEM`, `PAYMENT`, `PAYMENT_ALLOCATION`, `MOVEMENT` | Financieros: **solo se insertan si faltan** (`adopt*` con `OnConflictStrategy.IGNORE`). Nunca se reescriben, ni siquiera por una copia remota más nueva. Una corrección llega como su propio movimiento compensatorio, igual que en local. |

La única excepción a lo anterior es la **cancelación**: si el remoto trae una venta o un pago cancelado que este dispositivo no había visto, se aplica solo la bandera vía `markSaleCanceled` / `markPaymentCanceled`. La fila en sí no se toca, y una cancelación local nunca se deshace.

`RemoteMerger` lee el dinero con un lector que **rechaza `Double`** en vez de truncarlo — es exactamente por ahí por donde entraría dinero fraccionario a un sistema de centavos enteros. Un documento remoto malformado se descarta contando `rejected` sin abortar el lote.

### Roles y auditoría

`domain/Permissions.kt` es el **espejo cliente** de las funciones de rol de `firestore.rules`. No es el punto de aplicación — el servidor decide. Existe porque esconder un botón no es un control de permisos: cualquier ruta que llegue a un DAO debe poder preguntar si el miembro actual puede hacer esto y recibir la misma respuesta que daría el servidor.

| Capacidad | Función equivalente en las reglas | Roles |
|---|---|---|
| `VIEW_BUSINESS` | `canReadBusiness` | cualquier miembro `ACTIVE` |
| `MANAGE_CUSTOMERS`, `MANAGE_PRODUCTS`, `RECORD_SALE`, `RECORD_PAYMENT` | `canWriteOperationalData` | OWNER, ADMIN, EDITOR |
| `CANCEL_SALE`, `CANCEL_PAYMENT`, `REGISTER_ADJUSTMENT`, `VIEW_AUDIT_LOG` | `canAdministerBusiness` | OWNER, ADMIN |
| `MANAGE_MEMBERS`, `UPDATE_BUSINESS` | `isBusinessOwner` | **solo OWNER** |

⚠️ Dos sutilezas que se ven mal si no se leen las reglas:

- **ADMIN no puede gestionar miembros.** Las reglas comprueban `ownerUserId` del documento de negocio, no el rol. `Permissions` lo espeja tal cual.
- `effectiveRole(rawRole, status)` devuelve `null` si `status != "ACTIVE"` **o** si el string de rol no se reconoce. Un rol futuro que agregue un cliente más nuevo no degrada a algo permisivo aquí.

Cancelaciones y ajustes son el único punto donde el cliente es más estricto que el agrupamiento original de `canWriteOperationalData`. Para que eso no fuera teatro, se **endureció también la regla** de `/movements`: `CHARGE` y `PAYMENT` siguen bajo `canWriteOperationalData`; `CANCELLATION`, `REFUND` y `ADJUSTMENT` exigen `canAdministerBusiness`. Si cambias un lado, cambia el otro.

**La auditoría se escribe dentro de la transacción financiera.** `FinancialDao.recordAudit(...)` se llama desde los cinco `*Idempotently`, así que la fila de `audit_logs` y el movimiento que describe se confirman juntos o no se confirman. El id sale de `AuditTrail.eventId(idempotencyKey)`, determinista, así que reintentar una operación ya aplicada no duplica el evento.

`AuditAction.summary` es una frase fija, nunca interpolada con nombre, teléfono ni monto: el detalle está a un join de distancia vía `entityType`/`entityId`, y así un historial se puede mostrar o exportar sin filtrar quién debe cuánto.

`audit_logs` **no se sincroniza**: `firestore.rules` tiene `allow write: if false` para `auditLogs`. Es lectura para OWNER/ADMIN y escritura solo desde backend. No lo encoles.

### Reglas de Firestore

`firestore.rules` — el rol vive en `businesses/{businessId}/members/{uid}.role` ∈ `OWNER|ADMIN|EDITOR|VIEWER`, y `isMember()` exige además `status == 'ACTIVE'`.

- **Ningún `delete` está permitido desde cliente**, en ninguna colección.
- Ventas, ítems, pagos, asignaciones y movimientos son **create-only**.
- `auditLogs` es solo lectura para OWNER/ADMIN; toda escritura denegada.
- `hasExactKeys()` rechaza documentos con llaves de más *o* de menos.
- Las reglas validan aritmética: `totalCents == subtotalCents - discountCents` y `subtotalCents == quantity * unitPriceCents - discountCents`.

### Desajuste directorio/paquete

`MainActivity.kt` y `ui/theme/*` están físicamente en `app/src/main/java/com/example/organizationlabs/` pero declaran `package com.cuentivo.app`. Funciona por `android.disallowKotlinSourceSets=false` en `gradle.properties`. El único código realmente en `com.example.organizationlabs` es `ExampleUnitTest.kt`.

## Estado real por fases

`PROJECT_MEMORY.md` tiene la tabla completa. Resumen:

| Fase | Estado |
|---|---|
| 0. Diagnóstico | Completa |
| 1. Núcleo financiero | Completa — sólida y bien probada |
| 2. Interfaz Android | **Parcial** |
| 3. Firebase/sincronización | **Parcial — código completo, sin verificar contra Firestore** |
| 4. Colaboración/roles/auditoría | **Parcial — dominio y datos listos, sin UI** |
| 5–7 | Sin empezar |

### La Fase 3 NO está cerrada

Resuelto el 2026-07-24, con `testDebugUnitTest` en verde:

1. ✅ **Documento `MEMBER` sin encolar.** Era el bloqueante original: sin él toda subida distinta del negocio raíz recibía `PERMISSION_DENIED` y quedaba estacionada. `sync/OwnerMembershipBootstrap.kt` publica la membresía OWNER, al crear el negocio y como backfill en arranque. Se encola con `revision = createdAtEpochMillis + 1` para ordenar justo **después** del `BUSINESS` (las reglas hacen `getAfter` sobre ese documento) y **antes** de las filas operativas.
2. ✅ **Descarga remota.** `SyncRemotePullGateway.download` + `RemoteMerger` + tabla `sync_state`. Ver la sección de sincronización arriba. `RemoteMergePolicy` por fin tiene call site de producción, vía `RemoteMergePlanner`.
3. ✅ **Clasificación de fallos.** Ahora por `FirebaseFirestoreException.Code`, no por `contains()` sobre el mensaje. `INVALID_ARGUMENT`, `NOT_FOUND` y `FAILED_PRECONDITION` (= falta un índice) son permanentes; ya no se reintentan para siempre. Hay tope de 10 intentos vía `parkExhausted`.
4. ✅ **`SyncWorker` ya no puede morir.** El `doWork` envuelve todo en `try/catch` y devuelve `Result.retry()`, nunca `FAILED` — `FAILED` no se reprograma y dejaba la cola sin drenar. Y `FirestoreDocumentPath.forOperation` se resuelve **antes** del `try` de red: un `entityType` no soportado es fallo permanente, no transitorio.

Lo que sigue abierto:

5. **Sin verificación contra Firestore real ni Emulator.** *Ninguna subida ni descarga se ha confirmado end-to-end.* `firebase.json` no tiene bloque `emulators`, no hay `.firebaserc`, ningún `useEmulator(...)` → todo apunta a producción. **Este es ahora el siguiente bloqueante real**: no tiene sentido agregar más superficie de sync sobre una base que nunca ejecutó una sola escritura remota.
6. **El encolado no es atómico.** Ocurre como statement suelto después de la escritura local en `MainActivity`, no dentro de la misma transacción. Si el proceso muere entre ambos, la venta existe en local y nunca se encola.
7. **Sin `WriteBatch`.** El gateway hace `set()` individuales para venta, ítem y movimiento. `FIRESTORE_RULES_AUDIT.json` advierte exactamente contra esto: *"No activar sincronización financiera hasta implementar esa garantía."*
8. **`PAYMENT_ALLOCATION` no se encola.** Hoy no importa porque `MainActivity` registra pagos con `emptyList()` de asignaciones; al implementar abonos por venta habrá que agregarlo. La descarga sí lo maneja.
9. Los 16 checkboxes de `docs/superpowers/plans/2026-07-24-phase-3-sync.md` siguen **sin marcar**.

## Notas del repositorio

- Rama actual `phase1-financial-core`. **Todo el trabajo de Fase 3** (`auth/`, `sync/`, `ui/auth/`, configs de Firebase) está sin commitear — preservarlo.
- ⚠️ **`app/google-services.json` NO está en `.gitignore`** y aparece como untracked. No lo incluyas en un `git add -A` sin decisión explícita del usuario.
- `.audit-betterorganitazion/` es la app web original (PWA con Supabase/Stripe) como repo git anidado e ignorado. Es la referencia para la migración de datos: **no se compila ni se modifica**.
- `.agents/skills/` + `skills-lock.json`: 11 skills de Firebase vendorizadas con hash de integridad.
- No hay README ni CI.

## Forma de trabajar

- Cambios pequeños; compila y ejecuta pruebas después de cada bloque.
- No ocultes errores ni inventes resultados de build. Reporta el comando que realmente ejecutaste.
- Respeta la arquitectura existente; no reescribas por comodidad ni agregues dependencias innecesarias.
- No inventes nombres de archivos, clases, tablas ni colecciones: trabaja con lo que existe.
