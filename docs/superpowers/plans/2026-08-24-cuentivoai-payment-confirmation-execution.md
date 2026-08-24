# CuentivoAI Payment Confirmation and Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the already-resolved, read-only CuentivoAI payment preview into an explicit human confirmation that records exactly one real payment through the existing Room ledger, audit and sync paths.

**Architecture:** Gemini remains proposal-only. Android creates a stable local confirmation key when it resolves the proposal, then a dedicated command service revalidates the active customer and exact preview balance inside a Room transaction before using the existing `PaymentAllocator` and `registerPaymentIdempotently` path. Successful local entities are enqueued with deterministic IDs; stale previews, double taps, retries and failures never create a second payment.

**Tech Stack:** Kotlin, Jetpack Compose, Room v6, coroutines, JUnit, AndroidX instrumented tests, existing `PaymentAllocator`, `FinancialDao`, `SyncQueue`, Firebase-backed outbox.

## Global Constraints

- Work only in `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\feature-cuentivo-ai` on `diegodevxd/feature-cuentivo-ai`.
- Preserve `docs/superpowers/handoffs/` exactly; it is user-owned and untracked.
- Do not modify Room schema/version, Firestore rules, Functions, Gemini gateway, Billing, entitlement, quota, product plans, versionCode/versionName, AAB or Play configuration.
- Gemini supplies only `RecordPaymentProposal(customerName, declaredAmountCents)`; it never supplies trusted IDs, balances, allocations, entity IDs or idempotency keys.
- Only an `AiPaymentPreviewResult.Ready` can expose confirmation. Unknown, ambiguous, unavailable, zero debt and overpayment remain non-executable.
- Confirmation must re-read active customer/business and exact current balance. A changed balance returns `STALE_PREVIEW` with zero new financial rows.
- Payment IDs, movement IDs and allocation IDs must be deterministic children of the stable confirmation key.
- Double tap, retry, coroutine restart and retry after local success must return the same payment ID and create one payment, one movement, one audit entry and one allocation per affected sale at most.
- Payments remain registrable independent of Free/Pro/AI commercial limits; no new plan gate is added to the financial writer.
- Local Room remains source of truth. Sync enqueue occurs only after the Room transaction succeeds and is replay-safe through deterministic outbox IDs.
- Keep the feature debug-only through the existing `debugAiTransport()` bridge. Release must retain a null AI transport and no AI entry point.

---

## File Map

- Modify `app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt`: attach an opaque, locally generated confirmation key to a ready preview.
- Modify `app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt`: prove one stable key per resolution and no key for blocked states.
- Create `app/src/main/java/com/cuentivo/app/ai/AiPaymentExecution.kt`: immutable confirmation request/result and orchestration service.
- Create `app/src/test/java/com/cuentivo/app/ai/AiPaymentExecutionTest.kt`: service boundary, validation and replay-safe sync tests.
- Create `app/src/main/java/com/cuentivo/app/domain/PaymentOperationIds.kt`: deterministic financial entity IDs derived from an idempotency key, with no AI dependency.
- Create `app/src/test/java/com/cuentivo/app/domain/PaymentOperationIdsTest.kt`: deterministic, separated and stable ID tests.
- Modify `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`: transactional stale-preview check, deterministic allocation and replay path.
- Modify `app/src/androidTest/java/com/cuentivo/app/ai/AiPaymentExecutionIsolationTest.kt`: real Room mutation, stale, cross-business and concurrent retry evidence.
- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`: confirmation state machine and single-flight guards.
- Modify `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`: confirm, duplicate, stale and retry state tests.
- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`: explicit confirmation button and result rendering.
- Modify `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`: pass the scoped execution callback into the debug-only chat.
- Modify `app/src/main/java/com/example/organizationlabs/MainActivity.kt`: build the execution service from existing DAOs, user ID and `SyncQueue`.
- Modify `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml`: confirmation, success, stale and failure copy.
- Modify `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`: record only evidence actually observed after final gates.

---

### Task 1: Stable local confirmation identity

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt`

**Interfaces:**
- Consumes: `AiPaymentPreviewResolver.resolve(businessId, proposal)`.
- Produces: `AiResolvedPaymentPreview.confirmationKey: String` generated locally through an injected `newConfirmationKey: () -> String`.

- [ ] **Step 1: Write failing tests for confirmation identity**

Add tests that construct the resolver with `newConfirmationKey = { "ai-confirm-1" }` and assert:

```kotlin
@Test
fun `ready preview receives one opaque local confirmation key`() = runTest {
    var keyCalls = 0
    val resolver = AiPaymentPreviewResolver(
        searchCustomers = { _, _ -> listOf(customer("c1", "María", "b1")) },
        loadBalanceCents = { _, _ -> 50_000L },
        newConfirmationKey = { keyCalls++; "ai-confirm-1" },
    )

    val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))

    val ready = assertIs<AiPaymentPreviewResult.Ready>(result)
    assertEquals("ai-confirm-1", ready.preview.confirmationKey)
    assertEquals(1, keyCalls)
}

@Test
fun `blocked and unresolved previews never allocate a confirmation key`() = runTest {
    var keyCalls = 0
    val resolver = AiPaymentPreviewResolver(
        searchCustomers = { _, _ -> emptyList() },
        loadBalanceCents = { _, _ -> error("must not load") },
        newConfirmationKey = { keyCalls++; "forbidden" },
    )

    assertIs<AiPaymentPreviewResult.CustomerNotFound>(
        resolver.resolve("b1", RecordPaymentProposal("Nadie", 15_000L)),
    )
    assertEquals(0, keyCalls)
}
```

Update every ready-preview expectation to include a non-blank confirmation key. Add a test that blank keys fail closed to `Unavailable`.

- [ ] **Step 2: Run the focused test and observe RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiPaymentPreviewTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because the resolver parameter and `confirmationKey` do not exist.

- [ ] **Step 3: Add the minimal confirmation-key boundary**

Change the ready value and resolver constructor to these exact shapes:

```kotlin
data class AiResolvedPaymentPreview(
    val confirmationKey: String,
    val customerId: String,
    val customerName: String,
    val currentBalanceCents: Long,
    val declaredAmountCents: Long,
    val projectedBalanceCents: Long,
)

class AiPaymentPreviewResolver(
    private val searchCustomers: suspend (businessId: String, query: String) -> List<CustomerEntity>,
    private val loadBalanceCents: suspend (businessId: String, customerId: String) -> Long,
    private val newConfirmationKey: () -> String = { UUID.randomUUID().toString() },
)
```

Call `newConfirmationKey()` exactly once and only after all ready checks pass. Trim it and return `Unavailable` when blank. Never render or transmit this key.

- [ ] **Step 4: Run focused tests and observe GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add app/src/main/java/com/cuentivo/app/ai/AiPaymentPreview.kt app/src/test/java/com/cuentivo/app/ai/AiPaymentPreviewTest.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt
git diff --cached --check
git commit -m "feat(ai): bind payment preview to confirmation key"
```

---

### Task 2: Transactional AI payment command

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ai/AiPaymentExecution.kt`
- Create: `app/src/test/java/com/cuentivo/app/ai/AiPaymentExecutionTest.kt`
- Create: `app/src/main/java/com/cuentivo/app/domain/PaymentOperationIds.kt`
- Create: `app/src/test/java/com/cuentivo/app/domain/PaymentOperationIdsTest.kt`
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`
- Modify: `app/src/main/java/com/cuentivo/app/domain/PaymentRepository.kt`

**Interfaces:**
- Consumes: `AiResolvedPaymentPreview`, active `businessId`, current `userId`, `PaymentAllocator`, `PaymentRepository`, `SyncQueue` seams.
- Produces: `AiPaymentExecutionService.confirm(request): AiPaymentExecutionResult`.

- [ ] **Step 1: Write failing pure service tests**

Define tests around recording lambdas for the exact public model:

```kotlin
data class AiPaymentConfirmationRequest(
    val confirmationKey: String,
    val businessId: String,
    val customerId: String,
    val expectedBalanceCents: Long,
    val amountCents: Long,
)

sealed interface AiPaymentExecutionResult {
    data class Recorded(val paymentId: String) : AiPaymentExecutionResult
    data object StalePreview : AiPaymentExecutionResult
    data object Rejected : AiPaymentExecutionResult
    data object Unavailable : AiPaymentExecutionResult
}
```

Test: valid request delegates exact values; blank key/business/customer, non-positive balance/amount and amount above expected balance return `Rejected` without calling the writer; writer stale maps to `StalePreview`; cancellation is rethrown; other exceptions map to `Unavailable`; successful/replayed writes enqueue the returned stored entities, never request-supplied payloads.

- [ ] **Step 2: Run the focused service test and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiPaymentExecutionTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because `AiPaymentExecution.kt` does not exist.

- [ ] **Step 3: Add transactional DAO result and stored entity bundle**

Add these domain-neutral results beside `FinancialDao`:

```kotlin
sealed interface ExpectedBalancePaymentResult {
    data class Recorded(val paymentId: String) : ExpectedBalancePaymentResult
    data object StaleBalance : ExpectedBalancePaymentResult
}

data class StoredPaymentBundle(
    val payment: PaymentEntity,
    val allocations: List<PaymentAllocationEntity>,
    val movement: AccountMovementEntity,
)
```

Add scoped queries:

```kotlin
@Query("SELECT * FROM payment_allocations WHERE paymentId = :paymentId ORDER BY id")
suspend fun allocationsForPayment(paymentId: String): List<PaymentAllocationEntity>

@Query("SELECT * FROM account_movements WHERE businessId = :businessId AND paymentId = :paymentId AND type = 'PAYMENT' LIMIT 1")
suspend fun paymentMovement(businessId: String, paymentId: String): AccountMovementEntity?
```

Add an `@Transaction` method with this exact ordering:

```kotlin
@Transaction
suspend fun registerPaymentAgainstExpectedBalanceIdempotently(
    operation: OperationEntity,
    payment: PaymentEntity,
    movement: AccountMovementEntity,
    expectedBalanceCents: Long,
): ExpectedBalancePaymentResult {
    operationResultId(operation.idempotencyKey)?.let {
        return ExpectedBalancePaymentResult.Recorded(it)
    }
    if (customerBalanceCents(payment.businessId, payment.customerId) != expectedBalanceCents) {
        return ExpectedBalancePaymentResult.StaleBalance
    }
    val outstanding = outstandingSalesForCustomer(payment.businessId, payment.customerId)
        .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
    val allocationResult = PaymentAllocator.allocate(payment.amountCents, outstanding)
    val allocations = allocationResult.allocations.map { allocation ->
        PaymentAllocationEntity(
            id = PaymentOperationIds.allocation(operation.idempotencyKey, allocation.saleId),
            paymentId = payment.id,
            saleId = allocation.saleId,
            amountCents = allocation.amountCents,
        )
    }
    registerPaymentIdempotently(operation, payment, allocations, movement)
    return ExpectedBalancePaymentResult.Recorded(payment.id)
}
```

Create `PaymentOperationIds` in the domain package, never under `ai`, so `FinancialDao` does not depend on the AI layer:

```kotlin
object PaymentOperationIds {
    fun payment(idempotencyKey: String): String = child(idempotencyKey, "payment", "root")
    fun movement(idempotencyKey: String): String = child(idempotencyKey, "movement", "root")
    fun allocation(idempotencyKey: String, saleId: String): String = child(idempotencyKey, "allocation", saleId)

    private fun child(key: String, kind: String, suffix: String): String {
        require(key.isNotBlank())
        return UUID.nameUUIDFromBytes("$key:$kind:$suffix".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
```

`PaymentOperationIdsTest` must prove identical inputs are stable, different kinds/keys/sales are distinct and blank keys throw. This is deterministic identity, not cryptographic authentication.

- [ ] **Step 4: Implement repository and service**

Add to `PaymentRepository`:

```kotlin
suspend fun registerAgainstExpectedBalance(
    idempotencyKey: String,
    payment: PaymentEntity,
    movement: AccountMovementEntity,
    expectedBalanceCents: Long,
): ExpectedBalancePaymentResult =
    financialDao.registerPaymentAgainstExpectedBalanceIdempotently(
        OperationEntity(idempotencyKey, "PAYMENT", payment.id, payment.createdAtEpochMillis),
        payment,
        movement,
        expectedBalanceCents,
    )

suspend fun storedBundle(businessId: String, paymentId: String): StoredPaymentBundle? {
    val payment = financialDao.paymentForId(paymentId) ?: return null
    if (payment.businessId != businessId) return null
    val movement = financialDao.paymentMovement(businessId, paymentId) ?: return null
    return StoredPaymentBundle(payment, financialDao.allocationsForPayment(paymentId), movement)
}
```

Implement `AiPaymentExecutionService` with this exact dependency boundary so JVM tests need no Android context or Room mock:

```kotlin
class AiPaymentExecutionService(
    private val registerPayment: suspend (
        idempotencyKey: String,
        payment: PaymentEntity,
        movement: AccountMovementEntity,
        expectedBalanceCents: Long,
    ) -> ExpectedBalancePaymentResult,
    private val loadStoredBundle: suspend (businessId: String, paymentId: String) -> StoredPaymentBundle?,
    private val enqueueStoredBundle: suspend (StoredPaymentBundle) -> Unit,
    private val currentUserId: () -> String,
    private val nowMillis: () -> Long,
    private val todayEpochDay: () -> Long,
) {
    suspend fun confirm(request: AiPaymentConfirmationRequest): AiPaymentExecutionResult
}
```

It constructs deterministic payment/movement IDs, writes, reloads the stored bundle, then calls `enqueueStoredBundle` with the persisted entities. The host adapter enqueues `PAYMENT`, every `PAYMENT_ALLOCATION`, and `MOVEMENT` using existing `SyncPayloads`. It rethrows `CancellationException`; it never logs request content or amounts.

- [ ] **Step 5: Run focused JVM tests and observe GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ai.AiPaymentExecutionTest" --tests "com.cuentivo.app.domain.PaymentOperationIdsTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: PASS. `PaymentRepository` and the real DAO boundary are covered by Task 3 instrumented tests.

- [ ] **Step 6: Commit Task 2**

```powershell
git add app/src/main/java/com/cuentivo/app/ai/AiPaymentExecution.kt app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt app/src/main/java/com/cuentivo/app/domain/PaymentOperationIds.kt app/src/main/java/com/cuentivo/app/domain/PaymentRepository.kt app/src/test/java/com/cuentivo/app/ai/AiPaymentExecutionTest.kt app/src/test/java/com/cuentivo/app/domain/PaymentOperationIdsTest.kt
git diff --cached --check
git commit -m "feat(ai): execute confirmed payment idempotently"
```

---

### Task 3: Prove financial isolation, replay and concurrency in Room

**Files:**
- Create: `app/src/androidTest/java/com/cuentivo/app/ai/AiPaymentExecutionIsolationTest.kt`
- Test: `app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt`

**Interfaces:**
- Consumes: real in-memory `CuentivoDatabase`, `PaymentRepository`, `AiPaymentExecutionService` with a recording enqueue seam.
- Produces: instrumented evidence for exactly-once mutation and stale/cross-business rejection.

- [ ] **Step 1: Write the instrumented tests**

Create disposable business/customer/sales data and assert these exact cases:

1. Ready $500, confirm $150: one payment, allocations totaling $150, one PAYMENT/CREDIT movement, one `PAYMENT_REGISTERED` audit, balance $350 and correct outbox payload types.
2. Same confirmation request twice: same payment ID and unchanged counts after the second call.
3. Two concurrent confirmations with the same key: both return the same payment ID; exactly one financial mutation.
4. Preview $500, another payment changes balance to $450, then confirm original $150: `StalePreview`, no rows belonging to the AI confirmation key.
5. Customer archived/deleted or moved outside the active business after preview: rejected/stale and zero AI mutation.
6. Failure during enqueue after local commit followed by retry: Room rows remain single and missing deterministic outbox rows are recovered without duplicates.

Use count/snapshot queries already used in `AiPaymentPreviewIsolationTest`; add only test-only DAO queries where essential. Do not change schema.

- [ ] **Step 2: Run the focal instrumented test and observe RED/GREEN**

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.AiPaymentExecutionIsolationTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected initial result: at least one new test fails before its production guard exists. Expected final result: PASS on the authorized physical device or emulator.

- [ ] **Step 3: Harden active-customer validation discovered by the test**

The transactional method must query the customer by ID and require all conditions before balance/allocation:

```kotlin
val customer = customerForPayment(payment.customerId) ?: return ExpectedBalancePaymentResult.StaleBalance
if (customer.businessId != payment.businessId || !customer.isActive || customer.deletedAtEpochMillis != null) {
    return ExpectedBalancePaymentResult.StaleBalance
}
```

Add the narrowly named DAO query if absent:

```kotlin
@Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
suspend fun customerForPayment(customerId: String): CustomerEntity?
```

- [ ] **Step 4: Re-run focal and existing operation tests**

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.AiPaymentExecutionIsolationTest,com.cuentivo.app.data.local.OperationDaoTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: PASS; existing manual payment behavior and correction invariants remain green.

- [ ] **Step 5: Commit Task 3**

```powershell
git add app/src/androidTest/java/com/cuentivo/app/ai/AiPaymentExecutionIsolationTest.kt app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt
git diff --cached --check
git commit -m "test(ai): prove confirmed payment exactly once"
```

---

### Task 4: Confirmation-safe chat state and UI

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `AiChatMessage.AssistantPaymentPreview`, `AiPaymentConfirmationRequest`, suspend `confirmPayment` callback.
- Produces: explicit confirmation, single-flight execution state and deterministic assistant outcome.

- [ ] **Step 1: Write failing state-machine tests**

Add message/state shapes:

```kotlin
data class PaymentConfirmation(val preview: AiResolvedPaymentPreview) : AiDebugLabState
data class PaymentConfirming(val preview: AiResolvedPaymentPreview) : AiDebugLabState
data class AssistantPaymentRecorded(val paymentId: String) : AiChatMessage
data object AssistantPaymentStale : AiChatMessage
data object AssistantPaymentRejected : AiChatMessage
```

Test that `onConfirmPayment` accepts only the current `PaymentConfirmation`, increments or captures a confirmation generation, transitions once to `PaymentConfirming`, and returns an outcome containing the exact local confirmation request. A second tap is ignored. Test stale result, cancel/reset during execution, success, stale preview, rejected and unavailable. Success replaces the preview with a recorded assistant message and cannot be confirmed again.

- [ ] **Step 2: Run focused model tests and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.AiDebugLabMachineTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because confirmation transitions do not exist.

- [ ] **Step 3: Implement the pure transition**

Add:

```kotlin
sealed interface AiPaymentConfirmOutcome {
    data object Ignored : AiPaymentConfirmOutcome
    data class Started(
        val model: AiDebugLabModel,
        val request: AiPaymentConfirmationRequest,
        val generation: Long,
    ) : AiPaymentConfirmOutcome
}
```

`onConfirmPayment(model, businessId)` must build the request only from the locally resolved preview and active business ID. `onPaymentExecutionResult` accepts only the matching generation and `PaymentConfirming`; all other results are ignored.

- [ ] **Step 4: Add the explicit UI action and guard tests**

Extend `AiDebugLabScreen`:

```kotlin
confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)?
```

Render `Confirmar abono` only on `AssistantPaymentPreview`, followed by `Cancelar`. Disable both while confirming, invoke once in the existing coroutine scope, and pass the result through the state machine. Do not optimistically show success. The card continues showing exact current/proposed/projected amounts.

Guard tests must prove:

- no confirm action exists for blocked, unresolved, unavailable or arbitrary proposal messages;
- no callable/Gemini invocation occurs during confirmation;
- release still passes a null transport and cannot open the chat;
- strings keys match between Spanish and English;
- confirmation key and payment ID are not shown in UI or persisted in daily chat.

- [ ] **Step 5: Run focused UI tests and observe GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.AiDebugLabMachineTest" --tests "com.cuentivo.app.ui.ai.AiDebugLabGuardsTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/test/java/com/cuentivo/app/ui/ai
git diff --cached --check
git commit -m "feat(ai): confirm payment preview explicitly"
```

---

### Task 5: Scoped host wiring, full gates and physical acceptance

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify after evidence: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`

**Interfaces:**
- Consumes: active `DashboardUiState.Content.businessId`, authenticated `currentUserId`, `PaymentRepository`, `SyncQueue`.
- Produces: debug-only `confirmPayment` callback and auditable physical evidence.

- [ ] **Step 1: Add failing host wiring guards**

Extend `AiDebugLabGuardsTest` to assert that `CuentivoApp` receives a nullable suspend confirmation callback, passes it only to `AiDebugLabScreen`, and derives the request business ID from `DashboardUiState.Content`. Assert `MainActivity` constructs `AiPaymentExecutionService` from local repositories and sync queue; no database/DAO or writer is passed directly to Compose.

- [ ] **Step 2: Run guards and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.ai.AiDebugLabGuardsTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: FAIL because host confirmation wiring is absent.

- [ ] **Step 3: Wire the execution service**

Construct once beside `aiPaymentPreviewResolver`:

```kotlin
val aiPaymentExecutionService = remember(currentUserId) {
    val paymentRepository = PaymentRepository(database.financialDao())
    AiPaymentExecutionService(
        registerPayment = paymentRepository::registerAgainstExpectedBalance,
        loadStoredBundle = paymentRepository::storedBundle,
        enqueueStoredBundle = { bundle ->
            val payment = bundle.payment
            syncQueue.enqueue(payment.businessId, "PAYMENT", payment.id, payment.createdAtEpochMillis, SyncPayloads.payment(payment))
            bundle.allocations.forEach { allocation ->
                syncQueue.enqueue(payment.businessId, "PAYMENT_ALLOCATION", allocation.id, payment.createdAtEpochMillis, SyncPayloads.allocation(allocation))
            }
            syncQueue.enqueue(payment.businessId, "MOVEMENT", bundle.movement.id, payment.createdAtEpochMillis, SyncPayloads.movement(bundle.movement))
        },
        currentUserId = { currentUserId },
        nowMillis = System::currentTimeMillis,
        todayEpochDay = { LocalDate.now().toEpochDay() },
    )
}
```

Pass `confirmPayment = aiPaymentExecutionService::confirm` through `CuentivoApp`. The service must reject any request whose `businessId` differs from the active business captured by the host. After `Recorded`, increment `refreshKey`; after stale/rejected/unavailable, show only fixed vocabulary UI messages without PII or amounts in logs.

- [ ] **Step 4: Run the complete automated gate serially**

```powershell
.\gradlew.bat --stop
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.AiPaymentExecutionIsolationTest,com.cuentivo.app.data.local.OperationDaoTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
git diff --check
```

Expected: every Gradle command ends `BUILD SUCCESSFUL`; diff check prints nothing.

- [ ] **Step 5: Install and perform physical acceptance**

```powershell
& 'C:\Users\pibed\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

With disposable data: create María López with exactly $500 debt; ask “María López me pagó 150 pesos”; verify preview $500 → $150 → $350; tap Confirmar twice rapidly; verify exactly one abono $150, final debt $350, one timeline movement and no duplicate after reopening/retrying. Then create a fresh preview, register another manual payment before confirming it, and verify the AI preview reports stale without a second mutation. Verify normal manual payment still works and AI unavailable/offline does not block it.

- [ ] **Step 6: Audit cumulative diff and update source of truth**

Codex must inspect every commit and reject if it introduces a direct Gemini writer, trusts a model-provided ID/balance, permits confirmation of a blocked state, generates a fresh key per tap, skips the stale-balance transaction, duplicates outbox rows or changes release exposure. Record exact commands, counts, device serial and physical observations in the source of truth; do not claim complete Fase 3, beta or production.

- [ ] **Step 7: Commit wiring and verified documentation**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt app/src/main/java/com/example/organizationlabs/MainActivity.kt docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md
git diff --cached --check
git commit -m "feat(ai): wire confirmed payment to local ledger"
```

---

## Final Review Gate

This increment is DONE only when code review and evidence prove all of the following:

- explicit human confirmation is the only path from preview to payment;
- exact active business/customer and expected balance are revalidated transactionally;
- the same confirmation key always returns the same payment ID;
- concurrent double tap/retry creates exactly one payment, movement, audit record and allocation set;
- stale, blocked, ambiguous and unavailable states create zero financial rows;
- successful local writes reach the existing replay-safe outbox without exposing payloads in logs;
- manual payments, Free/Pro, offline operation and release builds remain functional;
- no AAB, Play rollout, AI Billing product or public activation occurred.
