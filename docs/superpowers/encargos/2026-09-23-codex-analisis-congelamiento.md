# Análisis: la revisión de la libreta se congela al girar (Codex gpt-6-sol)

**Sólo análisis. NO modifiques ningún archivo, NO commitees, NO corras Gradle ni el emulador** (Claude
coordina las corridas; si necesitas una, pídela en el reporte con el comando exacto). Lee `CLAUDE.md`.
Reporte en `C:/tmp/codex-analisis-congelamiento.md`. Rama actual `diegodevxd/v47-giro-congelado`
(`2abb881`, v47).

## El síntoma

`LedgerReviewRotationTest` (`app/src/androidTest/.../ui/LedgerReviewRotationTest.kt`) a veces se queda
colgada para siempre: emulador en horizontal, pantalla "Revisar libreta" quieta, `com.cuentivo.app` al
**85-96 % de CPU**, hilo principal ~58 %, GC sostenido ~7.8 MB/s, foco en `LedgerLabHostActivity`
(host de depuración en `app/src/debug/.../ui/LedgerLabHostActivity.kt`).

## Lo que ya se sabe (no lo rehagas, úsalo)

- Pasa sola casi siempre (30-54 s). Se colgó: en la suite completa tras ~230 pruebas (v43, v45 dos
  veces), y sola una vez tras correr otras clases con datos acumulados. OpenCode lo reprodujo **1 de 4**.
- v43: se arregló una variante (un ítem de la `LazyColumn` más alto que la pantalla en horizontal hacía
  bucle); desde entonces el encabezado va en ítems separados (comentario sobre `ledger-review-header` en
  `ui/ai/LedgerReviewScreen.kt`).
- OpenCode instrumentó con contadores el `SideEffect` del borrador, la restauración, el
  `LaunchedEffect(effectiveDateSignature…)` y `onUpdatePageDate`: **menos de 40 composiciones en el
  cuelgue** → **no es bucle de recomposición**. GC alto con pocas composiciones sugiere **medida o dibujo
  en bucle**. Detalle en `C:/tmp/oc-v47-reporte.md`.
- Descartado: orden con `LedgerRestoreConfirmSingleWriteTest`; la "fecha de página llena" no puede venir
  de esa prueba (nadie aplica la fecha detectada solo).
- `pm clear com.cuentivo.app` antes de la prueba la hizo pasar, pero con 1 de 4 eso no demuestra nada.
- No hay root en el emulador: no se puede sacar la pila con `kill -3`, `debuggerd` ni `simpleperf`.

## Lo que quiero de ti

1. **Hipótesis ordenadas por probabilidad** de un bucle de medida/dibujo (o de otra cosa que dé ese
   perfil) en la pantalla de revisión en horizontal tras recrear la Activity: `LazyColumn` dentro de algo
   con altura no acotada, `BoxWithConstraints`/`SubcomposeLayout`, `onGloballyPositioned`/`onSizeChanged`
   que cambian estado, `animateContentSize`, `TextField` que pide foco o `bringIntoView` tras restaurar,
   `IntrinsicSize`, `Modifier.weight` en `LazyColumn`, `performScrollToNode` de la prueba… Cada una con
   el **archivo:línea** que la haría posible y **por qué** encaja o no con los datos de arriba.
2. **Cómo confirmarlo sin root**: qué instrumentación mínima y temporal (contador de medida por
   composable, `Trace`, log de `onPlaced`…) o qué corrida la delataría, y cómo reproducirlo de forma
   fiable (si ves un estado que lo dispare).
3. **Si puede pasarle a una usuaria real** con el teléfono girado en la revisión, o si es exclusivo de la
   prueba/emulador (p. ej. por algo de `LedgerLabHostActivity`, `waitForIdle` o `performScrollToNode`).
4. Tu recomendación: arreglarlo ya, instrumentar y esperar, o aceptarlo como inestabilidad del emulador.
