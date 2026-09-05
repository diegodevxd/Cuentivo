# CuentivoAI — Fase B: pasar la libreta de papel a Cuentivo con una foto

**Fecha:** 2026-09-05
**Estado:** diseño, sin implementar
**Revisado por Codex el 2026-09-05; sus objeciones están incorporadas abajo.**

## Qué resuelve

Un negocio que lleva años apuntando fiado en una libreta no se pasa a una app si tiene que
capturar cien renglones a mano. Le toma una foto a la página, CuentivoAI lee lo que hay y le
pregunta qué es cada cosa, y el dueño lo registra de una vez.

Es el argumento de venta más fuerte de CuentivoAI porque ataca el momento exacto en que alguien
decide si adopta la app o vuelve al papel.

## Decisiones de producto

| Decisión | Elegido |
|---|---|
| Revisión | Lista editable con **una sola confirmación** al final |
| Alcance | **Página completa** de libreta, no una operación suelta |
| Clientes nuevos | Se crean, **marcados como nuevos** para detectar nombres mal leídos |
| **Tipo de movimiento** | **Gemini propone, el usuario decide.** Deuda anterior, venta o abono es un campo editable más |
| Fechas | **La que dice la libreta.** Si el renglón no la trae, el usuario elige una para la página |
| Cargos sin producto | **Permitidos.** "Juan, $300" se registra como cargo a la cuenta, sin inventar un producto |

La decisión del tipo de movimiento es la que hace viable la promesa. Una página real mezcla
compras, abonos y saldos arrastrados de antes; la app no puede adivinar cuál es cuál sin
equivocarse, y tampoco puede ignorarlos sin perder justo lo que el usuario quiere migrar.

## El riesgo central: interpretar, no leer

**El peligro mayor no es leer mal una cifra: es registrar correctamente algo que la libreta no
representa como venta.** Una página trae "saldo anterior $500", subtotales, tachaduras y notas.
Sumarlo todo produce deuda ficticia **aunque cada número esté perfectamente leído**.

Por eso la primera versión acepta únicamente **movimientos identificables** —con su monto, su
cliente y su tipo confirmado por el usuario— y nunca inventa una venta para cuadrar una página.
Debe poder decir explícitamente *"esta página no alcanza para registrar estos movimientos"*, por
ejemplo cuando empieza a mitad de la historia y sus abonos no tienen deuda que los justifique.

## Por qué el total agregado no basta

La primera versión de este plan proponía mostrar el total como red de seguridad. **No alcanza**, y
conviene dejar escrito por qué para que nadie lo reintroduzca:

- Cambiar un cargo de Ana a Rosa **conserva el total**.
- Omitir $500 y duplicar otros $500 también lo conserva.
- Y sobre todo: mucha gente usa la libreta **precisamente porque no conoce el agregado de
  memoria**. No puede detectar un total equivocado.

Se mantiene una sola confirmación, con estos controles:

- **Ventas y abonos separados**, con número de operaciones y cuántas filas quedaron excluidas.
- **Agrupación por cliente**, mostrando deuda anterior → deuda resultante, y cuáles clientes se van
  a crear.
- **El fragmento ampliado de la foto junto a cada propuesta.** Revisar el texto que produjo la IA
  contra otro texto producido por ella no es verificación: hay que poder mirar el renglón original.
- Un resumen final del tipo "crear 3 clientes y registrar 18 movimientos", ligado exactamente a la
  versión editada de la lista.

La incertidumbre se distingue por campo —importe, cliente, fecha y tipo—, no como una marca global.
Y tocar un renglón dudoso **abre la corrección**, no activa una casilla: si no se resuelve el campo
en duda, ese renglón no entra.

El grado de confianza que declare Gemini no garantiza que detecte sus propios errores, así que las
filas "claras" también llevan evidencia accesible. Antes de publicar hay que medir con páginas
reales anotadas a mano, contando **errores monetarios aceptados como claros**, no porcentaje de
texto reconocido.

## Registro: lote atómico

La versión anterior de este plan asumía "veinte escrituras que pueden fallar a la mitad". Es una
premisa equivocada: son escrituras **locales en Room**, y `FinancialDao` ya tiene métodos
transaccionales. Para un máximo de 25 operaciones se hace **todo o nada**: validar, resolver
clientes compartidos, calcular saldos intermedios y registrar en una transacción. La llamada a
Gemini y la red quedan fuera de esa transacción.

Eso evita de raíz los problemas que sí tiene el éxito parcial:

- **Dos abonos del mismo cliente**: `FinancialDao.kt:585` compara contra el saldo esperado, así que
  el segundo sería rechazado tras aplicarse el primero. En un lote atómico los saldos intermedios
  se calculan antes de escribir.
- **Un cliente repetido en varias filas** se crea una sola vez, no una por renglón.
- **Resultado desconocido** (escritura aplicada cuya respuesta se perdió) deja de ser un caso a
  distinguir.
- Las claves de idempotencia las genera **código confiable, nunca Gemini**, y quedan ligadas al
  negocio y al contenido aprobado.

Queda un caso que la idempotencia técnica no resuelve: **volver a fotografiar la misma página**
genera propuestas nuevas con claves nuevas. Hay que detectarlo en la revisión, no en la escritura.

Ojo al integrar: algunos ejecutores devuelven `StalePreview` en vez de lanzar. El ejecutor del lote
debe convertir cualquier rechazo en rollback.

## Permisos y privacidad

**No se añaden permisos.** Usando la cámara externa y el Photo Picker no hace falta `CAMERA` ni
lectura general de imágenes, y esa infraestructura ya existe en la app. Es una simplificación
grande respecto al plan original.

Sobre la foto, la promesa correcta es **"sin archivo permanente de fotos en Cuentivo"**, no "no se
guarda en ningún lado". Esto último sería falso en la política de privacidad: la cámara escribe un
archivo temporal local —necesario además para comparar durante la revisión— y el proveedor de IA
retiene registros para supervisión de abuso. Lo que sí se garantiza: temporal local hasta terminar
o descartar, limpieza tras interrupciones, y ninguna foto en la infraestructura del proyecto. El
borrador y el comprobante del lote sí pueden persistirse **sin conservar la imagen**.

## Cruce con la voz

Abrir la cámara ya cancela el dictado, pero eso no basta: quedan volver de la cámara, dictar
mientras se procesa la foto, recibir tarde una propuesta vieja, tomar una segunda foto, cambiar de
negocio o salir a media confirmación.

Para la primera versión, **la voz se deshabilita durante extracción, revisión y confirmación**.
Editar con teclado es suficiente y elimina toda esa clase de carreras. Cancelar la extracción
invalida su respuesta; cancelar durante la escritura tiene un resultado definido, nunca un mensaje
ambiguo que invite a fotografiar otra vez.

La sesión de importación se identifica **antes de abrir la cámara**, ligada al negocio y a una
versión del borrador, y sólo su resultado vigente puede modificar la revisión.

## Contrato

`contractVersion: 3`, con v2 intacto para los clientes ya instalados: el parser de v2 exige
exactamente cuatro claves en la raíz, y en T4 ya se comprobó que agregar una rompe todo cliente
publicado.

- La respuesta pasa de una operación a una **lista**, cada una con su tipo propuesto y su
  incertidumbre por campo.
- Límite de **25 renglones por foto**. Alcanzarlo produce un aviso explícito de **página
  incompleta**, nunca truncamiento silencioso.
- El proveedor actual limita la salida a **512 tokens**; v3 necesita su propio presupuesto o las
  listas largas se cortarán a media respuesta.

## Orden de implementación

1. Contrato v3 y schema de lista, con v2 intacto y pruebas de compatibilidad.
2. Extracción: imagen a Gemini, lista de operaciones con tipo propuesto e incertidumbre por campo.
3. Ejecutores: fecha de la libreta y cargo sin producto.
4. Pantalla de revisión: tipo editable, evidencia de la foto por renglón, agrupación por cliente.
5. Registro atómico del lote.
6. Prueba física con **una libreta real**, no con una imagen generada.

## Fuera de alcance

Cámara propia, edición del lote por voz, coordinaciones distribuidas, reconocer tablas complejas,
corregir la ortografía de los nombres, y guardar las fotos.

## Deuda que se arrastra de la Fase A

- La conexión entre el adaptador de voz y la pantalla no está cubierta por pruebas JVM.
- Falta el timeout del estado `Processing` y que el texto parcial no tape el aviso de procesando.
- La recomposición del waveform no está acotada; medir en gama baja antes de optimizar a ciegas.
