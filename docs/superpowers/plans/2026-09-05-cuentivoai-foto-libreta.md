# CuentivoAI — Fase B: pasar la libreta de papel a Cuentivo con una foto

**Fecha:** 2026-09-05
**Estado:** diseño, sin implementar
**Decisiones de producto tomadas por Diego el 2026-09-05**

## Qué resuelve

Un negocio que lleva años apuntando fiado en una libreta no se pasa a una app si tiene que
capturar cien renglones a mano. Le toma una foto a la página y CuentivoAI propone las ventas y
abonos que encuentra, para que el dueño los revise y los registre de una vez.

Es el argumento de venta más fuerte que tiene CuentivoAI, porque ataca el momento exacto en que
alguien decide si adopta la app o la abandona.

## Decisiones tomadas

| Decisión | Elegido | Descartado y por qué |
|---|---|---|
| Revisión | **Lista editable, una confirmación al final** | Uno por uno: con veinte filas la gente abandona a la mitad. Sólo lo dudoso: rompe la confirmación explícita y mete dinero que nadie revisó. |
| Alcance | **Página completa de libreta** | Una operación por foto sale antes pero no resuelve el problema real, que es pasar la libreta. |
| Clientes | **Crearlos, marcando cuáles son nuevos** | Sólo clientes existentes no sirve en una libreta virgen; preguntar uno por uno suma demasiados toques. |

## El riesgo central, y cómo se compensa

**La letra manuscrita se lee mal.** Un 3 se confunde con un 8, un nombre se parte, un renglón
tachado se interpreta como vigente. Eso no es un defecto a corregir: es una propiedad del
problema, y el diseño lo asume desde el principio.

La decisión de una sola confirmación al final acelera el flujo, pero abre un riesgo conocido: la
gente confirma sin leer. El control compensatorio es **mostrar el total agregado de forma
prominente antes de confirmar** — "se registrarán 18 ventas por $12,450 y 4 abonos por $3,100".
Nadie revisa dieciocho renglones, pero casi cualquiera detecta que el total no cuadra con lo que
tiene en la cabeza. El total es la red, no la lista.

Consecuencia de diseño: los montos y el total van en tipografía grande y con contraste alto, y los
renglones que la IA no leyó con claridad se marcan visualmente y **no** se incluyen hasta que el
usuario los toque.

## Contrato

El contrato v2 valida una lista blanca de exactamente cuatro claves y propone **una** operación.
Añadir una imagen y varias operaciones no cabe: se introduce `contractVersion: 3`, y v2 sigue
aceptándose para los clientes ya instalados. Precedente: en T4 se descubrió que agregar una clave
a la raíz rompía todo cliente publicado.

- La imagen viaja **en la petición y no se guarda en ningún lado**. No hay Storage, no hay costo de
  almacenamiento, no hay fotos de clientes en la infraestructura, y la declaración de privacidad se
  mantiene simple: la foto se procesa y se descarta.
- `gemini-3.5-flash-lite` ya es multimodal; no hay cambio de modelo.
- Límite duro de renglones por foto, para acotar costo y tiempo de revisión. Empezar en **25** y
  ajustar con uso real.
- El schema de propuesta pasa de una operación a una **lista** de operaciones, cada una con su
  propio grado de certeza para poder marcar las dudosas.

## Idempotencia del lote

Es la parte donde se pierde dinero si se hace mal. Confirmar veinte operaciones no es una
transacción: son veinte escrituras que pueden fallar a la mitad, y el usuario puede tocar dos veces.

- Cada operación propuesta lleva su propia `idempotencyKey` **generada al proponer, no al
  confirmar**, para que un doble toque no duplique.
- La confirmación reusa `reserveOperation` de `FinancialDao`, que ya garantiza que un reintento
  devuelva el `resultId` original en vez de duplicar el cargo.
- Un fallo a media lista deja registrado lo que ya entró y muestra explícitamente qué faltó, con la
  opción de reintentar sólo eso. Nunca se reintenta el lote entero a ciegas.
- Los clientes nuevos se crean **antes** de las ventas que los referencian, dentro del mismo flujo,
  para que ninguna venta quede huérfana.

## Permisos y Play

Suma **cámara** y **lectura de imágenes**, además del micrófono que ya entró en la Fase A. Implica:

- Declararlo en Seguridad de los datos de Play, en el mismo envío que el AAB.
- La política de privacidad debe decir que se toma una foto, que se envía al proveedor de IA para
  extraer texto, y que no se almacena.
- Igual que con `RECORD_AUDIO`, verificar con `aapt2` sobre el APK de release empaquetado, no sobre
  el manifiesto fuente.

Lección de la Fase A que aplica aquí: `RECORD_AUDIO` no bastaba sin la declaración `<queries>`.
Antes de dar por buena la cámara hay que confirmar que **funciona en un teléfono**, no que compila.

## Orden de implementación

1. **Contrato v3 y schema de lista**, con v2 intacto. Pruebas de que un cliente v2 sigue funcionando.
2. **Extracción**: enviar imagen a Gemini y devolver la lista de operaciones con su certeza.
   Sin UI todavía.
3. **Pantalla de revisión**: lista editable, dudosos marcados y excluidos, total agregado
   prominente, una confirmación.
4. **Confirmación idempotente**: creación de clientes nuevos primero, escritura por operación,
   manejo de fallo parcial.
5. **Permisos y captura**: cámara y galería, con el mismo cuidado de ciclo de vida que la voz —
   abrir la cámara ya cancela el dictado, y ese camino se cruza aquí.
6. **Prueba física con una libreta real**, no con una imagen generada.

## Lo que no entra

- Guardar las fotos: no hay caso de uso que lo justifique y multiplica costo y responsabilidad.
- Corregir la ortografía de los nombres leídos: el usuario los edita en la lista.
- Reconocer libretas con formatos de tabla complejos: primero el caso común, renglones simples.

## Deuda que se arrastra de la Fase A

- La conexión entre el adaptador de voz y la pantalla no está cubierta por pruebas JVM.
- Falta el timeout del estado `Processing` y que el texto parcial no tape el aviso de procesando.
- La recomposición del waveform no está acotada; medir en un teléfono de gama baja antes de
  optimizar a ciegas.
