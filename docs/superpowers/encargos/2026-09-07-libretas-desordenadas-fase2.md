# Libretas desordenadas — Fase 2: cruzar con lo que Cuentivo ya sabe (encargo para AGY)

**Fecha:** 2026-09-07. **Implementa:** AGY. **Segunda mente:** Claude. **Revisa el diff:** Sol (Codex).
**Base:** `4fe36ca` (Fase 1 ya comiteada: contrato v4, parser y prompt).

**Regla que manda sobre todas: no rompas lo que ya funciona.** La importación de libreta está en el
AAB `v37` de prueba cerrada. Sin refactors grandes, sin sistema paralelo, sin migración.

## Dos avisos sobre la entrega anterior, para que no se repitan

1. **No borres comentarios.** En la Fase 1 se perdieron los que explicaban por qué `amountCents`
   nunca puede ser un número JSON, por qué `toLong()` dejaría pasar un 3.9 y por qué una región mal
   formada descarta la región y no la operación. En este repo el *porqué* vive junto al código y no
   lo cubre ningún test.
2. **No reescribas bloques enteros desde cero: edítalos.** El prompt se reescribió y se llevó por
   delante la regla general de convertir pesos a centavos. Un renglón que dice `38` habría vuelto
   como `"38"` y se habría registrado como 38 centavos.

## El problema

Diego tiene **13 clientes y 552 productos con precio** en Cuentivo, y la importación los ignora casi
por completo. Hoy `AiLedgerBatchPlanner` sólo consulta `existingCustomerMap`, y sólo para deduplicar
por **nombre normalizado exacto**. Nada más.

Así que la libreta dice `Protect tap 79.90` y Cuentivo, que tiene un producto `Protector Tapete` a
79.90, no lo relaciona. Y dice `Mayra T5` teniendo exactamente ese cliente, y no sube la confianza.

## Dónde vive esto, y por qué importa

**En el cliente, en Kotlin puro, en `com.cuentivo.app.domain`.** No en el servidor.

Los clientes y productos viven en Room. Mandarlos al servidor para cruzarlos allí sería enviar los
nombres y las deudas de la gente a un tercero, y toda la declaración de privacidad de Play dice que
eso no pasa. El cruce ocurre **después** de que llega la extracción, en el teléfono.

Y por ser Kotlin puro sin `Context` ni Room, corre en `testDebugUnitTest`, que es lo que hace esta
pieza probable sin dispositivo. Recibe listas ya cargadas por parámetro; **no toca DAOs**.

## Lo que hay que construir

### 1. `LedgerEntityMatcher` (nuevo, `domain/`, puro)

Dado un candidato de texto y la lista de lo que ya existe, devuelve los mejores candidatos con una
puntuación y una decisión. **No escribe nada y no muta nada.**

Tres niveles de decisión, y el nombre importa porque describe qué puede hacer la app:

- **`AUTOMATIC`** (puntuación ≥ 0.90 **y** margen suficiente sobre el segundo candidato): el
  candidato se enseña ya elegido en el control, arriba del todo.
- **`SUGGESTED`** (0.70 – 0.89): se ofrece como candidato, sin elegir.
- **`UNRESOLVED`** (< 0.70, **o** empate/cercanía con el segundo candidato): no se ofrece nada. El
  campo se queda como lo leyó el modelo.

**Y aquí va la corrección más importante de todo el encargo, que la primera versión tenía mal.**
`AUTOMATIC` **no ancla nada por sí solo.** Enseñar un candidato en pantalla no es que alguien lo
haya confirmado: la gente da a "guardar" sin leer. Ningún cruce difuso puede tener efecto financiero
—decidir de quién es una deuda o a quién se le aplica un abono— sin que la persona lo **acepte
explícitamente**, sin importar la puntuación.

El caso que lo decide es un **abono**. Anclar mal una venta crea una deuda que se ve y se corrige;
anclar mal un abono **liquida la deuda real de otra persona**, y nada financiero se edita ni se
borra en Cuentivo. Eso se arregla con un movimiento compensatorio y una conversación incómoda.

El **margen** también es obligatorio: si el segundo candidato queda pegado al primero, no hay
`AUTOMATIC` que valga, es `UNRESOLVED`. Una letra de diferencia entre dos personas reales no la
resuelve una puntuación.

Ajusta los umbrales y el margen si al medirlos ves que otros números parten mejor los casos, pero
**documenta con qué los mediste**. Un umbral inventado es peor que ninguno.

### 2. Cómo se puntúa — no sólo Levenshtein

Combina señales, y que cada una sume una fracción que se pueda leer en el código:

- **Similitud de texto** sobre la salida de `CustomerNameNormalizer.key` (que ya quita acentos,
  baja a minúsculas y colapsa espacios), no sobre el texto crudo.
- **Abreviaturas y prefijos**: `Protect tap` contra `Protector Tapete` casa por prefijo de cada
  token. Levenshtein solo lo puntúa bajísimo y es el caso más común en una libreta.
- **Iniciales y marcas al lado**: `Mayra T5`, `Fer ND`.
- **Coincidencia de precio, sólo para productos**: que `salePriceCents` sea exactamente el monto del
  renglón es una señal fuerte. Que no coincida **no** descarta: la gente cambia precios y da
  descuentos.
- **Historial**: un cliente con movimientos recientes es más probable que uno archivado hace un año.

Sin dependencias nuevas. Se escribe a mano y se prueba.

### 3. Las tres cosas que este cruce NUNCA puede hacer

Éstas no son preferencias, son la línea que separa una ayuda de una pérdida de dinero:

- **Nunca cambiar el monto.** Si `Protect tap` casa con un producto de catálogo a 79.90 y la libreta
  dice 85, **el monto es 85**. La libreta manda sobre el catálogo, siempre, sin excepción y sin
  importar la puntuación.
- **Nunca reasignar una deuda en silencio.** Anclar un renglón a un cliente existente decide **de
  quién es la deuda**. Un `AUTOMATIC` equivocado entre `Sirenza TS` y `Sandy` le carga la venta a
  otra persona. Por eso la pantalla tiene que enseñar a qué cliente se ancló, siempre, también en
  `AUTOMATIC`.
- **Nunca inventar un cliente ni un producto que no existe.** Si no hay candidato, no hay candidato.

### 4. Que la confianza suba, pero sin decidir nada

Un cruce `AUTOMATIC` puede subir la confianza numérica que se enseña. Lo que **no** puede hacer,
igual que en la Fase 1, es tocar `uncertainFields` ni `unreadableFields`, ni hacer registrable un
renglón que no lo era. **`isRegisterableLedgerOperation` no lee `confidence` ni lee cruces.** Si un
cruce pudiera desbloquear un renglón, un fallo del emparejador escribiría dinero sin que nadie lo
confirmara.

### 5. El ancla es un `customerId`, nunca un nombre — esto lo marcó Sol y es la parte delicada

Hoy la clave del lote se construye con el **nombre normalizado**, y `AiLedgerBatchPlanner` vuelve a
resolver el cliente con `existingCustomerMap[normKey]`. Si el cruce se implementa reescribiendo
`customerName`, pasan dos cosas a la vez y las dos son malas: cambia el destinatario del dinero y
cambia la clave de idempotencia.

Así que:

- Una aceptación se guarda como un **`customerId` explícito** en el renglón, **aparte** de
  `customerName`. El nombre leído no se toca: es la evidencia de lo que decía el papel.
- Ese `customerId` viaja al planificador y **manda sobre** la resolución por nombre. El planificador
  no vuelve a adivinar lo que una persona ya decidió.
- Ese `customerId` entra en el **digest de la clave del lote**, igual que el resto de lo aprobado.
  Dos páginas idénticas ancladas a clientes distintos son lotes distintos y deben poder escribirse
  las dos.
- **Ninguna sugerencia no aceptada llega a `operations`.** Si no se aceptó, no existe fuera de la
  pantalla.

Cruza cuando se construye la revisión, con los clientes y productos ya cargados de Room por quien
llama —igual que `LedgerBatchCoordinator` ya recibe `loadCustomersByNormalizedName`— y guarda el
resultado en el modelo de revisión.

En la pantalla, **sin rediseñar nada**: en el diálogo de edición que ya existe, si hay candidato,
enséñalo para que la persona lo toque y lo acepte. Aceptar es una confirmación humana, así que ahí
sí es legítimo que se limpie `CUSTOMER` de `uncertainFields` —es lo que `LedgerReviewEdits.applyCustomer`
ya hace hoy cuando alguien edita—, pero **sólo al aceptar**, nunca por el hecho de que el
emparejador puntuara alto. El semáforo por campo y el resumen de importación son de una fase
posterior y **no se empiezan aquí**.

### 6. Qué hace el ancla de producto, que es distinto

El planificador escribe hoy **una partida genérica** por renglón (`UncatalogedSaleBuilder`). Anclar
a un producto del catálogo, en esta fase, sirve para **una sola cosa**: rellenar la descripción con
el nombre real del producto y subir la confianza que se enseña. **No** cambia el monto, **no**
convierte la partida en una referencia al producto del catálogo, y **no** toca el inventario. Si
crees que hace falta más, dilo en el reporte en vez de hacerlo.

## Lo que NO es de esta fase

Segunda pasada sobre recortes dudosos; semáforo por campo; resumen de importación; protección de
duplicados; y desplegar la v4 al servidor. Nada de eso se toca.

## Pruebas

JVM, sobre `LedgerEntityMatcher` de verdad, no sobre una copia de la regla dentro del test:

- `Protect tap` + 79.90 contra un catálogo con `Protector Tapete` a 79.90 → `AUTOMATIC`.
- `Protect tap` sin precio que coincida → sigue casando por texto, pero más abajo.
- `Mayra T5` contra `Mayra T5` existente → `AUTOMATIC`.
- `Srenza TS` contra `Sirenza TS` y `Sandy` → **no** `AUTOMATIC` para ninguno; a lo sumo
  `SUGGESTED` para el primero. Que una letra de diferencia no reasigne una deuda.
- Dos clientes existentes igual de parecidos → **`UNRESOLVED`**, no se elige a cara o cruz.
- Catálogo vacío → `UNRESOLVED`, sin excepciones.
- Nombre vacío o sólo espacios → `UNRESOLVED`.
- **El monto nunca cambia**: renglón de 8500, producto de catálogo a 7990, cruce `AUTOMATIC` →
  el monto sigue siendo 8500. Ésta es la prueba más importante del encargo.
- **Un cruce no vuelve registrable un renglón** que tenía `CUSTOMER` en `unreadableFields`.
- 552 productos y 25 renglones no tardan una eternidad: pon una cota y mídela.

**Y las que faltaban, que son de integración y no de unidad (Sol):** el camino real
`revisión → aceptar o rechazar → confirm → plan`, comprobando el `customerId` que se escribe de
verdad en Room y a qué ventas se asignan los abonos. En concreto:

- Un `AUTOMATIC` que **nadie aceptó** no cambia ni una operación ni la clave del lote.
- Aceptar un ancla escribe la venta en **ese** `customerId`, no en el que saldría del nombre.
- Un abono anclado se asigna a las ventas de **ese** cliente y de ningún otro.
- Dos candidatos casi empatados → `UNRESOLVED`, aunque el primero pase de 0.90.
- Dos nombres distintos que colapsan al mismo normalizado no se fusionan solos.
- Si el catálogo cambia entre revisar y confirmar, el resultado no cambia y **no** se produce un
  falso "ya estaba registrado" por una clave que se movió.

**Rompe al menos dos de tus pruebas a propósito** y comprueba que se ponen rojas. En este proyecto
una prueba en verde ya mintió seis veces.

## Gate

```
npm --prefix functions run release:check
```

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Cuenta las pruebas desde los XML, no del texto de Gradle. **El teléfono A059P NO está conectado:
Diego no está en casa.** Hay un emulador `Medium_Phone` (API 37) arrancado y desbloqueado
(`emulator-5554`); si corres instrumentadas, pasa
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

Puerta del idioma y paridad de claves si tocas `strings.xml` (hoy 611 = 611; cuenta, no confíes).

**Sin commits y sin deploys.**

## Cómo se reporta

Los comandos exactos y los números reales. Di lo que no hiciste. Y di **con qué mediste los
umbrales**: si los dejaste en 0.90 y 0.70 porque venían en el encargo, dilo tal cual en vez de
presentarlo como una decisión medida.
