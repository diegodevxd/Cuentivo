# CuentivoAI — consultas naturales deterministas

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que el usuario pregunte en lenguaje natural — "¿cuánto me debe Ana?", "¿quién me debe?", "¿cuánto vendí esta semana?" — y reciba **cifras calculadas por el dominio a partir de Room**, nunca por Gemini. Es el punto 4 del Tramo A en `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md`.

**Architecture:** el contrato ya existe. `BusinessQueryProposal` (`ai/AiContracts.kt`) llega del gateway con `queryType`, un `customerName` candidato y/o un `AiPeriodCandidate`, y `AiProposalSafety.validateQuery` ya valida su forma. Hoy la UI muestra **sólo la intención** y ninguna cifra. Este incremento añade la capa que falta, calcada del patrón de venta/abono: un resolvedor puro de sólo lectura, un estado en el laboratorio, la UI y el cableado del host.

**Tech Stack:** Kotlin puro en `com.cuentivo.app.ai` (sin Android, corre en `testDebugUnitTest`), Compose para la presentación, Room existente como única fuente de cifras.

## Global Constraints

- **Este incremento no escribe absolutamente nada.** Ni Room, ni Firestore, ni outbox, ni `audit_logs`. Una consulta es lectura: no lleva clave de idempotencia, no lleva confirmación y **no** genera fila de auditoría.
- **No se modifica el esquema y no se agrega ninguna `@Query` nueva.** Todo sale de funciones que ya existen:
  - `CustomerDao.searchActive(businessId, nameQuery, phoneQuery)` — resolución del nombre.
  - `CustomerDao.activeWithBalances(businessId): List<CustomerBalanceRow>` — ya viene ordenada por saldo descendente.
  - `FinancialDao.customerBalanceCents(businessId, customerId)`.
  - `FinancialDao.salesTotalCents(businessId, startEpochDay, endEpochDay)`.
  - `FinancialDao.collectedTotalCents(businessId, startEpochDay, endEpochDay)`.
  Si crees necesitar una consulta nueva, **detente y escala**: probablemente estás respondiendo algo fuera de la allow-list.
- **Gemini sólo aporta `queryType`, un nombre para mostrar y un periodo.** Ninguna cifra, ningún ID y ningún saldo puede provenir del modelo. Si una cifra no salió de un DAO, es un defecto.
- `businessId` lo pone el host desde el negocio activo, jamás la propuesta. Toda lectura filtra por él.
- Dinero en `Long` de centavos; la conversión a decimal ocurre sólo en la UI.
- **Una respuesta vacía es una respuesta, no un error.** "Nadie te debe" y "no vendiste nada en ese periodo" son resultados legítimos y deben verse distintos de "no pude".
- **Nada de PII ni saldos en logs, Analytics o Crashlytics.** La respuesta se dibuja en pantalla y muere ahí.
- **No se agrega ningún límite de plan nuevo.** La cuota de IA ya se aplica en el gateway. No tocar la regla de que registrar un pago o abono nunca se bloquea.
- El resolvedor no importa DAOs, Firebase ni sync: recibe *seams* como lambdas, igual que `AiSalePreviewResolver`.

## Task 1 — Resolvedor puro de sólo lectura

Archivo nuevo `app/src/main/java/com/cuentivo/app/ai/AiBusinessQuery.kt`.

- [ ] `AiBusinessQueryResolver` con seams por constructor: `searchCustomers`, `customerBalance`, `customersWithBalances`, `salesTotal`, `collectedTotal`. Sin dependencias de Android.
- [ ] `suspend fun resolve(businessId, proposal, choice: AiEntityChoice? = null): AiBusinessQueryResult`, envuelto como el de venta: **relanza `CancellationException`** y convierte cualquier otra excepción en `Unavailable`.
- [ ] `AiBusinessQueryResult` sellado: `Answer.CustomerBalance(customerId, customerName, balanceCents)`, `Answer.CustomersWithDebt(rows, totalCents)`, `Answer.SalesForPeriod(startEpochDay, endEpochDay, soldCents, collectedCents)`, `CustomerSelectionRequired(candidates)`, `CustomerNotFound(queriedName)`, `Blocked(reason)`, `Unavailable`.
- [ ] `CUSTOMER_BALANCE`: resuelve el nombre reusando la semántica de `AiSaleCustomerMatch` — exacto resuelve, varios candidatos piden aclaración, ninguno devuelve `CustomerNotFound`. Nunca crea clientes: aquí **no** existe la rama "crear nuevo".
- [ ] `CUSTOMERS_WITH_DEBT`: toma `customersWithBalances`, **filtra `balanceCents > 0`** y corta a los 20 primeros. Un saldo de 0 o a favor no es deuda. Devuelve además la suma de los positivos.
- [ ] `SALES_FOR_PERIOD`: convierte `AiPeriodCandidate` a `epochDay`. Bloquea con motivo si la fecha no existe en el calendario, si `start > end` o si el rango excede 366 días.
- [ ] Colecciones de la respuesta congeladas (`Collections.unmodifiableList`), igual que el resto de `ai/`.
- [ ] Pruebas JVM: cada tipo de consulta, nombre ambiguo, nombre inexistente, deuda vacía, periodo inválido en sus tres formas, filtrado de saldos ≤ 0, aislamiento por `businessId`, propagación de `CancellationException` y que ninguna cifra provenga de la propuesta.

## Task 2 — Estado en el laboratorio

`app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`.

- [ ] Estados `QueryResolving` y `QueryAnswer(result)`; mensaje `AssistantQueryAnswer(result)` en `AiChatMessage`.
- [ ] Rama de `BusinessQueryProposal` en el despacho de propuestas, con el mismo token de `generation` y el mismo descarte de resultados rancios que ya usan venta y abono.
- [ ] `onChooseQueryCustomer(customerId)`: valida el ID contra la lista de candidatos **local** y devuelve `Ignored` si no está. Nunca se resuelve escribiendo texto.
- [ ] Single-flight: una consulta en vuelo no se puede relanzar.
- [ ] Pruebas de máquina de estados para cada transición, incluidos resultado rancio y elección inválida.

## Task 3 — Presentación

`AiDebugLabScreen.kt` y recursos.

- [ ] Reemplazar el bloque actual de `BusinessQueryProposal`, que hoy imprime `proposal.queryType.name` crudo, por la respuesta real.
- [ ] Saldo de cliente: nombre y monto. Deudores: lista con nombre y saldo, más el total. Periodo: vendido y cobrado, con las fechas del rango.
- [ ] Estados vacíos con texto propio, distintos del de error.
- [ ] Candidatos tocables para la aclaración, reusando el patrón de venta.
- [ ] Cadenas en `values/` y `values-en/`; **puerta del idioma limpia y paridad de claves cuadrada** (cuenta, no confíes en el número escrito en `CLAUDE.md`).

## Task 4 — Cableado del host y aceptación

`app/src/main/java/com/example/organizationlabs/MainActivity.kt`.

- [ ] Construir el resolvedor con los DAOs reales y el negocio activo. Ni un `@Query` nuevo.
- [ ] Verificar que ninguna ruta de consulta encola nada en el outbox ni escribe auditoría.
- [ ] Prueba física en el A059P con las tres preguntas, contrastando cada cifra contra la pantalla normal de la app.

## Gate

Este incremento **no escribe dinero**, así que no exige pruebas conectadas de Room. Sí exige: pruebas focales, suite JVM completa, `assembleDebug`, `lintDebug`, puerta del idioma, paridad de claves y `git diff --check` limpio. La aceptación física es obligatoria antes de declararlo cerrado.

El riesgo de este incremento no es corromper datos: es **mostrar una cifra equivocada con cara de verdad**. Por eso la prueba física compara cada respuesta contra la pantalla existente, y por eso ninguna cifra puede venir de la propuesta.
