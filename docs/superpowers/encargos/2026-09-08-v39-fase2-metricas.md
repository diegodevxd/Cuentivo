# v39 · Fase 2 — Medir antes de complicar (encargo para OpenCode)

**Fecha:** 2026-09-08. **Implementa:** OpenCode (GLM-5.3). **Segunda mente:** Claude. **Revisa el diff:** Sol (Codex).
**Base:** `82df2c0`.

**Regla que manda sobre todas: no rompas lo que ya funciona.** Sin refactors grandes, sin tocar
`functions/`, sin desplegar y sin generar AAB. Esta fase es sólo Android.

Tres cosas de las entregas anteriores: **no borres comentarios**; **no reescribas bloques enteros,
edítalos**; y **corre las instrumentadas antes de darte por terminado**.

## Por qué esto va antes que la segunda pasada

La siguiente mejora grande —volver a preguntarle a Gemini por los renglones dudosos— cuesta dinero
por foto y puede empeorar las cosas tanto como mejorarlas. **No hay forma de saber si sirve sin una
medida de antes.** Esta fase construye esa medida y nada más.

## Lo que se guarda, y esto ya lo aprobó Diego palabra por palabra

**Una fila por importación.** Nunca por renglón: una fila por renglón, con su campo corregido, **es
la libreta reconstruible**, y eso no se guarda ni aunque sea local.

Local en Room. **Nunca a Firebase Analytics, nunca a Crashlytics, y nunca al outbox de
sincronización.** Estos datos no salen del teléfono.

Por importación:

- un id local, y **la hora redondeada** en que terminó — no el instante exacto, y esto lo explica el
  apartado de abajo;
- detectados, listos sin tocar, corregidos, descartados, ilegibles, duplicados sugeridos;
- **cuántos** campos se corrigieron de cada tipo: cliente, producto, monto, fecha, tipo. Sólo el
  conteo;
- llamadas a Gemini y milisegundos que tardó;
- la versión de contrato que respondió;
- cómo terminó: registrada o abandonada.

Y para poder calibrar la confianza más adelante, un **histograma agregado**: por cada cubeta
—`>=0.90`, `0.70–0.89`, `<0.70`— cuántos campos cayeron ahí y en cuántos hubo corrección. Seis
números por importación, sin orden y sin renglón. Con eso sale la tabla de precisión real.

## El fallo de privacidad que este diseño tenía, y que hay que evitar

**Una marca de tiempo exacta es una clave de unión.** Lo encontró Sol y la primera versión de este
encargo lo tenía mal. Guardar `finishedAtEpochMillis` al milisegundo permite cruzar la fila de
métrica contra las ventas y los movimientos creados en ese mismo instante —que están en la misma
base— y recuperar de ahí la persona, el monto y la operación. La promesa de "esto no guarda datos de
nadie" era falsa: no los guarda, los **señala**.

Así que:

- **La hora se redondea.** A la hora en punto es suficiente para saber si las importaciones de la
  tarde salen peor que las de la mañana, y deja de servir para unir con nada.
- **Nada de `lineIndex`, nada de pares ordenados por campo.** Un par por renglón, en orden, es una
  huella de la página aunque no lleve ni un nombre. Lo de la confianza se guarda como **histograma
  agregado**: cuántos campos cayeron en cada cubeta y de cuántos hubo corrección. Tres números por
  cubeta, sin orden y sin fila.

## Lo que NO se guarda, y va como comentario en el código

Ni la foto, ni base64, ni `rawText`, ni nombres de clientes o productos, ni montos, ni el id del
cliente, ni el `businessId` —que por sí solo ya identifica a una persona—, ni el texto que había
antes y después de una corrección.

**Y una prohibición explícita para quien venga después:** no añadas "monto original contra monto
corregido" para calibrar mejor. Suena razonable y es exactamente la libreta. Si algún día hace falta
esa precisión, se decide de nuevo y con esto delante.

## Cómo guardarlo

Tabla nueva en Room. Es **aditiva** —un `CREATE TABLE`, nada que altere lo que ya existe— pero sigue
el procedimiento completo de `CLAUDE.md`, que está ahí porque saltárselo rompe sólo una ruta y en
silencio:

1. Sube `version` y escribe la `Migration`, agregándola a `CUENTIVO_MIGRATIONS`.
2. **Regístrala en los tres builders de `"cuentivo.db"`**: `MainActivity.onCreate`,
   `SyncWorker.doWork` y `CollectionReminderWorker.doWork`. Olvidar uno hace que truene sólo esa
   ruta, y las dos de WorkManager pueden correr en un proceso donde la Activity nunca arrancó.
   Compruébalo con `grep -rn '"cuentivo.db"' app/src/main` en vez de fiarte de esta lista.
3. **Añade la entidad y su DAO a `@Database`**, y **commitea el JSON nuevo de `app/schemas/`**: los
   dos se olvidan a menudo y el segundo es el que permite verificar nada.
4. Verifica el SQL contra el `createSql` del esquema recién exportado. Room valida un hash de
   identidad, y no sólo el `CREATE TABLE`: nulabilidad, valores por defecto, índices y afinidades
   tienen que coincidir exactamente. Si difiere en un backtick, la migración corre y **aun así**
   falla al abrir.
5. **Prueba abrir la base migrada desde los tres sitios**, no sólo con `MigrationTestHelper`. Los dos
   Workers son los que fallan en silencio, porque nadie los mira hasta que la sincronización deja de
   funcionar.

**No hay `fallbackToDestructiveMigration` en este proyecto y no debe haberlo.**

## Cuándo se escribe, y cómo no perderla ni duplicarla

La primera versión de este encargo decía "al terminar, envuelto en `runCatching`". Eso protege el
registro pero **no protege la métrica**, y Sol lo desarmó: entre confirmar el lote y escribir la
fila hay una ventana en la que el proceso puede morir; al abandonar, lanzar la escritura desde el
scope de Compose y cerrar la pantalla acto seguido **la cancela**; y una recreación de la Activity
no es un abandono, pero puede reintentar la confirmación y escribir la métrica dos veces.

Así que se hace en dos tiempos, con **un id estable**:

1. Cuando llega la extracción se escribe ya una fila **en curso**, con un id derivado de la sesión de
   importación —no aleatorio en cada intento, o el reintento crea una segunda.
2. Al terminar se **actualiza** esa misma fila a *registrada* o *abandonada*, de forma idempotente:
   escribirlo dos veces deja el mismo resultado.

Ventajas de hacerlo así: una importación que muere a medias deja rastro de que existió, que es
justamente el caso que más interesa medir; y ninguna termina contada dos veces.

**Una recreación de la Activity NO se cuenta como abandono.** Hoy la revisión sobrevive a eso; contar
ahí un abandono mediría un defecto que ya no existe.

**Y sigue en pie:** escribir la métrica no puede hacer fracasar el registro. Va envuelta en
`runCatching`, igual que el encolado al outbox y por la misma razón.

**Retención.** Esto crece para siempre si nadie lo mira. Borra lo más viejo pasado un límite —una
cantidad de filas o una antigüedad, elige y **di por qué**—. Una tabla de diagnóstico que llena el
teléfono de alguien es un defecto, no una métrica.

## De dónde salen los números

Casi todos ya existen y no hay que inventarlos: `LedgerImportSummaryCalculator` calcula detectados,
listos y pendientes; `review.summary.excludedRowsCount` da los excluidos; el detector de duplicados
da los sugeridos.

Lo que **no** existe es saber qué campos tocó la persona. Hace falta comparar la lectura original
contra la final.

**Dónde vive esa lectura original:** dentro de `SavedLedgerReview`, junto a la revisión, e
**inmutable**. Hoy el estado guardado sólo conserva la versión ya editada, así que una recreación se
llevaría la referencia y las correcciones se contarían mal —o no se contarían.

**Cuatro formas de contar mal, y las cuatro hay que resolverlas:**

- **Abrir un renglón y guardarlo sin cambiar nada** resuelve su duda pero no corrige ningún valor.
  No es una corrección, y contarlo infla el número justo en el sentido que haría parecer que la
  segunda pasada hace falta.
- **Cambiar y deshacer** vuelve al valor original. Comparando sólo los extremos no se ve, y está
  bien que no se vea: el dato final es el que el modelo dio.
- **Lo que normaliza la app sola** —el monto pasando por `LedgerAmountInput`, un cliente anclado por
  aceptar una sugerencia— no lo corrigió la persona leyendo el papel. Decide si cuenta y **di cuál
  elegiste**; aceptar una sugerencia sí es un acto humano, reformatear un número no.
- **Un renglón eliminado** cuenta como descartado y no como corregido, aunque antes se hubiera
  editado.

Y define la comparación: dos fechas iguales escritas distinto son la misma fecha, y dos nombres que
sólo difieren en acentos o espacios ya tienen una forma canónica en `CustomerNameNormalizer`.

## Lo que NO es de esta fase

Segunda pasada, matching mejorado, preprocesado de imagen, panel de depuración, ítems nativos y la
comparativa v38/v39. Nada de eso se empieza. Tampoco una pantalla para ver las métricas: por ahora
se consultan con `adb` y `sqlite3`, y ya habrá tiempo de decidir si merecen interfaz.

## Pruebas

JVM, contra el código de producción:

- El contador de correcciones cuenta un cambio real de cliente, monto, fecha, tipo y producto.
- **Abrir un renglón y guardarlo sin tocar nada no cuenta como corrección.** La prueba que evita que
  el número mienta hacia arriba.
- Un renglón eliminado cuenta como descartado, no como corregido.
- Las cubetas de confianza reparten bien en los bordes: 0.90 y 0.70 exactos.
- Un campo sin confianza no entra en ninguna cubeta, en vez de caer en la más baja.
- **Nada de lo que se guarda contiene texto libre.** Escribe una prueba que recorra la fila
  construida y falle si aparece un nombre, un monto o `rawText`. Es la prueba que protege la promesa
  que Diego le hace a Play.

- Cambiar un valor y deshacerlo no cuenta como corrección.
- Un renglón corregido y luego eliminado cuenta como descartado, no como las dos cosas.
- **La hora guardada está redondeada**: dos importaciones en el mismo minuto no se distinguen por
  ella. Es la prueba que protege contra cruzar la métrica con las ventas de ese instante.

Instrumentadas en el emulador:

- La migración corre sobre una base de la versión anterior y la abre sin fallar, **y se abre también
  desde los dos Workers**, no sólo con `MigrationTestHelper`.
- Una importación registrada escribe exactamente una fila; una abandonada también.
- **Reintentar la confirmación con el mismo id deja una sola fila**, no dos.
- Una recreación de la Activity durante la revisión conserva la lectura original y **no** cuenta un
  abandono.
- Cancelar y cerrar la pantalla **persiste** la fila: comprueba que está en la base, no que se llamó
  a la función.
- **Que falle la escritura de la métrica no impide registrar la libreta.** Fuérzalo.
- La retención borra lo viejo y conserva lo reciente.
- Las filas de métrica **no** aparecen en `sync_operations`.

**Rompe al menos dos de tus pruebas a propósito** y comprueba que se ponen rojas.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Cuenta desde los XML de `app/build/test-results/`. Hoy son **1134 y 0 fallos**: ninguna existente
puede cambiar de expectativa. **El A059P NO está conectado.** Emulador `Medium_Phone` (API 37) en
`emulator-5554`. Para instrumentadas,
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, y corre tus clases por su nombre.

Paridad de claves y puerta del idioma si tocas `strings.xml` (hoy 627 = 627; cuenta, no confíes).

**Sin commits y sin deploys.**

## Cómo se reporta

Comandos exactos y números reales. Di lo que no hiciste, di qué retención elegiste y por qué, y
**enumera cada columna de la tabla nueva** para que se pueda comprobar de un vistazo que ninguna
guarda algo de nadie.
