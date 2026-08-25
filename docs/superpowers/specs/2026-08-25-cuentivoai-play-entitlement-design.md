# CuentivoAI — entitlement server-side verificado por Play (diseño, sin implementar)

**Estado: DISEÑO. No autoriza código.** Redactado el 2026-08-25 sobre HEAD `0a3826c` a petición del
relevo de Sol. Cierra el punto 3 de ese relevo en su rama de "preparar el diseño sin implementar",
porque el gate de producto **no** autoriza tocar Play/Billing y además hay un prerrequisito duro
que no depende de nosotros.

## Por qué esto no se puede implementar todavía

Dos bloqueos, y ninguno se resuelve escribiendo Kotlin o TypeScript:

1. **No existe el producto de suscripción de CuentivoAI en Play.** El código tiene un solo
   `SubscriptionTier.PRO_PRODUCT_ID` y el enum `SubscriptionTier` sólo declara `FREE` y `PRO`.
   Crear el producto es Fase 9 en la fuente de verdad, y la sección de economía dice literalmente
   que **no se fije ni el precio de $299 ni el tope de acciones hasta medir la beta**. Verificar
   contra Play exige que exista algo que verificar.
2. **El relevo prohíbe cambiar Billing/Play sin plan y autorización.** Añadir un tier `AI` toca
   `SubscriptionTier`, `BillingGateway` y `EntitlementStore`, que hoy sostienen las compras Pro
   reales de usuarios de producción.

Consecuencia práctica: el siguiente incremento **realmente desbloqueado** no es este, es el
enforcement de App Check de producción, que no depende de ninguna decisión comercial y es
prerrequisito de la beta igual que este.

## Lo que ya está resuelto y no hay que rediseñar

El contrato del control plane **ya tiene la forma exacta** que una suscripción de Play necesita:

- `aiEntitlements/{uid}` guarda `AiEntitlement = { enabled: boolean, expiresAtEpochMillis: number }`.
- `isEntitlementValid(entitlement, nowEpochMillis)` ya compara contra el reloj del servidor.
- La decisión `AI_ENTITLEMENT_REQUIRED` ya existe y ya falla cerrado.
- Las reglas de cliente ya niegan acceso directo a `aiEntitlements/`, `aiUsage/`, `aiRateWindows/`
  y `aiRequestLedger/`; sólo el Admin SDK escribe ahí.

**El hueco no es el modelo de datos: es quién escribe ese documento.** Hoy lo escribió una persona
a mano para un solo uid debug. Falta que lo escriba una compra verificada.

## Diseño

### Principio

El cliente **nunca declara su entitlement**. Manda un `purchaseToken` opaco; el servidor decide.
Un APK modificado puede decir "soy AI" — por eso `authorizeAiAction` no debe creerle nunca, ni
siquiera con Auth y App Check válidos.

### Camino 1 — verificación bajo demanda

Callable nueva, en la misma región `us-south1`, con Auth y App Check obligatorios igual que las dos
existentes:

1. Recibe `purchaseToken` y `productId`.
2. Llama a la Google Play Developer API (`purchases.subscriptionsv2.get`) con una cuenta de servicio
   propia. **El secreto de Gemini no se liga a esta función**, igual que no se ligó a
   `authorizeAiAction`.
3. Traduce la respuesta: estado activo o en periodo de gracia y `lineItems[].expiryTime` →
   `{ enabled, expiresAtEpochMillis }`.
4. Escribe `aiEntitlements/{uid}` con Admin SDK, en transacción.

### Camino 2 — notificaciones en tiempo real

Sin esto, una suscripción cancelada o reembolsada sigue viva hasta que el usuario abra la app.
Se suscribe un tópico de Pub/Sub a las Real-time Developer Notifications de Play y una función
traduce renovación, cancelación, pausa, hold y reembolso al mismo documento. Es el camino que
**revoca**; el camino 1 sólo concede.

### Invariantes que el diseño debe garantizar

- **Un `purchaseToken` pertenece a un solo uid.** Sin esto, un token se comparte entre cuentas y se
  paga una suscripción para muchos. Se ata con `obfuscatedAccountId` fijado al iniciar la compra
  **y** con un ledger token→uid de primer escritor gana; un token que llega con otro uid se rechaza
  y se registra, no se reasigna.
- **Idempotencia.** Reprocesar el mismo token o la misma notificación no debe extender la vigencia
  dos veces. Se escribe por estado absoluto (`expiresAtEpochMillis` que devuelve Play), nunca
  sumando tiempo.
- **Falla cerrado.** Si Play no responde, el entitlement existente **no** se toca: no se concede ni
  se revoca por un timeout. Un error de red no debe regalar ni quitar lo pagado.
- **Reloj del servidor.** La vigencia se evalúa contra el reloj del servidor; un teléfono con la
  fecha adelantada no extiende nada.
- **Pro no se toca.** CuentivoAI incluye Pro, pero el camino de Pro ya está en producción con
  compras reales. Este diseño **añade**, no reescribe: cualquier cambio en `SubscriptionTier` debe
  dejar el comportamiento de `FREE`/`PRO` idéntico byte a byte.
- **Registrar un pago o abono nunca se bloquea** por entitlement, cuota ni estado de suscripción.

### Fuera de alcance explícito

Precio, tope mensual, cohorte, texto de la ficha de Play y el propio producto de suscripción. Son
decisiones de producto de Diego, y la fuente de verdad pide medirlas en beta antes de fijarlas.

## Prerrequisitos que no son código

1. Decidir y crear el producto de suscripción de CuentivoAI en Play Console.
2. Habilitar la Google Play Developer API y crear la cuenta de servicio con acceso al desarrollador.
3. Crear el tópico de Pub/Sub y ligarlo a las notificaciones en tiempo real de la app.
4. Autorizar expresamente tocar `SubscriptionTier`, `BillingGateway` y `EntitlementStore`.

Ninguno lo puede hacer un agente: los cuatro son acciones manuales del propietario en consolas
externas. **Ninguna clave ni token va al repositorio, a un log, a un commit ni a un reporte.**

## Gates cuando llegue a autorizarse

Toca dinero, backend, secretos e idempotencia, así que aplica el gate completo, no el ligero:
Functions con suite completa, build/lint de TypeScript, pruebas de emulador para conceder, revocar,
reprocesar y token robado, JVM de Android con `--rerun-tasks`, `assembleDebug`, `assembleRelease`,
`lintDebug`, `git diff --check` y prueba física en el A059P con una compra de prueba real. La
evidencia se redacta: nunca el token.
