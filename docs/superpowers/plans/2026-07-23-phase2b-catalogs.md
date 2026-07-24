# Fase 2b: catálogos y detalle de cuenta Implementation Plan

**Goal:** Implementar clientes, productos y detalle financiero local en Compose.

**Architecture:** Las pantallas consumen repositorios Room filtrados por negocio. Los saldos e historial provienen del libro mayor; los formularios delegan validación a los repositorios existentes.

## Task 1: Consultas de detalle

- [ ] Añadir modelos de lectura y consultas Room para listar clientes con saldo, productos activos e historial de movimientos por negocio/cliente.
- [ ] Escribir primero pruebas instrumentadas que demuestren aislamiento por `businessId` y que un saldo no se calcula desde la UI.
- [ ] Ejecutar `connectedDebugAndroidTest` y confirmar éxito.

## Task 2: Estado y formularios

- [ ] Añadir controladores suspendibles para búsqueda, creación, edición y archivado de clientes/productos.
- [ ] Escribir pruebas unitarias para nombre obligatorio, precio no negativo, búsqueda por teléfono/SKU y archivado sin borrado físico.
- [ ] Ejecutar `testDebugUnitTest` y confirmar éxito.

## Task 3: Compose

- [ ] Sustituir los destinos provisionales Clientes y Más por listas buscables y el catálogo de productos.
- [ ] Crear formularios Material 3 de cliente/producto y detalle de cliente con saldo e historial.
- [ ] Añadir diálogos de confirmación antes de archivar y estados vacíos explicativos.
- [ ] Escribir pruebas Compose de búsqueda, navegación al detalle y visibilidad de saldo/estado vacío.

## Task 4: Verificación

- [ ] Ejecutar `testDebugUnitTest` y `connectedDebugAndroidTest`.
- [ ] Ejecutar `git diff --check`, revisar el diff y crear commits por bloque.
