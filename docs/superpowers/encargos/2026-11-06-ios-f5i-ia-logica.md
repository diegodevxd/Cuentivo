# Encargo iOS — F5-I: lógica de IA sin pantallas y sin dinero

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-I): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5i-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** mover a `commonMain` la lógica de IA que no ejecuta dinero ni dibuja pantallas, y dar a iOS sus tres transportes de IA, **sin cambiar comportamiento en Android**. No cambies textos, llaves del contrato, límites ni comentarios, salvo lo que esta hoja indica.

## Por qué así (ya decidido; no lo re-decidas)

- **`AiDebugLabScreen` es la pantalla real de CuentivoAI,** no de depuración: `CuentivoApp.kt:724` la abre en release con transporte real, y `debugAiOcrEngine` nulo solo apaga el OCR. **Sí va a iOS**, en la tanda F5-K, con `LedgerReviewScreen`. No la toques aquí ni le cambies el nombre.
- **Tandas de `ui/ai`:** F5-I (esta, sin dinero); F5-J (`AiBusinessQuery`, `AiPaymentPreview`, `AiPaymentExecution`, `AiSalePreview`, `AiSaleExecution`, `AiDebugLabModel`, `LedgerReviewSaver`); F5-K (las dos pantallas, con la captura y el recorte tras una interfaz inyectada). Nada de F5-J ni F5-K entra aquí.
- **Se quedan en `:app`, sin tocar:** `AiCaptureStore`, `AiCameraCaptureStore`, `LedgerImagePreparer`, `LedgerCropHelper`, `AiOcrEngine`, `VoiceRecognizer.kt`, `FirebaseCallableAiGateway`, `AiDebugFirebaseTransport`.
- **Las clases con `FirebaseFunctions` se quedan en su archivo actual de `:app`:** `FirebaseEmulatorsTest` y `MexalmLedgerWarmGatewayTest` leen esas rutas. Sale a común todo lo demás de esos archivos.
- **`commonMain` no nombra relojes ni `Instant`:** Android compila contra `kotlinx-datetime` 0.6.2 e iOS contra 0.7.1. La hora entra con `expect`.
- **Mismo paquete en cada archivo.** `internal` se quita **solo** de lo que `:app`, `:iosKit` o `androidTest` usan de verdad.

## Commit 1: políticas puras

Mueve a `shared/src/commonMain/kotlin/com/cuentivo/app/`, mismo paquete, sin cambios salvo lo indicado:

- `ai/`: `AiAvailabilityGateway`, `AiAvailabilityCoordinator`, `AiAvailabilityPolicy`, `AiBusinessScopePolicy`, `AiExperiment`, `AiProvider`, `FakeAiProvider`, `AiDraftSafety`, `AiProposalSafety`.
- `ai/capture/AiCapturePolicy.kt`.
- `ui/ai/`: `AiDebugTransport`, `AiDebugLabSubmitPolicy`, `AiDebugLabTurnPolicy`.

Cambios exactos:

- `AiBusinessScopePolicy`: `text.trim().lowercase(Locale.ROOT)` → `text.trim().lowercase()`, y quita el import. En el KDoc, `trim().lowercase(Locale.ROOT)` → `trim().lowercase()` (invariante de idioma, igual que antes).
- `AiAvailabilityCoordinator`: `{ System.currentTimeMillis() }` → `{ nowEpochMillis() }`, con `import com.cuentivo.app.platform.nowEpochMillis`.
- **`FirebaseAiAvailabilityGateway.kt`:** salen a `shared/.../ai/AiAvailabilityContract.kt` la función `parseAvailabilityResponse`, sin `internal`, y `const val AI_AVAILABILITY_CALLABLE_NAME = "getAiAvailability"`. La clase Firebase se queda, usando esa constante.
- **`MexalmLedgerWarmGateway.kt`:** salen a `shared/.../ai/MexalmLedgerWarm.kt` `MexalmLedgerWarmPolicy`, la interfaz `MexalmLedgerWarmGateway`, sus KDoc y `const val MEXALM_WARM_CALLABLE_NAME = "warmMexalmLedger"`. `FirebaseMexalmLedgerWarmGateway` se queda en el archivo de `:app`, **con `withContext(Dispatchers.IO)`, `Tasks.await(task)` y `catch (_: Exception)` literales,** porque `MexalmLedgerWarmGatewayTest` los lee.

**`PlatformDates`:** agrega a `shared/.../platform/PlatformDates.kt`, con KDoc:

```kotlin
/** Milisegundos de época del teléfono. */
expect fun nowEpochMillis(): Long

/** El día calendario local del teléfono en [epochMillis], en ISO. Nunca UTC. */
expect fun localDayIso(epochMillis: Long): String
```

- Android (`PlatformDates.android.kt`): `actual fun nowEpochMillis(): Long = System.currentTimeMillis()`; `actual fun localDayIso(epochMillis: Long): String = localDayIsoIn(epochMillis, ZoneId.systemDefault())`; e `internal fun localDayIsoIn(epochMillis: Long, zone: ZoneId): String = LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString()`.
- iOS (`PlatformDates.ios.kt`): `actual fun nowEpochMillis(): Long = iosNowEpochMillis()`, y:

```kotlin
actual fun localDayIso(epochMillis: Long): String = localDayIsoIn(epochMillis, TimeZone.currentSystemDefault())

@OptIn(ExperimentalTime::class)
internal fun localDayIsoIn(epochMillis: Long, zone: TimeZone): String =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date.toString()
```

con `import kotlin.time.Instant`, `kotlin.time.ExperimentalTime`, `kotlinx.datetime.TimeZone` y `kotlinx.datetime.toLocalDateTime`.

**Guardia que no debe quedar vacía:** `app/src/test/.../ui/ai/AiDebugLabGuardsTest.kt` recorre `src/main/java/com/cuentivo/app/ui/ai`. Al mover archivos revisaría menos sin avisar. Agrega `private val commonDir = File("../shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai")`, recorre `mainDir` **y** `commonDir` en las tres pruebas que hoy usan `sourcesOf(mainDir)`, y agrega una prueba `common ai lab sources are actually scanned` que afirme que `sourcesOf(commonDir)` no está vacía. Si otra prueba de este archivo leía una ruta movida, actualízala y dímelo.

## Commit 2: chat diario

- `ai/AiDailyChatModel.kt` y `data/local/AiDailyChatRepository.kt` pasan a común, mismo paquete.
- `AiDailyChatModel`: **borra `dayIsoOf`** (ahora es `localDayIso`). `cutoffDayIso` conserva firma y `require`, y su cuerpo queda:

```kotlin
return LocalDate.parse(todayIso).minus(retainDays - 1, DateTimeUnit.DAY).toString()
```

con `kotlinx.datetime.{LocalDate, DateTimeUnit, minus}`. Una fecha malformada sigue lanzando, ahora `IllegalArgumentException`.

- `AiDailyChatRepository`: sustituye `clock` y `zoneId` por estos dos, **que sí llevan valor por defecto** para no tocar `MainActivity.kt:1169` ni `LedgerLabHostActivity.kt:85`:

```kotlin
private val now: () -> Long = { nowEpochMillis() },
private val dayIsoOf: (Long) -> String = { localDayIso(it) },
```

  `currentDayIso()` es `dayIsoOf(now())` y cada `clock.millis()` es `now()`. Nada más cambia.

## Commit 3: contrato de la libreta

`app/.../ai/ledger/LedgerExtractionGateway.kt`: **todo sale a `shared/.../ai/ledger/LedgerExtractionContract.kt`, salvo `class LedgerExtractionGateway`, tal cual.**

- `CALLABLE_NAME` pasa a `const val LEDGER_CALLABLE_NAME = "extractLedgerPage"`, y lo usa la clase Firebase.
- Pierden `internal`: `buildLedgerCallablePayload`, `extractPageWithFallback` (los llamará `:iosKit`).
- **Línea 321:** `runCatching { java.time.LocalDate.of(date.year, date.month, date.day) }` → `runCatching { kotlinx.datetime.LocalDate(date.year, date.month, date.day) }`. **El `?: return null` de la línea siguiente se conserva:** es lo que rechaza un día inexistente. La variable `real` no se usa después y puede quedar igual; no la limpies.
- **Dinero:** el lector de centavos (`CANONICAL_LEDGER_CENTS_WIRE_RE`) y todo `parse*Wire` se mueven **sin una línea distinta.** Lo voy a comparar normalizado contra el original.

## Commit 4: transportes de iOS

Tres archivos nuevos en `iosKit/src/iosMain/kotlin/com/cuentivo/app/`. Nadie los llama aún: los conectará la raíz común. No toques `IosCallableAiGateway.kt`.

**`ai/IosAiTransports.kt`:**

```kotlin
package com.cuentivo.app.ai

import com.cuentivo.app.sync.normalizeFirestoreValue
import com.cuentivo.app.ui.ai.AiDebugTransport
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.ios
import kotlinx.cinterop.ExperimentalForeignApi

/** El transporte de la pantalla de IA en iOS. El nombre es el de la interfaz común. */
class IosAiTransport(
    private val gateway: IosCallableAiGateway = IosCallableAiGateway(),
) : AiDebugTransport {
    override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
        gateway.callContractV2(request)
}

/**
 * Disponibilidad remota de IA. Mismo diseño que Android: un fallo devuelve null para conservar el
 * estado conocido; quien decide de verdad es el servidor, que falla cerrado.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAiAvailabilityGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) : AiAvailabilityGateway {
    override suspend fun checkAvailability(): Boolean? = try {
        val result = functions.httpsCallable(AI_AVAILABILITY_CALLABLE_NAME, null).invoke()
        parseAvailabilityResponse(normalizeFirestoreValue(result.ios.data()))
    } catch (_: Throwable) {
        null
    }
}

/** Despertar del Ledger: es un favor, ningún error se propaga. */
class IosMexalmLedgerWarmGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) : MexalmLedgerWarmGateway {
    override suspend fun warm(): Boolean = try {
        functions.httpsCallable(MEXALM_WARM_CALLABLE_NAME, null).invoke()
        true
    } catch (_: Throwable) {
        false
    }
}
```

**`ai/ledger/IosLedgerExtractionGateway.kt`:**

```kotlin
package com.cuentivo.app.ai.ledger

import com.cuentivo.app.ai.AI_FUNCTIONS_REGION
import com.cuentivo.app.sync.normalizeFirestoreValue
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.ios
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Lectura de una página de libreta en iOS. Mismo payload, parser y reintento único que Android. Los
 * enteros no pasan por `Double`: la respuesta se lee del objeto nativo y se normaliza. Todo fallo
 * colapsa a Unavailable, sin registrar la imagen ni nada leído de ella.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLedgerExtractionGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) {
    suspend fun extractPage(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
    ): LedgerExtractionCallResult =
        extractPageWithFallback { version -> callRemote(requestId, imageBase64, imageMimeType, version) }

    private suspend fun callRemote(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
        version: Int,
    ): LedgerExtractionCallResult = try {
        val payload: Map<String, Any> =
            buildLedgerCallablePayload(requestId, imageBase64, imageMimeType) + ("contractVersion" to version)
        val result = functions.httpsCallable(LEDGER_CALLABLE_NAME, null).invoke(payload)
        parseLedgerExtractionResponse(normalizeFirestoreValue(result.ios.data()), requestId)
    } catch (_: Throwable) {
        LedgerExtractionCallResult.Unavailable
    }
}
```

No puedes compilar esto en Windows: **no lo adornes** y no accedas a `C:/tmp/kn-prebuilt`. Si crees que falta un símbolo, anótalo en el reporte.

## Pruebas: a `commonTest`, para que corran en iOS

Mueve, mismo paquete, y convierte `org.junit` a `kotlin.test`:

| De `app/src/test/.../` | A `shared/src/commonTest/kotlin/com/cuentivo/app/` |
|---|---|
| `ai/AiBusinessScopePolicyTest`, `AiExperimentTest`, `FakeAiProviderTest`, `AiDraftSafetyTest`, `AiProposalSafetyTest` | `ai/` igual |
| `ai/FirebaseAiAvailabilityGatewayTest` | `ai/AiAvailabilityTest.kt` |
| `ai/AiDailyChatModelTest` | `ai/` |
| `data/local/AiDailyChatRepositoryTest` | `data/local/` |
| `ai/ledger/LedgerExtractionGatewayTest` | `ai/ledger/LedgerExtractionContractTest.kt` |
| `ui/ai/AiDebugLabSubmitPolicyTest`, `AiDebugLabTurnPolicyTest` | `ui/ai/` |
| clase `AiCapturePolicyTest` de `ai/capture/AiCapturePolicyAndStoreTest.kt` | `ai/capture/AiCapturePolicyTest.kt` |

- `AiCaptureStoreTest` y `MexalmLedgerWarmGatewayTest` **se quedan** en `app`; solo ajusta sus imports si hace falta.
- **Orden de argumentos:** `org.junit.Assert.assertEquals(mensaje, esperado, real)` es `kotlin.test.assertEquals(esperado, real, mensaje)`. Lo mismo `assertTrue`: `assertTrue(condicion, mensaje)`. **Revisa cada una:** un mensaje en el lugar equivocado compila y no prueba lo mismo.
- `assertThrows(X::class.java) { }` → `assertFailsWith<X> { }`. `kotlin.test.assertEquals(Int, Long)` no iguala: escribe `12_500L` si hace falta.
- `runBlocking` → `runTest`, de `kotlinx.coroutines.test`.
- **`AiDailyChatModelTest`:** borra `dayIsoOf uses the given zone not UTC`, que pasa a las de plataforma de abajo. `cutoff rejects malformed iso dates` queda con `assertFailsWith<IllegalArgumentException>`.
- **`AiDailyChatRepositoryTest`:** quita `fixedZone` y `fixedClock`. `createRepository` queda con `now = { 1787594400000L }` (es `2026-08-24T18:00:00Z`) y `dayIsoOf = { "2026-08-24" }`, y si alguna prueba comparaba contra `fixedClock`, usa esa misma constante.
- **Nombres que Native rechaza:** renombra los que lleven `, . ; : / \ < > [ ]` entre backticks. Medido en `LedgerExtractionGatewayTest`, líneas 119, 303, 364, 482 y 490. Cuenta tú en todos los demás antes de decir que están limpios.
- Sin `java.*`, `android.*`, `String.format`, `Charsets`, `measureTimeMillis`, `@Test(expected=)` ni `org.junit` en lo movido.

**Nuevas de plataforma:**

- `shared/src/androidHostTest/.../platform/PlatformDatesAndroidHostTest.kt`, con `localDayIsoIn`: para `2026-08-23T02:00:00Z` (`1787450400000L`) da `2026-08-22` en `ZoneId.of("America/Mexico_City")` y `2026-08-23` en `ZoneOffset.UTC`.
- `shared/src/iosTest/.../platform/PlatformDatesIosTest.kt`, igual con `TimeZone.of("America/Mexico_City")` y `TimeZone.UTC`. Más `nowEpochMillis() > 1787450400000L`. No puedes correrlas en Windows: escríbelas con cuidado, las correré en CI.

## Control negativo obligatorio, en Windows

Rompe, corre, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. Restaura y confirma con `git diff`.

1. `AiBusinessScopePolicy.decide` sin `.lowercase()`.
2. `parseDetectedPageDateWire` sin la validación de la línea 321, para aceptar un día inexistente.
3. El lector de `amountCents` de `parseLedgerOperationsWire` (línea 220 del archivo original) aceptando también un `Double` truncado.
4. `AiDailyChatRepository.currentDayIso()` llamando a `dayIsoOf(0L)`.
5. `AiDebugLabGuardsTest`: pon `commonDir` en una ruta inexistente, y confirma que `common ai lab sources are actually scanned` falla.

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`. **Si una puerta da coincidencias nuevas, repórtalas: no la amplíes.**

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`, no de memoria.
- Conteos reales de los XML. La base es 805 + 777 = 1582: el total no debe bajar salvo por lo que expliques. Di cuántas pasaron de un lado al otro y cuántas son nuevas.
- Salida de las tareas, archivos tocados y los **cinco controles negativos**, con la prueba que falló.
- **Lista exacta de cada línea que no sea solo mover, cambiar `internal` o `import`.** La voy a auditar leyendo el diff.
- Qué perdió `internal`, y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que estos transportes funcionan: no puedes saberlo desde Windows.** Nada de esto se ha probado contra Firebase real.
