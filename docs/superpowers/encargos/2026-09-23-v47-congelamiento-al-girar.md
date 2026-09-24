# v47 · La revisión de la libreta se congela al girar con datos acumulados (OpenCode)

**Rama:** `diegodevxd/v47-giro-congelado`, ya creada desde `25d39c3` (v46, `1.23.2`). **Implementa:**
OpenCode, **sin delegar**. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release.** Reporte en
`C:/tmp/oc-v47-reporte.md`. Emulador `emulator-5554` (Diego no está en casa; no uses su teléfono).

## Lo que ya sabe Claude (verificado)

- `LedgerReviewRotationTest` **pasa sola con los datos de la app limpios** (`adb shell pm clear
  com.cuentivo.app` → 30 s, verde). Pasó igual en v43, v44 y v45.
- Se **cuelga** cuando la app arrastra datos de corridas anteriores (las instrumentadas se corren con
  `leaveApksInstalledAfterRun=true`, así que los datos quedan): en la suite completa tras ~230 pruebas, y
  **sola** después de haber corrido otras clases de libreta. Síntoma: emulador en horizontal,
  `com.cuentivo.app` al **85-96% de CPU** sin avanzar, pantalla "Revisar libreta" quieta.
- Diferencia visible: en el cuelgue el campo **"Fecha de la página" ya viene lleno (2026-07-30)**; en las
  corridas que pasan viene **vacío** con la sugerencia "CuentivoAI leyó 2026-07-30… Tócala para usarla".
- Ya se descartó: el orden con `LedgerRestoreConfirmSingleWriteTest` (juntas pasan) y un ítem de la
  LazyColumn más alto que la pantalla (el encabezado ya va en ítems separados desde la v43).
- **Esto puede pasarle a una usuaria real**: con datos guardados y el teléfono girado en la revisión.

## Qué hacer

1. **Reproducirlo a voluntad.** Averigua qué dato persistido deja la fecha de página llena (borrador de
   revisión, preferencia, Room, `LedgerReviewSaver`…). Con eso arma una reproducción corta: sembrar ese
   estado y girar. Si hace falta, corre las clases de libreta y luego la de rotación **sin** `pm clear`.
2. **Encontrar el bucle.** Sospecha principal de Claude, sin probar: el `LaunchedEffect` que re-consulta los
   movimientos registrados cuando cambia la "firma" de fechas efectivas (`AiDebugLabScreen.kt` ~764-786,
   de la tanda 3 de la v44): si esa firma o lo que devuelve la consulta no es estable, se relanza en cada
   recomposición. Otras candidatas: el campo de fecha de página que se re-escribe a sí mismo, o un estado
   que se re-sincroniza al restaurar tras el giro. **Confírmalo con evidencia** (log temporal, contador,
   traza), no por suposición.
3. **Arreglarlo** en el código de la app, no en la prueba.
4. **Prueba de regresión** que siembre el estado y gire, y que falle (se cuelgue o exceda un tiempo) sin el
   arreglo. Una prueba que sólo pasa con datos limpios no cuenta.

## Gate

El de siempre (`testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin`), referencia **1393**
JVM, paridad ES/EN **689/689**, puertas de idioma. Instrumentadas: la de rotación **sin** limpiar datos y
luego con datos limpios, más las de libreta que toques. Mutación de la regla que arregles.
