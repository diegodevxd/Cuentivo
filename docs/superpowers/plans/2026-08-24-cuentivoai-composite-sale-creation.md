# CuentivoAI Composite Sale Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a confirmed CuentivoAI sale create missing customers and products, persist the spoken price as the product's habitual sale price, and record the sale plus optional payment exactly once.

**Architecture:** Extend the existing read-only sale resolver with explicit existing/new/clarification decisions bound to one preview generation. A later Room transaction will create selected catalog rows and the financial bundle atomically; Gemini remains proposal-only and outbox rows are derived from persisted local rows after commit.

**Tech Stack:** Kotlin, coroutines, Room, Jetpack Compose, JUnit, AndroidX instrumented tests, existing Firebase callable transport unchanged.

## Global Constraints

- Work only in `diegodevxd/feature-cuentivo-ai`; preserve `docs/superpowers/handoffs/` untouched and untracked.
- TDD: demonstrate focused RED before production changes and focused GREEN afterward.
- Money remains `Long` cents; overflow, blank names, invalid prices and stale generations fail closed.
- Gemini never supplies trusted IDs, totals, fingerprints or idempotency authority and never writes Room, Firestore or outbox.
- A new product stores the confirmed declared unit price as `salePriceCents`; cost, stock, category, SKU, barcode and image remain unset.
- A new customer stores only name/normalized name plus existing neutral defaults; no PII is invented.
- Similar candidates require explicit `USE_EXISTING`, `CREATE_NEW` or cancellation. Duplicate names remain legal only after explicit `CREATE_NEW`, matching the current non-unique customer model.
- No Billing, version, AAB, provider, secret, Firestore schema or release-entry changes.

---

### Task 1: Read-only existing/new/clarification sale resolution

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ai/AiSalePreview.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ai/AiSalePreviewTest.kt`

**Interfaces:**
- Consumes: `businessId`, validated `CreateSaleProposal`, active customer/product search callbacks, optional explicit resolution choices.
- Produces: a preview whose customer and each line contain a locally controlled `EXISTING` or `NEW` entity resolution, or a clarification result containing safe candidates and an explicit create-new option.

- [ ] **Step 1: Add focused failing tests for new and ambiguous entities**

Add tests proving: no candidates proposes a new customer/product; a new product preserves the declared price as its habitual price candidate; mixed existing/new products resolve; similar candidates never auto-create; explicit `USE_EXISTING` uses its real ID/fingerprint; explicit `CREATE_NEW` uses a locally generated nonblank ID; wrong-business candidates are ignored; blank/negative/overflow inputs remain blocked; resolution writes nothing.

Use deterministic seams in tests:

```kotlin
val resolver = AiSalePreviewResolver(
    searchCustomers = { _, _ -> emptyList() },
    searchProducts = { _, _ -> emptyList() },
    newConfirmationKey = { "confirm-1" },
    newEntityId = { kind, name -> "$kind-${CustomerNameNormalizer.key(name)}" },
)
```

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiSalePreviewTest"
```

Expected: FAIL because the new resolution/choice types and `newEntityId` seam do not exist.

- [ ] **Step 2: Add immutable resolution contracts**

Introduce focused types in `AiSalePreview.kt` (names may be adjusted only to avoid an existing collision, not redesigned):

```kotlin
enum class AiEntityResolutionMode { EXISTING, NEW }

sealed interface AiEntityChoice {
    data class UseExisting(val entityId: String) : AiEntityChoice
    data object CreateNew : AiEntityChoice
}

data class AiSaleResolutionChoices(
    val customer: AiEntityChoice? = null,
    val productsByNormalizedName: Map<String, AiEntityChoice> = emptyMap(),
)

data class AiResolvedSaleCustomer(
    val customerId: String,
    val displayName: String,
    val normalizedName: String,
    val mode: AiEntityResolutionMode,
    val sourceFingerprint: String?,
)
```

Extend `AiResolvedSaleLine` with `resolutionMode` and nullable existing fingerprint while retaining a nonblank locally controlled `productId`. Extend clarification results so the caller can choose an existing candidate or `CREATE_NEW`; never let Gemini provide the chosen ID.

- [ ] **Step 3: Implement the minimal read-only resolver**

Rules:

```text
one exact active in-business match + no override -> EXISTING
zero candidates -> NEW
one or more non-exact candidates + no override -> SelectionRequired
UseExisting(id) -> accept only an active in-business candidate returned by the search
CreateNew -> NEW with locally generated ID
```

For new entities, trim the display name, normalize customer names with `CustomerNameNormalizer.key`, preserve the proposal's declared product unit price, and require `newEntityId` to return a nonblank value. Do not import DAOs, repositories, sync or Firebase.

- [ ] **Step 4: Run focused and regression gates**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiSalePreviewTest"
.\gradlew.bat testDebugUnitTest
git diff --check
```

Expected: all commands exit 0. Confirm `git diff --name-only` lists only the two Task 1 files.

- [ ] **Step 5: Commit Task 1**

```powershell
git add app/src/main/java/com/cuentivo/app/ai/AiSalePreview.kt app/src/test/java/com/cuentivo/app/ai/AiSalePreviewTest.kt
git commit -m "feat(ai): propose missing sale entities safely"
```

Stop and return the RED/GREEN evidence, commit SHA, diff summary and any ambiguity. Do not start Task 2.

---

### Task 2: Atomic creation and financial execution

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ai/AiSaleExecution.kt`
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`
- Modify: `app/src/main/java/com/cuentivo/app/domain/SaleRepository.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ai/AiSaleExecutionTest.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ai/AiSaleExecutionIsolationTest.kt`

**Interfaces:**
- Consumes: confirmation-bound resolved customer/lines from Task 1, including `EXISTING` fingerprints or complete minimal `NEW` snapshots.
- Produces: `ExpectedSaleResult.Recorded` only after one atomic transaction has created required catalog rows, sale, optional payment and audit evidence.

- [ ] **Step 1: Write RED JVM and connected tests** covering all-new, mixed, habitual price persistence, no invented optional fields, duplicate confirmation, partial failure rollback, stale existing fingerprints, a new exact-name row appearing after preview, explicit duplicate-name creation, wrong business and blank user.
- [ ] **Step 2: Extend the confirmation request** with immutable customer/product creation specs controlled by Android, never raw model IDs.
- [ ] **Step 3: Add one `@Transaction` DAO method** that reserves the operation key, revalidates existing rows/choices, inserts new catalog rows, then invokes the existing sale/payment insertion logic in the same transaction. A replay returns the originally persisted IDs.
- [ ] **Step 4: Reload exact persisted customer/product/sale/payment rows** and enqueue them after commit using deterministic outbox IDs; a partial outbox failure must recover on retry without duplicate local rows.
- [ ] **Step 5: Run** focused JVM tests, connected `AiSaleExecutionIsolationTest`, full JVM, `assembleDebug`, `assembleRelease`, `lintDebug` and `git diff --check`.
- [ ] **Step 6: Commit** with `feat(ai): create missing sale entities atomically` and stop for audit.

---

### Task 3: Clarification and composite preview UI

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`

**Interfaces:**
- Consumes: Task 1 clarification choices and composite preview; Task 2 confirmation callback.
- Produces: generation-safe choice cards and a preview that labels every entity `Existente` or `Nuevo` before a single explicit confirmation.

- [ ] **Step 1: Write RED state-machine tests** for choose-existing, choose-create-new, cancel, stale choice, mixed preview, edit/reset and duplicate confirm tap.
- [ ] **Step 2: Add clarification messages/actions** that expose only locally returned candidates plus `Crear nuevo`; never accept an arbitrary ID from text input.
- [ ] **Step 3: Extend the preview card** to show new customer/product badges and habitual product price, followed by deterministic total/payment/remainder.
- [ ] **Step 4: Preserve single-flight and cancellation semantics**; changing any choice creates a new preview generation and invalidates the old confirmation.
- [ ] **Step 5: Run** focal UI tests, full JVM, `assembleDebug`, `assembleRelease`, `lintDebug` and `git diff --check`.
- [ ] **Step 6: Commit** with `feat(ai): confirm new sale entities in preview` and stop for audit.

---

### Task 4: Host wiring, connected gate and physical acceptance

**Files:**
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt` only if callback signatures require it
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ai/AiSaleExecutionIsolationTest.kt`
- Modify after physical evidence: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`
- Modify after physical evidence: `PROJECT.md`

**Interfaces:**
- Consumes: active business/user, existing repositories/DAOs and exact persisted bundles.
- Produces: debug-only end-to-end composite confirmation; release remains unreachable/off until its separate beta gate.

- [ ] **Step 1: Write RED host/connected guards** proving active-business equality, blank-user rejection, preview writes zero rows and exact persisted customer/product/financial outbox payloads.
- [ ] **Step 2: Wire existing repositories and transaction** without adding Firebase or provider access to the domain/UI.
- [ ] **Step 3: Run complete automated gate:** full JVM, both focused connected financial suites, `assembleDebug`, `assembleRelease`, `lintDebug`, Functions tests/build/lint and `git diff --check`.
- [ ] **Step 4: Install with `adb install -r`** on the authorized A059P and preserve its debug data.
- [ ] **Step 5: Manually accept** `Ana se llevó dos cremas de $120 y pagó $100`: preview labels Ana/Crema new, stores habitual price $120, records one $240 sale and one $100 payment, leaves $140 due, and duplicate confirmation creates nothing extra.
- [ ] **Step 6: Record evidence only after observation**, commit documentation with `docs(ai): record composite sale acceptance`, and keep AAB/Play public as NO-GO.

## Self-review

- Spec coverage: new/existing/mixed resolution, explicit ambiguity choice, habitual price, atomicity, idempotency, rollback, outbox, UI and physical acceptance each map to a task.
- Scope: voice, queries, barcode, PDF/WhatsApp, Billing, release activation and provider changes remain outside this plan.
- Type consistency: Task 1 owns resolution contracts; Task 2 consumes them; Task 3 presents them; Task 4 only wires accepted interfaces.
- Compatibility: no Room schema migration is planned; existing tables and nullable defaults are reused.
