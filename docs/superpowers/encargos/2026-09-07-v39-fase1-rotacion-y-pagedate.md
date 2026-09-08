# v39 · Fase 1 — Que la revisión sobreviva a girar el teléfono, y la fecha de hoja (encargo para OpenCode)

**Fecha:** 2026-09-07. **Implementa:** OpenCode (GLM-5.3). **Segunda mente:** Claude. **Revisa el diff:** Sol (Codex).
**Base:** `a2c810e`, etiquetado `baseline-cuentivoai-v39-1.20.1`.

**Regla que manda sobre todas: no rompas lo que ya funciona.** La importación de libreta está
publicada. Sin refactors grandes, sin sistema paralelo, sin migración de esquema, y **sin tocar
`functions/`**: esta fase es sólo Android.

## Criterio de aceptación, y va primero

**Una pieza no está hecha hasta que se ve funcionando en el emulador.** Si al terminar giras el
teléfono a media revisión y se pierde el borrador, la fase no está entregada aunque sus pruebas estén
en verde. Corre las instrumentadas antes de darte por terminado.

Y dos cosas más:

1. **No borres comentarios.** En este repo el *porqué* vive junto al código y no lo cubre ningún test.
2. **No reescribas bloques enteros: edítalos.** Ya se perdió así la regla de convertir pesos a
   centavos, y un `38` habría entrado como 38 centavos.

## Objetivo A — La revisión sobrevive a que recreen la Activity

### El problema, exacto

`AiDebugLabScreen.kt:179` — `var model by remember { mutableStateOf(AiDebugLabModel()) }`.

`remember`, no `rememberSaveable`. Al girar el teléfono la Activity se recrea, el modelo vuelve a
`AiDebugLabModel()` y **se pierde la lectura entera de la página y todas las correcciones que la
persona llevara hechas**. La foto sí sobrevive (`ledgerCaptureFilePath` es `rememberSaveable`), así
que la única salida es volver a leerla: **otra llamada a Gemini, que se paga**.

### Cómo hacerlo, y por qué así

**Con `rememberSaveable` y un `Saver` propio. No metas `androidx.lifecycle.ViewModel` ni
`SavedStateHandle`.** Este repo no tiene ni uno: no hay Hilt, no hay DI, el cableado es manual en
`MainActivity`, y `DashboardViewModel` es deliberadamente una clase plana. Introducir esa
arquitectura aquí sería un refactor mayor disfrazado de arreglo.

**Guarda sólo lo que no se puede recalcular.** Un `Bundle` no es un sitio donde quepa todo, y pasarse
del límite de la transacción tumba la app:

- **Sí se guarda:** `sessionId`, `businessId`, las `operations` con lo que la persona haya editado,
  la fecha de página elegida, `todayEpochDay`, `unreadTextRemaining`, `pageIncomplete` y los
  `discards`. Eso es lo irrepetible: la lectura de Gemini más el trabajo humano.
- **NO se guarda, se vuelve a cargar de Room al restaurar:** los clientes candidatos, los 552
  productos, los saldos y lo ya registrado para duplicados. Son derivables y no caben.
- **NUNCA se guarda:** la imagen, base64, ni nada que se parezca a una foto. La ruta del archivo ya
  se guarda hoy y con eso basta.

Al restaurar, **no vuelvas a llamar al callable**. Ni una vez. Ese es el defecto que esta fase existe
para eliminar; volver a extraer "por si acaso" lo reintroduce con otra cara.

### Los caminos que hay que probar, todos

Rotar; irse a segundo plano y volver; que el sistema mate el proceso y se restaure; navegar fuera y
volver; el botón atrás; y **rotar con el borrador a medio editar**, que es el caso real.

**Y el que más importa:** restaurar **no puede** terminar registrando dos veces. La clave del lote y
la reserva de operación ya lo cubren, pero escribe la prueba igualmente: restaurar, confirmar, y que
haya exactamente un movimiento en Room.

## Objetivo B — La fecha de la hoja, sin que decida sola

### Qué hay hoy

El servidor detecta la fecha global de la página y la manda en `pageDate` (`{ date: {year, month,
day}, confidence }`). **El cliente la acepta entre las llaves permitidas y nunca la lee**:
`LedgerExtractionPayload` no la tiene. La persona sigue escribiéndola a mano.

### Qué hay que construir

Parsearla y **ofrecerla, jamás aplicarla sola**.

- `LedgerExtractionPayload` gana la fecha detectada con su confianza; el estado de la revisión la
  lleva.
- En la tarjeta de fecha de página que ya existe, cuando el campo esté vacío y haya una fecha
  detectada, enséñala para que la toquen: *"CuentivoAI leyó 2026-07-30 en la hoja"*.
- **Nunca se rellena sola, ni con confianza 0.99.** Una fecha equivocada manda diez movimientos al
  día que no es, y en un libro donde nada se edita eso se arregla con diez movimientos
  compensatorios. Que la ofrezca es ayuda; que la escriba sola es adivinar.
- Si la confianza es baja, se ofrece igual pero **dilo en el propio texto**, para que quien la toque
  sepa que está confirmando algo dudoso.

### La regla que no se puede romper

**Un renglón con fecha propia NUNCA cambia porque se toque la fecha de página.** `resolveDate` ya lo
hace bien: sólo un renglón sin fecha toma la de la hoja, y una fecha explícita inválida no cae a la
de página. **No toques esa función**; sólo comprueba con una prueba que sigue siendo verdad después
de tus cambios.

Corregir la fecha de página **sí** debe actualizar los renglones que dependían de ella, y eso ya
ocurre porque la fecha se resuelve al planificar y no se congela en el renglón. Pruébalo.

## Lo que NO es de esta fase

Métricas, segunda pasada, matching mejorado, preprocesado de imagen, panel de depuración, ítems
nativos y la comparativa v38/v39. Nada de eso se empieza. Tampoco se toca `functions/`, ni se
despliega, ni se genera AAB.

## Pruebas

JVM, contra el código de producción y no contra una copia de la regla:

- El `Saver` va y vuelve sin perder nada: operaciones editadas, fecha de página, sesión y negocio.
- Un borrador con 25 renglones con `rawText`, `items` y `region` **cabe holgadamente** en lo que se
  guarda: mide los bytes y ponle una cota.
- La fecha detectada se parsea de la respuesta v4; en v3 no viene y no rompe nada.
- Una `pageDate` con día inexistente o en el futuro **no se ofrece**.
- Un renglón con fecha propia no cambia al fijar la fecha de página.
- Un renglón sin fecha sí la toma.
- Una fecha de página inválida no vuelve registrable ningún renglón.

Instrumentadas en el emulador:

- Girar con la revisión abierta conserva las operaciones y las ediciones. **Sin llamar al callable**:
  usa un doble que cuente invocaciones y afirma que sigue en una.
- Girar con un renglón ya corregido conserva la corrección.
- Restaurar y confirmar escribe **una sola vez**.
- La fecha detectada se ofrece y **no** se aplica hasta que la tocan.

**Rompe al menos dos de tus pruebas a propósito** y comprueba que se ponen rojas. En este proyecto
una prueba en verde ya mintió seis veces, y dos de ellas afirmaban el comportamiento peligroso como
correcto.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Cuenta las pruebas desde los XML de `app/build/test-results/`, no del texto de Gradle. Hoy son
**1119 y 0 fallos**: ninguna existente puede cambiar de expectativa; si alguna falla, es que rompiste
algo. **El teléfono A059P NO está conectado.** Emulador `Medium_Phone` (API 37) arrancado y
desbloqueado, `emulator-5554`. Para instrumentadas,
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, y corre tus clases por su nombre:
la suite completa aborta en `ProductPhotoSourceTest`.

Paridad de claves y puerta del idioma si tocas `strings.xml` (hoy 625 = 625; cuenta, no confíes).

**Sin commits y sin deploys.** Los hace Claude después de verificar.

## Cómo se reporta

Los comandos exactos que corriste y los números reales. Di lo que no hiciste. Y di explícitamente
**qué pasa al girar el teléfono** antes y después de tu cambio, y **cuántas veces se llama al
callable** en ese camino.
