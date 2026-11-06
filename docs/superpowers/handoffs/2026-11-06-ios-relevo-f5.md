# Relevo iOS: de F4-D cerrada a F5

**Lee primero** `docs/superpowers/handoffs/2026-11-05-ios-relevo.md`: tu papel, cómo trabajar con OpenCode, cómo auditar, las trampas de iOS y las reglas siguen
vigentes. **Lo que dice ahí de "Estado", "Tu primer paso" y "Posibles fallos" ya caducó;** manda este archivo. Lee también `CLAUDE.md`, el plan
`docs/superpowers/plans/2026-11-04-cuentivo-ios-sin-mac.md` y la memoria `cuentivo-ios-sin-mac`, `cuentivo-reparto-de-agentes` y `relevo-de-sesiones-caras`.

## Estado al 2026-11-06

**F4 cerrada en verde:** `dbc34f4`, run 42157508323. `ios-build` y `android-check` en verde, `.app` generado (25.9 MB). Run 42155529689, el último con el recuento
sacado del XML: **617/617 en simulador iOS** (`dbc34f4` solo cambió Swift y una fachada). Android: 881 + 603 = 1484, 0 fallos, en el último `testDebugUnitTest`/`testAndroidHostTest` local.

**No corrí `assembleRelease` ni `lintDebug` en F4-D.** Corre ambos antes de declarar F5-A cerrada (ya están en su puerta).

Esta rama solo tiene este archivo sin subir, en un commit local. Súbelo junto con lo siguiente: un push solo de docs gasta un run de iOS.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone.** Compilar y correr en simulador no prueba eso; dilo con esas palabras.

## Tu primer paso

1. Usa la terminal de OpenCode `term_c2b793b3-78cb-45e2-b857-d2d200590bc8` si sigue viva y con contexto bajo: ya tiene "Allow always" a `C:\tmp\*`. Si no, crea otra (Flecha derecha + Enter + Enter al primer aviso).
2. Mándale `docs/superpowers/encargos/2026-11-05-ios-f5a-strings.md`, reporte `C:/tmp/oc-ios-f5a-reporte.md`, y arma el Monitor. El encargo ya está revisado contra el fuente de Compose 1.9.3; no lo re-decidas.
3. Audita, sube y lee el veredicto: A5 y A6 solo los prueba CI. Sigue con el resto de F5: interfaces (`Sharer`, `PhotoPicker`, `TextRecognizer` con Vision, `ReminderScheduler`, `AppFiles`) y pantallas en tandas. Luego F6, resto de F7.

## Pendientes que F5 debe cubrir

- **`deleteAccountWithApple`:** nadie la llama. La pantalla de borrar cuenta debe usarla si el proveedor es `apple.com`; `deleteAccount` no revoca el token y App Store lo exige.
- **`signOut()` en iOS puede lanzar** (`check` sobre el `NSError`), cosa que en Android no pasa. La pantalla debe atraparlo y **no** limpiar datos locales como si se hubiera cerrado la sesión.
- **Idioma en Android < API 33.** Al mover pantallas a `Res.string`: `attachBaseContext` solo envuelve el `Context` y no hay `Locale.setDefault`, y Compose Resources resuelve el idioma con el locale por defecto (fuente: `getSystemEnvironment` usa `Locale.getDefault()`; confirma la ruta de los Composables en `ResourceEnvironment.kt` de `commonMain`). Riesgo: pantallas en el idioma del sistema, ignorando el selector. Pruébalo con la primera pantalla movida, y no solo en API 37. `R.string` y `Res.string` no pueden contestar en idiomas distintos dentro de la misma app.
- **Idioma en iOS:** el selector de idioma no tiene equivalente aún. `getSystemEnvironment` usa `NSLocale.preferredLanguages`; `LanguagePreference` es Android. Decide cómo se resuelve al mover la pantalla de cuenta.
- **La auditoría paga.** Ejemplo de esta sesión: OpenCode pasó banderas de espera a campos de instancia y un callback tardío podía convertir un lote de borrado fallido en éxito (corregido con un objeto por espera). Lee el código de concurrencia, no el reporte.

## Trampas nuevas de interop Swift (ya resueltas en F4-D; aplica lo mismo en F5/F6)

- Un `typealias` de Kotlin no se exporta: en Swift, `PlatformAuthHost` es `UIViewController`.
- Los casos de un `sealed interface` no se anidan: `IosSignInOutcomeSuccess(...)`, `IosSignInOutcomeCancelled.shared`.
- `:iosKit` no hace `export(project(":shared"))`: Swift solo ve de `:shared` lo que aparece en la API pública de `:iosKit`. Para lo demás, una fachada mínima en `:iosKit`, como `IosAppleNonce`. Toma en cuenta esto para F6 (StoreKit).
- Cada archivo Swift importa lo suyo (`FirebaseCore` para `FirebaseApp`). El compilador suele parar en el primer archivo con errores: al corregir uno, revisa el otro.
- Un error de Swift solo aparece cuando Kotlin ya compila: errores nuevos en cada capa son lo normal, no una regresión.

## Costos

Regla de relevo: cerca de $50-$80 o antes de un tramo grande. F5 tiene varios: si tu sesión llega ahí, relévate entre tandas, con este mismo formato.
