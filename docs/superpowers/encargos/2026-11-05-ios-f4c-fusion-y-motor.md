# Encargo iOS — F4-C: fusión remota en común y motor de sync en iOS

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F4-C): ...`, uno por tarea.

Windows no compila iOS: lo verifica el runner después del push. Antes de usar una API nativa o de Room, **confirma el
nombre en la fuente oficial o en código que ya compila aquí; no lo adivines.** Reglas de código común: nada de JVM, y
en nombres entre backticks ninguno de `, . ; : / \ < > [ ]`. `:shared` no depende de Firebase.

**Fuera de este encargo:** login, App Check, pantallas y `FirestoreDeletionGateway`.

## Tareas

**C1. Transacción común.** En `:shared` crea `expect suspend fun <R> CuentivoDatabase.inTransaction(block: suspend () -> R): R`.
- `actual` Android: `withTransaction(block)`, **sin ningún cambio de comportamiento**.
- `actual` iOS: la API de transacción de Room KMP 2.7.2 (`useWriterConnection` + `immediateTransaction`). Confírmala en
  la documentación de Room 2.7, y comprueba que los DAOs llamados dentro del bloque participan de esa transacción.

**C2. `RemoteMerger` a `commonMain`**, con `git mv`, mismo paquete: solo cambia `withTransaction` por `inTransaction`. El
lector de dinero que acepta solo `Long` **queda idéntico**, y también las reglas de financiero solo-insertar y de
cancelación. Si `CloudRestore`, `OwnerMembershipBootstrap` u `OutboxBackfill` son portables con el mismo cambio, muévelos.
Si dependen de `Context`, de Firebase o de `android.util.Log`, extrae solo la parte pura. Lo que no se pueda, se queda y
se reporta. Los usos en `app` siguen funcionando con los mismos argumentos.

**C3. Pruebas de la fusión en iOS** (`shared/src/iosTest`), con **Room en memoria real** y `BundledSQLiteDriver`. Invocan
`RemoteMerger` real con documentos salidos de `normalizeFirestoreValue`:
- Un movimiento con `amountCents` `NSNumber(longLong = 12345)` se inserta con 12345 exacto.
- `amountCents` `NSNumber(double = 10.5)` y `NSNumber(double = 10.0)` **se rechazan**: suben `rejected`, no se escribe
  ninguna fila y el resto del lote sigue.
- Una venta remota ya existente con otro total **no se reescribe**.
- Una cancelación remota solo sella la bandera.
- Un cliente remoto más nuevo sobrescribe; con empate y contenido distinto gana el local.
- Reaplicar el mismo lote no duplica filas.

**C4. Motor de sync en iOS.** `iosKit/.../sync/IosSyncEngine.kt`, traducción de `SyncWorker.doWork`. **Push y luego pull,
en ese orden**, con los mismos tamaños de lote, `parkExhausted(10)` y el watermark `max(visto) - 5 min`. Reutiliza
`SyncDrainer`, `SyncCoordinator` y lo que ya esté en común: no dupliques su lógica.
- Base de datos: el mismo builder iOS de la Fase 3. Una sola instancia en el proceso.
- `suspend fun syncNow(): IosSyncOutcome`, siempre en `Dispatchers.Default`, nunca en el hilo principal porque el gateway
  bloquea. Dos llamadas simultáneas no pueden correr a la vez: la segunda devuelve `AlreadyRunning`.
- Sin usuario de Firebase, devuelve `SignedOut` sin tocar la red.
- `object IosSyncBridge`, llamable desde Swift, con `syncNow(completion: (Boolean) -> Unit)`.

**C5. Disparadores en Swift.**
- `Info.plist`: `BGTaskSchedulerPermittedIdentifiers = [com.cuentivo.app.sync]` y `UIBackgroundModes = [fetch, processing]`.
  Conserva todo lo demás.
- En `AppDelegate`: registra el `BGProcessingTask` **antes de que termine `didFinishLaunching`**, con `requiresNetworkConnectivity = true`.
  El handler llama a `IosSyncBridge`, llama a `setTaskCompleted` en éxito, en fallo y en `expirationHandler` (exactamente una
  vez), y reprograma.
- Dispara también al pasar a primer plano (`scenePhase == .active`), porque iOS no garantiza las tareas de fondo.

**C6. Puerta Android**, con el entorno de Norton:
`.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain`.
Cuenta del XML, 0 fallos y sin bajar el total de `app` + `:shared`. **`app/schemas` intacto**: `git diff --stat 4e96008..HEAD -- app/schemas` vacío.
No corras más de una vez: la PC tiene poca memoria libre.

## Reglas

- Nada de `Double`/`Float` con dinero. Dinero siempre `Long`.
- Una prueba no puede copiar la regla que verifica: invoca el código de producción.
- Sin dependencias nuevas. Si hacen falta, o si algo no se puede traducir igual, para y repórtalo.
- Antes de cada commit: `git status --short` sin plist, `google-services.json` ni `local.properties`.

## Reporte

`C:/tmp/oc-ios-f4c-reporte.md`: archivos, qué se movió y qué no (con motivo), tabla `doWork` → `IosSyncEngine`, cuenta del XML,
hashes y lo no verificado. Al terminar: `ENCARGO-IOS-F4C-TERMINADO`.
