# Brief para AGY — La compra se le acredita a quien no la hizo

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `81e0f99` · **Origen:** prueba real en la
prueba cerrada, 2026-08-28

Diego compró CuentivoAI desde Play con su cuenta y **funciona**: T3 quedó verificada de punta a
punta. Al probar con una segunda cuenta encontró esto:

> Cierro sesión, entro con otra cuenta, y **me deja abrir CuentivoAI como si esa cuenta también lo
> hubiera comprado**. Al mandar un mensaje dice "aún no disponible".

El servidor hizo bien su trabajo: negó. **El cliente mintió.**

## La causa, verificada en el código

`SubscriptionStatus.tierFrom(...)` decide el plan a partir de `PlayPurchase`, y `PlayPurchase`
declara **sólo tres campos**: `productIds`, `isPurchased`, `isAcknowledged`.

**No hay ningún campo de titularidad.** T2 escribe `setObfuscatedAccountId` al **comprar**
(`BillingGateway.kt:263`), pero `applyPurchases` (`BillingGateway.kt:382`) **nunca lee de vuelta**
`purchase.accountIdentifiers?.obfuscatedAccountId`. El vínculo se escribe y jamás se comprueba.

Consecuencia: `queryPurchasesAsync` devuelve lo que compró **la cuenta de Google Play del
dispositivo**, no la cuenta de Cuentivo con sesión iniciada. Cualquier usuario que inicie sesión en
ese teléfono hereda el tier.

**`EntitlementReconciler` no cubre esto.** Resuelve respuestas que llegan tarde o de otra sesión; no
comprueba de quién es la compra.

## Por qué importa más de lo que parece

La seguridad **aguantó**: el servidor es la autoridad y negó la acción. Pero hay dos daños reales:

1. **Se le promete a alguien algo que no tiene.** Entra al asistente y choca contra una pared.
2. **Y el peor, que es comercial:** a esa segunda cuenta la UI le dice que **ya lo compró**, así que
   **nunca le ofrece comprarlo**. Un segundo miembro de la familia en el mismo teléfono no puede
   pagar aunque quiera. Eso no es un mensaje feo: es una venta que no ocurre.

## El arreglo

`PlayPurchase` gana un campo con el identificador ofuscado que Play devuelve, y `tierFrom` recibe el
hash del usuario de Firebase actual —`BillingAccountHasher` ya lo produce— para decidir si esa compra
le concede algo **a esta persona**.

### La trampa que hay que evitar a toda costa

**Hay clientes de Pro en producción que compraron ANTES de que T2 existiera.** Sus compras **no
tienen `obfuscatedAccountId`**. Si exiges coincidencia a todas, **le quitas Pro a gente que está
pagando de verdad**, en la versión que sube a producción. Eso es mucho peor que el bug original.

La regla, entonces, distingue los tres casos:

- **Sin identificador ofuscado** (compra heredada, previa a T2) → **concede como hoy**. No hay a quién
  compararla y quitársela sería romper a un cliente real.
- **Con identificador que NO coincide** con el usuario actual → **no concede nada**.
- **Con identificador que coincide** → concede.

Como `cuentivo_ai_mensual` sólo existe desde T2, **toda compra de AI trae identificador**: para AI la
comprobación es estricta sin excepción. La tolerancia es únicamente para Pro heredado.

### Lo que NO cambia

- **El servidor sigue siendo la autoridad.** Esto arregla lo que se le muestra a la persona; no
  sustituye la verificación server-side, que se queda tal cual.
- **No relajes nada más.** Un tier concedido de más sigue chocando contra `authorizeInTransaction`.
- **El `purchaseToken` no se registra jamás** en Logcat, Analytics ni Crashlytics. Regla 4.
- **Registrar un pago o abono nunca se bloquea.**

## Pruebas — y lee la regla nueva de `CLAUDE.md` antes

`tierFrom` es Kotlin puro, así que esto se prueba en `testDebugUnitTest` sin dispositivo. Cubre:

- Compra de AI con identificador de **otro** usuario → **FREE**. Es el caso que Diego encontró.
- Compra de AI con identificador **del mismo** usuario → **AI**.
- Compra de Pro **sin** identificador → **PRO**. Es la regresión que protege a los clientes actuales;
  si esta prueba no existe, el arreglo puede romperlos sin que nadie lo note.
- Usuario sin sesión (hash nulo) → no concede nada de AI.
- Y que `applyPurchases` **propague** el identificador de verdad desde `purchase.accountIdentifiers`,
  no un valor inventado en el test.

Esa última importa: `CLAUDE.md` ahora exige que la prueba **invoque el código que producción
ejecuta**. Una prueba que sólo arme un `PlayPurchase` a mano comprueba `tierFrom`, no el cableado —
y el cableado es justo lo que faltaba aquí.

## Lo que NO entra

El consumidor de las notificaciones de Play. El despliegue de functions, que autoriza Diego. Y
**nada de tocar precios, topes ni reglas de Firestore**.

## Evidencia obligatoria

Corre el gate del proyecto — está empaquetado en `.claude/skills/gate/SKILL.md` y trae la receta del
emulador. Como mínimo:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

**Línea base: 930 pruebas JVM en 104 clases, 0 fallos.** Debe subir. Conteo real del XML, no el
resumen de Gradle. `git diff --check` limpio y árbol final con **sólo**
`?? docs/superpowers/handoffs/`.

**El teléfono de Diego está en modo depuración**, así que si quieres confirmarlo en dispositivo,
`connectedDebugAndroidTest` está disponible. Repórtalo aparte de las JVM.

Comitea al cerrar, incluyendo este brief. **No despliegues.**
