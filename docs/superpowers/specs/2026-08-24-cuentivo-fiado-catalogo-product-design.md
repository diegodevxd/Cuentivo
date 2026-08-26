# Cuentivo: sistema operativo del fiado y la venta por catálogo

**Fecha:** 2026-08-24

**Estado:** diseño de producto aprobado; implementación descompuesta en incrementos

**Fuente técnica maestra:** `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`

## 1. Decisión

Cuentivo no intentará ganar por tener más botones que un POS generalista ni convertirá CuentivoAI en un chatbot abierto. El foco principal será el comerciante mexicano que vende a crédito, fiado o por catálogo y administra pedidos, entregas, abonos y cobranza usando una mezcla de libreta, WhatsApp y memoria.

Posicionamiento aprobado:

> Cuentivo es el sistema operativo del fiado y la venta por catálogo. CuentivoAI permite describir el trabajo con texto, voz o foto; Cuentivo prepara una operación verificable y el usuario la confirma.

La aplicación seguirá atendiendo pequeños comercios generales. Inventario, caja y reportes apoyan el flujo principal, pero no desplazan la especialización en crédito, abonos, cobranza y captura ultrarrápida.

## 2. Evidencia competitiva

La investigación se basó en funciones oficiales, precios y reseñas visibles de Treinta, Kashi, Abona, Kyte, Alegra, Betterware+ y herramientas mexicanas centradas en WhatsApp.

- Treinta y Kyte cubren POS, inventario, catálogo y reportes. Las reseñas visibles mencionan fallas, duplicados, adaptación deficiente a pantallas y desconfianza en cantidades o cálculos. Cuentivo debe competir primero en confiabilidad y explicabilidad.
- Alegra ofrece una superficie más amplia de POS, contabilidad y CFDI. Replicar esa amplitud ahora diluiría el producto.
- Abona valida que el comerciante mexicano valora cobranza, apartados, funcionamiento offline, recibos y WhatsApp.
- Betterware organiza el trabajo alrededor de catálogos, pedidos semanales, distribución, entregas y crédito a plazo. Su app no elimina el trabajo que cada vendedora realiza con sus clientes finales.
- Las ventas por WhatsApp generan precios repetidos, fotos perdidas, variantes confusas y pedidos incompletos. El valor no es reemplazar WhatsApp, sino convertir esas entradas en trabajo estructurado.

Existe espacio entre el POS generalista y la app de una sola marca de catálogo. Cuentivo puede unir el ledger financiero local-first con captura natural, pedidos, entrega y cobranza explicable.

### 2.1 Actualización con precios fechados — 2026-08-26

La versión original nombraba competidores sin registrar un solo número, así que no se podía
reverificar ni envejecer. Consultado en la web el **2026-08-26**:

| Competidor | Precio publicado | ¿IA? |
|---|---|---|
| **Kashi** (mexicano, Rioverde S.L.P.) | Gratis: 1 usuario / 30 productos · Starter **$166/mes** ($1,990/año) · Pro **$333/mes** ($3,990/año) | **Sí — "Kashi AI"** |
| **Treinta** (20+ países) | Esencial USD $9.99/mes · Pro USD $19.99/mes · −25% anual | No visible |
| Libretas de deudas offline (categoría) | Gratis o compra única | No |

**Kashi es el competidor más cercano y el único con IA, pero su IA sólo consulta.** Su propio sitio
la describe como *"Responde tus preguntas sobre ventas, inventario o fiado"*, con ejemplos
*"¿Cuánto vendí hoy?"* y *"¿Quién todavía me debe?"*. CuentivoAI **ejecuta**: una frase crea el
cliente, crea los productos faltantes y registra la venta en una transacción, con preview,
confirmación e idempotencia. Ése es el diferenciador que debe vender el muro del plan — no la
palabra "inteligencia artificial".

**Encuadre de precio:** $299 queda entre Kashi Starter ($166) y Kashi Pro ($333), y por debajo de
Treinta Pro (~$360–400 MXN según tipo de cambio). La franja es defendible. El riesgo no es el
número sino la lectura: Kashi **incluye** su IA en el plan en vez de cobrarla aparte.

**Contexto de tamaño:** Kashi aparece en la App Store mexicana con **5.0 de 4 calificaciones**,
v1.0.3 de julio, publicada en marzo de 2026. Valida la categoría; no es un incumbente.

**El offline ya está disputado:** existe una categoría entera de "libreta de deudas" offline-first
con PDF y compartir por WhatsApp. Eso vuelve el tramo C una expectativa mínima más que una ventaja.
Ni Kashi ni Treinta declaran operar sin internet, así que ahí Cuentivo sigue arriba de los de pago.

**No resuelto:** si el Gratis de Kashi incluye su IA; si Kashi funciona offline; no existe todavía
corpus de reseñas mexicanas de Kashi. Treinta puede anunciar un asistente en cualquier momento.

> Corrección al §6: el precio de CuentivoAI dejó de ser sólo "configurable". Decisión del
> 2026-08-26: **$299 MXN/mes es el precio inicial para la prueba cerrada, sujeto a revisión antes
> de producción general.**

## 3. Personas y trabajos principales

### Vendedora por catálogo

Recibe pedidos durante una campaña, encarga productos, los recibe, entrega a clientes, acepta pagos parciales, gestiona faltantes/devoluciones y debe liquidar al proveedor aunque algunos clientes todavía no paguen.

### Comerciante que vende fiado

Necesita saber quién debe, por qué debe, cuándo prometió pagar y qué cambió después de cada abono. Su prioridad es no perder dinero ni discutir por saldos inexplicables.

### Pequeño comercio por WhatsApp

Recibe mensajes no estructurados, imágenes y audios. Necesita convertirlos en pedidos completos, detectar datos faltantes y responder claramente sin copiar todo manualmente.

## 4. Arquitectura de experiencia

```text
texto | voz transcrita | OCR | documento extraído
                       ↓
               propuesta tipada
                       ↓
       resolución contra datos reales locales
                       ↓
             preview editable y explicable
                       ↓
             confirmación explícita humana
                       ↓
        operación determinista e idempotente
                       ↓
          Room + auditoría + outbox + sync
```

Gemini no conoce DAOs ni recibe herramientas de escritura. El gateway entrega contratos allow-listed. Cuentivo resuelve identidad, productos, existencias, saldos, totales y fechas usando el dominio local. Una ambigüedad nunca se resuelve adivinando.

## 5. Capacidades de producto

### 5.1 Núcleo CuentivoAI

1. **Abono natural:** resolver cliente y deuda real; mostrar saldo actual, abono y saldo resultante; confirmar y ejecutar una sola vez.
2. **Venta completa en una frase:** cliente, productos, cantidades, precios declarados, pago inicial y promesa de pago; resolver datos reales, calcular, mostrar preview y confirmar.
3. **Consulta natural:** Gemini produce una intención allow-listed y Cuentivo consulta cifras reales.
4. **Voz:** reconocimiento Android/on-device cuando esté disponible, con transcripción editable.
5. **Foto/libreta/ticket:** OCR local primero, texto editable y extracción por lotes revisable.

### 5.2 Cobranza explicable

- Promesas de pago con fecha e importe.
- Cobros de hoy, vencidos y próximos mediante reglas deterministas.
- Línea de tiempo que explique cada peso desde movimientos reales.
- Estado de cuenta, recibo de abono y lista de cobranza.
- Ningún score mágico generado por IA.

### 5.3 WhatsApp asistido

- Preparar recordatorios con saldo y fecha provenientes del dominio.
- Compartir estado de cuenta, recibo o pedido.
- Mensaje editable antes de abrir WhatsApp.
- No enviar automáticamente ni leer conversaciones completas en la primera versión.

### 5.4 PDF y documentos

Primero salida determinista: estado de cuenta, recibo, lista de cobranza, relación de pedidos y resumen de campaña. La IA no calcula balances.

La entrada desde PDF será posterior: extraer productos, precios y cantidades como candidatos y exigir revisión. Un PDF jamás produce escritura directa.

### 5.5 Barcode local

ML Kit lee el código localmente y consulta `Product.sku`. Una coincidencia selecciona el producto; un código desconocido ofrece alta manual precargada. No usa Gemini, red ni cuota AI. Se mantiene como capacidad Pro propuesta.

### 5.6 Pedidos y campañas de catálogo

El modelo futuro debe distinguir pedido de venta/deuda. Estados conceptuales: solicitado, encargado, recibido, listo para entregar, entregado, parcialmente pagado, liquidado y cancelado/devuelto.

Los datos candidatos incluyen marca/proveedor, campaña, fecha límite, entrega estimada, costo, precio, ganancia y faltantes. Requieren auditoría de entidades y migración propia; no se insertan ahora en `Sale` ni generan deuda antes de entregar.

## 6. Planes comerciales

- **Gratis:** capacidad básica suficiente para comprobar valor; los límites actuales se auditarán antes de cambiarlos.
- **Pro:** operaciones sin IA, sync y futuras capacidades locales como barcode, PDF y compartir según decisión comercial posterior.
- **CuentivoAI:** incluye Pro y agrega captura/consulta natural mediante Gemini y voz/foto interpretadas.

Los límites y precios continúan configurables. Registrar pagos o abonos nunca se bloquea por plan o cuota.

## 7. Secuencia de entrega

### Tramo A — núcleo financiero confiable

1. Confirmación y ejecución idempotente del abono ya previsualizado.
2. Resolución y preview de venta completa.
3. Confirmación y ejecución idempotente de venta.
4. Consultas naturales deterministas.

### Tramo B — captura multimodal

5. Voz a transcripción editable.
6. OCR de múltiples líneas/pedidos con revisión.
7. Aclaraciones y correcciones consistentes.

### Tramo C — cobranza y documentos

8. Promesas de pago y agenda determinista.
9. Estado de cuenta y recibos PDF.
10. Compartir por WhatsApp con revisión humana.
11. Barcode local Pro.

### Tramo D — operación por catálogo

12. Diseño/migración de pedidos, campañas, entregas y devoluciones.
13. Flujo de campaña y resumen de ganancia.
14. Extracción revisable de PDF/documentos.
15. Posible bandeja WhatsApp tras revisión de privacidad y plataforma.

Cada número es un incremento o programa independiente. Ningún tramo autoriza automáticamente los siguientes.

## 8. Fuera de alcance inmediato

- CFDI o contabilidad completa.
- Chat personal o conversación abierta.
- Automatización silenciosa de WhatsApp.
- Score crediticio generado por IA.
- Escritura financiera directa desde Gemini, OCR, voz o PDF.
- Firebase Storage como dependencia de CuentivoAI.
- Importación destructiva o migraciones sin pruebas.
- AAB o activación pública antes de APK debug, prueba física, beta y gates.

## 9. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Convertir la visión en un cambio masivo | Un spec y plan por incremento; núcleo primero. |
| Confundir pedido con venta/deuda | Entidad/estado separado antes de campañas. |
| Duplicar abonos o ventas | Idempotency key y transacción verificable. |
| Gemini interpreta mal identidad o importe | Schema estricto, resolución local y aclaración. |
| PDF/OCR contiene instrucciones maliciosas | Contenido no confiable, allow-list y preview. |
| WhatsApp expone datos | Mensaje mínimo, preview y envío iniciado por el usuario. |
| Barcode altera inventario incorrecto | Lectura local y confirmación; no auto-crear. |
| Nuevas funciones rompen offline | Dominio local; AI falla cerrado sin bloquear Gratis/Pro. |

## 10. Gates y siguiente incremento

Todo incremento requiere tests focales, suite JVM, `assembleDebug`, `lintDebug` y `git diff --check`. Dinero, Room, migraciones, idempotencia o sync requieren pruebas instrumentadas/integración y prueba física proporcional.

El siguiente incremento legítimo es exclusivamente la confirmación explícita y ejecución idempotente del abono ya resuelto. Debe reutilizar la lógica financiera existente, no aceptar saldos o IDs provenientes de Gemini y demostrar doble toque/retry sin duplicación.

## 11. Referencias competitivas

- https://treinta.co/pr/funcionalidades
- https://www.getkashi.com/es
- https://abona.mx/
- https://www.kyteapp.com/pricing
- https://www.alegra.com/mexico/pos/
- https://play.google.com/store/apps/details?id=mx.com.betternet.bn3.app
- https://betterware.com.mx/pages/unete
- https://www.pide.com.mx/
