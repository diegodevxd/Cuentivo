# CuentivoAI Local Foundation Implementation Plan

**Goal:** Establish an off-by-default local seam: strict immutable draft contracts, deterministic `FakeAiProvider`, and draft safety validation. No UI wiring, persistence, network, Firebase, Billing, secrets, or dependencies.

**Architecture:** New code belongs only in `com.cuentivo.app.ai` and has no DAO, repository, Android `Context`, HTTP, Firebase or financial-factory reference. Preview/confirmation wiring is deferred until this branch is rebased on the frozen public-release commit.

**Constraints:** Work only on `diegodevxd/feature-cuentivo-ai`; no AAB, version changes, Play actions, backend or paid service. All money is `Long` cents. `AiExperiment.isEnabled()` is false by default. Tests precede each production behavior.

### Task 1: Gate and contracts

**Files:** create `app/src/main/java/com/cuentivo/app/ai/AiExperiment.kt`, `AiContracts.kt`; create `app/src/test/java/com/cuentivo/app/ai/AiExperimentTest.kt`, `AiContractsTest.kt`.

**Produces:** `AiExperiment.isEnabled(localOverride: Boolean = false): Boolean`; `AiInterpretRequest(text, requestId)`; sealed `AiProviderResult`; sealed `AiIntent` restricted to `RecordPaymentDraft`, `CreateSaleDraft`, `NeedClarification`, `Refuse`. Names are display candidates only; no entity IDs.

- [x] Write test-first cases proving the experiment is false by default, true only for explicit local override, and money is `Long` cents.
- [x] Run `./gradlew.bat :app:testDebugUnitTest --tests com.cuentivo.app.ai.AiExperimentTest --tests com.cuentivo.app.ai.AiContractsTest`; confirm it fails because the API is absent.
- [x] Implement only immutable Kotlin contracts and the local gate.
- [x] Rerun the test command and commit only these files with `feat(ai): add local experiment contracts`.

### Task 2: Provider boundary and deterministic fake

**Files:** create `app/src/main/java/com/cuentivo/app/ai/AiProvider.kt`, `FakeAiProvider.kt`; create `app/src/test/java/com/cuentivo/app/ai/FakeAiProviderTest.kt`.

**Produces:** `interface AiProvider { suspend fun interpret(request: AiInterpretRequest): AiProviderResult }`. The fake recognizes only `Diego me pagó 500` and `Diego se llevó dos perfumes de 300 y pagó 200`; every other input yields `NeedClarification`. It neither calculates final totals nor reads/persists entities.

- [x] Write failing fixture and unsupported-text tests first.
- [x] Run `./gradlew.bat :app:testDebugUnitTest --tests com.cuentivo.app.ai.FakeAiProviderTest`; confirm RED because the fake is absent.
- [x] Implement only the interface and deterministic fixture map; rerun GREEN and commit `feat(ai): add deterministic fake provider`.

### Task 3: Safety validator and phase evidence

**Files:** create `app/src/main/java/com/cuentivo/app/ai/AiDraftSafety.kt`; create `app/src/test/java/com/cuentivo/app/ai/AiDraftSafetyTest.kt`; modify `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`.

**Produces:** `AiDraftSafety.validate(result): AiDraftSafetyResult`. Only nonblank names and positive quantities/centavos become `ReadyForHumanPreview`; `NeedClarification`, `Refused`, zero/negative values and blank names never do. This result has no execute, confirm, DAO, repository, idempotency or persistence callback.

- [x] Write failing tests for a valid payment, zero amount, invalid sale and refusal/clarification.
- [x] Run `./gradlew.bat :app:testDebugUnitTest --tests com.cuentivo.app.ai.AiDraftSafetyTest`; confirm RED because validator is absent.
- [x] Implement minimum pure validation; rerun GREEN.
- [x] Run `./gradlew.bat :app:testDebugUnitTest`, `./gradlew.bat :app:assembleDebug`, `./gradlew.bat :app:lintDebug`, and `git diff --check`. Lint: 1 error preexistente fuera de alcance (`ui/NeoFintechShell.kt:206`, `UnusedContentLambdaTargetStateParameter`, introducido en `938b296`); cero problemas en `com/cuentivo/app/ai`.
- [x] Update the source of truth with Fase 0A, branch, commits, RED→GREEN evidence, no-GLM rationale, $0 service cost and deferred preview/confirmation; commit `feat(ai): validate local drafts safely`.

## Done Criteria

- AI is off by default and existing Gratis/Pro code does not call a provider.
- Fixtures return strict immutable drafts; unsupported/refused/invalid outputs cannot become preview-ready.
- No network, Firebase, Billing, Room migration, secret, dependency, permission, version/AAB or Play modification exists.
- Unit tests, debug build, lint and diff check have fresh green evidence.
- The source of truth is updated and all phase commits stay on the isolated branch.
