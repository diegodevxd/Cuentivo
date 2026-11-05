# Encargo iOS — C2: `commonTest` no compila en Kotlin/Native

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commit con formato `fix(ios): ...`.

`:shared:compileKotlinIosSimulatorArm64` ya pasa. Falla `:shared:compileTestKotlinIosSimulatorArm64`
(run 42125154213). Windows no puede compilar iOS: **no puedes reproducir el error aquí**, así que
además de lo listado **busca con grep todos los casos de la misma familia en `shared/src/commonTest`**.

## Errores y arreglo

1. **`Name contains illegal characters: ","`**: nombres de prueba entre backticks con coma. Quita la
   coma y deja el resto del nombre igual. Busca también en todo `commonTest` nombres con cualquiera de
   `, . ; : / \ < > [ ]` dentro de backticks. Archivos reportados: `AccountDeletionFailureTest`,
   `AppAlertsTest`, `BillingReconnectPolicyTest`, `BusinessDeletionPlannerTest`, `BusinessInsightsTest`,
   `CustomerStatementCsvTest`, `DefaultBusinessCreationPolicyTest`, `LedgerConfirmRefreshDecisionTest`,
   `LedgerCorrectionCounterTest`, `LedgerCustomerMatchingTest`, `LedgerImplausibleDateReasonTest`,
   `LedgerOldDatesBulkCorrectionTest`, `SyncEntitlementTest`, `SyncDrainerTest`.
2. **`No parameter with name 'expected'`** en `OpeningBalanceTest` (64, 69, 93, 109, 125, 141) y
   `SaleDraftTest` (84, 105): usa el parámetro que exige `kotlin.test` o pásalo por posición, sin cambiar
   el valor ni qué se compara.
3. **`LedgerEntityMatcherTest:171`**: `measureTimeMillis` de `kotlin.system` está prohibido en Native.
   Usa `kotlin.time.measureTime` y `.inWholeMilliseconds`. El límite de tiempo no cambia.
4. **`StatementImageTest:18`**: `String.format` no existe en común. Reemplázalo por código común que
   produzca exactamente el mismo texto, comprobado con los casos actuales.
5. **`SyncSchedulingTest:4,36,50`**: importa `org.junit`. Usa `kotlin.test.assertSame`.
6. **Misma familia:** busca en `commonTest` `import org.junit`, `import java.`, `.format(`,
   `kotlin.system.`, `System.`, `Thread.`, `@JvmStatic`, `::class.java` y corrige lo que encuentres.

## Reglas

- Mismos casos, mismas aserciones y mismos valores esperados. Ninguna prueba se borra, se ignora ni se
  debilita, y ninguna se mueve a `androidHostTest` para esquivar el error. Si alguna de verdad no se
  puede expresar en común, **para y repórtala**.
- Solo se toca `shared/src/commonTest`. Si necesitas tocar producción, para y repórtalo.
- Nada de `Double` con dinero.

## Puerta

Con el entorno de Norton de los encargos anteriores:
`.\gradlew.bat :shared:testAndroidHostTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain`.
Debe pasar con **550 pruebas o más y 0 fallos**; cuéntalas del XML en
`shared/build/test-results/testAndroidHostTest`.

## Reporte

`C:/tmp/oc-ios-c2-reporte.md`: archivos tocados, resultado de cada grep de la tarea 6, número de pruebas
leído del XML y hash del commit. Al terminar: `ENCARGO-IOS-C2-TERMINADO`.
