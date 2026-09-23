# Encargo OpenCode/DeepSeek — v44 tanda 6

Implementa los tres arreglos descritos abajo en el worktree activo de Cuentivo, rama `diegodevxd/v44-libreta`, partiendo del commit T5 `47587f7`. Lee primero `CLAUDE.md`, el encabezado de `PROJECT_MEMORY.md`, `docs/superpowers/plans/2026-09-23-v44-tanda6-pre-release.md` y `.claude/agents/test-honesty-reviewer.md`.

**No delegues.** Esta es una sesión nueva de OpenCode en el worktree actual; no abras otro worktree ni otra sesión. No hagas commit, no cambies versión, no prepares release, no hagas push/tag/deploy, y no toques `docs/superpowers/handoffs/`. No uses datos reales de libretas/clientes. El teléfono `00170157R000160` (A059P, Android 16) debe conservar la app instalada; si corres instrumentadas, usa `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

## 1. “Cambiar todos” sólo para `IMPLAUSIBLE_DATE`

Hoy `LedgerReviewScreen` entrega al planificador los índices de cualquier tipo de objeción. Cambia el camino de producción para que la corrección masiva sólo incluya renglones con objeción `IMPLAUSIBLE_DATE`.

Agrega primero una prueba que falle: dos renglones con `IMPLAUSIBLE_DATE` forman el plan y un tercero con otra objeción —por ejemplo `PRICE_MISMATCH_SUSPECT`— tiene una fecha distinta del año destino, pero plausible y reciente. Al planear/aplicar “Cambiar todos”, afirma que sólo cambian los dos renglones con objeción de fecha. La prueba debe fallar si el filtro de tipo desaparece, porque entonces incluiría el tercero. Haz que el planner de producción reciba o derive la objeción tipada; no dejes la garantía sólo en un `filter` incidental de la pantalla.

La auditoría también observó que años mezclados pueden hacer que el texto anuncie el año dominante mientras el conteo incluye los minoritarios. Ajusta el texto/conteo para que describa con verdad las filas que se modificarán.

## 2. “Precio habitual”: prueba de UI conductual

Elimina de `AiDebugLabGuardsTest.kt` la prueba que lee `AiDebugLabScreen.kt` como texto. Sustitúyela por una prueba Compose que renderice la vista previa de venta de producción para un producto con `resolutionMode = NEW`; comprueba que se ve el producto/precio indicado y que no se muestra “Precio habitual” (según el locale probado). La prueba debe llamar al composable de producción; extráelo si hace falta. No basta probar un helper paralelo ni buscar strings en archivos. Debe quedar roja si la fila reaparece en el render.

## 3. Cerrar detalle tras registrar no debe regresar a venta vacía

Reproduce la ruta real: nueva venta → registro exitoso → detalle de esa clienta → cerrar detalle/Atrás. Al cerrar debe volver a Inicio o Clientes con el formulario de venta cerrado, no al formulario vacío que queda debajo del detalle ahora. Haz que el test conduzca el callback exitoso y la apertura del detalle; no inicialices el test con el detalle ya abierto ni borres `showNewSale` solamente dentro del test. Mantén intacto el flujo de corrección/edición de venta que sí retorna al estado de cuenta.

## Verificación y reporte

- Sigue TDD por bloque: prueba roja reproducible, cambio mínimo, prueba verde.
- Ejecuta pruebas focalizadas de los tres cambios y reporta los comandos y resultados exactos. No reportes como ejecutado lo que sólo hayas inspeccionado.
- Deja el diff listo para la revisión independiente de Codex, sin commit ni bump de versión.
- Escribe el reporte final en `C:/tmp/oc-v44-tanda6-reporte.md`: resumen, archivos cambiados, evidencia roja/verde, comandos realmente ejecutados, conteos y skips/motivos, limitaciones conocidas.

El gate completo del teléfono y el release los ejecutará Codex después de que Diego confirme que terminaste. No lances `bundleRelease` ni cambies `versionCode`/`versionName`.
