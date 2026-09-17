# v41 · bloque 1, ronda 3 — todo o nada (OpenCode)

**Decisión de Diego, 2026-09-16: opción B.** Una página se registra **entera o no se registra**.
Sustituye a la propuesta de idempotencia por renglón (sección D de tu reporte), que **no se
implementa**. Sol la revisó y la dio por sólida, pero ofreció esta alternativa *"con igual rigor
financiero"* y mucho menos cambio en el núcleo.

**Lo que ya está bien y no se toca:** objeciones alimentadas por la lista completa de clientes y
productos, la huella de confirmación, la cantidad en la comparación de precio, y el cableado de
`blockedLineIndices`. Gate de Claude: **1194 pruebas JVM, 0 fallos.**

**No commitees, no despliegues, no generes AAB.**

## Por qué

La clave de idempotencia se calcula sobre los renglones que se escriben en cada intento. Si ese
conjunto cambia entre un intento cuyo commit se perdió (el proceso murió justo después de guardar)
y un reintento, sale otra clave y **un cargo se escribe dos veces**. Este defecto **ya existía antes
de v41**: los renglones no registrables se omitían, así que el lote ya podía cambiar.

La forma barata de cerrarlo: **que el conjunto no pueda cambiar**. Si una página sólo se registra
cuando todos sus renglones están resueltos, y la clave depende de la sesión y no del contenido, un
reintento siempre reconoce lo ya escrito.

## 1 · "Registrar" sólo con todo resuelto

Un renglón está **resuelto** si cumple una de estas:

- es registrable y no tiene objeción;
- es registrable y su objeción está confirmada (`LedgerObjectionConfirmation.isConfirmed`, con huella);
- la persona lo **borró** de la revisión.

Mientras quede **uno solo** sin resolver, el botón "Registrar" está desactivado y dice por qué y
cuántos faltan. **Se acaba el "M omitidos"**: ya no se omite nada en silencio. Quita ese conteo y su
mensaje, y cualquier texto que ya no se use (en `values/` **y** `values-en/`).

Esto vale también para lo que hoy no es registrable (sin importe, sin cliente, etc.): o se corrige o
se borra. La revisión guiada ya recorre los renglones uno a uno; úsala.

## 2 · Defensa en profundidad en la escritura

`LedgerBatchCoordinator.confirm` y `AiLedgerBatchPlanner.plan` **rechazan el lote entero** si llega
algún renglón sin resolver. **No lo saltan**: saltar es exactamente lo que abría el agujero. Un
llamador futuro que se equivoque debe recibir un rechazo, no una escritura parcial.

## 3 · Una clave por sesión, independiente del contenido

La clave de idempotencia de la página pasa a derivarse **sólo** de `(businessId, importSessionId)`,
con el mismo mecanismo que ya usa el proyecto (UUID determinista con prefijo de longitud). **No puede
incluir** importes, clientes, fechas, descripciones ni el conjunto de renglones: todo eso es editable
después de un commit perdido.

- `importSessionId` ya existe, es local, se persiste en `LedgerReviewSaver` y no viaja al servidor:
  **no toca el contrato**. Que llegue al coordinador como parámetro **obligatorio**.
- Los IDs de venta, item, movimiento, pago y asignación siguen derivándose de la clave de la página y
  del `lineIndex`, como hoy (`SaleOperationIds`). Como la página ya no se escribe a trozos, eso
  basta.
- **No hace falta migración**: la tabla `operations` ya tiene la clave como PK. No toques la versión
  de Room ni los tres builders de `"cuentivo.db"`.

## 4 · Reintento tras un commit perdido

Si al registrar `alreadyRecorded(clave)` dice que la página ya está:

- **no se escribe nada**;
- la pantalla dice claramente **"Esta página ya estaba registrada"** y cierra la revisión como un
  registro exitoso;
- si la persona había editado algo después de ese commit, **esas ediciones no se aplican**, y el
  mensaje tiene que decirlo: lo registrado se corrige con un movimiento compensatorio, como todo lo
  financiero aquí. Nunca con `UPDATE`.

## 5 · Revisiones guardadas por una versión anterior

Una revisión que se guardó con la clave antigua (basada en el contenido) y se restaura con esta
versión no permite saber qué se escribió. Añade una marca de versión al estado de
`LedgerReviewSaver`. **Una revisión restaurada sin esa marca no se puede registrar**: la pantalla
explica que hay que revisar el libro antes y ofrece descartarla. La integridad del dinero va antes
que conservar un borrador.

## 6 · Nombres: "Luz" dentro de "Foco Luz"

Tu propuesta del punto 4 queda **aprobada**:

- Antes de buscar el nombre en el `rawText`, quita las palabras que vienen de la evidencia de
  producto del propio renglón (`description` e `items[].description`), además de lo que va entre
  paréntesis.
- **"María" en el papel contra la clienta "María García" sigue objetando.** Tenías razón: cualquier
  regla que lo exima exime también "Rosa" por "Rosa Martínez". No se intentan resolver apodos.

## Pruebas obligatorias

Todas por el camino real (coordinador, planificador, `LedgerReviewSaver`), con datos **inventados**,
y **cada una tiene que fallar con el código de hoy**:

1. Con un renglón sin resolver, "Registrar" está desactivado; al resolverlo, se activa.
2. El coordinador y el planificador **rechazan** un lote con un renglón sin resolver (no lo saltan).
3. **Commit perdido:** se escribe la página; con la misma sesión se reintenta con contenido editado
   → **no se escribe nada** y el resultado es "ya registrada".
4. Misma sesión, contenido distinto → **misma clave**.
5. Sesión distinta, contenido idéntico → **clave distinta** (una segunda foto legítima se escribe; el
   detector de duplicados avisa, como hoy).
6. Revisión restaurada sin marca de versión → **no se puede registrar**.
7. "Luz" / "Foco Luz" → objeta; "Luz" con el papel diciendo "Luz 99" → no objeta.

Comprobación por mutación en cada una.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo desde los XML (referencia **1194**). Si tocas `strings.xml`, paridad ES/EN y en inglés sin
apóstrofos. **No lances Gradle mientras otro corre en esta carpeta.** Las instrumentadas las corre
Claude en el emulador: tú asegúrate de que `compileDebugAndroidTestKotlin` compila.

## Entrega

**Escribe el reporte completo en `C:/tmp/oc-ronda3-reporte.md`** (tu pantalla no guarda historial):
qué cambiaste con archivo y línea, el comando del gate con su salida, el conteo, la mutación de cada
prueba y qué no hiciste. Luego paras.
