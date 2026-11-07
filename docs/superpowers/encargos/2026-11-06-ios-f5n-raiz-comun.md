# Encargo iOS — F5-N: `setContent` a `CuentivoRoot` en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-N): ...`, **y cada commit debe compilar en Android**. Temporales en `build/ios-relevo/`; reporte en `build/ios-relevo/f5n-reporte.md`. **No pidas permisos fuera del repo.** No agregues a git `.agents/skills/gate/`, `.codex/` ni `AGENTS.md`: usa `git add` con rutas concretas.

**Objetivo:** que `MainActivity` quede en crear dependencias, `CuentivoRootPlatform` y `setContent { CuentivoRoot(...) }`, **sin cambiar comportamiento en Android**. iOS no usa todavía la raíz (eso es F5-O), pero CI ya la compila.

## Por qué así (ya decidido; no lo re-decidas)

- **Primero se limpia en su sitio, al final se corta.** Los commits 1 a 5 quitan Android del cuerpo de `setContent` sin sacarlo de `MainActivity`. El commit 6 solo mueve líneas. Así `git` puede demostrar que el corte no cambió nada.
- **El corte lo hace un script, nunca tú reescribiendo.** Son unas 2440 líneas: copiarlas a mano garantiza errores y gasta tu contexto. Con `sed` o Python, por rango medido.
- **Mueve, no reescribas.** Ni limpieza, ni renombres, ni partir los 57 estados, ni comentarios nuevos.
- **Los servicios de plataforma entran por parámetro**, sin valor por defecto, ni `CompositionLocal`, ni `expect` nuevo.
- **Una sola corrutina raíz:** usa `appScope` (en Android, `lifecycleScope`). **No uses `rememberCoroutineScope`:** una rama que sale de composición cancelaría una escritura financiera a medias.
- **Nada de firmas nuevas en el cuerpo:** dentro de `CuentivoRoot` los nombres locales siguen siendo los de hoy (`database`, `authenticationGateway`, `sharer`, `tierState`...). Salen de `val` al inicio de la función, como se indica abajo.

## Medidas (en `41e9593`; vuelve a medir con `grep`, las líneas se mueven con cada commit)

`MainActivity.kt`: 3022 líneas. Dependencias 415-530. `setContent` 531-2971. `CuentivoApp(` 1739. `balancePhrase` 2973, `reauthenticateWithGoogle` 2992, `deleteAccountAndAwait` 3006. `this@MainActivity` en 592, 601, 602, 805, 807, 844, 889, 930, 953, 963, 971, 1155, 1383, 1427, 1647, 1649, 2809, 2818, 2896, 2912, 2949, 2996. `lifecycleScope` en 1657, 1718, 1950, 1983, 2151, 2244, 2266, 2304, 2340, 2355, 2377, 2425, 2463, 2496, 2522, 2564, 2636, 2658, 2701, 2733, 2737, 2801, 2823, y las que `grep` encuentre. `withTransaction` en 1074, 1687, 2060, 2193, 2289, 2363. `Dispatchers.IO` en 1033, 2639, 2662, 2856, 2878. `BackHandler` 1401. `Build.VERSION` 2938. `BuildConfig.DEBUG` 892, 2932.

`grep -nE '%([^0-9]|[0-9]+[^$0-9])' values/strings.xml` sale vacío, así que `getString` de Compose Resources formatea igual que el de Android con estos textos. `Locale.setDefault` ya ocurre en `attachBaseContext` (líneas 258 y 272).

## Commit 1: piezas chicas a común

Cada una en su **paquete actual**:

1. `observability/CrashReporter.kt`: `CrashEvent`, `CrashReporter` y `NoOpCrashReporter` a `commonMain`. El reporte real y `CrashReporterFactory` se quedan en `:app`.
2. `ui/PaymentRefundFactory.kt` a `commonMain/.../ui/`. Desaparece de `com/example/organizationlabs/ui/`; ajusta la frase de `CLAUDE.md` en "Desajuste directorio/paquete".
3. `BackupFailure` y `BackupException` de `BackupCrypto.kt` a `commonMain/.../backup/BackupFailure.kt`. `BackupCrypto` y `DatabaseBackup` se quedan en `:app`.
4. `PendingAccountDeletionStore`, igual que los almacenes de F5-M: clase común sobre `KeyValueStore`; en `:app`, función `PendingAccountDeletionStore(context)` del mismo paquete. **No cambian** el archivo `cuentivo.account_deletion` ni la clave `pendingDeletionUserId`. Prueba en `commonTest` con `InMemoryKeyValueStore`: `mark`, `isPendingFor` con el mismo uid, con otro y con `null`, `clear`.
5. `PlatformLog`: de `internal` a público, en el `expect` y en sus `actual`. Nada más.
6. `platform/PlatformDates.kt`, función pura y su prueba en `commonTest`:

```kotlin
/** El mismo día `months` meses antes; un día que no existe se ajusta al último del mes. */
fun epochDayMonthsBefore(epochDay: Long, months: Int): Long =
    LocalDate.fromEpochDays(epochDay.toInt()).minus(months, DateTimeUnit.MONTH).toEpochDays().toLong()
```

Es `toEpochDays().toLong()` a propósito: en `kotlinx-datetime` 0.6.2 devuelve `Int` y en 0.7.1 `Long`. Prueba: `2026-08-31` menos 6 meses da `2026-02-28`, y `2026-03-15` menos 6 da `2025-09-15`.

## Commit 2: `CuentivoRootState`

`commonMain/.../ui/CuentivoRootState.kt`:

```kotlin
package com.cuentivo.app.ui

/** Lo que la plataforma le avisa a la raíz desde afuera de la composición. */
class CuentivoRootState {
    val tier = mutableStateOf(SubscriptionTier.FREE)
    val openOverdueCustomers = mutableStateOf(false)
    val accountMessage = mutableStateOf<String?>(null)
    val proPrice = mutableStateOf<String?>(null)
    val aiPrice = mutableStateOf<String?>(null)
    val proTrialMonths = mutableStateOf(0)
    val aiTrialMonths = mutableStateOf(0)
    val entitlementResolved = mutableStateOf(false)
    val billingMessage = mutableStateOf<String?>(null)
    val requireAccount = mutableStateOf(false)
}
```

En `MainActivity`, `private val rootState = CuentivoRootState()` en lugar de las diez propiedades, **conservando sus comentarios** junto a su campo de `CuentivoRootState`. Para no tocar el cuerpo, al inicio de `onCreate`: `val tierState = rootState.tier`, y así cada una. `handleOverdueCustomersAction` usa `rootState.openOverdueCustomers`.

## Commit 3: costuras y `CuentivoRootPlatform`

`commonMain/.../ui/CuentivoRootPlatform.kt`:

```kotlin
package com.cuentivo.app.ui

interface ReminderControl {
    fun sync()
    fun cancel()
}

interface PurchaseControl {
    fun refresh(userId: String?)
    fun launchPurchase(userId: String?, targetTier: SubscriptionTier, isAnonymous: Boolean)
}

interface BackupControl {
    fun suggestedFileName(todayIso: String): String
    /** Bloqueantes: la raíz las llama dentro de `withContext(databaseDispatcher)`. */
    fun export(password: CharArray): ByteArray
    fun restore(file: ByteArray, password: CharArray)
    /** La base ya es otra: Android reinicia el proceso; iOS la reabrirá (F5-P). */
    fun onRestored()
}

/** Las dos operaciones remotas del borrado de cuenta. Bloqueantes, como hoy. */
interface BusinessErasure {
    fun requestBusinessDeletion(businessId: String, nowEpochMillis: Long): Result<Unit>
    fun deleteBusiness(businessId: String): Result<Unit>
}

class CuentivoRootPlatform(
    val appScope: CoroutineScope,
    val isDebugBuild: Boolean,
    val authHost: PlatformAuthHost,
    val sharer: Sharer,
    val reminders: ReminderControl,
    /** Devuelve la acción de pedir permiso. Se llama siempre en el mismo punto de la composición. */
    val rememberReminderPermissionRequest: @Composable (onResult: (Boolean) -> Unit) -> (() -> Unit),
    val purchases: PurchaseControl,
    val backup: BackupControl,
    val deleteProductPhoto: (String) -> Unit,
    val clearLocalData: suspend () -> Unit,
    val rememberAppPlatform: @Composable () -> CuentivoAppPlatform,
)
```

Revisa la firma real de `Result` en `requestBusinessDeletion` y `deleteBusiness`, y si `Sharer` y `PlatformAuthHost` se importan de donde crees. Las implementaciones de Android, **todas en un archivo nuevo de `:app`** (`app/src/main/java/com/cuentivo/app/ui/AndroidCuentivoRootPlatform.kt`), son clases pequeñas que llaman exactamente lo que el cuerpo llama hoy:

- `ReminderControl` → `CollectionReminderScheduler.sync(context)` / `.cancel(context)`.
- `PurchaseControl` → `billing.refresh(userId)` y `billing.launchPurchase(activity = activity, userId = userId, targetTier = targetTier, isAnonymous = isAnonymous)`. **La Activity real, no `applicationContext`**: conserva el comentario de la línea 2805.
- `BackupControl` → `DatabaseBackup.*(applicationContext, database, ...)`; `onRestored` llama a `restartAfterRestore()`, que sigue en `MainActivity`.
- `BusinessErasure` → `FirestoreSyncGateway.requestBusinessDeletion` y `FirestoreDeletionGateway.deleteBusiness`.
- `clearLocalData = { database.clearAllTables() }`. La raíz la llama dentro de `withContext(databaseDispatcher)`, como hoy con `Dispatchers.IO` (línea 2878, y conserva su comentario).
- `rememberReminderPermissionRequest`, exacto a hoy (líneas 604 y 2938):

```kotlin
@Composable
fun rememberAndroidReminderPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        // Sólo Android 13+ tiene permiso de notificaciones; debajo, el aviso simplemente funciona.
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else onResult(true)
    }
}
```

Crea `rootPlatform` en `onCreate` y úsalo en el cuerpo: en este commit todavía se llega por `val` locales antes de `setContent`.

## Commit 4: Android fuera del cuerpo

Sustituciones **solo entre `setContent {` y su cierre**, y en las tres funciones de abajo:

| Hoy | Queda |
|---|---|
| `CollectionReminderScheduler.sync(this@MainActivity)` / `.cancel(...)` | `reminders.sync()` / `reminders.cancel()` |
| `authenticationGateway.signInWithGoogle(this@MainActivity, ...)`, `linkGoogle`, `reauthenticateWithGoogle` | `platform.authHost` en lugar de la Activity |
| `lifecycleScope.launch` | `appScope.launch` |
| `database.withTransaction {` | `database.inTransaction {`. **Mira `data/local/Transactions.kt` y confirma que el `actual` de Android llama `withTransaction`** |
| `withContext(Dispatchers.IO)` | `withContext(databaseDispatcher)` (**confirma que en Android es `Dispatchers.IO`**) |
| `System.currentTimeMillis()` | `nowEpochMillis()` |
| `UUID.randomUUID().toString()` | `newUuid()` |
| `LocalDate.now().toEpochDay()`, `java.time.LocalDate.now().toEpochDay()` | `todayEpochDay()` |
| `LocalDate.now().toString()` | `localDayIso(nowEpochMillis())` |
| `LocalDate.now().plusDays(it).toEpochDay()` | `todayEpochDay() + it` (**revisa el tipo de `it`**) |
| `LocalDate.now().minusMonths(6).toEpochDay()` | `epochDayMonthsBefore(todayEpochDay(), 6)` |
| `Instant.ofEpochMilli(x).atZone(ZoneId.systemDefault()).toLocalDate().toString()` | `localDayIso(x)` (**confirma en `actual` que es el mismo día local y el mismo formato**) |
| `Log.w(TAG, "...")`, `Log.e(TAG, "...")`, `Log.w(TAG, "...", error)` | `PlatformLog.warn(TAG, "...")`. **Se pierde la excepción adjunta:** anótalo en el reporte. No interpoles nunca el `error` |
| `error.javaClass.simpleName` | `error::class.simpleName` |
| `BuildConfig.DEBUG` | `platform.isDebugBuild` |
| `BackHandler {` | `PlatformBackHandler {` |
| `billing.refresh(...)`, `billing.launchPurchase(...)` | `platform.purchases.*` |
| `DatabaseBackup.suggestedFileName`, `.export`, `.restore`, `restartAfterRestore()` | `platform.backup.*`; `onRestored()` |
| `firestoreGateway.requestBusinessDeletion`, `deletionGateway.deleteBusiness` | `businessErasure.*` |
| `database.clearAllTables()` | `platform.clearLocalData()` |
| `productImages::delete`; `val productImages` | `platform.deleteProductPhoto(it)`; se elimina |
| `CollectionReminderPreferences(this@MainActivity)`, `SharedPreferencesFirstDebtFlagStore(this@MainActivity)` | dependencia ya creada, ver el commit 6 |
| el lanzador de permiso de la línea 604 y `Build.VERSION` de la 2938 | `val requestReminderPermission = platform.rememberReminderPermissionRequest { granted -> ... }` **en el mismo punto (línea 604)**, con el mismo cuerpo; en 2938, `requestReminderPermission()` |
| `rememberAndroidCuentivoAppPlatform()` | `platform.rememberAppPlatform()` |

`reauthenticateWithGoogle` y `deleteAccountAndAwait` reciben lo que antes tomaban de la Activity.

**Lo que se queda en `MainActivity`:** `onCreate` antes de `setContent`, incluido todo `BillingGateway`, `attachBaseContext`, `onResume`, `onNewIntent`, `handleOverdueCustomersAction`, `restartAfterRestore`, `onDestroy`, `onActivityResult`.

## Commit 5: textos a `Res`

Solo dentro del cuerpo y de `balancePhrase`, que pasa a `suspend`:

- Mismo estilo de imports que `shared/.../ui/CuentivoApp.kt`. `R.string.x` y `com.cuentivo.app.R.string.x` → `Res.string.x`, con `stringResource` de Compose Resources. Las claves no cambian.
- `getString(...)` → `getString` de `org.jetbrains.compose.resources`, que es `suspend`.
- **Dentro de un `appScope.launch`, cámbialo tal cual.**
- **En un lambda sin corrutina, envuelve en `appScope.launch { ... }` la frase completa,** es decir, el texto y lo que lo usa (`uiMessage = ...`; `sharer.shareText(getString(...), ...)`; `val notice = getString(...)` con lo que le sigue hasta donde se usa `notice`). **No conviertas el lambda en `suspend`** ni cambies las firmas de `CuentivoApp`.
- **Si el lambda hace algo después que dependa del orden** (por ejemplo, limpiar un estado o cerrar una hoja justo tras el mensaje), mete eso en el mismo `launch`, en el mismo orden. Lista cada uno de estos sitios en el reporte, con su línea.
- No traduzcas nada que se persista. `AuditAction.summary`, y el contenido de `DebtorShareText`, siguen como están.

Al terminar, `grep -nE 'R\.string|this@MainActivity|lifecycleScope|java\.time|LocalDate\.now|UUID\.|System\.current|Dispatchers\.IO|Log\.|BuildConfig|Build\.|applicationContext|BackHandler|Manifest|withTransaction|clearAllTables'` **sobre el cuerpo** debe dar solo comentarios. Pega la salida en el reporte.

## Commit 6: el corte

1. `commonMain/.../ui/CuentivoRoot.kt`:

```kotlin
package com.cuentivo.app.ui

private const val TAG = "CuentivoRoot"

class CuentivoRootDependencies(
    val database: CuentivoDatabase,
    val authenticationGateway: AuthenticationGateway,
    val syncQueue: SyncQueue,
    val membershipBootstrap: OwnerMembershipBootstrap,
    val remoteBusinessLookup: SyncRemoteBusinessLookup,
    val businessErasure: BusinessErasure,
    val cloudRestore: CloudRestore,
    val dashboardRepository: DashboardRepository,
    val sessionGuard: LocalSessionGuard,
    val entitlementStore: EntitlementStore,
    val creditLimitSuggestionStore: CreditLimitSuggestionStore,
    val pendingAccountDeletionStore: PendingAccountDeletionStore,
    val aiAvailabilityGateway: AiAvailabilityGateway,
    val mexalmLedgerWarmGateway: MexalmLedgerWarmGateway,
    val crashReporter: CrashReporter,
    val reminderPreferences: CollectionReminderPreferences,
    val firstDebtFlagStore: FirstDebtFlagStore,
)

@Composable
fun CuentivoRoot(
    dependencies: CuentivoRootDependencies,
    rootState: CuentivoRootState,
    platform: CuentivoRootPlatform,
) {
    val database = dependencies.database
    val authenticationGateway = dependencies.authenticationGateway
    // ... un `val` por dependencia, con el nombre que ya usa el cuerpo.
    val firestoreGateway = dependencies.remoteBusinessLookup
    val businessErasure = dependencies.businessErasure
    val appScope = platform.appScope
    val sharer = platform.sharer
    val reminders = platform.reminders
    val tierState = rootState.tier
    // ... uno por cada estado.
    CuentivoTheme {
        // Aquí va el cuerpo de `setContent`, idéntico.
    }
}
```

Ajusta los tipos a los nombres reales (`grep`). Si el compilador pide una dependencia que no está en la lista, agrégala con su tipo común. **Si un tipo no está en común, repórtalo y resuélvelo moviéndolo si es puro; si toca Android, costura en `CuentivoRootPlatform`.** Si `firstDebtFlagStore` o `reminderPreferences` se creaban dentro de un `remember`, sigue creándolos en el mismo punto a partir de la dependencia.

2. **El cuerpo se mueve con un script.** Con Python o `sed`: extrae las líneas de `CuentivoTheme {` a su cierre, y las tres funciones (`balancePhrase`, `reauthenticateWithGoogle`, `deleteAccountAndAwait`) como funciones privadas de primer nivel, sin cambiar sangría. **Las únicas ediciones permitidas en esas líneas son los `import` y lo que el compilador exija**, y cada una va en el reporte.
3. `MainActivity`: `setContent { CuentivoRoot(dependencies, rootState, rootPlatform) }`. Borra los `import` que sobren.
4. **`AiDebugLabGuardsTest`** lee `MainActivity.kt` por ruta en 6 sitios (líneas 484 a ~529) y busca textos que ahora viven en `CuentivoRoot.kt`. **Apunta cada uno a la ruta nueva**, sin debilitar ni quitar ninguna aserción. La ruta es relativa a `app/`: `../shared/src/commonMain/kotlin/com/cuentivo/app/ui/CuentivoRoot.kt`, como ya resolvió F5-L para `CuentivoApp.kt`. Si una aserción busca algo que se queda en `MainActivity`, se queda apuntando ahí.
5. **Busca `MainActivity` en el resto de pruebas y en las herramientas de `.claude/`** (`grep -rln`): `LedgerRestoreConfirmSingleWriteTest`, `LedgerConfirmRefreshDecisionTest`, `StatementCustomerRefreshTest`, `CustomerCreationFlowTest` y las demás. Si alguna depende de un texto movido, corrígela igual. Las puertas de i18n de `CLAUDE.md` ya cubren `shared/.../ui`; compruébalo corriéndolas.

**Si Kotlin responde `Method too large` en `CuentivoRoot`:** no reorganices. Mueve al cuerpo de una función privada `CuentivoRootContent(...)` la mayor rama interna y **repórtalo**.

## Antes de la puerta: lo que Windows no ve

`commonMain` no nombra relojes ni `Instant` (Android 0.6.2, iOS 0.7.1): usa `nowEpochMillis()`, `localDayIso()`, `todayEpochDay()`, `newUuid()`. Busca en `CuentivoRoot.kt`, `CuentivoRootPlatform.kt` y los archivos movidos: `java.`, `javaClass`, `String.format`, `.format(`, `synchronized`, `@JvmStatic`, `Thread`, `::class.java`, `kotlin.time.Clock`. Revisa que el `actual` de `PlatformLog` en iOS siga válido. Nombres de pruebas entre backticks sin comas ni `. ; : / \ < > [ ] ( )`. `assertEquals(Int, Long)` no iguala.

## Puerta

Entorno de `CLAUDE.md` (Norton), con `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`. **Una Gradle a la vez; no reintentes en bucle.** En cada commit, lo mínimo que compile: `:shared:compileCommonMainKotlinMetadata :app:compileDebugKotlin`. Al final:

```text
:shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
assembleRelease lintDebug
```

Corre también las dos puertas de i18n y la paridad de claves de `CLAUDE.md`, y `grep -c 'remember' ` y `wc -l` sobre `MainActivity.kt` y `CuentivoRoot.kt`.

## Controles negativos

Haz cada uno, corre lo mínimo, anota **el nombre de lo que falló y por qué**, y revierte:

1. Cambia la clave `pendingDeletionUserId`: debe fallar la prueba del almacén, **solo si la prueba fija la clave**; si no la fija, agrégale la aserción antes de este control.
2. En `epochDayMonthsBefore`, resta `months * 30` días: debe fallar su prueba.
3. En `CuentivoRoot.kt`, renombra `database.aiDailyMessageDao()`: debe fallar `AiDebugLabGuardsTest`. Esto demuestra que la guardia mira el archivo nuevo.
4. En `CuentivoRoot.kt`, renombra `AiPaymentExecutionService(`: debe fallar `AiDebugLabGuardsTest`.
5. Mete `java.util.UUID.randomUUID()` en `CuentivoRoot.kt`: debe fallar `:shared:compileCommonMainKotlinMetadata`. Anota el error.

## Reporte (`build/ios-relevo/f5n-reporte.md`)

- Rama y commits **sacados de `git`**, no de memoria.
- Comandos ejecutados; pruebas Android contadas de los XML (total y fallos).
- **Prueba del corte:** las líneas borradas de `MainActivity` y las agregadas a `CuentivoRoot.kt` en el commit 6, ordenadas y comparadas con un script, sin contar `import`. Pega las diferencias: deben ser las ediciones que listaste.
- Los sitios del commit 5 donde el orden importaba. Los `Log` que perdieron excepción.
- Los 5 controles, con prueba que falló y motivo.
- `wc -l` final de `MainActivity.kt` y `CuentivoRoot.kt`.
- Lo no compilado aquí: lista cada archivo de `iosMain`, `iosTest` e `iosKit` tocado.
- Lo que no pudiste hacer, y cualquier desvío de este encargo, con su razón.
- Sin push. Termina la última línea con `REPORTE LISTO`.
