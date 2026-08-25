# CuentivoAI — endurecimiento: ningún callejón sin salida

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** punto N4 del núcleo. Que **ningún estado del laboratorio termine en pared**. Hoy varios dicen "no se puede" y ahí se acaban: el usuario no sabe si se equivocó, si falta un dato o si la app está rota.

**El caso que lo motivó, real y no inventado:** "Demian me pagó 92 de un boleto" se clasificó como abono. El camino de abonos no crea clientes **a propósito** y exige deuda previa, así que mostró clientes que no eran y ahí murió. Diego no tenía forma de saber que lo que quería era una venta. La clasificación ya se corrigió en el servidor, pero **el modelo se va a volver a equivocar**, y cuando pase el usuario debe poder salir.

**Architecture — lo que este incremento NO es:** no se relaja ni una sola guarda. `NO_OUTSTANDING_DEBT` sigue bloqueando, el sobrepago sigue bloqueando, los límites de plan siguen aplicando. **Cambia lo que se dice, no lo que se permite.** Tampoco se reinterpreta la frase automáticamente ni se hacen llamadas nuevas al gateway: eso gastaría cuota y podría entrar en bucle. La salida es texto que enseña al usuario cómo decirlo.

El precedente correcto ya existe en el proyecto: `AssistantSaleLimitReached` no muestra un error, ofrece comprar y lleva al paywall. Todos los "no" deben parecerse a ése.

## Global Constraints

- **Ninguna guarda se debilita.** Si un test de bloqueo cambia de expectativa, algo va mal: detente y escala.
- **Ninguna cifra se inventa.** `AssistantPaymentBlocked` ya carga `preview: AiResolvedPaymentPreview?`, que trae el saldo real. Úsalo. Si el dato no está a la mano, el mensaje no lo menciona.
- **Cero llamadas nuevas al gateway.** No se reinterpreta la frase, no se reintenta con otra intención, no se consume cuota.
- **Cero cambios en contratos, control plane, Room o rutas financieras.**
- Nada de PII en logs, Analytics o Crashlytics. Los mensajes en pantalla sí pueden nombrar al cliente que el usuario acaba de escribir.
- Cadenas en `values/` y `values-en/`, paridad cuadrada y puerta del idioma limpia. **Cuenta las claves**, no confíes en el número escrito en `CLAUDE.md`.

## Task 1 — Los "no" del camino de abonos y de consultas

- [ ] `AssistantPaymentCustomerNotFound`: hoy es un callejón. Debe explicar que un abono va contra una deuda ya registrada, y **enseñar cómo decirlo si era una venta**, con un ejemplo concreto del estilo "le vendí un boleto a Demian en 92".
- [ ] `AssistantPaymentBlocked(NO_OUTSTANDING_DEBT)`: el cliente existe pero no debe nada. Mismo tratamiento, y si el preview trae el nombre, úsalo.
- [ ] `AssistantPaymentBlocked(AMOUNT_EXCEEDS_DEBT)`: **di cuánto debe realmente**, tomándolo del preview. "Carina te debe $300 y estás abonando $500" es útil; "no se puede" no lo es.
- [ ] Consultas: `CustomerNotFound` debe distinguir "no existe ese cliente" de "no te debe nada", que son cosas distintas y hoy se confunden.
- [ ] Consultas: los tres `AiQueryBlockReason` — fecha inválida, inicio después del fin, período mayor a 366 días — deben decir qué pasó con el período, no un error genérico.

## Task 2 — Los "no" del camino de venta y los estados rancios

- [ ] Los cinco `AiSaleBlockReason` con mensaje propio: pago inicial mayor al total, desbordamiento aritmético, cantidad inválida, precio inválido y precio inconsistente de producto nuevo. Cada uno dice qué corregir.
- [ ] `AssistantPaymentStale` y `AssistantSaleStale`: explicar que los datos cambiaron mientras revisaba y que hay que volver a pedirlo, no un "no se pudo".
- [ ] `AssistantPaymentRejected` y `AssistantSaleRejected`: hoy dicen "verifica los datos", que ya nos engañó una vez haciendo creer que la app fallaba. Que digan algo verificable.

## Task 3 — Aceptación física de los caminos feos

Se prueban **a mano en el A059P**, y ninguno debe terminar en pared:

- [ ] Abono a un cliente que no existe.
- [ ] Abono a un cliente que existe y no debe nada.
- [ ] Abono por más de lo que se debe: el mensaje debe traer el saldo real.
- [ ] Consulta de saldo de un cliente inexistente.
- [ ] Consulta con un período imposible, por ejemplo "cuánto vendí del 30 de febrero".
- [ ] Venta con precio o cantidad absurdos.

## Gate

No escribe dinero ni toca Room: no exige pruebas conectadas. Sí exige focales de estado, suite JVM completa, `assembleDebug`, `assembleRelease`, `lintDebug`, puerta del idioma, paridad de claves y `git diff --check`. **La aceptación física es obligatoria**, y en este incremento es el gate de verdad: el valor entero está en lo que ve una persona cuando algo sale mal.
