# Brief para AGY — N5 / T7: UI del plan de CuentivoAI y muro de suscripción

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `795ce24` · **Plan:**
`docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md`

T1, T2 y T3 están cerradas. El producto `cuentivo_ai_mensual` está **ACTIVO** en Play a $299 MXN y
`verifyAiSubscription` está **desplegada** en `us-south1` con su secreto cargado.

**Y aun así nadie puede comprar el plan, porque la app no tiene dónde venderlo.** Eso es T7, y es lo
que ahora bloquea todo lo demás.

## El hueco exacto, verificado en el código

- `MainActivity.kt:2347` es el **único** botón de compra de la app: llama a
  `billing.launchPurchase(this@MainActivity, currentUserId)` **sin indicar plan**, así que cae en el
  valor por omisión `PRO_PRODUCT_ID`. Compra Pro y nada más.
- La carpeta `app/src/main/java/com/cuentivo/app/ui/` **no menciona el plan AI en ningún lado**: cero
  referencias a `SubscriptionTier.AI` o `AI_PRODUCT_ID`.
- `CuentivoApp.kt:362` **esconde por completo** la entrada de CuentivoAI cuando el transporte de
  depuración es `null` — es decir, en release no existe.

T2 ya dejó lista la parte difícil: `launchPurchase` acepta un `targetTier` y sabe hacer el reemplazo
Pro → AI sin cobro doble. Falta la pantalla que lo invoque.

## Alcance de T7

### 1. La pantalla del plan

`SubscriptionSheet.kt` (en `ui/account/`) hoy presenta un solo plan de pago. Pasa a presentar
**tres**: Gratis, Pro y CuentivoAI.

- El precio de cada plan se lee de Play, **nunca se escribe a mano**. T2 ya dejó `onPriceLoaded`
  entregando el precio formateado por producto; úsalo. Un `"$299 MXN"` hardcodeado estaría mal para
  cualquiera fuera de México y quedaría obsoleto el día que cambie el precio.
- Deja claro que **CuentivoAI incluye todo lo de Pro**. El enum ya lo declara con
  `includesProBenefits`. Sin decirlo, quien ya paga Pro va a creer que pierde el respaldo y las
  fotos al cambiarse, y no se cambia.
- El botón de compra debe indicar el plan destino: `launchPurchase(activity, userId, targetTier)`.
  La sobrecarga que recibe `SubscriptionTier` ya existe en `BillingGateway.kt:324`.

### 2. El muro

Hoy la entrada de CuentivoAI se esconde cuando no hay transporte. **Deja de esconderse.** Decisión
de producto del 2026-08-26: la entrada es **visible para todos**, y lo que cambia según el plan es a
dónde lleva.

- Si el tier es AI → entra a CuentivoAI.
- Si el tier es FREE o PRO → **muro de suscripción**, siguiendo el patrón visual que ya usa
  `SubscriptionSheet` para los bloqueos de plan.

**Qué debe decir el muro**, y esto viene de la investigación de competencia del 2026-08-26 que está
en `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md` §2.1: el
diferenciador es que **CuentivoAI ejecuta, no sólo responde**. El competidor más cercano ya tiene un
asistente que contesta "¿quién me debe?". Lo que él no hace es registrar la venta.

Así que el muro vende una **operación completándose**, con un ejemplo concreto — una frase que deja
la venta registrada y el saldo actualizado — **no la palabra "inteligencia artificial"**, que ya no
distingue nada.

### 3. La invariante que no se puede romper

**Registrar un pago o abono nunca se bloquea**, por ningún plan. El muro de CuentivoAI **no puede
tocar ese camino**. `FeatureAccessManager.canRegisterPayment()` devuelve `Allowed` siempre y así se
queda.

**Regresión de UI obligatoria** (la pidió Sol en su auditoría, y es criterio de aceptación): en FREE
y en PRO, tocar "Registrar abono" entra al abono y **nunca** al muro. El muro sólo aparece en la
entrada de CuentivoAI.

### 4. Textos

Todo en `values/strings.xml` y `values-en/strings.xml`, con **paridad de claves**. Hoy están en
**527 = 527**; cuenta después, no confíes en ese número.

**Nada de literales en Composables** — la puerta del idioma de `CLAUDE.md` debe salir vacía. Y
cuidado con los apóstrofos en `values-en/`: redacta el inglés sin ellos ("do not" en vez de "don't")
para no romper el merge de recursos.

## Lo que NO entra

- **T6, el transporte de release.** `AiDebugTransportRelease` sigue devolviendo `null` en este
  commit. T7 construye la pantalla y el muro; que CuentivoAI funcione en release es su propia tarea.
- T4 (razones de rechazo), T5 (flag remoto), T8, T9.
- Cambiar precios o límites. Son decisiones de Diego.

## Por qué esto es lo urgente

Diego quiere verificar T3 comprando de verdad desde su teléfono, en vez de pelearse con el token de
depuración de App Check que se regenera en cada reinstalación. Es el camino correcto: instalando
desde Play, App Check atestigua por Play Integrity y no hace falta ningún token de depuración. Pero
**necesita una pantalla donde comprar**, y esa pantalla es ésta.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Más: conteo de paridad de claves ejecutado y reportado, puerta del idioma limpia,
`git diff --check` limpio, y árbol final con **sólo** `?? docs/superpowers/handoffs/`, que es de
Diego y no se toca.

Conteo real del XML de `app/build/test-results/`. **Línea base: 899 pruebas JVM, 0 fallos** — debe
subir con la regresión del abono, nunca bajar.
