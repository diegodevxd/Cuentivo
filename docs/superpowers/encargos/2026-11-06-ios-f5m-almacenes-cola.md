# Encargo iOS — F5-M: almacenes, cola y errores de autenticación en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-M): ...`, **y cada commit debe compilar en Android**. Temporales en `build/ios-relevo/`; reporte en `build/ios-relevo/f5m-reporte.md`. **No pidas permisos fuera del repo.**

**Objetivo:** mover a `commonMain`, cada una en su paquete actual, `EntitlementStore`, `LocalSessionGuard`, `CreditLimitSuggestionStore`, `SyncQueue`, `OutboxBackfill` y `OwnerMembershipBootstrap`, y sacar `Firebase*Exception` de `MainActivity`, **sin cambiar comportamiento en Android**.

## Por qué así (ya decidido; no lo re-decidas)

- **Mueve, no reescribas.** Ni limpieza, ni renombres, ni comentarios nuevos.
- **Los datos guardados en teléfonos reales no se pierden:** los nombres de archivo (`cuentivo.entitlement`, `cuentivo.session`, `cuentivo.credit_limit_suggestions`) y las claves no cambian. Un `apply()` sigue siendo un `apply()`, y varias escrituras juntas siguen en una sola edición.
- **Los llamadores de Android no cambian:** una función con el nombre de la clase, en `:app` y en el mismo paquete, conserva `EntitlementStore(context)` y compañía.
- `MainActivity` sólo cambia en los sitios de autenticación del commit 4.

## Commit 1: `KeyValueStore`

`shared/src/commonMain/kotlin/com/cuentivo/app/platform/KeyValueStore.kt`:

```kotlin
package com.cuentivo.app.platform

interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    /** Las escrituras del bloque se aplican juntas. */
    fun edit(block: KeyValueEditor.() -> Unit)
}

interface KeyValueEditor {
    fun putString(key: String, value: String)
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}
```

`shared/src/androidMain/.../platform/SharedPreferencesKeyValueStore.kt`: `class SharedPreferencesKeyValueStore(context: Context, name: String)`, con `context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)`. `edit` abre **una** edición, corre el bloque y termina con `apply()`.

`shared/src/iosMain/.../platform/NsUserDefaultsKeyValueStore.kt`:

```kotlin
class NsUserDefaultsKeyValueStore(
    private val name: String,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    private fun scoped(key: String) = "$name.$key"

    override fun getString(key: String): String? = defaults.stringForKey(scoped(key))

    override fun getLong(key: String, default: Long): Long =
        (defaults.objectForKey(scoped(key)) as? NSNumber)?.longLongValue ?: default

    override fun edit(block: KeyValueEditor.() -> Unit) {
        object : KeyValueEditor {
            override fun putString(key: String, value: String) =
                defaults.setObject(value, forKey = scoped(key))
            override fun putLong(key: String, value: Long) =
                defaults.setObject(NSNumber(longLong = value), forKey = scoped(key))
            override fun remove(key: String) = defaults.removeObjectForKey(scoped(key))
        }.block()
    }
}
```

`shared/src/iosTest/.../platform/NsUserDefaultsKeyValueStoreTest.kt`, con una `NSUserDefaults(suiteName = ...)` propia que limpies al terminar: `Long.MAX_VALUE` vuelve igual; una clave ausente da el valor por defecto, también `-1`; `remove` borra; dos `name` no se pisan. Mira `NsUserDefaultsReminderStore` para la forma de los imports. **Esto no compila en Windows:** revisa cada `import` a mano.

`shared/src/commonTest/.../platform/InMemoryKeyValueStore.kt`, el falso de las pruebas de abajo.

## Commit 2: los tres almacenes

**`EntitlementStore`:** `git mv` de `app/src/main/java/com/cuentivo/app/billing/EntitlementStore.kt` a `shared/src/commonMain/kotlin/com/cuentivo/app/billing/`. Si `SyncEntitlement` o `SubscriptionTier` no estuvieran en común, muévelos también y anótalo.

- `class EntitlementStore(private val preferences: KeyValueStore)`.
- `remember`: una sola edición con las tres claves. `verifiedAtEpochMillis: Long = nowEpochMillis()`, con la función de `platform/`.
- `allowsCloudSync(userId: String?, nowEpochMillis: Long = nowEpochMillis(), isEmailVerified: Boolean)`: **sin `FirebaseAuth`**. Conserva el orden.
- `const val PREFERENCES` pasa al `companion` como `public`; las tres claves siguen privadas.

Nuevo `app/src/main/java/com/cuentivo/app/billing/EntitlementStoreAndroid.kt`, paquete `com.cuentivo.app.billing`:

```kotlin
fun EntitlementStore(context: Context): EntitlementStore =
    EntitlementStore(SharedPreferencesKeyValueStore(context, EntitlementStore.PREFERENCES))

/** Lo que antes eran los valores por defecto del método: la sesión actual de Firebase. */
fun EntitlementStore.allowsCloudSync(): Boolean {
    val user = FirebaseAuth.getInstance().currentUser
    return allowsCloudSync(
        userId = user?.uid,
        isEmailVerified = user?.isEmailVerified == true,
    )
}
```

Importa la extensión donde el paquete sea otro: `SyncScheduler.kt:23`, `SyncWorker.kt:23`, `MainActivity.kt:432`. Si una prueba de `androidTest` pasaba sólo algunos argumentos, pásale los que le faltan con el mismo valor que tomaba antes y anótalo.

**`CreditLimitSuggestionStore`:** a `shared/.../customer/`, con `class CreditLimitSuggestionStore(private val preferences: KeyValueStore)`. Función `CreditLimitSuggestionStore(context)` en `:app`, igual que arriba.

**`LocalSessionGuard`:** a `shared/.../auth/`:

```kotlin
class LocalSessionGuard(
    private val preferences: KeyValueStore,
    private val firstBusiness: suspend () -> BusinessEntity?,
    private val clearAllTables: suspend () -> Unit,
)
```

`adopt` conserva su orden: leer, decidir con `firstBusiness()?.let { it.ownerUserId != userId } ?: false`, borrar y guardar. Función en `:app`:

```kotlin
fun LocalSessionGuard(context: Context, database: CuentivoDatabase): LocalSessionGuard =
    LocalSessionGuard(
        preferences = SharedPreferencesKeyValueStore(context, LocalSessionGuard.PREFERENCES),
        firstBusiness = { database.businessDao().first() },
        clearAllTables = { withContext(Dispatchers.IO) { database.clearAllTables() } },
    )
```

**iOS:** nuevo `shared/src/iosMain/.../data/local/ClearAllRows.kt`. `clearAllTables` no se usa aquí porque no pude confirmar que Room 2.7.2 lo exponga en nativo:

```kotlin
suspend fun CuentivoDatabase.clearAllRows() {
    useWriterConnection { transactor ->
        val tables = transactor.usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata')",
        ) { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        transactor.immediateTransaction {
            tables.forEach { table -> execSQL("DELETE FROM `$table`") }
        }
    }
}
```

Más una función `iosLocalSessionGuard(database: CuentivoDatabase)`, con `NsUserDefaultsKeyValueStore(LocalSessionGuard.PREFERENCES)`, en el mismo archivo.

`shared/src/iosTest/.../data/local/ClearAllRowsTest.kt`, con `Room.inMemoryDatabaseBuilder<CuentivoDatabase>(...)` y `BundledSQLiteDriver`, como abra la base `CuentivoDatabaseFactory.kt`: inserta un negocio, llama `clearAllRows()`, `businessDao().first()` da `null` y **`room_master_table` sigue con su fila**.

**`commonTest` con `InMemoryKeyValueStore`:**
- `EntitlementStoreTest`: las claves son literalmente `entitlementUserId`, `entitlementTier` y `entitlementVerifiedAt`; `PREFERENCES == "cuentivo.entitlement"`; `userId` en blanco no escribe; `forget` borra las tres.
- `CreditLimitSuggestionStoreTest`: clave `dismissed_<id>`, otro monto no cuenta como descartado, `PREFERENCES` igual al de hoy.
- `LocalSessionGuardTest`: otro `lastUserId` borra y devuelve `true`; el mismo no borra; sin `lastUserId` y con negocio ajeno, borra; sin `lastUserId` y sin negocio, no borra; la clave es `lastUserId`; `forget` la quita.

## Commit 3: cola

**`PlatformLog`:** agrega `fun info(tag: String, message: String)` al `expect` y a sus dos `actual`, como `warn`.

**`shared/src/commonMain/.../data/local/DatabaseDispatcher.kt`:** `expect val databaseDispatcher: CoroutineDispatcher`. Android e iOS: `Dispatchers.IO`; en iOS, con `import kotlinx.coroutines.IO`.

**`shared/src/commonMain/.../sync/SyncPayloadJson.kt`:** los payloads de `SyncPayloads` son planos: `String`, `Long`, `Int`, `Boolean` y `null`.

```kotlin
internal object SyncPayloadJson {
    fun encode(fields: Map<String, Any?>): String = buildString {
        append('{')
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendJsonString(key)
            append(':')
            when (value) {
                null -> append("null")
                is String -> appendJsonString(value)
                is Long, is Int, is Boolean -> append(value.toString())
                // Double/Float incluidos: por aquí entraría dinero fraccionario. Nunca en silencio.
                else -> throw IllegalArgumentException("Tipo no admitido en payload: ${value::class.simpleName}")
            }
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
}
```

**`SyncQueue`:** a `shared/.../sync/`. Si `SyncPayloadJson` queda `internal`, `:app` no lo ve, y no hace falta: la cola ya está en común.

```kotlin
class SyncQueue(
    private val dao: SyncOperationDao,
    private val scheduleSync: () -> Unit,
    private val allowsCloudSync: () -> Boolean = { true },
)
```

`payload = SyncPayloadJson.encode(fields)`. `SyncScheduling.attempt { scheduleSync() }`. `Log.w` pasa a `PlatformLog.warn`. Función en `:app`, para que `CloudRestoreTest` y `MainActivity` no cambien:

```kotlin
fun SyncQueue(dao: SyncOperationDao, context: Context, allowsCloudSync: () -> Boolean = { true }): SyncQueue =
    SyncQueue(dao, scheduleSync = { SyncScheduler.enqueue(context) }, allowsCloudSync = allowsCloudSync)
```

Si esa sobrecarga resulta ambigua, nómbrala igual y pasa los argumentos con nombre desde el llamador; repórtalo.

**`OutboxBackfill`:** a `shared/.../sync/`. `Dispatchers.IO` pasa a `databaseDispatcher`; `Log.w` a `PlatformLog.warn`; `Log.i` a `PlatformLog.info`. **`OwnerMembershipBootstrap`:** a `shared/.../sync/`, tal cual.

**Pruebas:**
- `commonTest/.../sync/SyncPayloadJsonTest`: comillas, diagonal inversa, salto de línea, control `\u0001`, acentos y emoji, `null`, `Long.MAX_VALUE`, `Boolean`, y que `Double` y `Float` lancen `IllegalArgumentException` **con ese mensaje** (`assertFails` solo puede fallar por otro motivo).
- `app/src/test/.../sync/SyncPayloadJsonCompatibilityTest`, que compara contra lo que hacía producción: para el mapa de cada función de `SyncPayloads`, con entidades de ejemplo, y uno con caracteres difíciles, `JSONObject(SyncQueue-encoded).similar(JSONObject(fields))`. Como `SyncPayloadJson` es `internal`, captura el payload real con un `SyncOperationDao` falso, pasando por `SyncQueue.enqueue`.

## Commit 4: errores de autenticación

Común, junto a `AuthenticationGateway` y en su paquete:

```kotlin
enum class AuthFailureKind { USER_COLLISION, RECENT_LOGIN_REQUIRED, OTHER }

class AuthFailure(val kind: AuthFailureKind, val code: String?)
```

Agrega `fun classifyFailure(error: Throwable): AuthFailure` a la interfaz, sin valor por defecto.

**Android** (`app/.../auth/AuthenticationGateway.kt`), función **de primer nivel** para que una prueba la invoque sin iniciar Firebase, y el `override` llamándola:

```kotlin
internal fun classifyFirebaseAuthFailure(error: Throwable): AuthFailure = AuthFailure(
    kind = when (error) {
        is FirebaseAuthUserCollisionException -> AuthFailureKind.USER_COLLISION
        is FirebaseAuthRecentLoginRequiredException -> AuthFailureKind.RECENT_LOGIN_REQUIRED
        else -> AuthFailureKind.OTHER
    },
    code = (error as? FirebaseAuthException)?.errorCode,
)
```

**iOS** (`IosAuthenticationGateway.kt`): igual, con las clases de `dev.gitlive.firebase.auth`, que existen en 2.8.0, y `code = (error as? FirebaseAuthException)?.iosErrorCode()`. **Revisa la firma real de `iosErrorCode` antes de usarla.**

**`MainActivity`**, líneas medidas en `a46f55d`; vuelve a medirlas con `grep`:

| Línea | Hoy | Queda |
|---|---|---|
| 562 | `(error as? FirebaseAuthException)?.errorCode ?: error.message` | `authenticationGateway.classifyFailure(error).code ?: error.message` |
| 766, 783, 1597, 1629 | `error is FirebaseAuthUserCollisionException` | `authenticationGateway.classifyFailure(error).kind == AuthFailureKind.USER_COLLISION` |
| 2904 | `(error as? FirebaseAuthException)?.errorCode` | `authenticationGateway.classifyFailure(error).code` |
| 2916 | `error is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException` | `authenticationGateway.classifyFailure(error).kind == AuthFailureKind.RECENT_LOGIN_REQUIRED` |

Quita los dos imports (137 y 175) y conserva el comentario de la 2887. **Si `error` en alguno de esos sitios no es `Throwable`, repórtalo.** Agrega el método a los falsos de `AuthenticationGateway` que encuentre el compilador.

`app/src/test/.../auth/FirebaseAuthFailureClassificationTest`, invocando `classifyFirebaseAuthFailure` con `FirebaseAuthUserCollisionException("c1", "m")`, `FirebaseAuthRecentLoginRequiredException("c2", "m")`, otra `FirebaseAuthException` y una `IllegalStateException`: tipo y código de cada una; la última con `code == null`. Si un constructor no es público o la prueba exige Firebase iniciado, repórtalo y no la reemplaces con una copia de la regla.

## Antes de la puerta: lo que Windows no ve

Revisa todo el `iosMain` y el `iosTest` que tocaste contra el `import` real de cada símbolo y el `package` de cada clase. En `:iosKit` también. Nombres de pruebas entre backticks sin comas ni `. ; : / \ < > [ ] ( )` en `commonTest` e `iosTest`. `commonMain` no nombra relojes ni `Instant`.

## Puerta

Entorno de `CLAUDE.md` (Norton), con `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`. **Una Gradle a la vez; no reintentes en bucle.**

```text
:shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
assembleRelease lintDebug
```

## Controles negativos

Haz cada uno, corre lo mínimo, anota **el nombre de lo que falló y por qué**, y revierte:

1. Cambia la clave `entitlementTier` por otra: debe fallar `EntitlementStoreTest`.
2. En `LocalSessionGuard.adopt`, quita el borrado: debe fallar `LocalSessionGuardTest`.
3. En `SyncPayloadJson`, quita el escape de `"`: debe fallar `SyncPayloadJsonTest` **y** `SyncPayloadJsonCompatibilityTest`.
4. En `SyncPayloadJson`, deja pasar `Double` con `toString()`: debe fallar `SyncPayloadJsonTest`.
5. En `classifyFirebaseAuthFailure`, intercambia los dos tipos: debe fallar `FirebaseAuthFailureClassificationTest`.

## Reporte (`build/ios-relevo/f5m-reporte.md`)

- Rama y commits **sacados de `git`**, no de memoria.
- Comandos ejecutados; pruebas Android contadas de los XML (total y fallos).
- Los 5 controles, con prueba que falló y motivo.
- Lo no compilado aquí: lista cada archivo de `iosMain`, `iosTest` e `iosKit`.
- Lo que no pudiste hacer, y cualquier desvío de este encargo, con su razón.
- Sin push. Termina la última línea con `REPORTE LISTO`.
