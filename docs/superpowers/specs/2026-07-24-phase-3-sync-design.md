# Sincronización offline-first — diseño de Fase 3

## Objetivo

Sincronizar datos de Cuentivo entre Room y Firestore sin perder ni duplicar movimientos financieros.

## Decisión

Room sigue siendo la fuente local de verdad. Cada cambio local genera una operación idempotente en `sync_operations`. Un `CoroutineWorker` con restricción de red procesa la cola por lotes y usa el identificador local como ID remoto determinista. Después de empujar, descarga el estado remoto del negocio y lo fusiona localmente.

## Datos y conflictos

- Ventas, pagos, asignaciones y movimientos son solo de creación. Una repetición escribe el mismo documento y no crea un duplicado.
- Clientes y productos se sincronizan como reemplazos con `updatedAtEpochMillis`; gana la versión más reciente. Si hay empate con distinto contenido, el cambio local permanece en cola con error visible.
- Los documentos se guardan bajo `businesses/{businessId}` y las subcolecciones ya definidas por las reglas.
- Un negocio se crea junto con el miembro propietario en un único lote remoto para que las reglas puedan comprobar la membresía de inmediato.

## Flujo

1. La UI escribe en Room y agrega una operación de sincronización.
2. WorkManager ejecuta la cola al haber red.
3. Cada operación se convierte en un lote Firestore idempotente.
4. Tras éxito se elimina de la cola; un error transitorio incrementa intentos y solicita reintento.
5. Se descarga el negocio remoto y se aplica una fusión que nunca borra movimientos financieros locales.

## Seguridad y pruebas

Firebase Auth determina el usuario activo. Firestore Rules mantienen el aislamiento por negocio y roles. Las pruebas unitarias cubren la decisión de conflicto, el orden de operaciones y la idempotencia; las pruebas de integración con Emulator Suite quedan incluidas antes de declarar la fase terminada.
