# Libretas desordenadas — Fase 4: duplicados y la foto (encargo para AGY)

**Fecha:** 2026-09-07. **Implementa:** AGY. **Segunda mente:** Claude. **Revisa el diff:** Sol (Codex).
**Base:** `ee44d9e`.

**Regla que manda sobre todas: no rompas lo que ya funciona.** La importación está en el AAB `v37`
de prueba cerrada. Sin refactors grandes, sin sistema paralelo, sin migración de esquema.

## Criterio de aceptación, y esta vez va primero

**Una pieza no está hecha hasta que se ve en la pantalla.** En las tres fases anteriores entregaste
objetos puros bien construidos y probados que **nadie llamaba**: `LedgerEntityMatcher`,
`LedgerProductMatching` y `LedgerFieldHealth` quedaron sin cablear, y el último incluso importado y
sin usar. Si al terminar esta fase el detector de duplicados no se ve al revisar una página, la fase
no está entregada aunque sus pruebas estén en verde.

Y tres cosas más de las entregas anteriores:

1. **No borres comentarios.** El *porqué* vive junto al código y no lo cubre ningún test.
2. **No reescribas bloques enteros: edítalos.** El prompt se reescribió y se llevó la regla de
   convertir pesos a centavos; un `38` habría entrado como 38 centavos.
3. **Corre las instrumentadas antes de darte por terminado.** En la Fase 3 el resumen usaba
   `stringResource` sobre un `plurals`: eso lanza `Resources$NotFoundException` y **revienta la
   pantalla entera** en el dispositivo. Ni el compilador ni las 1104 pruebas JVM lo vieron.

## Parte A — Posibles duplicados

### El problema

La clave del lote hace idempotente **la misma página**: refotografiarla sin cambiar nada devuelve el
resultado anterior. Pero fotografiarla otra vez habiendo corregido un renglón, o fotografiar una
hoja que ya se registró la semana pasada, produce otra clave y **entra completa por segunda vez**.
El dueño acaba cobrando dos veces la misma venta, y nada financiero se borra en Cuentivo: se corrige
con un movimiento compensatorio.

### Qué construir

Un objeto puro en `domain/` que, dado un renglón de la revisión y lo que ya hay registrado, diga si
**parece** un duplicado. Señales, combinadas:

- mismo cliente (por `customerId` anclado, o por nombre normalizado),
- mismo monto en centavos,
- misma fecha efectiva,
- concepto igual o muy parecido,
- mismo tipo (venta, abono, deuda anterior).

Lo recibe todo **por parámetro**, ya cargado de Room por quien llama. Kotlin puro, sin DAOs, sin
`Context`, para que corra en `testDebugUnitTest`.

### Las reglas que deciden si esto ayuda o estorba

- **Nunca bloquear ni descartar.** Se marca `Posible duplicado` y la persona decide. Un renglón
  marcado sigue siendo registrable si lo confirman.
- **Repetir no es duplicar.** Que Juan compre la misma Coca de 38 dos veces el mismo día es normal en
  una tienda, y es exactamente el patrón que dispara esta alarma.
- **Y avisar de más es igual de caro que avisar de menos, o peor** — esto lo marcó Sol y la primera
  versión de este encargo lo tenía mal. Una marca que salta en la mitad de los renglones deja de
  leerse en dos días, y peor: empuja a borrar ventas legítimas para "limpiar". Un aviso que no se
  cree no protege de nada. Ante la duda, **no avises**.
- **La marca nunca ofrece borrar.** Enseña lo que ya existe y deja decidir. Quitar el renglón es una
  acción que ya existe en la pantalla y se toma a propósito, no como sugerencia del sistema.

### Las decisiones ya tomadas, para que no las adivines

Estas las decido yo aquí y no quedan a interpretación. Si al implementarlas ves que alguna está mal,
**dilo en el reporte**; no la cambies por tu cuenta.

- **Coincidencia = mismo cliente Y mismo monto en centavos Y misma fecha efectiva Y mismo tipo.**
  Las cuatro. Conservador a propósito, por lo de arriba.
- **El concepto no crea una coincidencia**, sólo enriquece el mensaje cuando ya la hay
  (*"Ya hay una venta de Rosa por $250 el 2 de julio: Protector Tapete"*). Así no hace falta inventar
  una medida de "parecido" para el concepto, que es donde más falsos positivos saldrían.
- **Ventana: la fecha efectiva del renglón, ±7 días.** Suficiente para la hoja que se refotografía y
  acotado para no barrer el historial. Si mides que otra ventana parte mejor los casos, dilo.
- **Lo cancelado no cuenta.** Una venta con `isCanceled` o un pago con `canceledAtEpochMillis` ya no
  es deuda de nadie; volver a registrar algo así es legítimo.
- **El aviso se recalcula al editar**, igual que el semáforo: cambiar el cliente, el monto o la fecha
  puede hacer que aparezca o desaparezca, y tiene que hacerlo.
- **Dos renglones de la misma página con los mismos datos se marcan los dos, y ninguno se bloquea.**
  Puede ser el modelo leyendo un renglón dos veces y puede ser dos Cocas iguales; sólo quien tiene la
  hoja delante lo sabe.

### La consulta, y una deuda que hay que dejar escrita

Hace falta un método nuevo en `FinancialDao` acotado por **`businessId` y por rango de fecha**, que
devuelva una proyección uniforme de ventas, pagos y deuda anterior: cliente, monto, fecha, tipo y
concepto. Una sola consulta por importación, **no una por renglón**, y jamás cargar el historial
entero.

**Y la deuda, que Sol tiene razón en señalar:** no hay índices compuestos por
`(businessId, saleDateEpochDay)` ni `(businessId, paymentDateEpochDay)`, así que esto escanea. Con
los datos de hoy —decenas de filas— no se nota, y **en esta fase no se hace migración de esquema**:
subir la versión toca tres constructores de `"cuentivo.db"` y el hash de identidad de Room, y no vale
la pena por un escaneo de decenas de filas. **Déjalo escrito como comentario en el DAO**: antes de
que un negocio con miles de ventas use esto, hacen falta esos índices.

### En pantalla

Reusa lo que hay: una marca en el renglón, en el mismo sitio donde ya va el semáforo, y el motivo
visible al abrirlo — *"Ya hay una venta de Rosa por $250 el 2 de julio"*. Sin rediseñar.

## Parte B — La foto

Tres defectos anotados desde hace días, todos en `LedgerImagePreparer` y su prueba.

1. **Orientaciones espejadas.** La rotación EXIF cubre 90, 180 y 270, pero no `FLIP_HORIZONTAL`,
   `FLIP_VERTICAL`, `TRANSPOSE` ni `TRANSVERSE`. Un teléfono que las escriba manda la página al
   revés o en espejo, y el modelo lee píxeles.
2. **`prepareInPlace` sobrescribe el original.** Reintentar la lectura con más detalle es imposible:
   ampliar una imagen ya reducida no recupera trazos.

   Cómo hacerlo, con la forma que propuso Sol: el original y el preparado viven como **hermanos en
   un directorio propio de esa sesión de importación**, dentro de `cacheDir`. `LedgerImagePreparer`
   deja de escribir encima y produce un archivo aparte.

   **Quién borra, y en qué camino** — esto es lo que hay que enumerar y no dejarse ninguno: al
   registrar la página, al cancelarla, al cerrar la pantalla, y en cada fallo de copia, preparación o
   extracción. Y para la muerte del proceso, un **barrido de sesiones huérfanas al entrar** a la
   pantalla, **respetando el URI que la cámara pueda estar escribiendo en ese momento**: borrar ahí
   ya tiró una vez la foto que la persona acababa de tomar, y por eso el barrido actual ocurre al
   entrar y no en `onDispose`.

   La caché no es archivo permanente, así que esto no contradice la declaración de Play — pero no
   basta con confiar en que Android la purgue algún día.
3. **La prueba de rotación usa una imagen uniforme**, así que 270° pasaría como 90°. Usa una imagen
   asimétrica —una esquina distinta basta— para que cada orientación se distinga de verdad.

Y una cosa que **no** hay que hacer: filtros agresivos de contraste o binarización. Una libreta a
lápiz o con tinta clara pierde trazos con eso, y un trazo perdido es un renglón mal leído. Si
mejoras algo de la imagen, que sea reversible y demostrable.

## Lo que NO es de esta fase

La segunda pasada sobre recortes dudosos; desplegar la v4 al servidor; y generar el AAB. Nada de eso
se toca.

## Pruebas

JVM, contra el código de producción:

- Mismo cliente, mismo monto, misma fecha y mismo tipo ya registrado → posible duplicado.
- Mismo cliente y mismo monto pero **fecha distinta** → no lo es.
- Mismo monto y misma fecha pero **cliente distinto** → no lo es.
- Un renglón marcado como posible duplicado **sigue siendo registrable** si la persona lo confirma.
  La prueba que fija que esto avisa y no impide.
- Historial vacío → nada marcado, sin excepciones.
- Dos renglones **de la misma página** con los mismos datos: decide qué haces y pruébalo. Puede ser
  legítimo (dos Cocas iguales) y también puede ser el modelo leyendo un renglón dos veces.

- Una venta **cancelada** con los mismos datos no cuenta como duplicado.
- Editar el monto de un renglón marcado hace desaparecer la marca.

Instrumentadas en el emulador:

- **El DAO nuevo**: rango de fechas respetado, aislamiento entre negocios, y que lo cancelado no
  aparezca. Es la pieza con más superficie para fallar en silencio.
- El camino completo: Room → detector → marca visible → confirmar **sí escribe**. Una marca que
  bloqueara sin querer sería el fallo más caro de esta fase.
- **Las ocho orientaciones EXIF**, comprobando **píxeles**, no sólo el ancho y el alto: una imagen
  cuadrada rotada 180° tiene las mismas dimensiones y otro contenido, y es exactamente lo que la
  prueba actual no distingue.
- El original queda **intacto** tras preparar —compara su hash— y el preparado es otro archivo.
- Limpieza en cada camino de salida, incluido un fallo a medias, y el barrido tras simular que el
  proceso murió.
- Un fallo al escribir o comprimir **no trunca el original** ni deja temporales.

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

Cuenta las pruebas desde los XML. **El A059P NO está conectado.** Emulador `Medium_Phone` (API 37)
arrancado y desbloqueado, `emulator-5554`. Para instrumentadas,
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, y corre tus clases por su nombre:
la suite completa aborta en `ProductPhotoSourceTest`.

Paridad de claves y puerta del idioma si tocas `strings.xml` (hoy 620 = 620 y 8 = 8 plurales;
cuenta, no confíes).

**Sin commits y sin deploys.**

## Cómo se reporta

Comandos exactos y números reales. Di lo que no hiciste, di qué ventana de comparación elegiste para
los duplicados y por qué, y di explícitamente **qué se ve en la pantalla** al terminar.
