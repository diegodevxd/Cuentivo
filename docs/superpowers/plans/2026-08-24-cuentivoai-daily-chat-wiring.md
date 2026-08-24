# CuentivoAI Daily Chat Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Codex audits every commit before the next task.

**Goal:** Connect the approved 30-day local daily history and conservative business-only policy to the existing debug CuentivoAI chat without persisting model proposals or enabling financial execution.

**Architecture:** A focused repository owns owner/day scoping, retention and the allow-listed persisted message shapes. A pure turn policy prevents trivial social prompts from reaching the transport, while the existing state machine gains deterministic hydration and out-of-scope transitions. The debug Compose screen orchestrates these boundaries; release keeps its existing `null` transport and no AI entry.

**Tech Stack:** Kotlin, Room 2.7.2, coroutines, Jetpack Compose, JUnit4, Android instrumented tests.

## Global Constraints

- Work only in `feature-cuentivo-ai` on `diegodevxd/feature-cuentivo-ai`; preserve `docs/superpowers/handoffs/` untracked.
- No Firestore, Functions, Gemini SDK/key, deploy, Billing, version bump, AAB or Play changes.
- No sale, payment, balance, inventory, audit or outbox write.
- Persist only `USER_TEXT` text and the deterministic assistant kinds already allow-listed by `AiDailyMessageKind`; never persist proposal JSON, clarification payloads, photo or audio.
- Every query remains scoped by the authenticated `ownerUserId`; blank owners fail before I/O.
- Retention is exactly 30 local calendar days and uses `AiDailyChatModel.cutoffDayIso`.
- The UI remains debug-only because `debugAiTransport()` is `null` in release.
- Use TDD: observe the focused RED before adding each production behavior.

---

### Task 1: Add the owner-scoped daily chat repository

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/data/local/AiDailyChatRepository.kt`
- Create: `app/src/test/java/com/cuentivo/app/data/local/AiDailyChatRepositoryTest.kt`
- Reuse unchanged: `app/src/main/java/com/cuentivo/app/data/local/AiDailyMessageDao.kt`
- Reuse unchanged: `app/src/main/java/com/cuentivo/app/data/local/AiDailyMessageEntity.kt`

**Interfaces:**
- Consumes: `AiDailyMessageDao`, `AiDailyMessageEntity`, `AiDailyMessageKind`, `AiDailyChatModel.dayIsoOf`, `AiDailyChatModel.cutoffDayIso`.
- Produces:

```kotlin
data class AiDailyStoredMessage(
    val kind: AiDailyMessageKind,
    val textContent: String?,
)

data class AiDailyConversation(
    val dayIso: String,
    val messages: List<AiDailyStoredMessage>,
)

class AiDailyChatRepository(
    private val dao: AiDailyMessageDao,
    private val ownerUserId: String,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun openToday(): AiDailyConversation
    suspend fun appendUserText(text: String)
    suspend fun appendAssistant(kind: AiDailyMessageKind)
    suspend fun clearToday(): AiDailyConversation
}
```

- [ ] **Step 1: Write focused failing JVM tests with a fake DAO**

Cover exact behavior:

```kotlin
@Test fun `open today prunes only owner rows older than the exact 30 day cutoff and inserts one greeting when empty`()
@Test fun `repository never reads or deletes another owner`()
@Test fun `user text is trimmed and assistant rows never store text`()
@Test fun `blank owner and blank user text fail before dao io`()
@Test fun `unknown kinds and invalid text shapes fail closed during hydration`()
@Test fun `clear today removes only today and restores one deterministic greeting`()
```

Use `Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC)` and `ZoneId.of("America/Mexico_City")`. The fake DAO must implement the real DAO interface, record calls and store entities in memory; it must not mock Room internals.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.data.local.AiDailyChatRepositoryTest --no-daemon --no-configuration-cache --no-watch-fs
```

Expected: compilation failure because `AiDailyChatRepository` and its DTOs do not exist.

- [ ] **Step 3: Implement the minimal repository**

Implementation rules:

```kotlin
private fun currentDayIso(): String =
    AiDailyChatModel.dayIsoOf(clock.millis(), zoneId)
```

`openToday()` must call `deleteExpiredDays(ownerUserId, cutoff)`, read today, decode only exact valid shapes, and insert `ASSISTANT_GREETING` with `textContent = null` only if no valid messages remain. `appendUserText` trims and rejects blank input. `appendAssistant` rejects `USER_TEXT` and writes `null` text. `clearToday` deletes only `(owner, today)` and inserts one greeting. Do not catch or log database exceptions in this layer.

- [ ] **Step 4: Run focused GREEN and regression gates**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.data.local.AiDailyChatRepositoryTest --no-daemon --no-configuration-cache --no-watch-fs
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --no-configuration-cache --no-watch-fs
git diff --check
```

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git add app/src/main/java/com/cuentivo/app/data/local/AiDailyChatRepository.kt app/src/test/java/com/cuentivo/app/data/local/AiDailyChatRepositoryTest.kt
git commit -m "feat(ai-history): add scoped daily chat repository"
```

---

### Task 2: Add deterministic history and business-scope transitions

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabTurnPolicy.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`
- Create: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabTurnPolicyTest.kt`

**Interfaces:**
- Consumes: `AiDailyStoredMessage`, `AiBusinessScopePolicy`, `AiDebugLabSubmitPolicy`.
- Produces:

```kotlin
sealed interface AiDebugLabTurnResult {
    data object OutOfScope : AiDebugLabTurnResult
    data class Remote(val result: CallableAiProposalResult) : AiDebugLabTurnResult
}

object AiDebugLabTurnPolicy {
    suspend fun run(
        transport: AiDebugTransport,
        request: AiInterpretRequest,
    ): AiDebugLabTurnResult
}
```

and new presentational messages `AssistantGreeting` and `AssistantOutOfScope`, plus pure machine functions:

```kotlin
fun onHistoryLoaded(model: AiDebugLabModel, history: List<AiDailyStoredMessage>): AiDebugLabModel
fun onOutOfScope(model: AiDebugLabModel): AiDebugLabModel
```

- [ ] **Step 1: Write RED tests**

Required cases:

```kotlin
@Test fun `social exact prompt returns out of scope without calling transport`()
@Test fun `business and ambiguous text call transport exactly once`()
@Test fun `history hydration maps only persisted deterministic kinds`()
@Test fun `out of scope replaces the draft with user and deterministic assistant messages and clears text`()
@Test fun `out of scope while sending is ignored`()
```

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.ai.AiDebugLabMachineTest --tests com.cuentivo.app.ui.ai.AiDebugLabTurnPolicyTest --no-daemon --no-configuration-cache --no-watch-fs
```

- [ ] **Step 3: Implement minimal pure behavior**

`AiDebugLabTurnPolicy.run` checks `AiBusinessScopePolicy.decide(request.text)` first. `OutOfScope` returns immediately and must never invoke `AiDebugTransport`; otherwise it delegates once to the existing exception-safe `AiDebugLabSubmitPolicy.run`. `onHistoryLoaded` maps stored kinds only and preserves no pending/proposal/clarification state. `onOutOfScope` is allowed only from `TextEditing`, trims the current text, appends `UserText` plus `AssistantOutOfScope`, clears the editor and leaves the state editable.

- [ ] **Step 4: Run GREEN and regression gates**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.ai.AiDebugLabMachineTest --tests com.cuentivo.app.ui.ai.AiDebugLabTurnPolicyTest --no-daemon --no-configuration-cache --no-watch-fs
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --no-configuration-cache --no-watch-fs
git diff --check
```

- [ ] **Step 5: Commit only Task 2 files**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabTurnPolicy.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabTurnPolicyTest.kt
git commit -m "feat(ai-ui): enforce business-only daily turns"
```

---

### Task 3: Wire today's local conversation into the debug screen

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify or create focused source guard tests under `app/src/test/java/com/cuentivo/app/ui/ai/`

**Interfaces:**
- `AiDebugLabScreen` receives a non-null `AiDailyChatRepository` alongside the debug transport.
- `CuentivoApp` receives `aiDailyChatRepository: AiDailyChatRepository? = null`; the AI entry is available only when both transport and repository are non-null.
- `MainActivity` creates the repository with the existing `database.aiDailyMessageDao()` and the authenticated `currentUserId`, remembered by user id.

- [ ] **Step 1: Write failing source/behavior guards before wiring**

Tests must prove:

```text
- release debug transport remains null;
- the screen has no direct Firebase, financial repository, sale/payment DAO or outbox import;
- the screen uses AiDebugLabTurnPolicy rather than invoking transport directly;
- proposal and clarification results are never passed to appendAssistant;
- a persisted history failure cannot crash or block normal Cuentivo UI.
```

- [ ] **Step 2: Run focused guards and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.*" --no-daemon --no-configuration-cache --no-watch-fs
```

- [ ] **Step 3: Wire load, submit and persistence**

On screen open, call `openToday()` once and hydrate the machine. Disable send/delete until this load finishes. On a turn:

- Always append the trimmed user text locally.
- For `OutOfScope`, append only `ASSISTANT_OUT_OF_SCOPE` and apply `onOutOfScope`; never call the gateway.
- For remote `Unavailable`, append only `ASSISTANT_UNAVAILABLE` and apply the existing result transition.
- For proposal or clarification, render the existing ephemeral preview and persist no assistant payload.
- Database failures remain local/session-only failures: catch without logging private text, keep the current in-memory interaction usable and never affect normal Cuentivo.

Replace the misleading warning with localized copy equivalent to:

```text
Historial local por 30 días. Ningún borrador registra ventas o abonos sin confirmación.
```

Render the approved deterministic greeting and out-of-scope response from resources. Replace `Nuevo` with a current-day delete action protected by a confirmation dialog. Confirmed delete calls `clearToday()` and hydrates the single greeting; it never touches another day or any financial table. A previous-day selector and voice remain later increments.

- [ ] **Step 4: Run all local gates**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug --no-daemon --no-configuration-cache --no-watch-fs
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs
git diff --check
```

- [ ] **Step 5: Run focused physical-device checks**

On the authorized disposable A059P, run the existing DAO/migration tests and manually verify: greeting appears, social prompt stays local, business prompt follows the existing safe gateway path, close/reopen restores today's allowed history, delete restores one greeting, and normal Cuentivo remains usable.

- [ ] **Step 6: Commit Task 3 and update source of truth only after Codex gate**

Suggested implementation commit:

```powershell
git commit -m "feat(ai-ui): connect local daily business chat"
```

Codex then records evidence in `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`. Do not touch `docs/superpowers/handoffs/`.

## Explicitly Deferred

- Previous-day selector and browsing UI.
- Voice/microphone and runtime permission.
- Persisting proposals, clarifications, Gemini output, image or audio.
- Financial resolution, preview confirmation and idempotent execution.
- CuentivoAI Billing/entitlement UI, release visibility, AAB or Play rollout.
