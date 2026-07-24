# Hallazgos para reglas de Firestore

Fecha: 2026-07-23

## Código inspeccionado

- Android/Kotlin con Jetpack Compose y Room; no existen llamadas a `FirebaseFirestore`, `collection`, `where`, `orderBy` ni `limit` todavía.
- El acceso actual es local mediante DAOs de Room. Por lo tanto, ninguna consulta de Firestore debe habilitarse por compatibilidad con consultas existentes.
- Firebase Auth y Firestore ya están declarados como dependencias de Gradle, pero el flujo de autenticación y la sincronización remota todavía no se han implementado.

## Modelo local que se sincronizará

- `BusinessEntity`: negocio con propietario, moneda y zona horaria.
- `BusinessMemberEntity`: relación negocio/usuario con los roles `OWNER`, `ADMIN`, `EDITOR`, `VIEWER`.
- `CustomerEntity`: contiene PII (nombre, teléfono, correo y dirección); debe quedar estrictamente aislado por negocio y membresía.
- `ProductEntity`: catálogo por negocio.
- `SaleEntity` y `SaleItemEntity`: venta y sus copias históricas de producto/precio.
- `PaymentEntity`, `PaymentAllocationEntity` y `AccountMovementEntity`: montos en centavos (`Long`) y registro financiero. Los movimientos no deben modificarse ni borrarse.
- `AuditLogEntity`: datos potencialmente sensibles; solamente propietario/administrador puede leerlo. El cliente no lo escribirá directamente.
- `SyncOperationEntity`: cola exclusiva de Room; no se publicará como colección remota.

## Esquema remoto propuesto

`businesses/{businessId}` con subcolecciones `members`, `customers`, `products`, `sales`, `payments`, `movements` y `auditLogs`; los artículos se guardan en `sales/{saleId}/items`.

Cada documento de negocio conserva `businessId` para impedir cruces de ámbito. Los identificadores se generan en el dispositivo y las escrituras financieras usan documentos inmutables, lo que hace idempotentes los reintentos al usar el mismo ID.

## Decisiones de seguridad

- Denegar por defecto todo acceso no autenticado.
- El usuario que crea un negocio debe ser su propietario y crear, en el mismo lote, su membresía `OWNER`.
- No se permite crear ni elevar el rol `OWNER`; la membresía del propietario no se puede borrar ni degradar.
- Propietario/administrador gestionan miembros. `EDITOR` puede manejar datos operativos pero no membresías ni auditoría. `VIEWER` sólo lee.
- Todos los documentos de clientes, productos y operaciones se validan en creación y actualización; se limitan campos y tamaños.
- Ventas, artículos, pagos, movimientos y auditoría son inmutables desde el cliente. Correcciones financieras se representan mediante nuevos movimientos compensatorios.

## Limitación actual

Las reglas son un prototipo para el contrato de sincronización que se implementará en la siguiente parte de Fase 3. Antes de abrir colaboración general o publicar, deben probarse con Emulator Suite y con los flujos reales de autenticación/sincronización.
