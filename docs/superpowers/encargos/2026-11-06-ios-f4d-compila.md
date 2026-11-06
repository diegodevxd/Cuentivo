# Encargo iOS — F4-D: que compile `:iosKit` y las pruebas comunes en iOS

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `fix(ios): ...`. Reporte en `C:/tmp/oc-ios-f4d-compila-reporte.md`.

El run 42151646670 falló. Log completo: `C:/tmp/ios-relevo/log-f4d.txt` (filtra `\.kt:[0-9]+:[0-9]+: error` y `^e: `).
Windows no compila iOS: tus cambios solo se verifican en CI. **No adivines nombres:** confírmalos en el fuente de GitLive v2.8.0, en crudo en
`raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/` (`firebase-auth/src/appleMain`, `firebase-auth/src/commonMain`).

**Arregla solo errores de compilación, sin cambiar comportamiento.** Lee antes la versión Android de cada clase para conservar su semántica.

## Errores

1. **`shared/src/commonTest/.../auth/AuthenticationFlowPolicyTest.kt:121`:** `Unresolved reference 'java'`. Native no tiene `.java`. Reescribe esa
   parte con `kotlin.test` y sin JVM; la prueba debe seguir ejerciendo el mismo código de producción y afirmar lo mismo. Es el único error del
   paso de pruebas, pero revisa el archivo por si hay más JVM (`commonTest` en Android lo tolera y en Native no).
2. **`IosAuthenticationGateway.kt` líneas 146, 192, 237, 308 y 342:** se pasa `Int` donde se espera `String?`, con una referencia sin resolver.
   Lee el log completo de cada una y usa el tipo real de la excepción. Si el `when` depende de un código de error de Firebase, los códigos deben seguir distinguiéndose igual que en Android.
3. **`IosAuthenticationGateway.kt:52`:** no existe `creationTimestamp`. Usa la API real de GitLive (`metaData?.creationTime`, confírmalo). **Cuida las unidades:** confirma en el fuente si son segundos o milisegundos y deja lo que Android entrega en este campo. Anótalo en el reporte.
4. **`IosAuthenticationGateway.kt:94`:** `signOut()` es `suspend` y se llama desde una función que no lo es. Si el contrato común no es `suspend`,
   usa la API nativa síncrona `FIRAuth.auth().signOut(error)`. **Prohibido** `runBlocking`, `GlobalScope` o lanzar sin esperar: el cierre de sesión debe haber
   ocurrido cuando la función regrese, y un fallo no se traga en silencio si Android no lo hace.
5. **`IosCallableAiGateway.kt:32` e `IosFirestoreDeletionGateway.kt`:** faltan `@OptIn(ExperimentalForeignApi::class)` (y `BetaInteropApi` si el compilador lo pide). Ponlo a nivel de clase o archivo.
6. **`IosFirestoreDeletionGateway.kt:47`:** `return` en función con cuerpo de expresión; pásala a cuerpo de bloque, con el mismo flujo.
7. **`IosFirestoreDeletionGateway.kt:138-139`:** anotación en variables locales que solo aplica a campos. Quítala solo si no se necesita, o mueve el valor a un campo.

Después de corregir, **relee los tres archivos de `iosKit` buscando errores del mismo tipo** que el compilador aún no llegó a mostrar, y mira también el Swift
(`iosApp/`, proveedores de Google y Apple), que todavía no se ha compilado: un `Boolean` de lambda Kotlin es `KotlinBoolean` (`.boolValue`, `KotlinBoolean(bool:)`).

## Puerta

```powershell
.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```
Entorno Norton de `CLAUDE.md`. **Sin `--rerun-tasks`.** Total de pruebas leído de los XML: no puede bajar de 881 + 603. Reporta la salida real, qué decidiste en 2, 3 y 4 con
la ruta del fuente de GitLive que lo confirma, y lo que queda "pendiente de CI". Nunca abras ni imprimas `GoogleService-Info.plist`, `google-services.json` ni llaves. El log no los contiene; si vieras un valor sensible, no lo copies y avísalo.
