# Brief para AGY — Corrección de T2: pureza del dominio y lógica muerta

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `20ad776` · **Tu commit revisado:** `4a394fd`

T2 quedó bien en lo difícil. Verificado por mí, no supuesto: **888 pruebas JVM, 0 fallos, 0 errores**;
el `purchaseToken` no aparece en ningún log, Analytics ni Crashlytics; el `obfuscatedAccountId` es
SHA-256 del uid y es irreversible; y la rama de reemplazo de `BillingGateway.kt:210-229` sólo entra
cuando existe una compra previa distinta, así que el `?: UPGRADE_REPLACEMENT_MODE` de la línea 222
no puede convertir una compra nueva en un reemplazo. La política de prorrateo está decidida y
razonada en el código. Nada de eso hay que tocarlo.

Quedan dos cosas.

## 1. `SubscriptionReplacementPolicy` rompe la pureza de `domain/`

`app/src/main/java/com/cuentivo/app/domain/SubscriptionReplacementPolicy.kt` importa
`com.android.billingclient.api.BillingFlowParams` y expone sus constantes como `Int`.

`CLAUDE.md` es explícito: **los `object` de `domain/` son Kotlin puro sin dependencias de Android**,
y ésa es exactamente la razón por la que esa capa corre en `testDebugUnitTest` sin emulador. Hoy
compila y pasa porque las constantes de Play son enteros en un JAR, pero la regla existe para que
la capa de reglas no quede amarrada al SDK de una tienda. Un `Int` suelto tampoco dice nada en la
firma: `replacementModeFor` devuelve `Int?` y podría ser cualquier número.

**Lo que hay que hacer:**

- La policy declara su **propio enum**, p. ej. `enum class ReplacementMode { CHARGE_PRORATED, DEFERRED }`,
  y `replacementModeFor` devuelve `ReplacementMode?`. Sin `import com.android.*` en el archivo.
- **`BillingGateway` traduce** ese enum a la constante de `BillingFlowParams.SubscriptionUpdateParams.ReplacementMode`
  en el punto donde arma el flujo. La traducción vive en la capa Android, que es la que sí conoce Play.
- `SubscriptionReplacementPolicyTest` afirma sobre el enum propio, no sobre constantes de Play.
- El razonamiento que ya escribiste (por qué prorrateo al subir y diferido al bajar) **se conserva
  tal cual**. Es correcto y es lo valioso del archivo; sólo cambia el tipo que devuelve.

Verificación: `grep -rn "^import android\|^import com.android" app/src/main/java/com/cuentivo/app/domain/`
debe dejar de listar este archivo. `LegacyMigrationRepository.kt` sí puede seguir ahí: está
documentado en `CLAUDE.md` como la única excepción, porque toca la API de Room a propósito.

## 2. Hay lógica probada que no es la que se ejecuta

`SubscriptionReplacementPolicy.findProductToReplace(...)` e `isProductAlreadyActive(...)` tienen
pruebas, pero **nadie los llama en `app/src/main`**. `BillingGateway.kt:211-218` reimplementa esa
misma selección a mano sobre objetos `Purchase` de Play.

Eso es peor que no tener las funciones: la suite da confianza sobre un código que no se ejecuta, y
el que sí se ejecuta no está cubierto. Y son dos implementaciones que pueden divergir — justo en la
decisión de a quién se le cancela la suscripción.

**Elige una y deja sólo una:**

- **Preferido:** que `BillingGateway` mapee sus `Purchase` de Play a `PlayPurchase` y use
  `findProductToReplace`, para que la ruta de producción sea la ruta probada. Esa conversión ya
  existe en el gateway para `applyPurchases`.
- Si por algo no se puede, **borra** las dos funciones y sus pruebas, y no dejes lógica sin uso.

## Lo que NO entra

T3, T4, T5, T6, T7. Esto es corrección de T2, en su propio commit. No cambies la política de
prorrateo, el hasher, el manejo del token ni la consulta de los dos productos.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Reporta el conteo real del XML de `app/build/test-results/`. **Línea base: 888 pruebas, 0 fallos,
0 errores** — no puede bajar. Más `git diff --check` limpio y árbol final con **sólo**
`?? docs/superpowers/handoffs/`, que es de Diego y no se toca.
