# Encargo iOS — Resto del dominio a `:shared`

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
Mismas reglas, puerta (con `:shared:testAndroidHostTest`) y formato de commit que los encargos
anteriores. **Límite de tiempo: entrega el reporte antes de las 03:15**, aunque no termines; lo que
no alcances queda como pendiente.

## Tareas, en orden

**R1. Los 10 archivos de dominio que la Fase 3 desbloqueó** (lista en la sección 4 de
`C:/tmp/oc-ios-a-f3-reporte.md`): `LegacyMigrationPlanner`, `OpeningBalance`,
`OpeningBalancePlanner`, `StatementCustomerRefresh`, `BusinessDeletionPlanner`,
`LedgerConfirmAndRefresh`, `LedgerConfirmRefreshDecision`, `LedgerOldDatesBulkCorrection`,
`NewCustomerDraft`, `ProductSheetParser`. Muévelos con `git mv` a `commonMain` si su código ya
compila en común; si alguno depende de `ai.ledger` o de Java, déjalo para R2/R3 o pendiente. Mueve
también sus pruebas a `commonTest` (mismo método que F1.4: mismos casos, mismos valores esperados).

**R2. Paquete `com.cuentivo.app.ai.ledger` (y lo que necesite de `ai/`)**: identifica con grep qué
archivos son Kotlin puro (sin `android.*`, `androidx.*`, `com.google.firebase.*`, `Context`,
`R.string`, `java.*` no reemplazable). Muévelos a `commonMain` conservando paquete. Lo que use
Firebase o Android se queda en `app`.

**R3. Los 20 archivos `Ledger*` / `AiLedgerBatchPlanner` de `domain/`** que estaban bloqueados por
`ai.ledger`: muévelos tras R2, con sus pruebas, en tandas de ~10. Después `SaleRepository` si ya
no tiene dependencias en `app`. Y `LedgerEntityMatcherTest` a `commonTest`.

Reglas de siempre: mover ≠ reescribir; reemplazos de APIs Java solo con los equivalentes ya usados
(`kotlinx-datetime`, `sha256`, `canonicalDecomposition`, `Money`); nada de `Double` con dinero; el
número total de pruebas nunca baja; esquema intacto.

## Reporte

`C:/tmp/oc-ios-r-reporte.md`, mismo formato. Al terminar escribe `ENCARGO-IOS-R-TERMINADO`.
