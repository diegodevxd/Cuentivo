# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

**Cuentivo** — app Android offline-first para pequeños negocios que venden a crédito o fiado.
Modelo: negocio → clientes → productos → ventas → cargos → abonos → saldo.

El proyecto lo inició Codex siguiendo un prompt maestro por fases.

**Dónde vive cada cosa, para no leer de más:**

| Archivo | Qué contiene | Cuándo leerlo |
|---|---|---|
| `CLAUDE.md` (este) | Arquitectura, reglas innegociables, comandos y trampas del entorno | Siempre |
| `PROJECT_MEMORY.md` | Estado vivo: dónde estamos, qué sigue, blockers abiertos | Al empezar; **actualízalo al terminar trabajo relevante, sustituyendo, no añadiendo** |
| `docs/historia-del-proyecto.md` | Diario histórico completo, sesión por sesión | Sólo si necesitas arqueología de una decisión vieja |

`PROJECT_MEMORY.md` se mantiene por debajo de ~100 líneas a propósito: lo que deja de estar vigente baja a `docs/historia-del-proyecto.md`, y lo que se puede leer del código o de `git log` no va en ninguno de los dos.

## Comandos

Esta máquina tiene Norton interceptando TLS, así que **todo comando de Gradle** necesita el trust store temporal o falla con `PKIX path building failed`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

- `--no-watch-fs` no es opcional: el file watcher de Gradle se vuelve extremadamente lento en este entorno.
- Si se limpia `C:\tmp`, hay que reexportar `norton-web-mail-shield-root.cer` del almacén de Windows y reimportarlo en la copia del cacerts. **No se modifica el JDK instalado ni se desactiva TLS.**
- **Norton rota su raíz cada tantas semanas**, y entonces el trust store existente deja de servir aunque el archivo siga ahí. El síntoma engaña: todo compila mientras las dependencias estén en caché y sólo falla al bajar una nueva, con `PKIX path validation failed ... Path does not chain with any of the trust anchors`. Se diagnostica comparando huellas — si no coinciden, se regenera:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore C:\tmp\cuentivo-jbr-cacerts -storepass changeit -alias norton-web-mail-shield-root | Select-String "SHA1:"
Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Subject -match 'Norton Web/Mail Shield Root' } | Select-Object Thumbprint
```

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

### Automatizaciones del repo (`.claude/`)

Como no hay CI, algunas reglas de este archivo están además mecanizadas. **En Claude Code corren
solas; en otros agentes son sólo documentación, así que ahí hay que aplicarlas a mano.**

| Archivo | Qué hace |
|---|---|
| `.claude/skills/gate/SKILL.md` | El gate de evidencia completo, con la receta de entorno que no está en ningún otro lado: cómo levantar el emulador de Firestore sin `firebase` ni `java` en el PATH. Se invoca con `/gate`. |
| `.claude/hooks/strings-parity.sh` | Al editar `strings.xml`, avisa qué claves quedaron sin contraparte ES/EN. Contar totales no basta: dos archivos empatan y tienen claves distintas. |
| `.claude/hooks/guard-secrets.sh` | **Bloquea** volcar el contenido de `keystore.properties`, `*.jks`, `google-services.json` o claves de cuenta de servicio. Comprobar que existen (`ls`, `test -f`) sigue permitido. |
| `.claude/hooks/guard-money-type.sh` | Avisa si aparece `Double`/`Float` en una línea con vocabulario de dinero. Regla 1: un decimal ahí compila limpio y se publica. |
| `.claude/agents/test-honesty-reviewer.md` | Revisa si una prueba ejercita producción o sólo una copia de la regla. Las cinco formas que ya tomó ese defecto aquí están enumeradas dentro. |

### Emulador

SDK en `C:\Users\pibed\AppData\Local\Android\Sdk`, `adb` en `$SDK/platform-tools/`. Hay un AVD, `Medium_Phone`, **API 37** — es la única imagen instalada, por eso nada por debajo de API 33 se ha probado nunca. Para `adb pull` desde Git Bash hace falta `MSYS_NO_PATHCONV=1` y rutas de Windows, o falla. Cuando el teléfono físico de Diego (A059P, Android 16) está conectado en depuración, sustituye al emulador y es la ruta preferida: no se degrada.

**El emulador se degrada con el uso.** Si una suite aborta con `INSTRUMENTATION_ABORTED: System has crashed` o aparece `RootViewWithoutFocusException`, la causa suele ser un SIGSEGV del driver GL que deja un diálogo ANR de `com.android.systemui` dueño del foco. Se comprueba con `adb shell dumpsys window | grep mCurrentFocus` y se resuelve arrancando en frío hasta que `adb shell dumpsys user` dé `0=RUNNING_UNLOCKED`:

```bash
adb emu kill
emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim
```

Para sacar archivos que escribió una prueba instrumentada hay que impedir que Gradle desinstale el APK al terminar: `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, y luego `adb exec-out run-as com.cuentivo.app cat files/<ruta>`.

Para verificar la firma de un **APK**, **`apksigner verify --print-certs`**. `jarsigner -verify` sólo entiende firma v1 y dice "jar is unsigned" para un APK firmado con v2: es un falso negativo.

Para un **AAB es al revés**: `apksigner` truena con `ApkFormatException: Missing AndroidManifest.xml` porque un bundle no es un APK. Ahí va **`jarsigner -verify`** (debe decir `jar verified`; los avisos de certificado autofirmado y sin timestamp son normales con una llave de subida). Para comparar que dos AAB llevan la **misma llave** — obligatorio antes de subir un parche, porque Play rechaza para siempre un bundle firmado con otra: `keytool -printcert -jarfile <aab>` y contrastar el SHA1.

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

`data/local/CuentivoDatabase.kt` — `version = 8`, `exportSchema = true` → `app/schemas/`. 15 entidades. El número envejece: léelo del archivo antes de decidir si un cambio necesita migración.

**No hay `fallbackToDestructiveMigration()` y no debe haberlo.** Hay cinco migraciones — de `MIGRATION_3_4` a `MIGRATION_7_8` — exportadas en el arreglo `CUENTIVO_MIGRATIONS`. Si subes `version`:

1. Escribe la `Migration` y agrégala a `CUENTIVO_MIGRATIONS`.
2. Registra el arreglo en **todos** los builders de `"cuentivo.db"` — hoy son **tres**: `MainActivity.onCreate`, `SyncWorker.doWork` y `CollectionReminderWorker.doWork`. Olvidar uno hace que solo truene esa ruta, y las dos rutas de WorkManager pueden correr en un proceso donde la Activity nunca arrancó. Verifica con `grep -rn '"cuentivo.db"' app/src/main` en vez de confiar en esta lista.
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
| `CustomerNameNormalizer` | NFD → quita acentos → minúsculas → colapsa espacios. Alimenta `customers.normalizedName`, mitad del índice `(businessId, normalizedName)`. **El índice NO es único y no debe serlo**: el nombre es ayuda de búsqueda, nunca identidad, y un "Diego" archivado no puede impedir crear otro Diego. La identidad es `customer.id`. El propio `CustomerEntity` lo documenta. |
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

### CuentivoAI (`ai/` + `functions/`)

Capacidad adicional y desactivable; `PROJECT.md` es la puerta de entrada y apunta al source of truth
en `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`. La regla que condiciona todo
el código de esta capa: **Gemini sólo propone intents y borradores tipados; nunca escribe dinero,
Room, Firestore, inventario ni outbox.** La app resuelve datos reales, calcula, muestra una preview
editable y ejecuta únicamente tras confirmación explícita e idempotente (`AiSaleExecution`,
`AiPaymentExecution` → los mismos `*Idempotently` de `FinancialDao`). Por eso hay un `*Safety` /
`*Policy` por cada salida del modelo (`AiDraftSafety`, `AiProposalSafety`, `AiBusinessScopePolicy`):
son el filtro entre texto del modelo y el libro mayor.

- **Cliente → servidor**: `FirebaseCallableAiGateway` llama Cloud Functions; el modelo nunca se
  invoca desde el dispositivo. `FakeAiProvider` es el seam de pruebas.
- **`functions/`** (Node/TS, Vitest): `interpretAiRequest`, `extractLedgerPage` (libreta por foto),
  `authorizeAiAction`, `getAiAvailability`, `verifyAiSubscription` y
  `handlePlayRtdnNotification` (suscripciones de Play). Los contratos viven en `contract.ts` y
  `shared-fixtures/ledgerOperationFixtures.json` los comparte con las pruebas de la app: si cambias
  un tipo de operación, cambia ambos lados.
- Comandos de `functions/`: `npm test` (sin gate), `npm run test:firestore-emulator` (gate),
  `npm run lint` (`tsc --noEmit`), `npm run release:check` (todo lo anterior, antes de desplegar).
- Tablas propias en Room: `ai_daily_messages` (cupo diario) y `ai_ledger_import_metrics`.

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

`MainActivity.kt` está físicamente en `app/src/main/java/com/example/organizationlabs/` pero declara `package com.cuentivo.app`. Funciona por `android.disallowKotlinSourceSets=false` en `gradle.properties`. Lo que queda realmente en `com.example.organizationlabs` es esa Activity, `ui/PaymentRefundFactory.kt` y `ExampleUnitTest.kt`.

El tema (`ui/theme/*`) vivía en ese mismo directorio por la misma razón; desde F5-B está en `shared/src/commonMain/kotlin/com/cuentivo/app/ui/theme/`, junto con las piezas base de UI y la barra de navegación.

## Huecos conocidos de la sincronización

El estado por fases y los pendientes del momento están en `PROJECT_MEMORY.md`. Lo que va aquí son
dos huecos **estructurales** del código de sync, porque condicionan cómo se escribe encima:

- **El encolado no es atómico, y es deliberado.** El `syncQueue.enqueue(...)` ocurre *después* del
  `database.withTransaction { ... }` en `MainActivity`, no dentro. La razón está comentada en el
  código: una fila ya guardada en local no puede quedar oculta porque falle el outbox, así que
  encolar es *best effort* respecto de ella. El costo: si el proceso muere entre ambos, la venta
  existe en local y **nunca se encola**. No hay reconciliación que lo recupere.
- **Sin `WriteBatch`.** `FirestoreSyncGateway` hace `set()` individuales para venta, ítem y
  movimiento, así que una venta puede quedar publicada a medias en Firestore.
  `FIRESTORE_RULES_AUDIT.json` advierte exactamente contra esto.

### Puerta de verificación del idioma

El trabajo de i18n no está hecho hasta que **las dos** salgan vacías (salvo etiquetas de animación
de Compose). La segunda existe porque los avisos que se ven desde cualquier pantalla —los de
`MainActivity` y los de compra de `billing/`— no viven en `ui/`, y estuvieron fijos en español hasta
la v43 mientras esta puerta sólo miraba un directorio. **Un patrón que no casa nada en un archivo
que dice escanear es una puerta que hace menos de lo que parece**: la de `billing/` caza
`onMessage("..."`, que es como ahí se dicen los avisos.

```bash
# 1) Composables de ui/. La exclusión de la cola son etiquetas de animación (el "salvo" de arriba):
#    están listadas a mano para que la salida sea VACÍA de verdad; una animación nueva aparecerá
#    en la puerta y habrá que añadirla aquí conscientemente, no filtrarla a ciegas.
grep -rnE '(Text\(|text = |label = |title = |detail = |placeholder = |contentDescription = |emptyText = |actionLabel = |eyebrow = |note = |backDescription = )"[^"]*[a-zA-ZáéíóúñÁÉÍÓÚÑ¿¡][^"]*"' app/src/main/java/com/cuentivo/app/ui shared/src/commonMain/kotlin/com/cuentivo/app/ui --include=*.kt | grep -vE '"\$ "|" · "|testTag|neo-action-press|"Cuentivo"|"destination entrance"|"navigation press depth"|"navigation content color"|"waveform-bar"'

# 2) Avisos fijos al usuario fuera de ui/: asignaciones, asuntos de compartir y ramas de `when`,
#    incluida la línea que sólo contiene el literal (ramas que se parten en dos líneas).
grep -rnE '(uiMessage = |share\(|subject = |onMessage\(|-> )"[^"]*"|^\s*"[^"]*[a-zA-ZáéíóúñÁÉÍÓÚÑ][^"]*"$' \
  app/src/main/java/com/example/organizationlabs/MainActivity.kt \
  app/src/main/java/com/cuentivo/app/billing \
  shared/src/commonMain/kotlin/com/cuentivo/app/ui --include=*.kt | grep -vE 'Long\.MIN_VALUE|parts\.isEmpty\(\) -> "\?"'
```

La exclusión de la segunda puerta es igual de consciente que la de la primera: un mensaje de `require`
de `BalancePresentation` y el `"?"` de iniciales de `CustomerDirectoryPresentation`, que no son texto
traducible. Entraron al moverse a `shared/.../ui`.

**Fuera de la puerta, a propósito:** `Log.*`, `error(...)` y comentarios — no son avisos que vea
nadie, y el contenido mandado **al cliente** (`DebtorShareText`, `StatementImage`,
`StatementLineFormat`, el CSV) se queda en español por decisión de Diego: sus clientes están en
México.

Y la paridad de claves debe seguir cuadrando (2026-11-05: 702 y 702). El
número envejece, así que cuenta, no confíes en esta línea:

```bash
grep -c "<string name=" shared/src/commonMain/composeResources/values/strings.xml shared/src/commonMain/composeResources/values-en/strings.xml
```

Los textos viven en **un solo lugar**, `shared/src/commonMain/composeResources` (`values/` en
español y `values-en/` en inglés): es la fuente de `Res.string` en iOS y de `R.string` en Android,
donde una tarea de Gradle en `:app` (`GenerateAndroidStringsTask`) escribe sus `strings.xml` en el
build. No los edites en `app/src/main/res`: ahí ya no existen.

**Los textos van sin diagonales de escape**: Compose Multiplatform sólo entiende `\n`, `\t`,
`\uXXXX` y `\\`, así que un `\"` o un `\'` se vería con la diagonal en pantalla. Las comillas y los
apóstrofos se escriben tal cual (`No se pudo`, `"x"`) y el generador de Android es quien les pone la
diagonal al compilar. Nada de `don\'t` ni de `\"` en la fuente.

**Los `object` de `domain/` son Kotlin puro sin `Context`** — por eso corren en `testDebugUnitTest`.
No les metas `Context` ni `R.string`: rompe las pruebas y la separación que hace valiosa esa capa.
El patrón correcto es que el `object` devuelva una **clave o un enum** y la UI resuelva el texto.
`AuditAction.summary` se persiste en `audit_logs`: **no se traduce al escribir**.

## Notas del repositorio

- Rama de trabajo `diegodevxd/feature-cuentivo-ai`, con upstream en `origin`. `codex/neo-fintech` lo fue hasta agosto de 2026 y `main` quedó en el commit inicial.
- `app/google-services.json` **sí está en `.gitignore`** (y también `*.apk`, `*.aab`, `/dist/`, `/artifacts/`, `*.jks`, `keystore.properties`). Aun así, revisa lo que quedó en staging antes de comitear.
- `.audit-betterorganitazion/` es la app web original (PWA con Supabase/Stripe) como repo git anidado e ignorado. Es la referencia para la migración de datos: **no se compila ni se modifica**.
- `.agents/skills/` + `skills-lock.json`: 11 skills de Firebase vendorizadas con hash de integridad.
- No hay README ni CI.

## Forma de trabajar

- Cambios pequeños; compila y ejecuta pruebas después de cada bloque.
- No ocultes errores ni inventes resultados de build. Reporta el comando que realmente ejecutaste.
- Respeta la arquitectura existente; no reescribas por comodidad ni agregues dependencias innecesarias.
- No inventes nombres de archivos, clases, tablas ni colecciones: trabaja con lo que existe.
- **Una prueba no puede reimplementar la lógica que verifica.** Tiene que invocar el código que
  producción ejecuta, y tiene que **fallar si ese código se rompe**. Una prueba que copia la regla
  dentro de sí misma —o que afirma contra un objeto paralelo que nadie llama— pasa en verde mientras
  el defecto se publica. Ya ocurrió cinco veces en este proyecto. Si el código que importa vive
  dentro de un Composable o de una Activity, la salida correcta es **extraerlo** a un objeto que la
  prueba pueda invocar, no reescribirlo en el test.
- **Correr la suite no es correr todo.** En `functions/`, `npm test` **no** incluye el gate del
  emulador: `vitest.config.ts` sólo toma `test/**/*.test.ts`, y `firestoreEmulator.gate.ts` va
  aparte con `npm run test:firestore-emulator`. Reportar el total de `npm test` como si cubriera el
  gate es reportar evidencia que no ocurrió.
- **Antes de desplegar `functions/`, corre `npm --prefix functions run release:check`.** Encadena
  `npm ci` limpio → lint → pruebas → **gate del emulador** → borrar `lib/` y compilar, y **falla si
  falta el JDK** en vez de saltarse el gate en silencio. No despliega: eso lo autoriza Diego aparte.
  Existe porque este proyecto ya tuvo **tres fallos de la misma familia**, todos verdes haciendo
  menos de lo que parecían: `npm test` que no cubre el gate del emulador, `npm test` que no valida
  el lockfile —sólo `npm ci` lo caza— y `firebase deploy` que publicaba `lib/` sin compilar. Ese
  último ya tiene además su propia red: el hook `predeploy` de `firebase.json` recompila siempre.
