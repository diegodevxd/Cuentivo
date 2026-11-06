# Encargo iOS — F5-E: primera tanda de pantallas y presentaciones

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-E): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5e-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** mover a `shared/src/commonMain` lo que no toca plataforma, **sin cambiar comportamiento en Android**. No cambies textos, lógica, `testTag` ni comentarios. No es una refactorización: si algo se ve mejorable, anótalo en el reporte y déjalo igual.

## Por qué así (ya decidido; no lo re-decidas)

- **Mismo paquete en cada archivo.** Así casi ningún consumidor de `:app` cambia sus imports.
- **Fuera de esta tanda, no los toques:** `FirstDebtScreen`, `FirstDebtPreferenceStore`, `OpeningBalanceImportDialog`, `PaymentScreen` (dinero o plataforma: tandas propias), `LanguageSheet`, `AccountSheet`, `ProductPhoto`, `ProductsScreen`, `CustomersScreen`, `CustomerDetailSheet`, `DashboardScreen`, `NewSaleScreen`, `ui/ai/*`, `CuentivoApp.kt`, `MainActivity.kt`, salvo los ajustes mínimos que se indican abajo.
- **Los textos `subscription_*` se quedan como están**, aunque digan Google Play: se hacen por plataforma en F6.

## Tareas

### Commit 1: presentaciones y políticas, con sus pruebas

**Mueve, mismo paquete, de `app/src/main/java/com/cuentivo/app/ui/` a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/`:**

- `customer/`: `BalancePresentation.kt`, `CorrectionCandidatePresentation.kt`, `CustomerLedgerPresentation.kt`, `CustomerDirectoryPresentation.kt`
- `dashboard/`: `DashboardPresentation.kt`, `DashboardActivityPresentation.kt`, `DashboardViewModel.kt`
- `onboarding/`: `FirstDebtPolicy.kt`
- `account/`: `AiEntryPolicy.kt`, `PlanPriceText.kt`
- `product/`: `ProductImportPolicy.kt`, `ProductImportRunner.kt`, `ProductPhotoDraft.kt`

**`internal`:** quítalo **solo** de lo que `:app` o `androidTest` usan de verdad, porque ya es otro módulo. Lo que solo se usa dentro de `:shared` (por ejemplo `planPriceText`, cuando sus dos consumidores ya estén movidos) se queda `internal`. Deja el compilador guiarte; no hagas público todo por comodidad.

**`CustomerDirectoryPresentation.kt`:**
- `PaymentReliability.labelRes(): Int?` pasa a `StringResource?` y `CustomerFilter(val labelRes: Int)` a `StringResource`, con `Res.string.*`.
- Consumidores que siguen en `app`: `CustomersScreen.kt` (líneas ~242, ~774, ~798-799) y `CustomerDetailSheet.kt` (~358). Agrega `import org.jetbrains.compose.resources.stringResource` **junto** al de `androidx`: conviven por sobrecarga, con `Int` en un caso y `StringResource` en otro. No cambies nada más ahí.
- Si el compilador señala otro consumidor, ajústalo igual y dímelo.

**`PlanPriceText.kt`:** `stringResource` de `org.jetbrains.compose.resources` y `Res.string.*`. Los argumentos se quedan igual; las cadenas ya son posicionales (`%1$d`, `%2$s`).

**Mueve las pruebas de `app/src/test/java/com/cuentivo/app/ui/` a `shared/src/commonTest/kotlin/com/cuentivo/app/ui/`,** mismo paquete, sin perder ninguna ni debilitar su intención:

`customer/BalancePresentationTest`, `customer/CustomerDirectoryPresentationTest`, `customer/CustomerLedgerPresentationTest`, `dashboard/DashboardPresentationTest`, `dashboard/DashboardViewModelTest`, `onboarding/FirstDebtPolicyTest`, `product/ProductImportPolicyTest`, `product/ProductImportRunnerTest`, `product/ProductPhotoDraftPolicyTest`, `account/SubscriptionUiPolicyTest`.

| JUnit | `kotlin.test` |
|---|---|
| `org.junit.Test` | `kotlin.test.Test` |
| `org.junit.Assert.assertEquals/True/False/Null/Same/fail` | `kotlin.test.*`, con el mismo orden de argumentos |
| `assertThrows(X::class.java) { }` | `assertFailsWith<X> { }`, **conserva el tipo exacto** |
| `runBlocking` | `kotlinx.coroutines.test.runTest` |

- **Revisa `a_cancellation_propagates_instead_of_becoming_...` en `ProductImportRunnerTest`:** debe seguir afirmando **la misma instancia** de la cancelación (`assertSame`). Si con `runTest` deja de poder afirmarlo, dímelo; no la relajes.
- **`SubscriptionUiPolicyTest` puede leer algo de `SubscriptionSheet` del commit 2.** Si es así, muévela en el commit 2.
- Agrega una sola prueba nueva en `CustomerDirectoryPresentationTest`: `PaymentReliability.NO_HISTORY.labelRes()` es `null` y los otros tres dan valores distintos entre sí. Es lo que evita que el cambio de tipo oculte un punto de más.

### Commit 2: pantallas

**Mueve, mismo paquete:** `auth/AuthenticationScreen.kt`, `auth/EmailVerificationScreen.kt`, `account/SubscriptionSheet.kt`, `account/AiPaywallSheet.kt`.

- `stringResource` y `painterResource` de `org.jetbrains.compose.resources`; `Res.string.*`, `Res.drawable.*`; importa `com.cuentivo.shared.resources.*`, como en `AlertsSheet.kt`. Sin `com.cuentivo.app.R`.
- **Logo:** copia `app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp` a `shared/src/commonMain/composeResources/drawable/ic_cuentivo_logo.webp`. **No borres ni toques los `mipmap`:** son el icono de la app y `FirstDebtScreen` los sigue usando. `AuthenticationScreen` usa `painterResource(Res.drawable.ic_cuentivo_logo)`. Conserva `scale(1.5f)` y su comentario.
- `SubscriptionContent` lo usan `AccountSheet`, `PlayStoreCaptureTest` y `StitchCaptureTest`: tiene que ser público, con todos los tipos de su firma.
- `AuthenticationFlowTest`, `EmailVerificationScreenTest` y `StitchCaptureTest` deben seguir compilando sin cambios de lógica. Si tocas uno, dime por qué.

## Lo que Native rechaza

Comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`, `Dispatchers.IO` en código común. `commonMain` **no nombra relojes** y no usa `Charsets` ni `java.*`. Ya medí estos archivos y no encontré ninguno de esos casos: **si encuentras uno, no lo arregles por tu cuenta sin decírmelo en el reporte.**

**No hay símbolos nuevos de iOS en este encargo.** Si crees que hace falta uno, anótalo: no lo adivines y no accedas a `C:/tmp/kn-prebuilt`.

## Control negativo obligatorio, en Windows

Para cada uno, rompe temporalmente, corre `:shared:testAndroidHostTest` y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. Restaura y confirma con `git diff`.

1. `BalancePresenter`: invierte el resultado de un saldo positivo y uno negativo.
2. `ProductImportRunner`: quita el apagado de la bandera en la ruta de fallo.
3. `CustomerDirectoryPresentation`: cambia `labelRes` para que `NO_HISTORY` devuelva un valor.
4. `DashboardViewModel`: haz que un negocio inexistente no dé el tablero vacío.

Si alguno no hace fallar nada, falta una prueba: dímelo y agrégala.

## Puerta

Sin emulador ni pruebas instrumentadas: la PC se queda sin memoria. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`.

## Reporte

- **Rama y commit:** sácalos de `git branch --show-current` y `git rev-parse HEAD`, no de memoria.
- Conteos reales de los XML. La base es 861 + 697 = 1558: explica el movimiento clase por clase y cuántas pruebas son nuevas. **Debe ser exactamente una más.**
- Salida de las tareas, archivos tocados y los **cuatro controles negativos**, con el nombre de la prueba que falló.
- Qué declaraciones perdieron `internal`.
- Cualquier cosa distinta a este encargo, y por qué.

**No digas que iOS compila ni que estas pantallas funcionan en iOS: no puedes saberlo desde Windows.** `:shared:compileCommonMainKotlinMetadata` solo compila `commonMain`.
