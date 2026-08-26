# Brief para AGY — N5 / T2: Billing con dos productos y cambio de plan

**Repo:** `OrganizationLabs-neo-fintech` · **Rama:** `diegodevxd/feature-cuentivo-ai` ·
**Base:** `2569f39` · **Plan:** `docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md`

T1 ya está cerrada y commiteada. **No la rehagas.** `SubscriptionTier` ya tiene `AI`, `rank`,
`includesProBenefits` y `AI_PRODUCT_ID = "cuentivo_ai_mensual"`; `SubscriptionStatus.tierFrom` ya
resuelve con `maxByOrNull { it.rank }`. Lo que falta es todo de la capa Android de billing.

## Qué está mal hoy

`app/src/main/java/com/cuentivo/app/billing/BillingGateway.kt` sigue siendo de un solo producto:

- `loadProduct()` consulta **sólo** `SubscriptionTier.PRO_PRODUCT_ID` y guarda el resultado en un
  único campo `proProduct`.
- `launchPurchase(activity, userId)` abre siempre una **compra normal** de ese único producto.
- `onPriceLoaded(String?)` publica **un solo** precio formateado.
- No se configura ninguna identidad ofuscada en `BillingFlowParams`.

Consecuencia: si sólo añades el segundo id a la consulta, un usuario Pro que compre CuentivoAI
acaba con **dos suscripciones activas cobrando a la vez** ($199 + $299). Eso es un reembolso y una
reseña de una estrella, no un bug menor.

## Alcance de T2

### 1. Dos productos, dos precios

- `loadProduct()` consulta ambos ids en la misma `QueryProductDetailsParams` y guarda ambos
  `ProductDetails` (p. ej. un `Map<String, ProductDetails>` o dos campos).
- `onPriceLoaded` deja de ser un único `String?`. La UI necesita el precio de **cada** plan, así
  que cambia la firma a algo que los distinga por producto. Mantén la regla que ya está
  documentada en el código: el precio se lee de Play, **nunca** se escribe a mano — un `"$299 MXN"`
  hardcodeado estaría mal para cualquiera fuera de México.
- `applyPurchases` / `acknowledge` ya recorren la lista completa: verifica que acusan recibo de
  **ambos** productos. Play reembolsa a los tres días lo que no se acuse.

### 2. Cambio de plan como reemplazo, no como segunda compra

Esto es el corazón de T2.

- `launchPurchase` recibe **qué plan** se está comprando.
- Si el usuario ya tiene una suscripción activa, el flujo se lanza con
  `SubscriptionUpdateParams`, pasando el `purchaseToken` de la suscripción anterior, en vez de
  abrir una compra limpia.
- **Fija una política de prorrateo explícita y escrita en el código**, con el porqué en un
  comentario. No dejes la que Play traiga por defecto sin decidirla.
- Cubre también la baja AI → Pro si se ofrece en la UI.
- Trata el estado transitorio en que Play todavía reporta **ambas** compras: `tierFrom` ya resuelve
  al mayor, pero necesita prueba de que la ruta de Android no intenta comprar encima.

**Criterio de aceptación:** no debe existir ninguna ruta de la app por la que alguien termine con
las dos suscripciones activas.

### 3. Los datos que T3 va a necesitar

T3 (entitlement server-side validado contra la Play Developer API) queda desconectada si T2 no
prepara esto:

- **`obfuscatedAccountId` estable y no reversible** derivado del uid — hash, no el uid en claro —
  puesto en `BillingFlowParams` al lanzar la compra.
- Captura del **`purchaseToken`** de la compra y su envío a la callable de verificación.
- **El `purchaseToken` no se registra jamás** en logs, Analytics ni Crashlytics. Es la regla 4 del
  proyecto: nada de PII, saldos ni tokens.
- El tier calculado en Android **nunca** concede la IA por sí solo. Es señal de UI; quien concede
  es el servidor. Deja eso explícito en el código.

## Reglas del repo que aplican

- Dinero siempre `Long` en centavos. Cero `Double`/`Float`.
- Nada de PII, saldos ni tokens en logs, Analytics ni Crashlytics.
- **Registrar un pago o abono nunca se bloquea** por plan. Esta tarea no toca ese camino.
- Los `object` de `domain/` son Kotlin puro sin `Context`: la lógica testeable va ahí, el
  `BillingGateway` se queda con la parte Android.
- Textos de UI en `values/` y `values-en/` con paridad de claves. Nada de literales en Composables.
- **No toques** `docs/superpowers/handoffs/` — es de Diego y va sin trackear.

## Evidencia obligatoria antes de decir que está hecho

No declares la tarea completa sin ejecutar de verdad y pegar la salida:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Reporta el conteo real del XML de `app/build/test-results/`, no un resumen. Línea base actual:
**278 pruebas, 0 fallos, 0 errores**. Además `git diff --check` limpio y árbol final con
**sólo** `?? docs/superpowers/handoffs/`.

## Lo que NO entra en T2

T3 (validación server-side), T4 (razones de rechazo), T5 (flag remoto), T6 (transporte de release),
T7 (UI del plan y muro). Un commit por tarea; no adelantes trabajo de otra dentro de éste.

## Dependencia externa

La verificación de punta a punta necesita que Diego cree `cuentivo_ai_mensual` en Play Console
(T0). Sin eso el código se escribe y compila, pero la compra real no se puede probar. Dilo en el
reporte si te frena, no lo simules.
