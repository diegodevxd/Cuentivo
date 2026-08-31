# Brief para AGY — Consumidor de notificaciones de Play, y la cancelación que revoca de más

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `af1d850` · **Origen:** criterio de Codex del
2026-08-30 sobre el diseño de revocación

El núcleo está cerrado y el `v34` ya está en prueba cerrada, verificado por Diego: la segunda cuenta
ya no entra al asistente. Esto es lo que falta para poder cobrarle a alguien que no sea él.

Son **dos cosas y una es un defecto que ya está en producción.** Empieza por esa.

---

## Parte 1 — Cancelar no es perder lo pagado

`translatePlaySubscription` (`functions/src/ai/playSubscriptionVerifier.ts:103`) concede el
entitlement **sólo** con `SUBSCRIPTION_STATE_ACTIVE` o `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`. Todo lo
demás cae en `INACTIVE` con `enabled: false`.

**`SUBSCRIPTION_STATE_CANCELED` cae ahí.** Y en Play, una suscripción cancelada **sigue vigente hasta
que termina el periodo pagado**: conserva un `expiryTime` en el futuro.

Traducido: alguien paga $299 el día 1, cancela el día 3 porque no quiere que se le renueve, **y
pierde la IA en ese momento**, con 27 días pagados por delante. Eso es un reembolso pedido con razón
y una reseña de una estrella. **Ya está desplegado.**

**El arreglo:** `CANCELED` con vigencia futura concede igual que `ACTIVE`. No hace falta lógica de
fechas nueva — el entitlement ya guarda `expiresAtEpochMillis` y `isEntitlementValid` lo compara
contra el reloj del servidor, así que una suscripción cancelada **se apaga sola** el día correcto.

Lo que **no** concede, y aquí no te muevas:

| Estado | Concede | Por qué |
|---|---|---|
| `ACTIVE`, `IN_GRACE_PERIOD` | **Sí** | Como hoy |
| `CANCELED` | **Sí**, hasta `expiryTime` | Ya pagó ese periodo |
| `ON_HOLD`, `PAUSED` | No | El cobro falló o está pausada |
| `EXPIRED` | No | Se acabó |
| `PENDING`, `UNSPECIFIED` | No | Todavía no hay dinero |

---

## Parte 2 — El consumidor de las notificaciones

Hoy Play publica en `projects/organitazionlabs/topics/play-rtdn-cuentivo` —creado, con permiso
otorgado y **notificación de prueba recibida**— y **nadie lo escucha**. Nada revoca cuando alguien
pide reembolso.

Función nueva disparada por Pub/Sub sobre ese tópico, región **`us-south1`**, con el secreto de la
cuenta de servicio de Play ligado, igual que `verifyAiSubscription`. **El secreto de Gemini no se
liga aquí.**

### La regla que gobierna todo el diseño

**La notificación es una señal, no un veredicto.** El consumidor **no decide el estado a partir del
tipo de evento**: toma el `purchaseToken`, **le pregunta a Play** con
`purchases.subscriptionsv2.get` y actúa según el estado real que Play devuelva, reusando
`translatePlaySubscription` ya corregido con la Parte 1.

Esto no es un rodeo. Es lo que hace que un mensaje duplicado, reintentado o fuera de orden dé el
mismo resultado: **Play siempre devuelve el estado de ahora**, así que el orden de llegada deja de
importar.

### Lo que tiene que manejar

- **`testNotification`**: reconocerla y salir sin error. Diego va a mandar más desde Play Console y
  no pueden ensuciar los logs ni disparar reintentos.
- **`packageName` distinto de `com.cuentivo.app`**: ignorar.
- **`subscriptionNotification`**: el camino principal descrito arriba.
- **`voidedPurchaseNotification`**: es el reembolso, el caso que más importa. Mismo camino: consultar
  Play y escribir el estado real.
- **Un token sin uid enlazado**: no hay a quién revocarle nada. Regístralo y termina bien.
  **Nunca crees un entitlement desde una notificación** — conceder es camino de compra, no de aviso.
- **Fallo al consultar Play**: deja que el mensaje se reintente. **No escribas nada:** un timeout no
  puede quitarle el plan a nadie. Falla cerrado hacia *no tocar*, no hacia *revocar*.

### Reusa lo que existe, no lo dupliques

- `hashPurchaseToken` y `tokenBindingPath` para resolver el uid — el ledger token→uid de T3 ya está.
- `entitlementPath` y el repositorio de entitlement para escribir.
- **Estado absoluto, nunca sumar tiempo.** `expiresAtEpochMillis` es el que devuelve Play.
- El reloj es el del servidor.

---

## Invariantes que no se tocan

- **La concesión sigue siendo camino de compra.** Esta función **quita o ajusta**; nunca otorga a
  quien no compró.
- **Un `purchaseToken` pertenece a un solo uid**, y no se reasigna. El ledger es primer escritor gana.
- **Nada de PII, montos, saldos ni tokens** en logs. Ni el `purchaseToken`, ni siquiera truncado.
  Regla 4.
- **Registrar un pago o abono nunca se bloquea.**
- `firestore.rules` no cambia: `aiEntitlements` sigue cerrado al cliente.

## Lo que NO entra

La atribución del Pro histórico. El enforcement de App Check. El simulacro del apagador. Y **el
despliegue, que autoriza Diego** — aunque esta vez dilo explícitamente en tu reporte, porque sin
desplegar esto no existe.

## Evidencia obligatoria

Corre `/gate`. Y en `functions/`, **los tres comandos por separado** — `npm test`,
`npm run test:firestore-emulator`, `npm run lint`.

Pruebas mínimas, y la primera es la que protege al cliente:

- **Cancelada con vigencia futura → concede.** Es el defecto de la Parte 1 hecho prueba.
- `ON_HOLD` y `EXPIRED` → no conceden.
- Notificación de prueba → termina bien, sin escribir nada.
- Token sin uid enlazado → no escribe y no truena.
- **El mismo mensaje procesado dos veces deja el mismo estado.**
- Fallo al consultar Play → **no se toca el entitlement existente**.

Y contra el emulador: conceder, revocar por reembolso, y reprocesar.

**Línea base: 940 pruebas JVM en 104 clases; functions 266; gate del emulador 6.** Deben subir.
`git diff --check` limpio y árbol final con **sólo** `?? docs/superpowers/handoffs/`.

Comitea al cerrar incluyendo este brief.
