# Brief para AGY — Cierre de T2: la carrera que todavía permite cobro doble

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `09be6e8` · **Origen:** auditoría de Sol del
2026-08-26 sobre `4a394fd` y `09be6e8`.

Tu corrección anterior quedó bien: `domain/` volvió a ser puro, la traducción a las constantes de
Play vive en el gateway, y la ruta de producción ya usa la policy probada. Nada de eso se toca.

**Pero T2 no puede cerrarse todavía.** La auditoría encontró dos rutas reales de riesgo económico
que las 21 pruebas focales no cubren, porque son carreras asíncronas y no lógica pura. Las verifiqué
en el código antes de escribirte: son ciertas.

## 1. Bloqueante — se puede comprar AI sin reemplazar Pro

`BillingGateway.kt:70` declara `@Volatile private var activePurchases: List<Purchase> = emptyList()`
y **sólo** se llena en `applyPurchases` (`BillingGateway.kt:312`), es decir cuando responde la
consulta asíncrona de `refresh()`. Pero `launchPurchase` la lee en `BillingGateway.kt:211`.

La secuencia que rompe:

1. Se conecta el cliente; `loadProduct()` y `refresh()` corren de forma independiente.
2. Play devuelve los productos y la entrada de compra queda disponible.
3. La consulta de compras **todavía no responde**.
4. El usuario toca comprar AI.
5. `activePurchases` sigue vacío, así que `findProductToReplace` no ve el Pro activo.
6. Se abre una **compra limpia de AI** sin `SubscriptionUpdateParams`.

Resultado: Pro y AI cobrando a la vez. Es justo lo que T2 existía para impedir.

La variante igual de mala: la consulta **falla** y la caché conserva una lista vieja, así que se
manda un `purchaseToken` vencido o de otra sesión.

**Lo que hay que hacer.** Dejar de construir el flujo desde una caché. Al tocar comprar:

- consultar las compras activas **en ese momento** y construir el flujo sólo con esa respuesta
  exitosa;
- **si Play no responde o la consulta falla, no abrir la compra** — mensaje fijo al usuario, sin
  literales en Composables;
- distinguir explícitamente **"ya resolví y de verdad no hay nada"** de **"todavía no sé"**. Un
  `emptyList()` inicial no puede significar lo mismo que una respuesta vacía confirmada;
- atar esa fotografía de compras **al uid que pidió la consulta**, igual que ya hace `BillingRequest`
  para las respuestas de tier;
- volver a comprobar que la sesión no cambió justo antes de abrir Play.

## 2. Bloqueante — `isProductAlreadyActive` no era deuda menor

Yo lo dejé para T3 y me equivoqué. `SubscriptionReplacementPolicy.kt:41` existe, tiene prueba, y no
protege producción. Hoy:

- si **AI ya está activo** y se vuelve a pedir AI, `findProductToReplace` devuelve `null` (filtra
  `it != targetProductId`) y se intenta una compra limpia;
- si Play reporta **Pro y AI a la vez** y el destino es AI, la política elige reemplazar Pro por AI
  **aunque AI ya exista**;
- lo simétrico al pedir Pro.

Play puede rechazar algunos de esos casos con `ITEM_ALREADY_OWNED`, pero **la app no puede depender
del rechazo de la tienda para proteger dinero**.

**Lo que hay que hacer:**

- usar `isProductAlreadyActive` **antes de cualquier compra**: si el producto destino ya está activo,
  el flujo se detiene y se refresca/reconcilia el estado, no se lanza otra compra;
- tratar **"Pro y AI activos simultáneamente"** como cambio en curso o estado inconsistente: nunca
  lanzar una compra nueva desde ahí.

## 3. Quitar el fallback monetario silencioso

`BillingGateway.kt:227`: si hay producto a reemplazar pero `replacementModeFor` devuelve `null`, se
usa `CHARGE_PRORATED` en silencio.

Hoy esa rama es inalcanzable, porque `findProductToReplace` ya garantiza que el producto actual sea
distinto del destino. Aun así se cambia: **en código que decide cobros, un estado imposible debe
fallar cerrado**, no escoger una política monetaria por omisión. Cancela el flujo y muestra error
fijo si eso ocurre.

## 4. Una compra AI sin uid enlazable debe fallar cerrado

`BillingAccountHasher.obfuscate` devuelve `null` con uid vacío y hoy simplemente se omite
`setObfuscatedAccountId`. Para que T3 pueda validar contra la Play Developer API, esa compra **no
debe abrirse**: sin uid no hay a quién atribuirle la suscripción.

## 5. Pruebas

Las carreras no se prueban desde el gateway. **Extrae una decisión pura** —del estilo
`PurchaseLaunchDecision`— que reciba el estado de la consulta (resuelta / pendiente / fallida), las
compras conocidas, el uid solicitante, el uid actual y el producto destino, y devuelva
`Proceed(replace = …)` / `Blocked(motivo)`. Esa función se prueba en `testDebugUnitTest` y el gateway
sólo la obedece.

Casos mínimos: consulta pendiente; consulta fallida; resuelta y vacía de verdad; Pro activo → AI
(reemplaza); AI activo → AI (bloquea); Pro y AI activos → AI (bloquea por inconsistente); uid
cambiado entre consulta y compra (bloquea); uid ausente (bloquea).

## Lo que NO entra

T3, T4, T5, T6, T7. Esto cierra T2, en su propio commit. No cambies la política de prorrateo, el
hasher, la captura del token ni la consulta de los dos productos.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo real del XML de `app/build/test-results/`. **Línea base: 888 pruebas, 0 fallos, 0 errores** —
debe **subir** con los casos nuevos, nunca bajar. `git diff --check` limpio y árbol final con
**sólo** `?? docs/superpowers/handoffs/`, que es de Diego y no se toca.

## Nota para más adelante (no la implementes aquí)

T7 tendrá que distinguir **"compra AI reportada localmente"** de **"entitlement AI confirmado por el
servidor"**, y nunca tratar `tier == AI` como autorización suficiente para invocar Gemini. Queda
anotado para que no se pierda; no es parte de este commit.
