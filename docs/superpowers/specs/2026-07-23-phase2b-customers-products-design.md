# Fase 2b: clientes, productos y detalle de cuenta

## Objetivo

Conectar los catálogos locales ya existentes con Compose para consultar, buscar, crear, editar y archivar clientes y productos; mostrar el saldo y el historial financiero de cada cliente.

## Comportamiento

- Clientes: lista buscable por nombre/teléfono, saldo derivado del libro mayor y detalle individual.
- Productos: lista buscable por nombre/SKU, categoría, precio, estado y formulario de alta/edición.
- Detalle de cuenta: saldo total e historial cronológico filtrado por `businessId` y `customerId`.
- Los formularios validan los campos requeridos antes de guardar; archivar pide confirmación y nunca borra registros.

## Límites

No se implementan ventas ni pagos desde los botones del detalle: se enlazarán a Fase 2c. Tampoco se añaden sincronización, autenticación ni roles.

## Pruebas

Pruebas Room para historial aislado por negocio/cliente, pruebas unitarias de validación y pruebas Compose para búsqueda, formulario, archivado y detalle vacío.
