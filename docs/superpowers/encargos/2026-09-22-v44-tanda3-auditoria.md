# v44 · Tercera tanda: lo que encontró la auditoría de Codex (OpenCode con DeepSeek)

**Rama:** `diegodevxd/v44-libreta`, todo sin commitear de las tandas 1 y 2: **no lo reviertas**.
**Implementa:** OpenCode, **sin delegar**. Lee `CLAUDE.md` y **`C:/tmp/codex-v44-auditoria.md`**
completo. **No commitees, no generes APK ni AAB de release. El gate sí se corre.** Reporte en
`C:/tmp/oc-v44-tanda3-reporte.md`. **No pruebes D en el teléfono**: eso lo hace Claude con Diego.

Claude comprobó en el código los dos hallazgos principales (orden de D en `MainActivity.kt:2617` →
`:2628`, y la huella sin fecha ni tipo). Orden: **1 → 2 → 3 → 4 → 5**, gate tras cada uno.

## 1 · D — Si Auth falla después de borrar, no puede quedar "sesión abierta y sin datos"

Reautenticar primero bajó la probabilidad, pero `FirebaseUser.delete()` todavía puede fallar (red) **después**
de haber borrado la nube y Room. Diseño decidido por Claude:

- **Marca durable antes de borrar**: al empezar la parte destructiva (tras reautenticar), guarda en
  `SharedPreferences` un "borrado de cuenta pendiente" con el `uid`. Se limpia sólo cuando Auth se borra.
- **Si Auth falla tras borrar**: `signOut()` igualmente y a la pantalla de inicio de sesión, con un
  mensaje que diga la verdad ("Tus datos se borraron, pero no pudimos cerrar tu cuenta. Vuelve a entrar
  para terminar."). **Nunca** quedarse en la app con sesión y sin datos.
- **Al volver a entrar con ese mismo `uid`** y la marca puesta: **no** crear negocio nuevo ni adoptar nada;
  reintentar el borrado de Auth (reautenticando si hace falta) y, si sale, a inicio de sesión.
- **Si el proceso muere a mitad**: la marca durable es lo que permite retomar al siguiente arranque.
- Corrige el KDoc de `AccountDeletionPlan.kt:74-86`: `ABORT_WITH_MESSAGE` **no** significa "no se borró
  nada" cuando falla `DELETE_AUTH`.
- Que `MainActivity` **despache por el `Step`** del plan en vez de codificar los pasos aparte, para que la
  prueba del plan pruebe lo que corre.
- Pruebas JVM del plan con cada corte: falla la reautenticación / la nube / Auth; proceso muerto con la
  marca puesta; volver a entrar con la marca.

## 2 · C — La consulta de duplicados no ve lo que el detector sí compara

`AiDebugLabScreen.kt:2122-2136` (`loadRegisteredAround`) usa **sólo fechas de renglón** y, si no hay,
hoy ±14; el detector usa también la **fecha de la página** (`LedgerDuplicateDetector.kt:59-82`). Una página
de 2023 sin fechas por renglón consulta 2026 y no encuentra nada.

- La ventana de consulta debe incluir la **fecha efectiva** de cada renglón, la misma que usa el detector
  (renglón, o página si el renglón no trae). Extráelo a un objeto puro que usen **los dos**.
- **Re-consultar** cuando cambie la fecha de un renglón o la de la página durante la revisión (hoy sólo se
  consulta al extraer/restaurar, `:630-634`, `:376-380`).
- Prueba del escenario de Codex: página de 2023 sin fechas por renglón, ya registrada → la segunda vez
  **sí** queda frenada.

## 3 · B y C — Una confirmación no puede sobrevivir a un cambio de fecha o de tipo

`LedgerObjectionConfirmation.fingerprint` (`:7-15`) incluye cliente, monto y descripción, **pero no fecha
ni tipo**. Tras confirmar, cambiar sólo la fecha (a otro año equivocado) o el tipo mantiene la
confirmación y el renglón pasa sin revisar.

- Agrega **fecha y tipo** a la huella. Cualquier cambio en ellos **anula** la confirmación.
- Revisa qué confirmaciones ya guardadas en borradores (`LedgerReviewSaver`) quedarían con huella vieja: al
  restaurar deben volver a pedir confirmación, **no** romper el borrador. Si hace falta tocar
  `LedgerReviewSchema`, **para y dilo**.
- Pruebas: confirmar → cambiar fecha → vuelve a objetar; confirmar → cambiar tipo → vuelve a objetar;
  confirmar → no cambiar nada → sigue confirmado.

## 4 · A — La prueba 3 lee el código fuente

`LedgerConfirmRefreshDecisionTest.kt:47-77` hace `File(...).readText().contains(...)`: es la forma 5 de
`.claude/agents/test-honesty-reviewer.md` y queda verde con el refresco en código inalcanzable. **Bórrala**
y sustitúyela por una prueba de **conducta**: extrae el callback (confirmar + refrescar) a una función o
clase que reciba `confirm` y `onRefresh` como lambdas, y prueba que tras `Recorded` y `AlreadyRecorded` se
llama `onRefresh` y tras una excepción no. `MainActivity` usa esa misma función.

## 5 · E — Cablear la elección de texto

`StatementShareTextChoice.choose(true, …)` existe pero la ruta exitosa no lo usa. Que `shareStatementImage`
llame a `choose` en **las dos** ramas, para que la prueba pura cubra lo que de verdad corre.

## Gate

El mismo de siempre. Conteo desde los XML (referencia **1317**), paridad ES/EN (hoy **672/672**), las dos
puertas de idioma. **Una mutación por regla.** Si pasas del 80% de contexto, para, corre el gate y escribe
el reporte con lo hecho y lo pendiente separados.
