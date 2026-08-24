# CuentivoAI: resolución determinista y preview de abono

**Fecha:** 2026-08-24
**Estado:** aprobado para planificación
**Alcance:** continuación mínima de Fase 3; debug y no ejecutable

## Objetivo

Transformar una `RecordPaymentProposal` ya validada —por ejemplo, “María pagó 150 pesos”— en una preview vinculada a datos reales del negocio activo. Gemini propone el nombre y el monto; Cuentivo resuelve al cliente, consulta el saldo y calcula el saldo proyectado.

Este incremento demuestra resolución segura. No registra pagos, no confirma operaciones y no escribe datos financieros.

## Invariantes

1. Gemini nunca proporciona un `customerId`, saldo real, asignación ni resultado financiero autoritativo.
2. Toda búsqueda se limita al `businessId` activo y a clientes activos no eliminados.
3. El saldo actual procede exclusivamente de `FinancialDao.customerBalanceCents`.
4. Los importes permanecen como `Long` en centavos; no se usa `Double` ni `Float`.
5. La preview no tiene ruta de confirmación, DAO de escritura, outbox, auditoría ni sincronización.
6. Cero o múltiples coincidencias requieren intervención humana; Cuentivo no adivina.
7. Un abono mayor que la deuda o una deuda no positiva no se vuelve ejecutable.
8. Cuentivo normal, Pro y el historial financiero siguen funcionando con IA desactivada o no disponible.

## Flujo

1. El gateway devuelve una `RecordPaymentProposal` aceptada por `SafeAiProposalValidator`.
2. Una unidad local de resolución recibe la propuesta y el `businessId` activo.
3. El nombre se normaliza con `CustomerNameNormalizer` y se buscan clientes activos del mismo negocio.
4. La resolución produce exactamente uno de estos resultados:
   - `CustomerNotFound`: no hay coincidencias.
   - `CustomerSelectionRequired`: hay varias coincidencias posibles; contiene sólo candidatos seguros para presentación.
   - `PaymentPreview`: existe una coincidencia inequívoca y se consultó su saldo real.
   - `InvalidPaymentState`: el saldo no es positivo o el monto declarado excede la deuda.
   - `Unavailable`: falló una lectura local o el contexto de negocio no es válido.
5. Para `PaymentPreview`, Cuentivo calcula `projectedBalanceCents = currentBalanceCents - declaredAmountCents` con aritmética exacta y comprobación de rango.
6. La conversación muestra el resultado como borrador no ejecutable. No aparece un botón de confirmar en este incremento.

## Regla de coincidencia

La búsqueda existente por subcadena sirve para obtener candidatos, pero una coincidencia exacta normalizada tiene prioridad sólo si es única. Si no hay una coincidencia exacta única y la búsqueda devuelve más de un candidato, la UI exige selección. Una coincidencia parcial única puede mostrarse como candidata pendiente de selección humana; no se considera identidad inequívoca para una futura ejecución.

Esta regla evita que “María” seleccione silenciosamente a “María López” cuando también existe “María García”.

## Componentes

### Resolutor puro de candidatos

Decide entre coincidencia exacta, selección requerida y ausencia usando entidades ya filtradas por repositorio. No accede a Android, Room ni Firebase y puede probarse en JVM.

### Repositorio de preview

Orquesta únicamente lecturas:

- `CustomerRepository.search(businessId, customerName)`;
- `FinancialDao.customerBalanceCents(businessId, customerId)`.

No recibe DAOs de escritura y no expone funciones de confirmación.

### Modelo de preview

Contiene identificadores locales sólo después de la resolución determinista, además de nombre mostrado, saldo actual, monto declarado, saldo proyectado y estado de seguridad. No se persiste en historial AI ni se envía de regreso a Gemini.

### Chat debug

Sustituye la tarjeta de propuesta cruda por el resultado local cuando la propuesta es un abono. Las propuestas de venta y consulta conservan su comportamiento actual hasta incrementos posteriores.

## Errores y comportamiento seguro

- Nombre vacío o inválido: estado de aclaración.
- Negocio ausente o en blanco: `Unavailable`.
- Cliente archivado, eliminado o de otro negocio: se trata como inexistente.
- Saldo igual o menor que cero: preview bloqueada con explicación determinista.
- Monto mayor que saldo: preview bloqueada; no se convierte automáticamente en crédito a favor.
- Error de Room o cancelación de coroutine: no se fabrica saldo ni cliente; se muestra indisponibilidad.
- Resultado obsoleto tras cerrar, reiniciar o cambiar de sesión: se descarta mediante el token de generación existente.

## Pruebas requeridas

### JVM puras

- cero candidatos;
- coincidencia exacta única;
- múltiples coincidencias exactas o parciales;
- prioridad de coincidencia normalizada;
- acentos, mayúsculas y espacios;
- saldo positivo y resta exacta;
- saldo cero o negativo;
- monto mayor que deuda;
- límites de `Long` sin overflow;
- la resolución no ofrece ninguna acción ejecutable.

### Repositorio/Room

- aislamiento por `businessId`;
- exclusión de archivados y eliminados;
- saldo leído desde movimientos reales;
- ninguna fila de pagos, movimientos, operaciones, auditoría u outbox cambia durante la preview.

### UI/estado

- request obsoleto no altera la conversación;
- cero coincidencias solicita aclaración;
- varias coincidencias solicita selección;
- preview muestra saldo actual, abono y saldo proyectado;
- estado bloqueado para sobrepago;
- no existe acción de confirmación.

## Fuera de alcance

- registrar o confirmar un abono;
- idempotencia de ejecución;
- asignar pagos a ventas;
- crear clientes automáticamente;
- resolver productos o ventas completas;
- editar propuestas;
- sincronizar o persistir previews;
- cambios de Room, Firestore, Billing, cuotas o gateway;
- habilitar CuentivoAI en release, AAB o Play.

## Rollback

El incremento debe quedar contenido en clases nuevas de resolución/preview y en el cableado debug del chat. Revertir sus commits restaura la tarjeta de propuesta cruda sin tocar esquema, datos, gateway ni lógica financiera existente.

## Criterio de terminado

El incremento queda aceptable sólo cuando la preview de abono usa cliente y saldo reales del negocio activo, maneja ambigüedad de forma explícita, demuestra cero mutaciones y pasan pruebas JVM, pruebas Room focales, `assembleDebug`, `lintDebug` y `git diff --check`. No declara Fase 3 completa ni autoriza confirmación financiera.
