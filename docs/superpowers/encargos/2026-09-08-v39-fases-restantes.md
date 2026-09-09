# v39 · lo que falta del plan, completo (encargo para AGY)

**Fecha:** 2026-09-08. **Implementa:** AGY (Gemini 3.7 Flash). **Orquesta y verifica:** Claude.
**Revisa el diff de cada bloque:** Sol (Codex). **Base:** `4fd46ae` más los cambios sin commitear
que ya hay en el árbol.

**Regla que manda sobre todas: no rompas lo que ya funciona.** Sin refactors grandes, sin añadir
dependencias, **sin tocar `functions/` salvo donde este encargo lo diga**, **sin desplegar** y
**sin generar ningún AAB**. Lo que sale hacia Play lo autoriza Diego, aparte.

Tres cosas de entregas anteriores, que aquí ya costaron caro: **no borres comentarios**; **no
reescribas bloques enteros, edítalos**; y **corre las pruebas antes de darte por terminado**.

Lee `CLAUDE.md` antes de empezar: manda por encima de este encargo.

## Cómo se trabaja esto

**Un bloque a la vez, y paras.** Al terminar cada bloque: reportas, Claude corre el gate, Sol
revisa el diff, y sólo entonces empiezas el siguiente. **No encadenes bloques sin ese paso.**
Cinco bloques entregados de golpe no se pueden revisar, y aquí la revisión ha encontrado algo real
en todas las fases hasta ahora.

**No commitees nada.** El árbol se queda sucio a propósito entre bloque y bloque.

**En cada bloque, el cableado es parte de la entrega.** Un objeto nuevo sin llamador desde el
camino real no cuenta como terminado. Ya pasó tres veces en este proyecto.

**Cada prueba tiene que invocar producción y caerse si producción se rompe.** Compruébalo por
mutación: rompe a mano la línea que arreglaste, corre la prueba, confirma que falla, deshaz. Una
prueba que le pasa a mano el valor correcto a la función que estás probando **no prueba el
cableado** — es justo lo que Sol acaba de cazar en el bloque anterior.

---

# BLOQUE 0 — los seis hallazgos de Sol (esto va primero y solo)

**Esto es lo único que bloquea el `versionCode 40`, que es el bundle de medición.** Hasta que esté,
no se empieza ningún otro bloque. Los cambios ya están en el árbol sin commitear; esto los corrige.

## 0.1 — El barrido marca `STALE` importaciones vivas de poco más de una hora

`LedgerImportMetricRecorder.kt`, `sweepStaleInProgress`, y la consulta de
`AiLedgerImportMetricDao.markStaleInProgress`.

`hourRounded` trunca **hacia abajo** a la hora entera. Una importación empezada a las 12:59 se
guarda como `12:00`; a las 14:01 el corte es `14:00 - 2h = 12:00`, y con `<=` la fila cumple
`12:00 <= 12:00` teniendo **62 minutos de vida**. El umbral nominal de dos horas es en realidad de
una hora y pico, y el barrido corre en cada escritura de métrica y al arrancar, así que puede
marcar `STALE` una revisión que la persona todavía tiene abierta.

Arréglalo de forma que **el umbral real nunca sea menor que el nominal**. Piensa en qué lado
compensa el truncado y si `<=` debe ser `<`. Y **prueba el borde**: una fila creada al final de una
hora, barrida justo después de cruzar el límite, tiene que sobrevivir.

## 0.2 — Una importación exitosa puede acabar contada como `STALE`

`AiDebugLabScreen.kt`, `recordImportEnded`.

Si Room registra el dinero y el proceso muere antes de que el `upsert` de la métrica llegue, el
barrido posterior convierte esa fila en `STALE` — pero **esa importación fue exitosa**. La línea
base contaría un acierto como cierre anómalo. El barrido no puede saber el desenlace.

**Decisión tomada, impleméntala así:** `STALE` significa **"no sabemos cómo acabó"**, no
"fracasó". Déjalo escrito en el KDoc de la constante y del método de barrido, y **asegúrate de que
esas filas queden excluibles de las proporciones de la línea base** — quien lea la tabla tiene que
poder separar *registradas*, *abandonadas* y *desconocidas* sin adivinar. No hace falta
`WorkManager` para esto; el punto es no mentir en la columna.

## 0.3 — `duplicateSuggestedCount` con default `0` es una trampa de cero silencioso

`LedgerImportMetricRecorder.onImportEnded`. El parámetro tiene valor por defecto `0`, así que
cualquier llamador nuevo que lo olvide compila y **sobrescribe el conteo con cero sin avisar**.
Hazlo obligatorio. Si eso rompe llamadores en pruebas, arréglalos: ése es el punto.

## 0.4 — `detectedCount` significa dos cosas distintas

En la métrica ahora es `originalOperations.size + descartes del parser`
(`LedgerImportMetricRecorder.kt`), mientras que `LedgerImportSummaryCalculator` define "detectados"
como `review.operations.size`, **sin** descartes, y eso es lo que ve la persona en pantalla. El
mismo nombre para dos cosas hace ambigua la línea base.

Resuélvelo **sin cambiar lo que ve la persona**: o la columna de la métrica se llama como lo que
de verdad contiene, o vuelve a la definición de la pantalla y los descartes del parser viven en su
propia columna. Elige, y deja el porqué en un comentario. Recuerda que si añades columna hay que
subir la versión de la base, escribir la `Migration`, meterla en `CUENTIVO_MIGRATIONS`,
registrarla en **los tres** builders de `"cuentivo.db"` y verificar el SQL contra el `createSql`
del esquema exportado — mira `CLAUDE.md`. **Si puedes resolverlo sin columna nueva, mejor.**

## 0.5 — La prueba de duplicados no prueba el cableado que dice probar

`LedgerImportMetricRecorderTest`: fabrica un `2` y se lo pasa directo al recorder, así que seguiría
verde aunque la pantalla mandara cero o leyera la colección equivocada.

Lo que hay que demostrar es que **el valor guardado sale de `LedgerReviewing.duplicateMatches`**.
Si eso vive dentro de un Composable y no se puede invocar desde una prueba, la salida correcta es
**extraerlo** a algo invocable —una función que reciba el estado y devuelva lo que se va a
registrar— y probar esa. No lo reescribas dentro del test.

## 0.6 — Las pruebas del barrido esquivan el borde defectuoso

Cubre el caso de 0.1 en la prueba JVM **y** en la instrumentada. Y ojo: el doble de DAO de la
prueba JVM **reimplementa el predicado SQL en Kotlin**, así que no cuestiona la consulta real —
la instrumentada contra Room es la que vale para eso. Está bien tener las dos, pero que la
instrumentada cubra el borde.

**Gate del bloque 0:** `testDebugUnitTest` completo, la instrumentada
`AiLedgerImportMetricSweepTest` y `Migration6To7Test` por nombre de clase, y `assembleDebug`.

---

# BLOQUE 1 — matching con más señales que el texto

Hoy `LedgerEntityMatcher` decide por parecido de texto, y `LedgerCustomerMatching` /
`LedgerProductMatching` deciden *cuándo* preguntarle. Léelos antes de tocar nada.

**Objetivo: menos correcciones de cliente y de producto, sin inventar coincidencias.** Las señales
que faltan y que el papel sí da:

- **el precio**, que ya llega a `matchProduct` pero conviene revisar cuánto pesa;
- **el historial**: a quién se le vende normalmente ese producto, qué clientes aparecen en las
  libretas de este negocio;
- **alias**: nombres que ya se corrigieron a mano antes y podrían recordarse.

**Antes de escribir código, propón.** Di qué señales vas a usar, con qué peso, y **qué pasa en el
empate** — el desempate tiene que ser determinista, como ya lo es en `PaymentAllocator`
(`dueDate → saleDate → id`). Espera el visto bueno.

**El límite innegociable:** una coincidencia dudosa se **ofrece**, nunca se aplica sola. Igual que
la fecha de hoja. Un match equivocado aplicado en silencio manda dinero al cliente que no es, y
aquí nada financiero se edita: se corrige con un movimiento compensatorio.

Si guardas aliases o historial, es **local y por negocio** (`businessId` en toda consulta), y **no
puede convertirse en la libreta reconstruible**: agregados, no un renglón por corrección.

---

# BLOQUE 2 — preprocesado prudente de la foto

**Objetivo: que Gemini reciba una imagen más legible, sin inventarse nada.** Enderezado y recorte
de bordes; nada de "mejoras" que alteren cifras.

**Prudente quiere decir:** si el preprocesado no está seguro, **manda la original**. Una foto
recortada de más pierde renglones enteros, y un renglón perdido es peor que un renglón torcido:
el que falta no se ve, el torcido sí.

Ojo con `prepareInPlace`, que hoy **sobrescribe el original** y por eso impide reintentar con más
detalle. Está anotado como hueco conocido. Si tu trabajo lo toca, arréglalo de paso.

Mide el efecto con la tabla de métricas, no de oídas.

---

# BLOQUE 3 — segunda pasada de Gemini (sólo código, no se despliega)

**Esto es lo más caro y lo que más puede alucinar, y por eso va el último de los tres grandes.**

La idea: volver a preguntar **sólo por los renglones dudosos**, no por la página entera. Lo que
tiene que quedar clarísimo en el diseño:

- **Cuándo se dispara**, y que la persona pueda no pagarlo.
- **Qué se manda**: los renglones dudosos, no la libreta completa otra vez.
- **Qué puede hacer la respuesta.** Ya hay una regla escrita para esto y se respeta:
  **la certeza sólo puede empeorar el estado de un campo, nunca mejorarlo.** Una autoridad para lo
  que se escribe, una segunda opinión sólo para lo que se mira.
- **Cuánto cuesta**, contado en la tabla de métricas: llamadas y milisegundos ya tienen columna.

Si esto necesita tocar `functions/`, **escribe el código pero no despliegues**. Antes de cualquier
despliegue va `npm --prefix functions run release:check`, y la autorización es de Diego. Recuerda
que el contrato v4 ya está en producción y **acepta v3 y v4**: no rompas esa compatibilidad, que
es lo que protege al `v37` que hay publicado.

**Propón el diseño antes de escribir.** Este bloque no se empieza a ciegas.

---

# BLOQUE 4 — panel de depuración interno

Para poder mirar lo que la tabla de métricas ya guarda sin sacar el teléfono de casa. Interno,
sólo en la variante debug, **fuera de release** — y ojo con el defecto histórico contrario: la
importación de libreta llegó a no existir en release porque su botón estaba anidado bajo
`if (ocrEngine != null)`. Aquí es al revés y a propósito, pero compruébalo, no lo supongas.

Muestra **agregados**. Nada por renglón, ni siquiera en debug.

---

# BLOQUE 5 — items nativos en la venta

Lo que la libreta lee como productos debería poder registrarse como ítems de venta de verdad, no
sólo como un total. Toca dinero, así que aquí la regla de oro: **`Long` en centavos, nunca
`Double` ni `Float`**, y las reglas de Firestore ya validan la aritmética
(`subtotalCents == quantity * unitPriceCents - discountCents`). Si tu cambio no cuadra con eso, el
servidor lo rechaza.

**Este bloque es el que más cerca pasa del núcleo financiero. No se empieza sin propuesta previa.**

---

## Gate, en cada bloque

Norton intercepta TLS: **sin el trust store, Gradle falla con `PKIX path building failed`.**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

`--no-watch-fs` no es opcional aquí.

1. `testDebugUnitTest`. **Cuenta desde los XML** (`app/build/test-results/testDebugUnitTest/*.xml`),
   no del texto de Gradle. Referencia actual: **1159, 0 fallos**. Tiene que subir, nunca bajar.
   Y si Gradle dice `UP-TO-DATE`, **no ha ejecutado nada**: fuérzalo con `--rerun`.
2. Instrumentadas **por nombre de clase** (la suite completa aborta en `ProductPhotoSourceTest`).
   Emulador `Medium_Phone` en `emulator-5554`; comprueba `adb shell dumpsys user | grep
   RUNNING_UNLOCKED` antes, porque `adb devices` dice `device` con el sistema a medias.
   **Pasa siempre `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.** Sin eso
   Gradle desinstala la app y **borra la base local — ya pasó una vez, con datos reales de Diego.**
3. `assembleDebug`.
4. Si tocas `strings.xml`: paridad ES/EN y la puerta del idioma de `CLAUDE.md`. Y en `values-en/`,
   **redacta sin apóstrofos** ("do not", no "don't"): el escapado se mangla y rompe el merge de
   recursos.

## Qué reportas al terminar cada bloque

- Qué cambiaste y **por qué elegiste esa forma** donde hubo decisión.
- **El comando que ejecutaste de verdad y su salida.** Sin resumir, sin inventar.
- El conteo de pruebas desde los XML.
- La comprobación por mutación de cada prueba nueva: qué rompiste, qué prueba cayó.
- Qué **no** hiciste y por qué, si dejaste algo fuera.
