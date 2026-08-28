# Brief para AGY — N5 / T6: transporte de release

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `0da80f9` · **Plan:**
`docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md`

T1, T2, T3 y T7 están cerradas con evidencia. Ya existe la pantalla que vende el plan de $299 y ya
existe la callable que verifica la compra contra Play. **Y aun así, un usuario que instale el AAB
desde Play y pague, no recibe nada.** Eso es T6.

## El hueco real, verificado en el código de `0da80f9`

El relevo decía que faltaba una cosa. Son **tres**, y la segunda es la grave.

### 1. No hay transporte de IA en release

`app/src/release/java/com/cuentivo/app/ui/ai/AiDebugTransportRelease.kt:13` devuelve `null`, y
`CuentivoApp.kt:300` lo consume. Con `null`, `AiEntryPolicy` enruta a `UnavailableInCurrentBuild` —
el suscriptor que ya pagó ve un aviso de "no disponible en esta versión". Correcto como estado
intermedio de T7; inaceptable como release.

Además, `FirebaseCallableAiGateway.kt` **sólo existe en `app/src/debug/java/com/cuentivo/app/ai/`**.
No es que el release no lo use: no está compilado ahí.

### 2. La verificación de la suscripción **no funciona en release**, y eso rompe T3

`app/src/release/java/com/cuentivo/app/billing/SubscriptionVerificationTransportRelease.kt` devuelve
un `NoOpSubscriptionVerificationGateway` que **siempre falla**:

```kotlin
onComplete(Result.failure(IllegalStateException("Subscription verification callable not configured in release build yet")))
```

Ese gateway es el que `MainActivity.kt:375` construye y el que `onPurchaseTokenReceived`
(`MainActivity.kt:385-394`) invoca al recibir el `purchaseToken` de una compra de AI.

**Consecuencia:** en el AAB, alguien paga $299, Play cobra, y el `purchaseToken` muere en un no-op.
`aiEntitlements/{uid}` nunca se escribe. La persona pagó y no tiene el plan. T3 está desplegada y
probada, pero **inalcanzable desde el build que vamos a publicar**.

Esto es lo primero que hay que arreglar, y no es opcional en T6: sin ello, la prueba cerrada cobra
dinero real sin conceder nada.

### 3. El SDK de callables no entra al release

`app/build.gradle.kts:116`:

```kotlin
debugImplementation("com.google.firebase:firebase-functions")
```

Mientras esa línea diga `debugImplementation`, **ninguno** de los dos transportes anteriores puede
existir en release aunque muevas los archivos. Es la causa raíz de 1 y 2.

## Alcance de T6

### A. La dependencia

`firebase-functions` pasa a `implementation`. Es el único cambio de dependencias que autoriza este
brief.

**No toques** `firebase-appcheck-debug` (`app/build.gradle.kts:136`): sigue siendo
`debugImplementation` y el comentario que está encima explica por qué — un proveedor que reparte
tokens a petición en el APK publicado anula App Check por completo. `AppCheckProvider` de release ya
usa Play Integrity y **se queda como está**.

### B. El gateway se mueve, no se duplica

`FirebaseCallableAiGateway.kt` sube de `app/src/debug/java/com/cuentivo/app/ai/` a
`app/src/main/java/com/cuentivo/app/ai/`. Igual el de verificación: la clase
`FirebaseCallableSubscriptionVerificationGateway` sube a `main`.

**Una sola implementación de cada uno.** Este es exactamente el patrón que ya corregimos cuatro
veces en este proyecto: no dejes una copia en `debug/` y otra en `release/`. Lo único que se queda
partido por source set son las **funciones fábrica** — `debugAiTransport()` y
`subscriptionVerificationGateway()` — y a partir de T6 las dos devuelven la implementación real en
los dos lados. Si las dos fábricas quedan idénticas y ya no hay diferencia entre source sets, dilo
en el reporte y propón colapsarlas; no lo decidas tú.

### C. La prueba que se contradice con T6, y cómo tratarla

`app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt:298-304` afirma hoy:

```kotlin
assertFalse("release transport must not reference Firebase", source.contains("Firebase"))
```

T6 **invierte esa regla a propósito**. No la borres en silencio ni la ajustes hasta que pase: es una
guarda de seguridad y merece una decisión escrita.

La frontera que sigue importando **no es** que release no toque Firebase, sino que
`app/src/main/java/com/cuentivo/app/ui/` no dependa de un source set. Reescribe la guarda para
afirmar lo que sí sigue siendo cierto después de T6:

- El proveedor de App Check de **debug** no aparece en el source set de release.
- `RECORD_AUDIO` sigue declarado **sólo** en `app/src/debug/AndroidManifest.xml`.
- Las fábricas siguen existiendo en ambos source sets con la misma firma.

Y ojo: esas pruebas leen el **texto fuente** con `File(...).readText()`. Una aserción sobre texto no
demuestra comportamiento. Donde puedas afirmar la conducta en vez del texto, hazlo.

### D. Lo que no cambia

- **Ninguna guarda se debilita.** T6 mueve código entre source sets y cambia un `debugImplementation`.
  No relaja cuota, rate limit, entitlement, App Check ni reglas de Firestore.
- El gateway sigue colapsando **todo** fallo a `CallableAiProposalResult.Unavailable` **sin logs**.
  Distinguir motivos es T4, no esto.
- **Ninguna key, token ni `purchaseToken` entra al APK, a un log, a Analytics ni a Crashlytics.**
  Regla 4 del proyecto.
- El OCR se queda fuera: `debugAiOcrEngine()` sigue devolviendo `null` en release. La beta es
  text-first, igual que la copia que cerramos en T7. (Nota aparte para el reporte, no para el
  código: `com.google.mlkit:text-recognition` ya está como `implementation` en la línea 131, así que
  el AAB **ya carga** esa librería sin que nada la consuma. Eso es peso muerto y una decisión de
  Diego, no tuya. Sólo repórtalo.)
- No renombres `AiDebugTransport`, `debugAiTransport()` ni `AiDebugLabScreen` aunque los nombres
  digan "debug" y ya no lo sean. Ensucia el diff justo donde hay que auditarlo. Anótalo como deuda.

### E. R8

El release compila con `optimization { enable = true }`. `proguard-rules.pro` ya conserva
`com.google.firebase.**` y sus miembros, y los dos gateways hablan con `Map<String, Any?>` y casts
`as?`, sin clases modelo que R8 pueda renombrar. **En principio no hacen falta reglas nuevas** — pero
"en principio" no es evidencia: si `assembleRelease` o el AAB minificado muestran otra cosa, agrega
la regla mínima y explica cuál y por qué.

## Lo que NO entra

T4 (razones de rechazo), T5 (flag remoto), T8 (observabilidad y rollback), T9 (gate final y AAB).
Tampoco Real-time Developer Notifications, ni precios, ni límites.

## Qué se puede verificar ahora y qué no

Sé explícito en el reporte, no lo mezcles:

- **Sí se puede ahora:** que `assembleRelease` compile con minificación; que el APK/AAB de release
  contenga el SDK de functions; que las fábricas de release devuelvan implementación real y no
  `null`; que las guardas de source set sigan pasando.
- **No se puede todavía:** que la callable responda de verdad desde un build de release. Requiere el
  AAB en prueba interna, instalado **desde Play Store**, para que App Check atestigüe con Play
  Integrity. **No simules esa verificación ni la des por hecha.** Dila como pendiente conocido.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

`assembleRelease` **no es opcional en esta tarea**: es la única que toca lo que llega al build
publicado, y el `debugImplementation` movido sólo se rompe ahí.

Más: conteo real del XML de `app/build/test-results/testDebugUnitTest/` — **línea base 908 pruebas,
0 fallos, 0 errores**, puede subir, nunca bajar. Paridad de claves ejecutada y reportada si tocaste
textos. `git diff --check` limpio. Árbol final con **sólo** `?? docs/superpowers/handoffs/`, que es
de Diego y no se toca.

Y comprobación directa de que `RECORD_AUDIO` sigue sin estar en release, con `aapt2` sobre el APK
empaquetado, igual que se hizo al cerrar N3: release `RECORD_AUDIO=0`, debug `=1`.
