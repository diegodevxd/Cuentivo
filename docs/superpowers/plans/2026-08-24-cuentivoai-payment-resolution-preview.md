# CuentivoAI Payment Resolution Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve a validated Gemini payment proposal against the active business's real local customer and ledger balance, then show a non-executable deterministic preview.

**Architecture:** Add a pure candidate matcher plus a read-only preview resolver whose dependencies are suspend function seams backed by `CustomerRepository.search` and `FinancialDao.customerBalanceCents`. Inject that resolver through the existing manual composition root into the debug AI chat; keep every result generation-scoped and omit all confirmation or write APIs.

**Tech Stack:** Kotlin, coroutines, Room read DAOs, Jetpack Compose, JUnit, existing Cuentivo domain and debug AI transport.

## Global Constraints

- Gemini proposes display names and declared amounts only; it never supplies authoritative IDs, balances or allocations.
- Money stays `Long` whole centavos. Never use `Double` or `Float`.
- Every lookup is scoped to the active `businessId`; archived, deleted and cross-business customers cannot resolve.
- The current balance comes only from `FinancialDao.customerBalanceCents(businessId, customerId)`.
- This increment exposes no confirm/save/execute callback and writes no payment, movement, operation, audit, outbox, chat proposal or Firestore document.
- An exact normalized name must be uniquely resolved. Partial or multiple candidates require human clarification.
- Zero/non-positive debt and overpayment are blocked preview states; never create credit automatically.
- No Room schema, Firebase Function, Firestore rules, Billing, entitlement, quota, AAB, Play or release behavior changes.
- Preserve `docs/superpowers/handoffs/` exactly; it is user-owned and untracked.

---

## File Map

- Create `app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt`: immutable result types, pure candidate matcher, checked preview calculation and read-only resolver.
- Create `app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt`: pure and suspend-seam tests for matching, isolation inputs, balances and zero mutation surface.
- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`: generation-safe transition from raw payment proposal to resolved assistant message.
- Modify `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`: state-machine tests.
- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`: invoke the read-only resolver and render deterministic result cards.
- Modify `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`: pass the active business and resolver into the debug-only screen.
- Modify `app/src/main/java/com/example/organizationlabs/MainActivity.kt`: construct the resolver from read-only method references.
- Modify `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml`: bilingual preview and clarification labels with key parity.
- Modify `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`: record only the verified outcome after all gates pass.

---

### Task 1: Pure candidate matching and preview states

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt`
- Create: `app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt`

**Interfaces:**
- Consumes: `CustomerEntity`, `CustomerNameNormalizer.key`, `RecordPaymentProposal`.
- Produces: `AiPaymentCandidateMatcher.match(query, candidates): AiPaymentCustomerMatch` and sealed `AiPaymentPreviewResult`.

- [ ] **Step 1: Write failing matcher tests**

Create customers with explicit `businessId`, active/deleted flags and normalized names, then assert these exact cases:

```kotlin
@Test fun `unique exact normalized match resolves`() {
    val result = AiPaymentCandidateMatcher.match("  MARÍA ", listOf(customer("1", "María")))
    assertEquals(AiPaymentCustomerMatch.Exact(customer("1", "María")), result)
}

@Test fun `multiple exact rows require selection`() {
    val rows = listOf(customer("1", "María"), customer("2", "Maria"))
    assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), AiPaymentCandidateMatcher.match("María", rows))
}

@Test fun `single partial row still requires selection`() {
    val rows = listOf(customer("1", "María López"))
    assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), AiPaymentCandidateMatcher.match("María", rows))
}

@Test fun `no candidates is not found`() {
    assertEquals(AiPaymentCustomerMatch.NotFound, AiPaymentCandidateMatcher.match("María", emptyList()))
}
```

- [ ] **Step 2: Run the focused test and observe RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiPaymentPreviewTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because `AiPaymentCandidateMatcher` and result types do not exist.

- [ ] **Step 3: Add the minimal immutable result model and matcher**

Implement these shapes in `AiPaymentPreview.kt`:

```kotlin
sealed interface AiPaymentCustomerMatch {
    data object NotFound : AiPaymentCustomerMatch
    data class Exact(val customer: CustomerEntity) : AiPaymentCustomerMatch
    data class SelectionRequired(val candidates: List<CustomerEntity>) : AiPaymentCustomerMatch
}

sealed interface AiPaymentPreviewResult {
    data object CustomerNotFound : AiPaymentPreviewResult
    data class CustomerSelectionRequired(val candidates: List<AiPaymentCustomerCandidate>) : AiPaymentPreviewResult
    data class Ready(val preview: AiResolvedPaymentPreview) : AiPaymentPreviewResult
    data class Blocked(val reason: AiPaymentBlockReason, val preview: AiResolvedPaymentPreview?) : AiPaymentPreviewResult
    data object Unavailable : AiPaymentPreviewResult
}

data class AiPaymentCustomerCandidate(val customerId: String, val displayName: String)
data class AiResolvedPaymentPreview(
    val customerId: String,
    val customerName: String,
    val currentBalanceCents: Long,
    val declaredAmountCents: Long,
    val projectedBalanceCents: Long,
)
enum class AiPaymentBlockReason { NO_OUTSTANDING_DEBT, AMOUNT_EXCEEDS_DEBT }
```

`match` must normalize the query, preserve deterministic DAO order, copy candidate lists with `toList()`, return `Exact` only for exactly one normalized equality, and otherwise return `SelectionRequired` for any non-empty candidate list. It must not filter by business itself; the repository seam supplies already-scoped rows.

- [ ] **Step 4: Run focused tests and observe GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt
git diff --cached --check
git commit -m "feat(ai): match payment customer candidates safely"
```

---

### Task 2: Read-only local payment preview resolver

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt`

**Interfaces:**
- Consumes: `AiPaymentCandidateMatcher`, `RecordPaymentProposal`, suspend search and balance seams.
- Produces: `AiPaymentPreviewResolver.resolve(businessId, proposal): AiPaymentPreviewResult`.

- [ ] **Step 1: Write failing resolver tests**

Use recording lambdas rather than mocks. Cover exact arguments and results:

```kotlin
@Test fun `ready preview uses scoped search and real balance`() = runTest {
    var searchArgs: Pair<String, String>? = null
    var balanceArgs: Pair<String, String>? = null
    val resolver = AiPaymentPreviewResolver(
        searchCustomers = { businessId, name ->
            searchArgs = businessId to name
            listOf(customer("c1", "María", businessId))
        },
        loadBalanceCents = { businessId, customerId ->
            balanceArgs = businessId to customerId
            40_000L
        },
    )

    val result = resolver.resolve("business-1", RecordPaymentProposal("María", 15_000L))

    assertEquals("business-1" to "María", searchArgs)
    assertEquals("business-1" to "c1", balanceArgs)
    assertEquals(
        AiPaymentPreviewResult.Ready(AiResolvedPaymentPreview("c1", "María", 40_000L, 15_000L, 25_000L)),
        result,
    )
}
```

Add tests proving: blank business returns `Unavailable` without invoking either seam; not-found and selection do not load a balance; zero/negative balance returns `Blocked(NO_OUTSTANDING_DEBT, null)`; amount greater than balance returns `Blocked(AMOUNT_EXCEEDS_DEBT, preview)`; exact payment yields projected zero; seam exceptions return `Unavailable`; and `Long.MIN_VALUE`/overflow cannot fabricate a ready preview.

- [ ] **Step 2: Run focused tests and observe RED**

Run the Task 1 focused command. Expected: FAIL because `AiPaymentPreviewResolver` is absent.

- [ ] **Step 3: Implement the read-only resolver**

Add:

```kotlin
class AiPaymentPreviewResolver(
    private val searchCustomers: suspend (businessId: String, query: String) -> List<CustomerEntity>,
    private val loadBalanceCents: suspend (businessId: String, customerId: String) -> Long,
) {
    suspend fun resolve(
        businessId: String,
        proposal: RecordPaymentProposal,
    ): AiPaymentPreviewResult
}
```

Implementation requirements:

- return `Unavailable` for blank `businessId` or any exception other than coroutine cancellation;
- rethrow `CancellationException`;
- call `searchCustomers(businessId, proposal.customerName)` exactly once;
- never call `loadBalanceCents` unless the matcher returns `Exact`;
- map candidates to local ID/display name only;
- require `proposal.declaredAmountCents > 0`, otherwise fail closed as `Unavailable` because the proposal validator should already enforce this boundary;
- for positive balances compute `Math.subtractExact(balance, amount)` and fail closed on `ArithmeticException`;
- return blocked overpayment with the negative projected result available only for display; it never becomes executable;
- expose no writer, callback or DAO mutation method.

- [ ] **Step 4: Run focused tests and observe GREEN**

Run the Task 1 focused command. Expected: PASS.

- [ ] **Step 5: Run the existing AI safety tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.*" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: PASS; existing proposal validation remains unchanged.

- [ ] **Step 6: Commit Task 2**

```powershell
git add app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt
git diff --cached --check
git commit -m "feat(ai): resolve payment preview from local ledger"
```

---

### Task 3: Generation-safe chat state integration

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`

**Interfaces:**
- Consumes: `AiPaymentPreviewResult`.
- Produces: `AiDebugLabMachine.onPaymentPreviewResult(model, result, generation): AiDebugLabModel` and presentational assistant messages.

- [ ] **Step 1: Write failing state-machine tests**

Tests must construct a model whose latest message is the raw `AssistantProposal(RecordPaymentProposal(...))`, then assert:

```kotlin
@Test fun `resolved payment replaces raw proposal only for current generation`() {
    val ready = AiPaymentPreviewResult.Ready(AiResolvedPaymentPreview("c1", "María", 40_000, 15_000, 25_000))
    val updated = AiDebugLabMachine.onPaymentPreviewResult(paymentModel(generation = 4), ready, generation = 4)
    assertIs<AiChatMessage.AssistantPaymentPreview>(updated.messages.last())
}

@Test fun `stale payment resolution is ignored`() {
    val model = paymentModel(generation = 5)
    assertSame(model, AiDebugLabMachine.onPaymentPreviewResult(model, readyResult(), generation = 4))
}
```

Also cover not found, selection required, blocked reasons, unavailable, non-payment latest message, and reset/cancel generation changes.

- [ ] **Step 2: Run focused UI-model tests and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.AiDebugLabMachineTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because the transition and messages do not exist.

- [ ] **Step 3: Add presentational messages and transition**

Add only these non-executable message shapes:

```kotlin
data class AssistantPaymentPreview(val preview: AiResolvedPaymentPreview) : AiChatMessage
data object AssistantPaymentCustomerNotFound : AiChatMessage
data class AssistantPaymentCustomerSelection(val candidates: List<AiPaymentCustomerCandidate>) : AiChatMessage
data class AssistantPaymentBlocked(val reason: AiPaymentBlockReason, val preview: AiResolvedPaymentPreview?) : AiChatMessage
```

`onPaymentPreviewResult` must accept only the current generation and only when the latest assistant message is a raw payment proposal. Replace that last message; do not append duplicate user text, persist the result, expose confirmation, or change financial state.

- [ ] **Step 4: Run focused tests and observe GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt
git diff --cached --check
git commit -m "feat(ai): model resolved payment preview states"
```

---

### Task 4: Wire read-only resolution and render the preview

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Test: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`
- Test: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`

**Interfaces:**
- Consumes: `AiPaymentPreviewResolver.resolve`, active `DashboardUiState.Content.businessId`, existing generation token.
- Produces: visible read-only payment preview in the debug chat.

- [ ] **Step 1: Write failing wiring and guard tests**

Add source/contract guards proving:

- `AiDebugLabScreen` receives a nullable suspend resolver callback and active business ID;
- the callback is invoked only for `RecordPaymentProposal` after the remote result is accepted;
- `CuentivoApp` passes the active `businessId` rather than a user-provided value;
- release still has no non-null AI transport entry;
- no strings or callbacks named confirm/save/execute are introduced into the payment card;
- EN/ES resource keys remain equal.

- [ ] **Step 2: Run focused tests and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.AiDebugLabGuardsTest" --tests "com.cuentivo.app.ui.ai.AiDebugLabMachineTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because the resolver is not wired or rendered.

- [ ] **Step 3: Add the resolver parameter and manual wiring**

Use this boundary in `AiDebugLabScreen`:

```kotlin
paymentPreviewResolver: (suspend (businessId: String, proposal: RecordPaymentProposal) -> AiPaymentPreviewResult)?,
activeBusinessId: String?,
```

After `AiDebugLabMachine.onResult` returns a current raw payment proposal, launch resolution using the same generation token. If either dependency is null, map to `Unavailable`. Run Room reads on `Dispatchers.IO`. A late result must go through `onPaymentPreviewResult` and be discarded if the generation changed.

In `MainActivity`, construct once:

```kotlin
val customerRepository = CustomerRepository(database.customerDao())
val aiPaymentPreviewResolver = AiPaymentPreviewResolver(
    searchCustomers = customerRepository::search,
    loadBalanceCents = database.financialDao()::customerBalanceCents,
)
```

Pass the resolver to `CuentivoApp`; derive `activeBusinessId` only from `DashboardUiState.Content`. Do not pass `database`, DAOs or a write callback into Compose.

- [ ] **Step 4: Render deterministic cards**

Add bilingual resources for these meanings:

- customer not found: ask the user to write the full registered name;
- selection required: list candidate display names and ask for a full name; do not auto-select;
- ready: “Saldo actual”, “Abono propuesto”, “Saldo después del abono”, and “Borrador: no registra nada”;
- no debt and overpayment: deterministic blocked explanations;
- unavailable: existing generic safe text.

Reuse existing Material 3 surfaces and money formatting at the UI boundary with `BigDecimal.valueOf(cents, 2)`. Add no confirmation button.

- [ ] **Step 5: Run focused tests and observe GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Run the complete Android gate**

Run serially:

```powershell
.\gradlew.bat --stop
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
git diff --check
```

Expected: every Gradle command ends `BUILD SUCCESSFUL`; diff check prints nothing.

- [ ] **Step 7: Commit Task 4**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/test/java/com/cuentivo/app/ui/ai
git diff --cached --check
git commit -m "feat(ai): show resolved payment preview in debug chat"
```

---

### Task 5: Prove zero mutation with Room and physical debug APK

**Files:**
- Create: `app/src/androidTest/java/com/cuentivo/app/ai/AiPaymentPreviewIsolationTest.kt`
- Modify after evidence: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`

**Interfaces:**
- Consumes: real Room DAOs and `AiPaymentPreviewResolver`.
- Produces: evidence that preview reads only and an auditable source-of-truth entry.

- [ ] **Step 1: Write the Room isolation test**

Seed two businesses, active/archived customers and ledger movements. Record counts or snapshots of `payments`, `account_movements`, `operations`, `audit_logs` and `sync_operations`; resolve a preview; assert the correct scoped balance and exact equality of every before/after snapshot. Also prove the other business and archived customer cannot resolve.

- [ ] **Step 2: Run the focal instrumented test and observe the initial result**

With the authorized physical device connected:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.AiPaymentPreviewIsolationTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected after correct implementation: PASS. If the first run fails, preserve the exact failure as RED evidence and change only production code justified by that failure.

- [ ] **Step 3: Install the debug APK without clearing authenticated state**

```powershell
& 'C:\Users\pibed\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Perform the physical acceptance check**

Create or use disposable test data only: one active customer María with a known positive debt. Submit “María pagó 150 pesos”. Verify the chat shows the exact real customer, real current debt, 150-peso proposed payment and locally calculated remaining debt. Then verify Clientes/Abonos still show no newly registered payment or movement. Repeat with an unknown full name and with a payment above the debt; both must block safely.

- [ ] **Step 5: Re-run the full final gate**

Repeat Task 4 Step 6 plus the focal instrumented test. Record actual test counts and device serial; do not infer success from prior runs.

- [ ] **Step 6: Update the source of truth only with observed evidence**

Add one dated reconciliation entry stating the exact commits, commands, test results and physical observations. State explicitly: payment resolution preview accepted; financial confirmation/execution, complete Fase 3, beta, AAB and production remain NOT DONE/NO-GO.

- [ ] **Step 7: Commit Task 5 documentation and test**

```powershell
git add app/src/androidTest/java/com/cuentivo/app/ai/AiPaymentPreviewIsolationTest.kt docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md
git diff --cached --check
git commit -m "test(ai): prove payment preview has zero mutation"
```

---

## Final Review Gate

Codex must independently inspect every commit and the cumulative diff. Reject the increment if it introduces any writer/callback from the AI preview, accepts partial-name identity silently, reads a balance without `businessId`, persists proposal data, changes Room schema, or lacks real RED/GREEN evidence. Only after all gates pass may the increment be labeled accepted; it must never be described as completed financial AI.
