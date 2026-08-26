# Brief para AGY — N5 / T3: entitlement server-side verificado por Play

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `542da12` · **Diseño maestro:**
`docs/superpowers/specs/2026-08-25-cuentivoai-play-entitlement-design.md`

T1 y T2 están cerradas con evidencia (899 pruebas JVM, 101 clases, 0 fallos). T3 es el trabajo más
largo del plan y el que desbloquea B3: hoy el entitlement de CuentivoAI **se creó a mano** para un
solo uid de prueba. Falta que lo escriba una compra verificada.

**El diseño ya existe y no se rediseña.** Léelo entero antes de escribir código. Este brief sólo lo
aterriza y le pone los límites de este incremento.

## El principio, que gobierna todo lo demás

**El cliente nunca declara su entitlement.** Manda un `purchaseToken` opaco; el servidor decide. Un
APK modificado puede decir "soy AI" — por eso `authorizeAiAction` no debe creerle nunca, ni con Auth
y App Check válidos. El tier que Android calcula es señal de UI y nada más.

## Lo que ya está y no hay que tocar

- `functions/src/ai/controlPlane.ts:20` ya define
  `AiEntitlement = { enabled: boolean, expiresAtEpochMillis: number }`.
- `isEntitlementValid(entitlement, nowEpochMillis)` (`controlPlane.ts:92`) ya compara contra el reloj
  del servidor.
- `AI_ENTITLEMENT_REQUIRED` ya existe como razón propia y ya falla cerrado.
- Las reglas ya niegan al cliente el acceso directo a `aiEntitlements/`, `aiUsage/`, `aiRateWindows/`
  y `aiRequestLedger/`. Sólo escribe el Admin SDK. **No las relajes.**
- La colección es **`aiEntitlements/{uid}`**, plural. No crees la singular.
- Del lado Android, `BillingGateway` ya captura el token y lo emite por `onPurchaseTokenReceived`.
  El consumidor está **vacío a propósito** en `MainActivity.kt:383`, esperando esta tarea.

## Alcance de T3

### 1. Callable de verificación

Nueva callable en `functions/src/index.ts`, junto a `interpretAiRequest` y `authorizeAiAction`,
misma región **`us-south1`**, con **Auth y App Check obligatorios** igual que las dos existentes.

1. Recibe `purchaseToken` y `productId`.
2. Llama a la Play Developer API (`purchases.subscriptionsv2.get`) con una **cuenta de servicio
   propia**. El secreto de Gemini **no** se liga a esta función, igual que no se ligó a
   `authorizeAiAction`.
3. Traduce la respuesta: estado activo o en periodo de gracia y `lineItems[].expiryTime` →
   `{ enabled, expiresAtEpochMillis }`.
4. Escribe `aiEntitlements/{uid}` con Admin SDK, **en transacción**.

### 2. El consumidor de Android

`MainActivity.kt:383` deja de estar vacío y llama a la callable con el token recibido. Reglas duras:

- **El `purchaseToken` no se registra jamás** en Logcat, Analytics ni Crashlytics. Es la regla 4 del
  proyecto. Tampoco en un mensaje de error ni en un reporte.
- Sólo se manda el token del **usuario autenticado en ese momento**.
- Si la callable falla, **no se concede nada localmente**. El tier de Android no es autoridad.

### 3. Las cuatro invariantes que hay que garantizar con pruebas

Están en el diseño; las repito porque son el criterio de aceptación:

- **Un `purchaseToken` pertenece a un solo uid.** Se ata con el `obfuscatedAccountId` que T2 ya fija
  al iniciar la compra **y** con un ledger token→uid de **primer escritor gana**. Un token que llega
  con otro uid se **rechaza y se registra**, nunca se reasigna. Sin esto, se comparte un token entre
  cuentas y se paga una suscripción para muchos.
- **Idempotencia.** Reprocesar el mismo token no extiende la vigencia dos veces. Se escribe por
  **estado absoluto** (`expiresAtEpochMillis` tal como lo devuelve Play), **nunca sumando tiempo**.
- **Falla cerrado.** Si Play no responde, el entitlement existente **no se toca**: ni se concede ni
  se revoca por un timeout. Un error de red no regala ni quita lo pagado.
- **Reloj del servidor.** La vigencia se evalúa contra el reloj del servidor. Un teléfono con la
  fecha adelantada no extiende nada.

Y dos que vienen de la auditoría de Sol sobre T2:

- **Una compra AI sin uid enlazable falla cerrado.** T2 ya bloquea el lanzamiento con
  `MISSING_USER_ID`; aquí el servidor tampoco acepta un token sin uid autenticado.
- **`tier == AI` calculado en Android nunca es autorización suficiente** para invocar Gemini. La
  autoridad es este documento server-side.

### 4. Pro no se toca

CuentivoAI incluye Pro, pero el camino de Pro está en producción con compras reales. Esto **añade**:
el comportamiento de `FREE` y `PRO` debe quedar idéntico.

## Lo que NO entra en T3

- **Camino 2, las Real-time Developer Notifications.** Es el camino que **revoca** (cancelación,
  reembolso, pausa, hold); el camino 1 sólo concede. Va en su propio incremento con su propio brief,
  porque necesita un tópico de Pub/Sub que todavía no existe. **Dilo en tu reporte como pendiente
  conocido**, no lo simules.
- T4 (razones de rechazo), T5 (flag remoto), T6 (transporte de release), T7 (UI y muro).
- Precio, tope mensual, texto de la ficha de Play. Son decisiones de Diego.

## Lo que depende de Diego y puede bloquearte

Dos cosas que **no** puede hacer un agente, y sin las cuales puedes escribir el código pero no
verificarlo de punta a punta:

1. El producto de suscripción `cuentivo_ai_mensual` creado en Play Console.
2. La Play Developer API habilitada y la **cuenta de servicio** con acceso al desarrollador.

**Escribe todo lo que sí se pueda sin ellas** —la callable, el traductor de la respuesta de Play, el
ledger token→uid, el consumidor de Android, y todas las pruebas con la API simulada— y **di
claramente en el reporte qué quedó sin verificar contra Play real**. No inventes una verificación
que no ocurrió.

## Secretos

**Ninguna clave, token ni credencial va al repositorio, a un log, a un commit ni a un reporte.** La
cuenta de servicio se configura fuera del repo. Si necesitas un valor que no tienes, dilo; no lo
inventes ni lo dejes de marcador en el código.

## Evidencia obligatoria

Esto toca dinero, backend, secretos e idempotencia, así que aplica el **gate completo**, no el
ligero:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Más, en `functions/`: suite completa y build/lint de TypeScript, y pruebas contra el emulador para
**conceder, revocar, reprocesar y token robado** (un token que llega con un uid distinto).

Conteo real del XML de `app/build/test-results/`. **Línea base: 899 pruebas, 0 fallos, 0 errores** —
debe subir, nunca bajar. `git diff --check` limpio y árbol final con **sólo**
`?? docs/superpowers/handoffs/`, que es de Diego y no se toca.

**La evidencia se redacta: nunca el token.**
