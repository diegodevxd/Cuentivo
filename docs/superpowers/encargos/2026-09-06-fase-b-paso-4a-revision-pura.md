# Fase B — Paso 4a: la lógica de la revisión, sin pantalla (encargo para AGY)

**Fecha:** 2026-09-06
**Base:** pasos 1–3 commiteados (`b30a408`, `b3f21f2`, `aadbb5c`). El servidor devuelve la lista de
operaciones con incertidumbre e ilegibles por campo, y el ejecutor ya sabe registrar con la fecha de
la libreta y cargos sin producto.
**Alcance:** SOLO la lógica. **No escribas Composables en este encargo.** La pantalla es el 4b.
Tampoco el registro del lote (paso 5). Sin commits, sin deploys.

## Por qué la lógica va primero y aparte

La regla más dura de este proyecto es que **una prueba no puede reimplementar lo que verifica**, y
que si lo que importa vive dentro de un Composable, la salida correcta es **extraerlo**. Ya pasó
cinco veces aquí. Así que todo lo que decide qué se registra y qué no vive en objetos **puros de
Kotlin**, sin `Context` ni `R.string`, que corren en `testDebugUnitTest`. La pantalla del 4b sólo
los dibuja.

Mira cómo está hecho `ui/ai/AiDebugLabModel.kt`: es una máquina de estados plana con su prueba
`AiDebugLabMachineTest`. Sigue ese patrón, no inventes otro.

## Qué construir

### 1. Estados de la importación

Añade a la máquina existente los estados del flujo de libreta: extrayendo, en revisión,
confirmando. Reglas que vienen del plan y no son negociables:

- **La voz se deshabilita** durante extracción, revisión y confirmación. Editar con teclado es
  suficiente y elimina una clase entera de carreras.
- **La sesión de importación se identifica antes de abrir la cámara**, ligada al negocio y a una
  versión del borrador. **Sólo su resultado vigente** puede modificar la revisión: una respuesta que
  llega tarde, de una foto anterior o de otro negocio, se descarta.
- Cancelar la extracción **invalida su respuesta**.

### 2. El modelo de la revisión (el corazón de este encargo)

Un objeto puro que, dada la lista de operaciones y las ediciones del usuario, derive:

- **Agrupación por cliente**, con **deuda anterior → deuda resultante**. La deuda anterior sale del
  saldo real que le pases; el objeto **no consulta la base**, la recibe.
- **Cuáles clientes se van a crear**, marcados como nuevos.
- **Ventas y abonos separados**, con número de operaciones de cada tipo.
- **Cuántas filas quedaron excluidas y por qué**, usando los diagnósticos de descarte que ya trae
  `parseLedgerExtraction`.
- **Qué renglones NO entran todavía**: los que tienen un campo incierto sin resolver o un campo
  ilegible sin completar. Reusa `isRegisterableLedgerOperation`; **no escribas otra versión de esa
  regla**.
- Un resumen final del tipo "crear 3 clientes y registrar 18 movimientos", **ligado exactamente a la
  versión editada de la lista**, no a la original.

### 3. Ediciones del usuario

Aplicar una edición produce una **lista nueva**, no muta la anterior. Un renglón editado tiene que
poder pasar de no registrable a registrable —y al revés— y el resumen debe reflejarlo sin que nadie
lo recalcule a mano.

El **tipo de movimiento es editable**: deuda anterior, venta o abono. Gemini propone, el usuario
decide.

### 4. Lo que este objeto NO hace

- No escribe nada. No toca Room, ni red, ni reloj: la fecha de "hoy" y los saldos entran como
  parámetros, igual que `DashboardRepository.todayProvider`.
- **No traduce.** Devuelve claves o enums; el texto lo resuelve la pantalla en el 4b. Los `object`
  de dominio no llevan `Context` ni `R.string`.
- No inventa un renglón para cuadrar la página, ni usa el total agregado como red de seguridad: el
  plan explica por qué eso no sirve, y no hay que reintroducirlo.

## Pruebas

En `app/src/test/`. Que **invoquen estos objetos**, no una copia de sus reglas. Como mínimo:

- Una operación ilegible no es registrable; completarla la vuelve registrable; el resumen cambia.
- Una incierta sin resolver no entra; resolverla la mete.
- Cambiar el tipo de un renglón cambia el grupo y la deuda resultante del cliente.
- Dos operaciones del mismo cliente producen **un solo cliente** en el resumen, no dos.
- Un cliente repetido con nombre que ya existe no se marca como nuevo.
- Un resultado que llega de una sesión vieja **no modifica** la revisión vigente.
- Cancelar durante la extracción deja un estado definido, nunca ambiguo.
- El conteo de excluidas coincide con los diagnósticos de descarte.

## Gate

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Y `assembleDebug`. Sin `--rerun-tasks` el verde no prueba nada. Cuenta las pruebas desde los XML de
`app/build/test-results/testDebugUnitTest`, no del texto de Gradle. Reporta números reales y el
comando exacto.

---

# Ronda 2 — revisión de Codex (2026-09-06)

Codex confirma que el modelo **es puro** (sin Android, consultas ni reloj), que la identidad de
sesión y generación se comprueba bien, y que las pruebas llaman a producción en vez de copiar
reglas. Pero **no cierra el 4a**: hay cinco defectos, ordenados por daño.

## 1. Editar borra el saldo conocido — defecto

`onUpdateLedgerOperations` recibe `existingCustomerBalances = emptyMap()` y **reconstruye todo con
ese valor**. Si cargas a Juan, que ya debía, y luego editas sólo su importe sin volver a pasar el
mapa, Juan se convierte en **cliente nuevo con saldo previo cero**. La deuda anterior desaparece de
la pantalla que existe justamente para mostrarla.

El modelo tiene que **conservar ese contexto**. Que la pantalla se acuerde de reinyectarlo en cada
edición es exactamente la clase de garantía que no puede depender de quien llama.

## 2. El desbordamiento produce un saldo falso, en silencio — defecto

En `LedgerReview.kt:199–232` los `catch` conservan el acumulado anterior **pero siguen contando la
operación como registrable**. Diez cargos de 999999999999999999 centavos —cada uno válido y dentro
del tope de 25— desbordan `Long`, y el usuario ve un saldo que no es.

Devuelve un **diagnóstico bloqueante**. Dinero que no cabe no se ignora.

## 3. "Registrable" todavía no significa ejecutable — defecto

Un abono de 3000 **sin deuda previa** aparece como registrable y deja el saldo en −3000. Peor: hay
una prueba que **exige** ese comportamiento, o sea que consolida el defecto. El plan pide justo lo
contrario — que el sistema pueda decir *"esta página no alcanza para registrar estos movimientos"*
cuando empieza a mitad de la historia y sus abonos no tienen deuda que los justifique.

También acepta **importe cero**, que los ejecutores rechazan.

Conserva el validador del contrato tal cual, y **añade aparte una validación financiera de la
revisión** que alimente el resumen y la confirmación. Son dos preguntas distintas: si el renglón
está completo, y si tiene sentido registrarlo.

## 4. El resumen promete crear clientes de filas que no entran — defecto

`newCustomersCount` aumenta **antes** de comprobar si alguna operación del grupo entra. Una sola
fila de Juan con importe ilegible produce *"crear 1 cliente, registrar 0 movimientos"*. Distingue
**grupos visibles** de **clientes que efectivamente se van a crear**.

## 5. La revisión no es una instantánea inmutable

`build` conserva las listas que recibe en `operations` y `discards`, y también quedan compartidas
las listas de incertidumbres. Si quien las creó modifica una lista mutable, **cambian las
operaciones sin recalcular el resumen**, incluso después de confirmar. Aísla la instantánea.

## 6. El espejo Kotlin: aceptable, pero hay que amarrarlo

Codex acepta el espejo —no hace falta compartir ejecución entre lenguajes, igual que
`domain/Permissions.kt` espeja `firestore.rules`— pero pide **fixtures compartidos ejecutados contra
las dos funciones reales**, no un comentario de paridad.

Una divergencia **real** hoy: un nombre compuesto **sólo por U+FEFF** lo rechaza `trim()` de
JavaScript y lo **acepta** Kotlin, porque `Character.isWhitespace('\uFEFF')` es `false`.

Codex citó una segunda divergencia, que `"100\n"` pasa el regex de JavaScript: **la verifiqué y es
falsa**. En JavaScript, a diferencia de Python, `$` sin bandera `m` no casa antes de un salto final,
así que `/^(0|[1-9][0-9]{0,17})$/.test("100\n")` da `false`. No la persigas.

## Pruebas

Cada uno de los cinco defectos necesita su prueba, y **la del abono sin deuda hay que corregirla**,
no conservarla: hoy afirma el comportamiento equivocado.

## Gate

`testDebugUnitTest --rerun-tasks` y `assembleDebug`. Cuenta desde los XML. Sin commits.

---

# Ronda 3 — lo último del 4a (2026-09-06)

Codex da por resuelto, sin reservas: la edición normal conserva el saldo, el desbordamiento deja
diagnóstico y excluye la fila del cálculo, el cero y los abonos inválidos tienen su validación
financiera separada, desapareció la prueba que exigía el saldo negativo, y los clientes visibles ya
se cuentan aparte de los que se van a crear. Quedan tres.

## 1. El diagnóstico financiero no bloquea la confirmación — defecto

`onConfirmLedger` sigue devolviendo `Started` aunque haya `OVERFLOW`, abonos sin deuda o **cero
operaciones ejecutables**. La separación contrato/finanzas está bien hecha, pero **no está conectada
a esa transición**: hoy el bloqueo dependería de que la pantalla se acuerde de mirar, y eso es
precisamente lo que estamos sacando de la pantalla.

Las pruebas nuevas verifican que el diagnóstico existe, **no que impida confirmar**. Añade esa
prueba.

## 2. El mapa de saldos sigue compartido

Las listas de operaciones, diagnósticos e incertidumbres sí quedaron aisladas. Pero
`LedgerReviewing` guarda **directamente** `existingCustomerBalances`, tanto al recibir la extracción
como al actualizarla. Si quien entregó un `MutableMap` lo vacía o lo modifica, la siguiente edición
vuelve a perder el saldo o cambia la clasificación del cliente — el mismo defecto de la ronda
anterior por otra puerta. Copia y protege también ese mapa.

## 3. Los fixtures compartidos: un hueco de paridad y un lector frágil

- **`trim().trim('\uFEFF').trim()` no equivale a JavaScript.** Con `"\uFEFF \uFEFF \uFEFF"` queda un
  BOM en Kotlin y se acepta; JavaScript lo rechaza. Resuélvelo de forma general, no quitando un
  carácter concreto y esperando que no haya otro.
- **El parser JSON artesanal del test de Kotlin interpreta `\t` como la letra `t` y `\f` como `f`.**
  Eso significa que las dos suites pueden estar comprobando **entradas distintas** mientras las dos
  se ven verdes, que es justo lo que los fixtures compartidos venían a evitar. Usa un parser JSON de
  verdad y **exige el número de casos leído**, para que un archivo mal leído falle en vez de pasar
  de largo.
- El fixture **no está declarado como entrada de Gradle**, así que un cambio sólo del JSON puede
  dejar el test `UP-TO-DATE`. Decláralo como input, como ya se hizo con el manifiesto mergeado en la
  Fase A. Mi corrida con `--rerun-tasks` evita el problema, pero no debe depender de que yo me
  acuerde.

## Gate

`npm --prefix functions test`, `testDebugUnitTest --rerun-tasks` y `assembleDebug`. Cuenta desde los
XML. Sin commits.
