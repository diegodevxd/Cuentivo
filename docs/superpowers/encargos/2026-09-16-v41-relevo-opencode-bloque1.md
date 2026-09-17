# v41 · relevo a OpenCode: cerrar el bloque 1

**Fecha:** 2026-09-16. **Implementa:** OpenCode (DeepSeek V4.1 Flash), en relevo de AGY.
**Orquesta y verifica:** Claude. **Revisa el diff:** Sol.

Lee primero, en este orden:

1. `CLAUDE.md` — manda por encima de todo.
2. `docs/superpowers/encargos/2026-09-16-v41-libreta-que-no-se-engana.md` — el encargo completo.
3. Este archivo, que dice **dónde está el trabajo y qué falta**.

**No commitees, no despliegues, no generes AAB.**

## El árbol tiene trabajo sin commitear, y es a propósito

`git status` muestra el bloque 1 a medias, hecho por AGY. **No lo descartes, no hagas `git stash`,
no hagas `git checkout -- .`**: el stash se comparte con otras sesiones y lo que hay es trabajo bueno.
Construye encima.

## Lo que ya está bien y NO se toca

Revisado por Claude contra el código:

- **Regla del cliente existente** (`domain/LedgerDeterministicObjections.kt`): quita lo que va entre
  paréntesis del `rawText` antes de buscar el nombre, compara por **palabras completas** (no por
  subcadena), y en renglones heredados por comilla sólo objeta si el cliente heredado **ya existe**.
- **Sin `Double`**: la comparación de precios usa multiplicación cruzada con `Long`.
- **La salida para la persona**: `LedgerOperation.isObjectionConfirmed`,
  `LedgerReviewEdits.confirmObjection`, el botón en `LedgerReviewScreen`, y la persistencia en
  `LedgerReviewSaver` para que sobreviva a recrear la Activity.
- Los parámetros de `evaluate`, `calculate` y de los composables ya son obligatorios.
- `LedgerV4FidelityTest` sólo cambió porque esos parámetros ya no tienen valor por defecto; las
  afirmaciones son las mismas.

Último gate de Claude: **1185 pruebas JVM, 0 fallos**; `assembleDebug` exit 0.

## LO QUE FALTA — el bloqueante

**Las objeciones no impiden escribir.** La pantalla las muestra, los contadores las respetan, pero
lo que de verdad se escribe en la base **no las mira**:

- `domain/AiLedgerBatchPlanner.kt:151` y `:183` planifican todo renglón que cumpla
  `isRegisterableLedgerOperation`.
- `domain/LedgerBatchCoordinator.kt:37` filtra igual.
- `ai/ledger/LedgerReview.kt:357` cuenta los registrables para el botón "Registrar N movimientos"
  con la misma función.
- `ui/ai/AiDebugLabScreen.kt:844` cuenta los omitidos con la misma función.

**Consecuencia:** la app avisa "este cliente ya existe, confírmalo", la persona pulsa "Registrar",
**y el cargo a la clienta equivocada se escribe igual.** Es el defecto más repetido de este proyecto:
un aviso sin cableado.

**Cómo se arregla, y por qué no vale meterlo en `isRegisterableLedgerOperation`:** las objeciones
dependen del contexto de la página (catálogo, clientes existentes, patrón de centavos), y esa función
sólo recibe un renglón. Así que:

1. **Donde se lanza el registro** (`AiDebugLabScreen`), calcula las objeciones con los mismos datos
   que usa la pantalla y **excluye los renglones con objeción no confirmada** antes de entregarlos al
   planificador y al coordinador.
2. **Defensa en profundidad:** que el planificador y el coordinador **reciban el conjunto de
   renglones bloqueados y se nieguen a escribirlos**, como parámetro **obligatorio**. Así, un llamador
   futuro no puede saltárselo sin que no compile.
3. **El botón y el mensaje final tienen que decir la verdad:** "Registrar N" cuenta sólo lo que de
   verdad se va a escribir, y "M omitidos" incluye los bloqueados.
4. Tras `confirmObjection`, ese renglón **sí** se escribe.

## Una cosa que hay que verificar, no suponer

Se pidió que la comparación de precios no se hiciera **total del renglón contra precio unitario**:
un renglón con cantidad 3 o con varios `items` daría un aviso falso. **Comprueba si quedó resuelto**;
si no, resuélvelo (comparar por unidad, o no objetar cuando hay más de un item o una cantidad ≠ 1).

## Pruebas

**Tienen que atravesar el camino real de escritura**, no una copia de la regla:

- Un renglón con objeción **no confirmada** llega al planificador/coordinador → **no se escribe**.
- El mismo renglón tras `confirmObjection` → **sí se escribe**.
- El conteo del botón coincide con lo que se escribe.
- El caso Charly: renglón `"22562 Bottle 149 Dana TS (Charly)"`, clienta existente "Charly" asignada
  por el modelo → objeción → no se escribe sin confirmar.

**Cada prueba nueva tiene que fallar con el código de hoy.** Compruébalo por mutación: rompe la
línea, corre la prueba, confirma que cae, deshaz. Datos **inventados**: nunca nombres ni montos de
libretas reales.

## Gate

Norton intercepta TLS; sin esto Gradle falla con `PKIX path building failed`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

- `--no-watch-fs` no es opcional.
- Cuenta las pruebas **desde los XML** (`app/build/test-results/testDebugUnitTest/*.xml`).
  Referencia: **1185, 0 fallos**. Tiene que subir.
- **No lances Gradle mientras otro Gradle corre en esta carpeta**: dos compilaciones a la vez
  corrompen `app/build/intermediates` y fallan con `graph.bin ... no puede encontrar la ruta`.
- Si tocas `strings.xml`: paridad ES/EN, y en `values-en/` **sin apóstrofos**.

## Qué entregar

- Qué cambiaste, con archivo y línea.
- **El comando que ejecutaste de verdad y su salida.** Sin resumir, sin inventar.
- El conteo de pruebas desde los XML.
- La comprobación por mutación de cada prueba nueva.
- Qué no hiciste y por qué.

**Termina el bloque 1 y para.** Los bloques 2 a 4 van después, cuando Sol haya revisado éste.
