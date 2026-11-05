# Encargo iOS — Correcciones de la auditoría de F1/F2, y Fase 3 (Room multiplataforma)

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`
(rama `diegodevxd/cuentivo-ios`). Mismas reglas, puerta y formato de commit que el encargo anterior
(`2026-11-04-ios-f1-f2.md`), más `:shared:testAndroidHostTest` en la puerta.

## Parte A — Correcciones (hacer primero; un commit cada una)

**A1. `gradlew` sin permiso de ejecución en git.** `git ls-files -s gradlew` da `100644`; en los
runners de GitHub `./gradlew` falla con "Permission denied" y los dos workflows mueren en el primer
paso. Corrige con `git update-index --chmod=+x gradlew` (debe quedar `100755`). Commit `ios(A1): ...`.

**A2. `centsFromDecimalText` acepta un exponente sin dígitos.** `"1e+"` y `"1e-"` devuelven `100`
(el `if (index >= text.length) return null` está antes del signo, no después), mientras que
`BigDecimal("1e+")` lanzaba y `Money.centsFromPesos` daba `null`. Exige al menos un dígito de
exponente después del signo opcional. Agrega los casos `"1e+"`, `"1e-"`, `"1e"`, `"e5"`, `"."`,
`"+"`, `"-"` a `MoneyDecimalTextCommonTest` esperando `null`.

**A3. Prueba diferencial contra el comportamiento original (el oráculo es `BigDecimal`).** El
parser reescribe a mano lo que antes hacía `BigDecimal(text.trim()).movePointRight(2).longValueExact()`,
y es la puerta de entrada de dinero. Crea `shared/src/androidHostTest/kotlin/com/cuentivo/app/domain/MoneyParserBigDecimalParityTest.kt`
(o el nombre de source set de pruebas de host que use el plugin; verifica cuál es) que:
- Defina dentro de la prueba la función **original** como oráculo:
  `fun oracle(t: String): Long? = try { java.math.BigDecimal(t.trim()).movePointRight(2).longValueExact() } catch (e: Exception) { null }`.
  Aquí sí es correcto copiar la regla vieja: es la referencia contra la que se compara el código
  nuevo de producción (`Money.centsFromPesos`), no un sustituto de él.
- Compare `Money.centsFromPesos(x)` con `oracle(x)` en: una lista fija de ≥40 casos (enteros,
  1 y 2 decimales, `"1.230"`, `"10.005"`, ceros a la izquierda, signos, espacios alrededor,
  exponentes positivos y negativos, `"9223372036854775807"`, `"92233720368547758.07"`,
  `"92233720368547758.08"`, vacíos, letras, `"1,5"`, `"1.2.3"`) **y** 20,000 cadenas aleatorias
  con semilla fija generadas del alfabeto `0123456789.+-eE ` de longitud 0–12.
- Excepción documentada permitida: dígitos no ASCII (`BigDecimal` acepta `"١٢"`); el nuevo los
  rechaza, que es más seguro. No los generes en el aleatorio; agrega un caso explícito que
  documente la diferencia.
- Si aparece cualquier otra discrepancia, **corrige el parser**, no la prueba.

**A4. Antes de seguir**, corre la puerta y anota el conteo de pruebas.

## Parte B — Fase 3 del plan (Room multiplataforma)

Lee en `docs/superpowers/plans/2026-11-04-cuentivo-ios-sin-mac.md` **solo** la "Fase 3". Haz
F3.1, F3.2, F3.3 y F3.4 en orden.

Reglas extra de esta fase (es la de mayor riesgo para Android):
- **El esquema no puede cambiar.** Tras compilar, `git diff --stat -- app/schemas` (o la carpeta
  de esquemas a la que apunte `room {}`) debe estar vacío salvo un `git mv` de la carpeta. Si Room
  regenera `8.json` con diferencias, para y reporta.
- `CuentivoDatabase` sigue en `version = 8`. Las 5 migraciones (`MIGRATION_3_4`…`MIGRATION_7_8`)
  y `CUENTIVO_MIGRATIONS` se quedan en código Android. `grep -rn '"cuentivo.db"' app/src/main`
  debe seguir mostrando los tres builders registrando `CUENTIVO_MIGRATIONS`.
- No cambies ni una consulta SQL ni un `@Transaction` de `FinancialDao`. Mover ≠ reescribir.
- Si Room KMP 2.7.2 no compila con Kotlin 2.2.10/KSP 2.2.10-2.0.2 sin subir versiones, **para y
  reporta** qué versión haría falta; no subas versiones por tu cuenta.
- Al terminar F3.1 y al final de la fase, además de la puerta, corre las pruebas instrumentadas de
  Room en el emulador:
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone -no-snapshot-load -no-boot-anim
  # esperar a que `adb shell getprop sys.boot_completed` dé 1
  .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=com.cuentivo.app.data.local" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
  ```
  (El emulador se lanza en segundo plano; si `CLAUDE.md` describe el síntoma de "System has
  crashed", aplica su receta.) Al terminar, apágalo: `adb emu kill`.
- Si una tarea rompe `OperationDaoTest` o una prueba de migración y no lo resuelves sin cambiar
  la lógica, **revierte esa tarea** y repórtala.

## Reporte

`C:/tmp/oc-ios-a-f3-reporte.md`: mismo formato que el anterior (conteos antes/después, por tarea:
commit, archivos, comando de puerta real y resultado; qué no se movió y por qué; versiones;
pendientes). Incluye la salida de `git diff --stat release/v49-mexalm..HEAD -- app/schemas`.

Al terminar escribe en la terminal: `ENCARGO-IOS-A-F3-TERMINADO`.
