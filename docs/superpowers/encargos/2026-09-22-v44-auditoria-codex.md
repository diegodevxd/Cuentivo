# Auditoría de la v44 (Codex)

**Rama:** `diegodevxd/v44-libreta`, **todo sin commitear** (`git diff` + archivos nuevos). **Audita:**
Codex, **sólo lectura**: no edites, no commitees, no corras Gradle (Claude lo corre). Lee `CLAUDE.md`.
Reporte en `C:/tmp/codex-v44-auditoria.md`.

Lo implementaron dos tandas de OpenCode. Sus reportes: `C:/tmp/oc-v44-reporte.md` (A, B, C
investigado) y `C:/tmp/oc-v44-tanda2-reporte.md` (F, C, E, D). Encargos en
`docs/superpowers/encargos/2026-09-22-v44-*.md`. **No les des la razón: compruébalo contra el código.**

## Dónde mirar más fuerte (lo decidió el enrutador Laya)

- **D — eliminar cuenta (máxima prioridad).** Toca Auth y borra datos. Verifica: que la
  re-autenticación ocurre **antes** de borrar nada; que ningún fallo deje a la persona con sesión abierta
  y sin datos (el bug original); que una cuenta **anónima** no pida Google; que el código
  `ERROR_REQUIRES_RECENT_LOGIN` y la detección por tipo `FirebaseAuthRecentLoginRequiredException` son
  correctos para el SDK de Android que usa el proyecto; que `AccountDeletionPlan` es lo que de verdad
  ejecuta `MainActivity` (y no una copia paralela que nadie llama — regla de `CLAUDE.md`); cancelación de
  la corrutina; qué pasa si el proceso muere a mitad.
- **C — el duplicado ahora frena.** Que la pantalla y la puerta de escritura usen **el mismo** objeto
  (`LedgerDuplicateBlocking`); que confirmar desbloquee y corregir el renglón quite la duda; que una
  compra repetida legítima siga siendo registrable; que no se pueda registrar un duplicado sin confirmar
  por ninguna otra ruta.

## Y además

- **F** — `key(op.lineIndex)` alrededor del diálogo: ¿quedan `remember` fuera de esa llave que arrastren
  datos de un renglón a otro?
- **A** — el refresco tras registrar: la prueba de A lee el **código fuente** de `MainActivity`
  (`LedgerConfirmRefreshDecisionTest`, prueba 3). Juzga si eso es honesto o es una de las formas de
  "prueba que no ejercita producción" de `.claude/agents/test-honesty-reviewer.md`.
- **B** — la objeción de fechas imposibles: límites (180 días, año distinto, futura) y que no bloquee
  una libreta vieja legítima para siempre.
- **E** — texto corto con imagen y el respaldo si el PNG falla.

## Entrega

Por parte: **OK / PROBLEMA / DUDA**, con archivo:línea y, si es problema, el escenario concreto que
falla. Al final, si la v44 se puede publicar tal cual o qué hay que arreglar antes.
