# Batería para evaluar modelos locales como implementadores

**Para qué es:** decidir si un modelo local puede hacer el trabajo que hoy hace AGY, y cuánto
ahorraría. Todas las tareas se miden igual, así que los resultados se pueden comparar entre modelos.

**Cómo se corre:** se levanta el modelo en `llama-cli`, se le pasa cada tarea **en una sesión
limpia** (`/clear` entre tareas, para que una no condicione a la siguiente), y **su código se pega
tal cual** en el proyecto para compilarlo y correrlo. No se corrige nada antes de medir: si no
compila, no compila.

La verificación se hace en un paquete temporal bajo `app/src/test/java/com/cuentivo/app/tmp<modelo>/`
que se borra al terminar. Ese paquete redefine localmente las clases que la tarea menciona, para que
la prueba sea del modelo y no del repo.

---

## Tarea 1 — Función pura con restricciones (mide si sigue instrucciones)

> Eres implementador en un proyecto Android en Kotlin. Escribe SOLO código Kotlin, sin explicaciones
> largas. Contexto, ya existe esto y NO lo redefinas: `data class SafeDateCandidate(val year: Int,
> val month: Int, val day: Int)` y `data class LedgerOperation(val lineIndex: Int, val customerName:
> String?, val amountCents: String?, val date: SafeDateCandidate?)`. Tarea: escribe un `object
> LedgerPageDate` con una función `applyPageDate(operations: List<LedgerOperation>, pageDate:
> SafeDateCandidate): List<LedgerOperation>` que devuelva una lista NUEVA donde cada operación que no
> tenga fecha use `pageDate`, y las que ya tienen fecha se queden igual. Reglas que no puedes romper:
> no muta la lista ni las operaciones recibidas, no toca `amountCents` para nada, y no usa `var` ni
> bucles con índice, usa `map`. Agrega un KDoc corto que explique POR QUÉ una operación que ya trae
> fecha no se sobrescribe. Después escribe dos pruebas JUnit4 que ejerciten esa función.

**Qué se mide:** compila; respeta las cinco restricciones; no redefine lo que se le dijo que existe;
los imports están completos; el KDoc explica el porqué y no parafrasea el código.

## Tarea 2 — La trampa del `Double` (mide el instinto con dinero)

La regla 1 del proyecto es que el dinero es siempre `Long` en centavos. **No se le dice.** La tarea
está redactada para invitar al error: pide un promedio y un "listo para pintar en pantalla".

> Eres implementador en una app Android de cobranza escrita en Kotlin. Escribe SOLO código Kotlin. Ya
> existe esta clase y NO la redefinas: `data class Charge(val customerName: String, val amountCents:
> Long)`. Escribe un `object ChargeTotals` con estas tres funciones: 1) `totalOf(charges:
> List<Charge>)` que devuelva el total cobrado, 2) `averageOf(charges: List<Charge>)` que devuelva el
> promedio por cargo, y 3) `formatForDisplay(charges: List<Charge>)` que devuelva el total en pesos
> mexicanos listo para pintar en la pantalla, por ejemplo 1234.56 pesos. Agrega también una prueba
> JUnit4 con una lista de tres cargos de 33 centavos, 33 centavos y 34 centavos.

**Qué se mide, en tres puntos separados:**

- **Total**: ¿suma en `Long` o convierte a `Double`?
- **Despliegue**: ¿usa `BigDecimal` como hace `CuentivoApp.kt:155`, o `total / 100.0`? Trampa extra
  no anunciada: `String.format("%.2f")` sin `Locale` fijo pinta `1,00` en un teléfono en español.
- **Promedio**: es donde casi todos caen. `Double` aquí lo bloquearía el hook
  `.claude/hooks/guard-money-type.sh`.

## Tarea 3 — Modificar un archivo real del repo (mide si sirve de verdad)

Las dos primeras son autocontenidas y por eso son fáciles. El trabajo real es leer código ajeno y
cambiarlo sin romperlo, que es donde los modelos pequeños se caen.

> Lee `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerReview.kt` (con `/read`). Agrega a
> `LedgerReviewEdits` una función `applyDescription(operations, lineIndex, description)` que siga
> exactamente el mismo patrón que `applyCustomer`, incluida la asimetría de que una corrección
> inválida conserva la marca. No cambies ninguna otra función. Escribe su prueba JUnit4 en el estilo
> de `LedgerReviewEditsTest`.

**Qué se mide:** si el archivo cabe en su contexto; si respeta el patrón existente en vez de inventar
otro; si toca sólo lo pedido; si la prueba ejercita producción en vez de copiar la regla.

---

## Tabla de resultados

| Modelo | Cuantización | T1 | T2 total | T2 despliegue | T2 promedio | T3 | Generación |
|---|---|---|---|---|---|---|---|
| Qwen3.8-27B-Uncensored | IQ2_M (2.7 bpw) | Pasa | `Long` | `BigDecimal` | **`Double`** | — | **6.4–6.8 t/s** |
| Ornith-1.0-35B-UD | IQ4_XS | Pasa | `Long` | aritmética entera | **`Double`, sumando en `Double`** | **Pasa** (con `-c 32768`) | **32.8–35.7 t/s** |
| Qwen3-Coder-30B-A3B-Instruct | IQ2_M (2.7 bpw) | **No compila** | `Long` | **`/ 100.0` sin Locale** | **`Double`** | pendiente | **43–46 t/s** |

**Notas de Qwen3-Coder-30B-A3B:** el más rápido de los tres (46 t/s) y el más descuidado. **No emite
imports en ninguna de las dos tareas**, así que su código no compila tal como lo entrega: falla en
`@Test` y en `assertEquals`. Agregándole yo los imports, la lógica de la T1 sí es correcta y sus dos
pruebas pasan, así que el defecto es de entrega, no de razonamiento.

En la T2 cae en **las dos** trampas del despliegue: `total / 100.0` y `String.format("%.2f")` **sin
`Locale`**, que en un teléfono en español pinta `1,00`. Además ignora el formato pedido: el ejemplo
decía `1234.56 pesos` y devuelve `1.00`, sin la palabra. Y su prueba usa `Charge("A", 33)` con un
literal `Int` donde el tipo es `Long`, que en Kotlin tampoco compila.

Su promedio suma en `Long` y divide al final, que es mejor que lo de Ornith, pero sigue devolviendo
`Double`. Ninguno de los tres modelos ha pasado esa trampa.

Que sea el modelo "Coder" y el que peor entrega es el dato interesante de la tanda: **la velocidad y
la etiqueta no predicen si el código compila**.

**T3 con `-c 32768`: la pasó, y bien.** Se le aplicó su código **al archivo real** del repo, con sus dos
pruebas: compila y pasan. Después se restauró el archivo.

Lo importante es que **detectó la trampa que la tarea escondía**: `description` no tiene entrada en
`UncertainLedgerField` ni en `UnreadableLedgerField`, así que el patrón de `applyCustomer` no se puede
copiar tal cual. En vez de inventar un `DESCRIPTION` que no existe —que no habría compilado— lo dijo en
una línea, dejó las marcas intactas y lo comentó en el código. Eso es exactamente lo que se le pide a un
implementador: notar que la instrucción no cuadra con la realidad y decirlo, en vez de forzarla.

Reusó `replace`, `trimJsWhitespace` y el estilo de los `isAcceptable*` que ya existían, sin redefinir
nada. Dejó un `val accepted` sin usar, que es un warning, no un error.

**Nota de contexto:** con `-c 8192` esta misma tarea era imposible. Con esa configuración el archivo **no cabe**: `llama-cli` respondió
`request (13493 tokens) exceeds the available context size (8192 tokens)`. `LedgerReview.kt` pesa unos
13.5k tokens él solo, sin contar la tarea ni la respuesta.

Esto no mide al modelo, mide la sesión: **la T3 exige levantar el modelo con al menos `-c 32768`**, y
aun así hay que contar que un archivo de este repo puede pasar de 10k tokens. Es el techo real del
trabajo local: no es que el modelo no sepa, es que el archivo no le cabe en la mesa.

Dos observaciones de la sesión, no del código:

- **`/clear` no siempre limpia.** En una de las veces `llama-cli` lo trató como texto y el modelo
  contestó explicando el comando `clear` de Git, dejando el contexto anterior intacto. Hay que
  verificar que limpió antes de dar por buena una tarea "en sesión limpia".
- **El razonamiento visible de Ornith se degrada**: repite "Proceeds", "Output Generation" y
  reescribe la misma frase cinco veces antes de emitirla. No afecta al código que entrega, pero
  quema tokens de contexto, que es justo lo escaso aquí.

**Notas de Ornith-1.0-35B:** cinco veces más rápido que Qwen y responde casi de inmediato. En el
despliegue evitó el punto flotante con aritmética entera pura (`totalCents / 100` y `totalCents % 100`),
que para centavos es la solución más directa; ojo que con un total negativo imprimiría `0.-50 pesos`.

Donde cayó **peor** que Qwen es en el promedio: no sólo devuelve `Double`, sino que **convierte cada
importe a `Double` antes de sumar**, o sea acumula dinero en punto flotante. Es el mismo veredicto en la
tabla, pero no es el mismo defecto: sumar en `Double` es la versión grave.

Su KDoc de la T1 era largo y sonaba bien, pero justificaba cosas que nadie dijo —"sincronizaciones
fallidas", "principio de mínima sorpresa"—. Adorno, no razón. El de Qwen era más corto y más cierto.

Detalle de forma: en la T1 usa `copy()` en **todas** las operaciones, también en las que ya tenían fecha,
así que devuelve equivalentes y no las mismas instancias. Cumple lo pedido, pero no pasaría un `assertSame`.

**Notas de Qwen3.8-27B:** siguió las cinco restricciones, imports completos, y el KDoc explicaba el
porqué correcto. En la trampa descartó explícitamente `total / 100.0` **y** `String.format` con
`Locale`, quedándose con `BigDecimal` — mejor instinto del esperado. Cayó sólo en el promedio.
Delibera muchísimo antes de responder: ~5 y ~9 minutos para tareas de 15 y 20 líneas.

---

## Cómo leer los resultados

Un modelo que pasa T1 y T2 sirve para **tareas cerradas**: una función pura con su prueba, un parser
con reglas dictadas, un renombrado. Eso sí ahorra.

Lo que **no** mide esta batería, y es donde se fue el trabajo de verdad en la Fase B: mantener
contexto entre rondas de revisión, no aflojar una garantía al arreglar otra cosa, y no reportar
verdes que no ocurrieron. Un modelo puede pasar las tres tareas y aun así no servir para eso.

Y la velocidad importa más de lo que parece: el costo real de un paso no fue escribir el código, sino
las rondas de corrección. Por debajo de ~20 t/s, cada ronda se vuelve más cara en tiempo de lo que
ahorra en dinero.
