# CuentivoAI: venta completa por lenguaje natural

## Objetivo

Convertir una propuesta tipada como “María se llevó dos perfumes de $300 y pagó $200” en una preview local verificable y, sólo después de confirmación explícita, registrar exactamente una venta y su abono inicial.

## Diseño aprobado

`CreateSaleProposal` continúa siendo una sugerencia sin autoridad. Android valida el contrato, resuelve el cliente y cada producto contra el negocio activo, conserva el precio declarado para revisión humana y calcula subtotal, pago y saldo con lógica determinista en centavos enteros. La preview muestra las coincidencias reales, cantidades, precios, total, pago inicial, saldo restante y fecha prometida si existe.

La resolución falla cerrada cuando el cliente o un producto no existen, hay más de una coincidencia, cambia el negocio activo, una cantidad/precio desborda, el pago supera el total o los datos usados por la preview quedan obsoletos. No se crean clientes ni productos implícitamente.

La confirmación usa una clave estable derivada de la preview resuelta, no IDs creados por Gemini. Una única transacción Room reserva esa clave y crea venta, líneas, cargo, abono inicial opcional, asignación al cargo y movimientos/auditoría correspondientes. Un retry o doble toque devuelve el resultado original sin duplicar filas. Outbox/sync se encola desde los registros confirmados siguiendo el patrón existente; un fallo de AI nunca afecta captura manual.

## Fuera de alcance

- UI definitiva, lanzamiento público, AAB o cambios de versión/Billing.
- Crear clientes/productos, modificar inventario o aceptar ambigüedad automáticamente.
- OCR/voz nuevos: ambos ya alimentan texto y reutilizarán este mismo flujo.
- Promesas de pago persistentes; la fecha sólo se presenta hasta que exista un contrato de dominio aprobado.

## Gates

1. Resolución y preview puras/read-only con tests JVM.
2. Ejecución Room atómica e idempotente con tests instrumentados adversariales.
3. Máquina de estados y tarjeta de confirmación con tests JVM/Compose.
4. Cableado debug, suite completa, build, lint, diff-check y prueba física.

