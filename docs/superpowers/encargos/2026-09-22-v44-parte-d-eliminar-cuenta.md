# v44 · Parte D — "Eliminar cuenta" no borra la cuenta (OpenCode)

**Rama:** sigue en `diegodevxd/v44-libreta`, **encima de lo que ya hiciste de A, B y C**. **Implementa:**
OpenCode, **sin delegar**. **Audita:** Codex. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de
release. El gate sí se corre.** Reporte en `C:/tmp/oc-v44-parte-d-reporte.md`.

Google Play **exige** que eliminar la cuenta funcione. Hoy no funciona.

## Lo que pasó en el teléfono de Diego (2026-09-22), verificado por Claude

Diego pulsó Tu cuenta → Eliminar cuenta. La pantalla **se quedó ahí**; tuvo que cerrar y abrir la app.
Al volver **no le pidió iniciar sesión**. Claude lo comprobó en Firebase:

- **Firestore sí se borró**: el negocio viejo desapareció. Al reabrir, la app creó uno nuevo vacío
  ("Mi negocio", `createdAt` 2026-09-23 01:15 UTC) porque la sesión seguía viva.
- **El usuario de Auth NO se borró**: mismo `uid`, creado el 2026-08-12, `lastLoginAt` 2026-09-18.

## Lo que Claude ya localizó — confírmalo, no lo supongas

1. **El orden es el equivocado.** `MainActivity.kt:2549-2597`: borra la nube → borra Room
   (`clearAllTables`) → olvida entitlement → **y al final** `authenticationGateway.deleteAccount`. Si Auth
   falla, la persona queda **con sesión abierta y sin datos**, que es exactamente el estado raro de Diego.
2. **Firebase exige un inicio de sesión reciente** para borrar un usuario (el de Diego era de hace 4
   días). La app no re-autentica antes.
3. **Probable bug de código de error:** `domain/AccountDeletionFailure.kt:37` compara contra
   `"requires-recent-login"`, que es el formato **web**. En Android `FirebaseAuthException.errorCode`
   es **`ERROR_REQUIRES_RECENT_LOGIN`** (y existe `FirebaseAuthRecentLoginRequiredException`). Si es así,
   el aviso de "vuelve a iniciar sesión" **nunca se ha mostrado**. Verifícalo contra el SDK y díselo al
   reporte con la prueba.
4. **Por qué la pantalla se congela**: averígualo (¿el callback de `deleteAccount` nunca llega? ¿llega
   y el mensaje no se ve? ¿`authenticatedUserId` no cambia?). Con archivo:línea.

## Lo que tiene que pasar

- **Re-autenticar con Google primero**, antes de borrar nada: pedir la credencial con el mismo
  Credential Manager del inicio de sesión (`auth/AuthenticationGateway.kt`) y
  `user.reauthenticate(credential)`. Si la persona cancela o falla, **no se borra nada** y se le dice.
- Sólo con la re-autenticación hecha: nube → local → Auth, como hoy.
- Al terminar: **fuera de la sesión y a la pantalla de inicio de sesión**, sin reiniciar la app.
- Si algo falla a mitad, que el mensaje diga qué pasó y la app **no quede congelada**.
- Arregla el mapeo del código de error del punto 3, con prueba JVM sobre `AccountDeletionFailure`
  usando el código real de Android.
- Cuenta invitada (anónima): comprueba que siga funcionando sin re-autenticar con Google.

## Pruebas

- JVM: el mapeo de errores (el código real de Android da el mensaje de sesión reciente).
- La decisión de orden (re-autenticar → nube → local → Auth, y qué se hace si cada paso falla)
  **extraída a un objeto puro** y probada; no reescrita en el test (regla de `CLAUDE.md`).
- En el **teléfono de Diego** (`00170157R000160`): eliminar la cuenta de punta a punta. **Pídele antes
  a Claude** que avise a Diego: esto borra su usuario de verdad, y su plan Pro de prueba puede tener
  que volver a detectarse al entrar.

## Gate

El mismo de la parte A-C. Conteo desde los XML, paridad ES/EN, inglés sin apóstrofos, las dos puertas
de idioma. **No lances Gradle mientras otro corre en esta carpeta.**
