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

# BLOQUE 0 — los seis hallazgos de Sol ✅ CERRADO

**Cerrado el 2026-09-08 en cuatro rondas**, commiteado en `5636618`, `b3066b1` y `f60dde6`, y con
el `versionCode 40` (`1.20.2`) generado y firmado. Se deja aquí escrito porque documenta por qué la
tabla cuenta lo que cuenta; lo que cambió está resumido en `PROJECT_MEMORY.md`.

**Orden del resto, decidido con Diego el 2026-09-08 y ya aplicado abajo:**
1 matching → 2 preprocesado → **3 panel** → 4 segunda pasada **apagada** → 5 **puerta: medir y
decidir** → 6 comparativa → 7 items → 8 hardening.

El panel se adelantó a propósito: es el instrumento con el que se mira qué hizo la segunda pasada,
y construirla antes es depurarla a ciegas.

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

# BLOQUE 3 — panel de depuración interno

**Cambió de sitio: va ANTES de la segunda pasada, no después.** El panel es el instrumento con el
que se mira qué hizo la segunda pasada — primera lectura, confianza, candidatos, resultado y
motivo. Construir la segunda pasada primero es depurarla a ciegas, y es justo el bloque con más
riesgo de que el modelo **elija** un candidato en vez de **leerlo**.

Para poder mirar lo que la tabla de métricas ya guarda sin sacar el teléfono de casa. Interno,
sólo en la variante debug, **fuera de release** — y ojo con el defecto histórico contrario: la
importación de libreta llegó a no existir en release porque su botón estaba anidado bajo
`if (ocrEngine != null)`. Aquí es al revés y a propósito, pero compruébalo, no lo supongas.

En la tabla de métricas muestra **agregados**. Nada por renglón, ni siquiera en debug.

Lo de la **sesión en curso** es otra cosa y sí puede verse renglón a renglón: no está persistido,
vive en memoria mientras dura la revisión, y es exactamente lo que hace falta para entender una
lectura concreta. La línea que no se cruza es guardarlo.

---

# BLOQUE 4 — segunda pasada de Gemini (sólo código, apagada, no se despliega)

**Esto es lo más caro y lo que más puede alucinar.** Se construye **con el flag apagado** y no se
enciende hasta pasar la puerta del bloque 5.

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

## El caso real que esta fase tiene que cazar

Es el único dato duro que hay, de la prueba de Diego con su propia libreta:

> Hoja del **03/06/26**, 9 renglones. Los 9 detectados, y las notas que no eran movimiento
> correctamente excluidas. **Un fallo: un cargo de $139.90 inventado**, asignado a Caro Vera,
> **sin marca de dudoso** — mientras los dos renglones que sí existían de esa misma clienta **sí**
> salieron marcados.

Léelo despacio, porque dice más de lo que parece: **la confianza del modelo no correlaciona con la
verdad**. Lo que se invento llegó con más seguridad que lo que sí estaba escrito. Una segunda
pasada que sólo pregunte por los renglones ya marcados como dudosos **no habría cazado éste**, que
es el único que hacía daño. El diseño tiene que responder a eso explícitamente.

---

# BLOQUE 5 — PUERTA: medir contra la línea base y decidir

**No es una fase de código. Es una decisión, y puede salir que no.**

Con la línea base recogida por el `versionCode 40` y las métricas de la segunda pasada encendida en
pruebas, se compara contra `baseline-cuentivoai-v39-1.20.1` y se responde: ¿hay **menos
correcciones humanas sin más errores de dinero**? Ése es el criterio que fijó Diego y no hay otro.

**Queda escrito, y no es una fórmula de cortesía: el flag de la segunda pasada puede quedarse
apagado para siempre si los números no acompañan.** Construirla no obliga a encenderla. Cuesta
dinero por foto y puede meter cifras que nadie escribió; si no demuestra que mejora, se queda
apagada y el trabajo sirvió para saberlo.

Lo que la comparativa no puede medir sola —*cliente equivocado sin marca*, *monto equivocado sin
marca*, *inventados u omitidos*— sólo sale de que Diego use libretas reales. Eso no lo sustituye
ninguna prueba.

---

# BLOQUE 6 — comparativa v38 contra v39

Con los números de los dos lados, la comparativa formal contra
`baseline-cuentivoai-v39-1.20.1` (`a2c810e`), **no** contra `1.20.0`: ése salió con la tarjeta de
revisión que ocupaba una pantalla por renglón, y compararse con él mediría ese defecto en vez del
pipeline.

---

# BLOQUE 7 — items nativos en la venta

Lo que la libreta lee como productos debería poder registrarse como ítems de venta de verdad, no
sólo como un total. Toca dinero, así que aquí la regla de oro: **`Long` en centavos, nunca
`Double` ni `Float`**, y las reglas de Firestore ya validan la aritmética
(`subtotalCents == quantity * unitPriceCents - discountCents`). Si tu cambio no cuadra con eso, el
servidor lo rechaza.

**Este bloque es el que más cerca pasa del núcleo financiero. No se empieza sin propuesta previa.**

---

# BLOQUE 8 — hardening

Los huecos conocidos que llevan tiempo anotados y no son de ninguna fase en concreto:

- `prepareInPlace` sobrescribe el original, así que impide reintentar con más detalle (si el
  bloque 2 no lo arregló ya).
- `OwnerMembershipBootstrap` llama a `reopenParkedOperations()` a secas y desaparca de más.
- Faltan índices compuestos `(businessId, fecha)` para la consulta de duplicados, anotado como
  deuda en el DAO.
- El encolado del outbox no es atómico y **es deliberado**, pero si el proceso muere entre la
  transacción y el `enqueue`, la venta existe en local y no se encola nunca. No hay reconciliación.
- `FirestoreSyncGateway` hace `set()` individuales sin `WriteBatch`, así que una venta puede quedar
  publicada a medias.

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
