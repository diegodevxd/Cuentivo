# Libretas desordenadas — Fase 3: el producto, el semáforo y el resumen (encargo para AGY)

**Fecha:** 2026-09-07. **Implementa:** AGY. **Segunda mente:** Claude. **Revisa el diff:** Sol (Codex).
**Base:** `1b50ab9`.

**Regla que manda sobre todas: no rompas lo que ya funciona.** La importación está en el AAB `v37`
de prueba cerrada. Sin refactors grandes, sin sistema paralelo, sin migración.

## Tres avisos de las entregas anteriores

1. **No borres comentarios.** En la Fase 1 se perdieron los que explicaban por qué `amountCents`
   nunca puede ser número JSON y por qué una región mal formada descarta la región y no la
   operación. En este repo el *porqué* vive junto al código y no lo cubre ningún test.
2. **No reescribas bloques enteros: edítalos.** El prompt se reescribió y se llevó la regla de
   convertir pesos a centavos. Un `38` habría entrado como 38 centavos.
3. **No escribas una prueba que afirme el comportamiento peligroso.** En la Fase 2 dos pruebas
   anclaban a clientes inexistentes y esperaban que se crearan; estaban en verde protegiendo el
   defecto. Antes de dar una prueba por buena, pregúntate qué pasaría si eso ocurriera de verdad.

## Qué existe ya y no hay que rehacer

- `LedgerEntityMatcher.matchCustomer` y `matchProduct`, puros, con umbrales y margen.
- `LedgerCustomerMatching.matchesFor`, que decide **a qué renglones** vale la pena ofrecer cliente.
- `LedgerReviewEngine.setOperationCustomerAnchor`, que sólo puede llamarse tras aceptación humana.
- El diálogo de edición ya enseña el candidato de cliente y lo ancla al tocarlo.

## Lo que hay que construir

### 1. El producto también se ofrece

`matchProduct` existe y **no lo llama nadie**. Diego tiene 552 productos con precio.

Haz para productos lo mismo que ya está hecho para clientes: un objeto puro que decida a qué
renglones ofrecerles un producto, y la oferta dentro del mismo diálogo de edición.

Reglas, y las dos primeras no se negocian:

- **El monto NUNCA cambia.** Si `Protect tap` casa con un producto de catálogo a 79.90 y la libreta
  dice 85, el monto es 85. La libreta manda sobre el catálogo, siempre.
- **Aceptar un producto sólo rellena `description`.** No convierte la partida en una referencia al
  catálogo, no toca inventario, no toca precio. Si crees que hace falta más, **dilo en el reporte en
  vez de hacerlo**.
- **Y por eso el texto tiene que decir la verdad (lo marcó Sol).** El planificador seguirá escribiendo
  `productId = null`, cantidad 1 y el monto entero como precio: no descuenta existencias ni asocia
  costo ni margen. Una etiqueta que diga "es este producto" promete un vínculo que no existe y deja
  al dueño creyendo que su inventario se movió. Escribe la acción como lo que hace de verdad —
  **usar ese nombre**, no vincular un producto. Y ponlo también en una prueba: tras aceptar, el plan
  sigue con `productId` nulo y sin ningún movimiento de inventario.
- No se ofrece nada cuando `description` ya coincide exactamente con un producto: no hay nada que
  decidir y cada oferta de más hace que la gente acepte sin leer.
- Que el precio del catálogo coincida con el monto del renglón es una señal fuerte a favor; que no
  coincida **no descarta**.

### 2. Semáforo por campo, sin rediseñar la pantalla

Hoy un renglón dudoso se pinta de error entero. Tiene que verse **qué campo** necesita atención:

- **Verde / ✓** — el campo está resuelto **y sirve para registrar**.
- **Amarillo / ?** — `uncertainFields`: se leyó, pero el modelo no lo firma.
- **Rojo** — `unreadableFields`, un campo que falta o no es válido, o un error financiero.

**Ojo con el verde, que es donde Sol encontró la trampa.** Las dos listas vacías **no** significan
registrable: a un renglón le puede faltar `proposedKind`, el nombre del cliente o un `amountCents`
que pase el formato canónico, y `isRegisterableLedgerOperation` lo rechaza igual. Pintar eso de
verde le dice a la persona que ya está, y luego no se registra. El color de un campo se deriva de
**las mismas condiciones que usa ese predicado**, campo por campo, no sólo de las listas.

Y los diagnósticos financieros (`ZERO_AMOUNT`, `OVERFLOW`, `PAYMENT_WITHOUT_DEBT`,
`PAYMENT_EXCEEDS_DEBT`) van todos al campo **monto**: es de lo que hablan. Ponlo por escrito en el
objeto puro para que no quede a interpretación.

`confidence` de la v4 puede **matizar** el amarillo, nunca cambiar el color de verde a amarillo ni
al revés: **la autoridad siguen siendo las dos listas**, como en las fases anteriores.
`isRegisterableLedgerOperation` no lee `confidence` y eso no cambia aquí.

La decisión de qué color va cada campo es **lógica, no dibujo**: vive en un objeto puro de
`ai/ledger/` o `domain/` que se pueda probar en JVM, y el Composable sólo pinta lo que ese objeto
dice. `CLAUDE.md`: "ninguna decisión vive en el Composable".

Al tocar "revisar", el diálogo enseña el recorte grande —ya lo hace— y **debajo, primero, los campos
que necesitan corrección**. Los que están bien no estorban.

### 3. El resumen de importación, que hoy miente

El botón dice hoy `Crear 0 clientes y registrar 0 movimientos` cuando se detectaron nueve renglones
y ninguno está confirmado. Es cierto y es inútil: parece que no se leyó nada.

Tiene que decir lo que pasó y lo que falta. Algo de esta forma, con las palabras que mejor suenen en
español de México:

```
9 movimientos detectados
1 listo para registrar · 8 por revisar
```

Y el botón principal debe decir **exactamente lo que va a escribir**: `Registrar 1 movimiento`, no
"registrar la página". Cuando no hay ninguno listo, el botón está apagado y lo que se ofrece es
`Revisar los 8 pendientes`, que ya existe.

**Define cada número antes de escribir el código, y déjalo escrito en el objeto puro**, porque Sol
demostró que los tres se pueden contar mal:

- **Detectados** son los renglones que el modelo devolvió y sobrevivieron al parser
  (`review.operations`). Los `discards` **no** son renglones detectados: son respuestas malformadas
  que se tiraron, y algunas ni siquiera traen `lineIndex`. Si los sumas, el número deja de
  corresponder con nada que la persona pueda ver en su hoja.
- **Listos para registrar** son los que pasan `isRegisterableLedgerOperation` **y** no tienen ningún
  diagnóstico financiero. Un renglón registrable pero con `PAYMENT_EXCEEDS_DEBT` no está listo.
- **Por revisar** es el resto de los detectados. Ni los descartes ni las exclusiones del modelo
  cuentan aquí: un subtotal o una nota que se excluyó a propósito no es trabajo pendiente de nadie.

**Y el caso que hace mentir a todo lo anterior:** `canConfirm` bloquea la **página entera** en cuanto
hay un error financiero. Así que puede haber "1 listo" y escribirse **cero**. Si `canConfirm` es
falso, el botón **no puede prometer un número**: se apaga y el aviso dice por qué no se va a escribir
nada. Esa es literalmente la mentira que este apartado existe para eliminar; no la cambies por otra.

Cuando la página venga incompleta (`pageIncomplete` o `unreadTextRemaining`) hay renglones que nadie
puede contar. Dilo con palabras —"puede quedar contenido sin leer"— y **nunca con un número**.

Los **plurales**: `1 movimiento` y `2 movimientos`. Usa `plurals` de Android, no concatenes una "s".
En inglés, sin apóstrofos.

### 4. Idioma

Todo texto nuevo va en `values/strings.xml` **y** en `values-en/strings.xml`. La paridad debe cuadrar
(hoy 612 = 612; **cuenta, no confíes en ese número**) y la puerta del idioma de `CLAUDE.md` tiene que
salir vacía salvo las etiquetas de animación de Compose.

## Lo que NO es de esta fase

Protección de duplicados; segunda pasada sobre recortes dudosos; calidad de imagen y rotaciones
espejadas; y desplegar la v4 al servidor. Nada de eso se toca.

## Pruebas

JVM, contra el código de producción y no contra una copia de la regla:

- Producto: `Protect tap` a 79.90 con catálogo `Protector Tapete` a 79.90 → se ofrece.
- **El monto no cambia** al aceptar un producto cuyo precio de catálogo es otro. La prueba más
  importante del encargo.
- Aceptar un producto **no** vuelve registrable un renglón que tenía `CUSTOMER` en
  `unreadableFields`.
- Semáforo: un campo en `uncertainFields` es amarillo; en `unreadableFields`, rojo; ninguno, verde.
- Semáforo: `confidence` bajísima con las dos listas vacías **sigue siendo verde**. La confianza no
  manda.
- Resumen: 9 detectados, 1 registrable, 3 excluidos → dice 1 listo y **5** por revisar, no 8.
- Resumen: 0 registrables → botón apagado.
- **Resumen con `canConfirm` en falso teniendo un renglón listo → el botón no promete ningún número.**
  Es el caso que Sol encontró y el que más importa de esta lista.
- Descartes sin `lineIndex` no se cuentan como detectados.
- Página incompleta → aviso con palabras, sin número.
- Un renglón registrable pero con `PAYMENT_EXCEEDS_DEBT` cuenta como por revisar, no como listo.
- Tras aceptar un producto, el plan sigue con `productId` nulo, el monto intacto y sin movimiento de
  inventario.
- Un renglón sin `proposedKind` y con las dos listas vacías **no** se pinta de verde.
- Plurales: 1 y 2 movimientos, en los dos idiomas.

Instrumentada en el emulador: que el resumen y el semáforo se pinten con lo que dice el objeto puro,
y que aceptar un producto rellene la descripción sin tocar el monto.

**Rompe al menos dos de tus pruebas a propósito** y comprueba que se ponen rojas.

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

Cuenta las pruebas desde los XML, no del texto de Gradle. **El A059P NO está conectado: Diego no
está en casa.** Emulador `Medium_Phone` (API 37) arrancado y desbloqueado, `emulator-5554`. Para
instrumentadas, `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. La suite completa
aborta en `ProductPhotoSourceTest`: corre tus clases por su nombre.

**Sin commits y sin deploys.**

## Cómo se reporta

Comandos exactos y números reales. Di lo que no hiciste.
