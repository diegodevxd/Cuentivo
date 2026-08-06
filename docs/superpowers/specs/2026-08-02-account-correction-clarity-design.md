# Claridad y seguridad de correcciones — Diseño

**Objetivo:** eliminar ambigüedad al corregir movimientos y expresar créditos a favor sin alterar el libro mayor append-only.

## Alcance

1. Mostrar saldos negativos como crédito a favor, usando importe absoluto en la UI.
2. Distinguir pagos cancelables de mismo importe y fecha mediante hora y ordinal estable.
3. Explicar una cancelación de venta parcialmente abonada con total, abonos previos y remanente a compensar.
4. Bloquear cancelar un abono que tenga una asignación sobre una venta ya cancelada.
5. Localizar errores de autenticación y mensajes de estos flujos en español e inglés.

## Fuera de alcance

- Flujo de reembolso completo de una venta cancelada.
- Promociones, migración Room y cambios de Firestore.
- Localización de todos los flujos restantes de `MainActivity`; se abordará después de este bloque.
- Cambio de las reglas contables, de los importes almacenados o de la política de cancelación existente.

## Decisión financiera

Un pago asignado a cualquier venta ya cancelada no puede cancelarse desde la UI. Se conserva como historial y se muestra una razón clara. Esta restricción evita que un `REFUND/DEBIT` posterior cree una deuda artificial tras la compensación de la venta.

## Diseño de datos de presentación

### Saldo

`balanceCents` permanece firmado y es la única fuente para cálculos. Una función pura de presentación devuelve:

- `OWED` y `amountCents = balanceCents` cuando el saldo es positivo.
- `SETTLED` y `amountCents = 0` cuando es cero.
- `CUSTOMER_CREDIT` y `amountCents = -balanceCents` cuando es negativo.

La UI nunca muestra “Debe hoy -$X” ni “Quedó debiendo -$X”. En crédito muestra “Saldo a favor” / “Customer credit” y el valor absoluto.

### Correcciones

Los candidatos dejan de depender de títulos españoles formados en `MainActivity`. El modelo transporta datos estructurados:

- Venta: identificador, folio/fecha, total original, abonos activos y remanente cancelable.
- Abono: identificador, importe, fecha de negocio, hora de registro, ordinal dentro de pagos iguales, total de ese grupo y condición de bloqueo.

El ID interno nunca se muestra. Dos pagos con mismo importe y fecha se ordenan establemente por `createdAtEpochMillis`, con `id` como desempate, y se muestran como “Abono 1 de 2”, “Abono 2 de 2”.

## Diseño de interacción

### Saldo a favor

- Hero: `SALDO A FAVOR` + importe absoluto + etiqueta explicativa.
- Totales: reemplaza “Debe hoy” por “Saldo a favor”.
- Movimiento: reemplaza “Quedó debiendo” por “Quedó con $X a favor”.
- Directorio y archivado: distinguen crédito de estar al día.

El color menta comunica resultado favorable, pero la etiqueta siempre explica el significado.

### Cancelación de venta parcial

La confirmación muestra un bloque claro:

- Total original.
- Abonos que siguen registrados.
- Remanente que se compensará.

El texto declara que la venta no se borra, que solo se compensa el pendiente y que los pagos anteriores no se cancelan ni devuelven automáticamente.

### Cancelación de abono bloqueada

La fila conserva su monto, fecha, hora y ordinal, pero no expone acción de cancelar. Muestra la razón: el abono corresponde a una venta ya cancelada y se conserva para no alterar el saldo.

## Localización

Los errores de autenticación se expresan como claves o tipos en la capa Android y se resuelven con recursos en la UI. No se agrega `Context` a dominio. Toda clave nueva se añade a `values/strings.xml` y `values-en/strings.xml` en la misma modificación.

## Evidencia requerida

1. Unitarias rojas y verdes para la presentación de saldo: deuda, cero y crédito.
2. Unitarias o instrumentadas que prueben dos pagos iguales con identificadores visuales distintos.
3. Instrumentada que muestre una venta parcialmente abonada y su diálogo con total, abonado y remanente.
4. DAO/instrumentada que pruebe el bloqueo de pago asociado a venta cancelada y preserve el saldo.
5. Instrumentada de autenticación en inglés para los errores de validación.
6. Recorrido manual en el AVD con la cuenta de prueba: crédito a favor, pagos duplicados, venta parcial y cambio de idioma.

## Invariantes

- Dinero en `Long` centavos; no `Double` ni `Float`.
- Ninguna venta, pago o movimiento se actualiza ni elimina para corregirlo.
- El saldo mostrado conserva el valor del libro mayor; solo cambia su etiqueta y signo visual.
- Toda consulta de datos se mantiene filtrada por `businessId`.
- No se exponen PII, saldos o tokens en logs.
