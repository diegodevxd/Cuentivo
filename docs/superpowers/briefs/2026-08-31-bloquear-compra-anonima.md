# Brief para AGY — No se le puede cobrar a una sesión anónima

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `1ed3001` · **Origen:** Diego lo vivió en
carne propia el 2026-08-31, con dinero real de por medio

## Lo que pasó, y es el único bloqueo que queda para producción

Diego abrió la app **sin haber iniciado sesión** —o sea, con la sesión anónima que se crea al primer
arranque— y **la app le dejó comprar CuentivoAI**. Pagó.

Después inició sesión con su cuenta real y **no pudo usar lo que había pagado**. Entra al muro, toca
comprar, y Play le dice que ya lo tiene.

**Nada de eso es un fallo de los guardas: cada pieza hizo bien su trabajo.**

- La compra se selló con el `obfuscatedAccountId` del **uid anónimo**.
- Al iniciar sesión, el uid cambió, así que la comprobación estricta de titularidad —correcta— dijo
  *"esta compra no es de esta cuenta"*.
- Y el servidor **rechaza sesiones anónimas** para la IA, por diseño y desde el principio, así que
  la verificación nunca creó entitlement. Lo confirmé en Firestore: `aiPurchaseTokens` estaba
  **vacía** y no se generó ningún documento en `aiEntitlements`.

**El defecto está antes de todo eso: la app vendió algo que era imposible de usar.**

Un anónimo **nunca** va a poder usar CuentivoAI —el servidor no se lo va a permitir— así que
ofrecerle el botón de compra es cobrar por un callejón sin salida. Diego se desatoró porque era él,
sabía qué había pasado y su compra era de prueba. **Un cliente real no puede hacer ninguna de esas
tres cosas.**

## El arreglo

Ya tienes todo lo que hace falta; no hay que inventar nada.

- **`AuthenticationGateway.isAnonymous`** existe (`app/src/main/java/com/cuentivo/app/auth/AuthenticationGateway.kt:26`
  y `:102`, resuelto con `auth.currentUser?.isAnonymous == true`).
- **`PurchaseLaunchPolicy`** ya bloquea por ocho motivos explícitos: `QUERY_PENDING`, `QUERY_FAILED`,
  `USER_MISMATCH`, `MISSING_USER_ID`, `ALREADY_ACTIVE`, `INCONSISTENT_STATE`, `UNKNOWN_PRODUCT` e
  `INVALID_REPLACEMENT`.

**Agrega un noveno motivo para la sesión anónima** y bloquea el lanzamiento de la compra con él.

**Por qué un motivo propio y no reusar `MISSING_USER_ID`:** un anónimo **sí tiene uid**, así que pasa
ese filtro limpiamente — por eso el agujero existía. Y el mensaje que hay que darle es distinto: no
es un error, es *"inicia sesión para poder usar tu plan"*.

### Lo que ve la persona

No un error. **Un camino.** Al tocar comprar sin sesión, se le explica en una línea que necesita una
cuenta para que su plan quede ligado a ella, y se le lleva a iniciar sesión o registrarse. Si después
de eso vuelve, compra normal.

Textos en `values/` y `values-en/` con paridad de claves. **Línea base 552 = 552; cuenta después.**
Nada de literales en Composables. Inglés sin apóstrofos.

### Lo que NO cambia

- **La sesión anónima sigue existiendo y sigue siendo buena.** Es lo que dejó de exigir cuenta para
  probar la app, y arregló la retención. **No la toques**: lo único que se bloquea es *comprar*.
- Gratis y Pro **no se tocan**. Un anónimo puede seguir usando la app entera sin cuenta.
- La comprobación estricta de titularidad **se queda como está**. Es correcta.
- El servidor sigue rechazando anónimos para la IA. Esto no lo sustituye: lo complementa evitando
  que alguien llegue a pagar antes de chocar.
- **Registrar un pago o abono nunca se bloquea.**

## Pruebas

`PurchaseLaunchPolicy` es Kotlin puro, así que esto se prueba en `testDebugUnitTest` sin dispositivo:

- Sesión **anónima** + producto válido → **bloqueado** con el motivo nuevo. Es el caso que Diego
  vivió.
- Sesión **con cuenta real** → **no** bloquea por este motivo; el resto de la política sigue igual.
- Los **ocho motivos anteriores siguen comportándose igual**. Este cambio no puede alterar ninguno.

Y recuerda la regla de `CLAUDE.md`: la prueba **invoca el código que producción ejecuta**. Comprobar
la política a mano no basta si el botón no la consulta — asegúrate de que el camino real pase por
ahí, que es exactamente lo que faltaba aquí.

## Lo que NO entra

Nada más. Es el último bloqueo para producción y quiero el diff mínimo.

## Evidencia y cierre

`/gate`, con `--rerun-tasks` en `testDebugUnitTest` porque esto termina en AAB. **Línea base 940
pruebas JVM en 104 clases, 0 fallos** — debe subir. Paridad contada. `git diff --check` limpio y
árbol final con **sólo** `?? docs/superpowers/handoffs/`.

Cuando esté verde, cierra con el bundle:

- `versionCode` a **36**, `versionName` se queda en **1.18.0**.
- `bundleRelease`.
- `jarsigner -verify` → `jar verified`, y **contraste de SHA1** con `keytool -printcert -jarfile`
  contra `C:/Users/pibed/AndroidStudioProjects/OrganizationLabs-neo-fintech/dist/Cuentivo-v32-1.17.0.aab`,
  que debe dar `25:05:8A:9C:F3:1F:D7:B4:1E:8E:B3:2D:9A:AB:8F:9F:7A:E8:1D:BF`. **Si no coincide,
  PARA** — Play rechaza para siempre un bundle firmado con otra llave.
- `aapt2` para confirmar `RECORD_AUDIO=0` en el APK de release.
- Cópialo a `dist/Cuentivo-v36-1.18.0.aab` y dame la ruta absoluta.

Comitea incluyendo este brief. **No subas nada a Play** — eso lo hace Diego, y va **primero a prueba
interna**.
