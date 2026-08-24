# CuentivoAI Sale Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve a typed AI sale proposal against local business data, preview it, and record exactly one sale plus optional initial payment after explicit confirmation.

**Architecture:** Gemini remains proposal-only. Focused Android domain services resolve candidates and calculate a confirmation-bound preview; a Room transaction performs the financial write atomically and idempotently; the existing debug chat state machine exposes explicit confirm/cancel states.

**Tech Stack:** Kotlin, coroutines, Room, Jetpack Compose, JUnit, AndroidX instrumented tests.

## Global Constraints

- Work only in `diegodevxd/feature-cuentivo-ai`; do not touch the stable release workspace.
- TDD: show a focused RED before production changes, then GREEN.
- Money is `Long` cents and arithmetic must fail closed on overflow.
- Gemini never supplies entity IDs, computed totals, idempotency authority, or writes.
- No implicit customer/product creation; ambiguity and stale data block confirmation.
- Preserve the accepted AI payment flow and all manual Free/Pro behavior.
- Do not touch Billing, versions, AAB, deployment, secrets, Firestore schema, or the user-owned `docs/superpowers/handoffs/` directory.

---

### Task 1: Read-only sale resolution and confirmation-bound preview

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ai/AiSalePreview.kt`
- Create: `app/src/test/java/com/cuentivo/app/ai/AiSalePreviewTest.kt`
- Modify only if a narrow read seam is required: existing customer/product DAO query files

**Interfaces:**
- Consumes: `CreateSaleProposal`, active `businessId`, local customer/product candidate readers.
- Produces: immutable resolved preview containing real IDs, declared lines, deterministic total/payment/remainder, source fingerprints and confirmation key; blocked outcomes for missing, ambiguous, invalid, overpayment, overflow or unavailable reads.

- [ ] Write focused tests for exact resolution, missing/ambiguous customer, missing/ambiguous product, repeated product lines, zero payment, overpayment, overflow, wrong-business candidates and immutable output.
- [ ] Run only `AiSalePreviewTest` and capture the expected RED caused by missing preview types/resolver.
- [ ] Implement the smallest read-only resolver; it must not import a write-capable repository, sync queue or Firebase API.
- [ ] Run the focused test GREEN, then `testDebugUnitTest` and `git diff --check`.
- [ ] Commit only Task 1 files with `feat(ai): resolve sale proposals for preview`.

### Task 2: Atomic idempotent sale with optional initial payment

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ai/AiSaleExecution.kt`
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`
- Create: `app/src/test/java/com/cuentivo/app/ai/AiSaleExecutionTest.kt`
- Create: `app/src/androidTest/java/com/cuentivo/app/ai/AiSaleExecutionIsolationTest.kt`

**Interfaces:**
- Consumes: confirmed preview and active business/user IDs.
- Produces: recorded result with sale/payment IDs, already-recorded result, stale/rejected/unavailable outcomes.

- [ ] Write JVM policy tests and Room tests proving no write without confirmation, one atomic sale/cargo, optional payment/allocation/crédito, audit evidence, double-tap/retry exactly once, stale preview rejection and rollback on any failure.
- [ ] Run focused tests and capture RED.
- [ ] Add one Room transaction that revalidates business/customer/product facts, reserves one operation key and commits all financial rows or none.
- [ ] Run focused GREEN, DAO regression tests, full JVM tests and `git diff --check`.
- [ ] Commit with `feat(ai): execute confirmed sale atomically`.

### Task 3: Explicit sale confirmation state and chat card

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: relevant tests under `app/src/test/java/com/cuentivo/app/ui/ai/`

**Interfaces:**
- Consumes: Task 1 preview and Task 2 execution outcomes.
- Produces: generation-safe preview/confirming/recorded/blocked chat states and explicit Confirmar/Cancelar actions.

- [ ] Write RED tests for stale generation, duplicate tap, cancel, blocked resolution and preservation of the accepted payment path.
- [ ] Add minimal sale preview card and transitions; editing returns to a new proposal turn and never mutates directly.
- [ ] Run focused GREEN, full JVM suite, `assembleDebug`, `lintDebug` and `git diff --check`.
- [ ] Commit with `feat(ai): confirm sale preview explicitly`.

### Task 4: Debug host wiring and physical acceptance

**Files:**
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: source of truth after evidence exists.

- [ ] Write/extend an isolation test proving preview alone changes no financial table.
- [ ] Wire read resolution and confirmed executor behind the existing debug-only AI entry point, requiring active business equality.
- [ ] Enqueue sync from recorded local rows without exposing Gemini as financial authority.
- [ ] Run `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `lintDebug`, focused connected tests and `git diff --check`.
- [ ] Install debug APK on the authorized test phone and manually verify one phrase produces exactly one sale/payment and correct remaining balance; retry must not duplicate.
- [ ] Record evidence in the source of truth and commit with `docs(ai): record physical sale acceptance`.

