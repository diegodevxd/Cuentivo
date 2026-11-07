# Encargo iOS — F5-J: previsualización y ejecución de IA en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-J): ...`, **y cada commit debe compilar en Android**. Reporte y temporales en `build/ios-relevo/`; reporte en `build/ios-relevo/oc-ios-f5j-reporte.md`. **No pidas acceso a nada fuera del repo.**

**Objetivo:** mover a `commonMain` la lógica que previsualiza y ejecuta ventas y abonos propuestos por IA, **sin cambiar comportamiento en Android**. Esto llega al libro mayor: **una línea de lógica distinta es un defecto, aunque compile.** Solo cambian las líneas que esta hoja lista. Lo compararé normalizado contra el original.

## Por qué así (ya decidido; no lo re-decidas)

- **Mueve, no reescribas.** Ni limpieza, ni renombres, ni comentarios nuevos.
- **Las pantallas no entran:** `AiDebugLabScreen`, `LedgerReviewScreen`, `LedgerCropHelper`, `AiCaptureStore` y `AiCameraCaptureStore` se quedan en `:app`. Van en F5-K.
- **`Bundle` no existe en común.** El estado guardado y `rebuildReview` van a común; la conversión a `Bundle` va a `shared/src/androidMain`, para que F5-K la use desde su `expect`.
- **Mismo paquete en cada archivo.** `internal` se quita **solo** de lo que `:app` o `androidTest` usan de verdad: `generation` y `onRestoreLedgerReview` de `AiDebugLabModel` y `LedgerReviewSaver` son candidatos. Lista cada uno.

## Commit 1: envoltorio de mapa

`shared/.../ai/UnmodifiableCollections.kt`, agrega:

```kotlin
internal fun <K, V> unmodifiableMap(values: Map<K, V>): Map<K, V> = UnmodifiableMap(values)

private class UnmodifiableMap<K, V>(private val delegate: Map<K, V>) : AbstractMutableMap<K, V>() {
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> =
        UnmodifiableSet(delegate.entries.mapTo(LinkedHashSet()) { ReadOnlyEntry(it.key, it.value) })

    override val size: Int get() = delegate.size

    override fun containsKey(key: K): Boolean = delegate.containsKey(key)

    override fun get(key: K): V? = delegate[key]

    override fun put(key: K, value: V): V? = throw UnsupportedOperationException("This map is read-only")

    override fun remove(key: K): V? = throw UnsupportedOperationException("This map is read-only")

    override fun clear(): Unit = throw UnsupportedOperationException("This map is read-only")
}

private class ReadOnlyEntry<K, V>(
    override val key: K,
    override val value: V,
) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V = throw UnsupportedOperationException("This map is read-only")

    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && other.key == key && other.value == value

    override fun hashCode(): Int = key.hashCode() xor value.hashCode()
}
```

Si `unmodifiableSet` necesita que el tipo cuadre, usa `UnmodifiableSet` directo, como arriba; **no cambies su comportamiento**. `key.hashCode()` sobre genérico admite null en Kotlin; si no compila, escribe `(key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)`.

**Prueba nueva**, `shared/src/commonTest/.../ai/UnmodifiableCollectionsTest.kt`, con nombres sin símbolos: `map_reads_through`, `map_refuses_put_by_cast`, `map_refuses_remove_by_cast`, `map_entry_refuses_set_value`, `map_equals_a_plain_map`. "Por cast" es `(map as MutableMap<String, Long>).put(...)` con `assertFailsWith<UnsupportedOperationException>`.

## Commit 2: consultas y previsualización

Mueve a `shared/src/commonMain/kotlin/com/cuentivo/app/ai/`: `AiBusinessQuery.kt`, `AiPaymentPreview.kt`, `AiSalePreview.kt`.

**Únicos cambios:**

| Antes | Después |
|---|---|
| `Collections.unmodifiableList(x)` | `unmodifiableList(x)` |
| `Collections.unmodifiableMap(x)` | `unmodifiableMap(x)` |
| `UUID.randomUUID().toString()` (`AiPaymentPreview.kt:138`, `AiSalePreview.kt:354-355`) | `newUuid()`, con `import com.cuentivo.app.platform.newUuid` |
| `LocalDate.of(year, month, day).toEpochDay()` (`AiBusinessQuery.kt:268`) | `LocalDate(year, month, day).toEpochDays().toLong()`, con `import kotlinx.datetime.LocalDate` |

El `.toList()`, `.toMap()` y el `try/catch (_: Exception)` que rodean a esas líneas **se conservan**: el catch es lo que convierte un día inexistente en `null`.

## Commit 3: ejecución

Mueve a `shared/.../ai/`, **sin una sola línea distinta**: `AiPaymentExecution.kt`, `AiSaleExecution.kt`. Si algo no compila, no lo arregles cambiando lógica: dímelo en el reporte.

## Commit 4: modelo y estado guardado

**`AiDebugLabModel.kt`** a `shared/.../ui/ai/`:

| Línea | Cambio |
|---|---|
| 186 | `nowEpochMillis: Long = System.currentTimeMillis()` → `nowEpochMillis: Long = com.cuentivo.app.platform.nowEpochMillis()` |
| 295, 326, 347, 1127, 1149-1153, 1186, 1206-1211, 1270, 1289 y demás | `Collections.` y `java.util.Collections.` → `unmodifiableList` y `unmodifiableMap`. **Conserva el `ArrayList(...)` y el `LinkedHashMap(...)` de adentro:** esa copia es lo que congela el saldo. |
| 897 | `LocalDate(promise.year, promise.month, promise.day).toEpochDays().toLong()`, con su `try/catch` intacto |
| 1111, 1232, 1293 | `java.time.LocalDate.now().toEpochDay()` → `com.cuentivo.app.platform.todayEpochDay()`, **con el nombre completo:** el parámetro se llama igual y no debe resolverse a sí mismo |

**`LedgerReviewSaver.kt`** se parte en dos:

- `shared/.../ui/ai/LedgerReviewSaver.kt`: `object LedgerReviewSaver` (sin `internal`) con `SavedLedgerReview` y `rebuildReview`, y sus KDoc. El nombre calificado `LedgerReviewSaver.SavedLedgerReview` no cambia, para no tocar a quienes lo usan.
- `shared/src/androidMain/kotlin/com/cuentivo/app/ui/ai/LedgerReviewBundle.kt`: `object LedgerReviewBundle` con `toBundle`, `fromBundle`, todas las `KEY_*` y `OP_*`, y las funciones privadas de `Bundle`. **Sus cuerpos no cambian: las llaves son el formato de un borrador ya guardado en un teléfono.**
- `AiDebugLabScreen.kt` (líneas 163-164) y `app/src/androidTest/.../ui/LedgerReviewSaverBundleTest.kt`: `LedgerReviewSaver.toBundle` y `fromBundle` → `LedgerReviewBundle`. **Y nada más en la pantalla.**

## Guardias que leen fuentes

`app/src/test/.../ui/ai/AiDebugLabGuardsTest.kt` lee archivos por ruta. Cámbialas sin debilitar lo que afirman:

- Líneas 741 y 935: `src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt` → `../shared/src/commonMain/kotlin/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`.
- Línea 1000: `src/main/java/com/cuentivo/app/ai/AiBusinessQuery.kt` → `../shared/src/commonMain/kotlin/com/cuentivo/app/ai/AiBusinessQuery.kt`.
- Si una afirmación buscaba un texto que esta hoja cambia, ajústalo al texto nuevo y dímelo con la línea. **Nunca la borres.**

## Pruebas: a `commonTest`, para que corran en iOS

Mueve, mismo paquete, y convierte `org.junit` a `kotlin.test`:

- `ai/`: `AiBusinessQueryTest`, `AiPaymentExecutionTest`, `AiPaymentPreviewTest`, `AiSaleExecutionTest`, `AiSalePreviewTest`.
- `ui/ai/`: `AiDebugLabMachineTest`, `LedgerDueDatePlazoStateTest`, `LedgerLegacyRestoreGateTest`, `LedgerPageCompletenessGateTest`, `LedgerReviewSaverTest`.

Se quedan: `AiDebugLabGuardsTest`, `ai/ledger/LedgerReviewTest` (lee un archivo) y todo `androidTest`.

- **Orden de argumentos:** `assertEquals(mensaje, esperado, real)` → `assertEquals(esperado, real, mensaje)`; `assertTrue(mensaje, c)` → `assertTrue(c, mensaje)`. **Revisa cada una.**
- `assertThrows(X::class.java) { }` → `assertFailsWith<X> { }`. Con funciones `suspend` dentro, usa `runCatching`, como en F5-I, y afirma el tipo exacto.
- `runBlocking` → `runTest`. Si la prueba usa el valor que devuelve el bloque, guárdalo en un `var` de afuera.
- `java.time.LocalDate.of(a, m, d).toEpochDay()` → `LocalDate(a, m, d).toEpochDays().toLong()`, de `kotlinx.datetime`. Cualquier `.plusDays` o similar, con la misma aritmética en días como `Long`. Las de esas cuatro pruebas de `AiDebugLabMachineTest` (líneas 1126, 2139, 2158, 2165 y 2168) y los `import java.time.LocalDate` de las tres `Ledger*`.
- **`kotlin.test.assertEquals(Int, Long)` no iguala:** escribe `12_500L` donde haga falta.
- **Nombres que Native rechaza,** medidos: 2 en `AiBusinessQueryTest` y 3 en `AiDebugLabMachineTest`. Renómbralos quitando el símbolo.
- Sin `java.*`, `android.*`, `String.format`, `Charsets`, `measureTimeMillis`, `@Test(expected=)` ni `org.junit` en lo movido.

## Control negativo obligatorio, en Windows

Rompe, corre, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. **Si nada falla, agrega la prueba que falta** y dímelo: F5-I destapó así un hueco de mi encargo. Restaura y confirma con `git diff`.

1. `UnmodifiableMap.put` que escriba en vez de lanzar.
2. `AiSaleExecution`: quita el relanzado de `CancellationException`.
3. `AiBusinessQuery.parseEpochDay` sin el `try/catch`, con un día inexistente.
4. `AiDebugLabModel`, línea 1127: `frozenBalances` sin la copia, es decir `unmodifiableMap(existingCustomerBalances)`, y muta el mapa original después. **Debe fallar una prueba que demuestre que el saldo quedó congelado.**
5. `AiDebugLabModel`, línea 897, sin el `try/catch`: una promesa con día inexistente debe seguir dando `Ignored`.
6. `AiPaymentExecution`: cambia el monto confirmado por el de la previsualización sumándole 1 centavo. Debe fallar una prueba de monto.

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n de `CLAUDE.md`. **Si una puerta da coincidencias nuevas, repórtalas: no la amplíes.**

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`.
- **Pruebas leídas de los XML** (`app/build/test-results/testDebugUnitTest`, `shared/build/test-results/testAndroidHostTest`). La base es **680 + 904 = 1584 pruebas**, no textos: el total solo debe subir.
- Salida de las tareas, archivos tocados y los **seis controles negativos**.
- **Lista exacta de cada línea que no sea solo mover, cambiar `internal` o `import`.**
- Qué perdió `internal`, y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que esto funciona en iOS: no puedes saberlo desde Windows.** Las pruebas de `androidTest` que tocan estos archivos no corrieron.
