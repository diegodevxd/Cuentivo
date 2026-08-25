# CuentivoAI Phase 2 Live Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** deploy and physically verify the existing Gemini-only authorization control plane for the single personal debug cohort, without Play Billing integration or financial mutations.

**Architecture:** `authorizeAiAction` remains a small Firebase Functions v2 callable protected by Auth and App Check. It delegates to the existing transactional Firestore repository and bounded warm-instance edge guard; a debug-only connected probe validates the real boundary without sending business data or invoking Gemini. Live control changes are manual, narrowly scoped and restored after each denial test.

**Tech Stack:** Node 22, TypeScript, Firebase Functions v2/Admin SDK, Firestore transactions and Emulator, Vitest, Android/Kotlin instrumented tests, Firebase Auth/App Check/Functions SDK.

## Global Constraints

- Work only in `C:/Users/pibed/orca/workspaces/OrganizationLabs-neo-fintech/feature-cuentivo-ai` on `diegodevxd/feature-cuentivo-ai`.
- Preserve `docs/superpowers/handoffs/` untracked and untouched.
- Do not change Room, Firestore financial collections/rules, Billing, Free/Pro limits, versions, sync/outbox, Android production UI or the Gemini provider.
- Do not bind `CUENTIVOAI_GEMINI_API_KEY` to `authorizeAiAction`; no secret, prompt, model output, customer, amount, uid or token may appear in source, logs or reports.
- Only `AI_AUTHORIZED` writes ledger/month/rate counters, once and atomically. Every denial writes nothing.
- The warm edge guard is a bounded cost mitigation, never described as a globally durable rate limit.
- No deployment occurs until Sol independently approves Tasks 1–2. Deploy only `functions:authorizeAiAction`.
- Any live config change records the original values first and restores them even after failure.

---

### Task 1: Replace the obsolete production callable probe

**Files:**
- Modify: `app/src/androidTest/java/com/cuentivo/app/ai/FirebaseCallableGatewayInstrumentedTest.kt`
- Test: `app/src/androidTest/java/com/cuentivo/app/ai/FirebaseCallableGatewayInstrumentedTest.kt`

**Interfaces:**
- Consumes: deployed callable request `{ contractVersion: 1, requestId: UUID, action: "interpret_text" }`.
- Produces: connected-test evidence for exact `AuthorizeAiActionResponse` and App Check rejection.

- [ ] **Step 1: Remove the stale v1 `interpretAiRequest` expectation**

Delete `deployedCallableReturnsExactNotConfiguredContract`: the deployed interpretation callable is now v2 and configured, so `AI_NOT_CONFIGURED` is historical and false.

- [ ] **Step 2: Add a reusable exact-response assertion**

Add a private helper that accepts `requestId`, response data and expected decision, then asserts exactly these five keys:

```kotlin
private fun assertAuthorizeResponse(
    requestId: String,
    raw: Any?,
    expectedDecision: String,
) {
    val data = raw as? Map<*, *> ?: error("callable response must be a map")
    assertEquals(1L, (data["contractVersion"] as? Number)?.toLong())
    assertEquals(requestId, data["requestId"])
    assertEquals(expectedDecision, data["decision"])
    assertEquals(if (expectedDecision == "AI_AUTHORIZED") "GEMINI" else null, data["provider"])
    assertEquals(false, data["fallbackUsed"])
    assertEquals(
        setOf("contractVersion", "requestId", "decision", "provider", "fallbackUsed"),
        data.keys.map(Any?::toString).toSet(),
    )
}
```

- [ ] **Step 3: Add the authenticated App Check idempotency probe**

Use one UUID twice against `getHttpsCallable("authorizeAiAction")`. Read the expected decision from the instrumentation argument `cuentivoAiExpectedDecision`, defaulting to `AI_AUTHORIZED`. Assert both responses are exact and identical. Send no text or financial fields.

```kotlin
val expected = InstrumentationRegistry.getArguments()
    .getString("cuentivoAiExpectedDecision", "AI_AUTHORIZED")!!
val requestId = UUID.randomUUID().toString()
val payload = mapOf(
    "contractVersion" to 1,
    "requestId" to requestId,
    "action" to "interpret_text",
)
val callable = FirebaseFunctions.getInstance("us-south1").getHttpsCallable("authorizeAiAction")
val first = Tasks.await(callable.call(payload))
val replay = Tasks.await(callable.call(payload))
assertAuthorizeResponse(requestId, first.data, expected)
assertAuthorizeResponse(requestId, replay.data, expected)
assertEquals(first.data, replay.data)
```

- [ ] **Step 4: Retarget the missing-App-Check probe**

Keep the existing raw HTTPS test but change only its URL to
`https://us-south1-organitazionlabs.cloudfunctions.net/authorizeAiAction`. It must still send a valid
Auth bearer with no App Check header and expect HTTP 401. Never print the bearer.

- [ ] **Step 5: Compile without invoking production**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`. Do not run the connected class before deployment.

- [ ] **Step 6: Commit**

```powershell
git add app/src/androidTest/java/com/cuentivo/app/ai/FirebaseCallableGatewayInstrumentedTest.kt
git commit -m "test(ai): probe live authorization control plane"
```

### Task 2: Revalidate the local control plane before deployment

**Files:**
- Modify only if a test exposes a real defect: `functions/src/authorizeAiAction.ts`, `functions/src/ai/controlPlane.ts`, `functions/src/ai/firestoreControlPlane.ts`, `functions/src/ai/edgeRequestGuard.ts`
- Tests: matching files under `functions/test/`

**Interfaces:**
- Consumes: existing Phase 2A code at `c435c31` plus later gateway integration.
- Produces: fresh evidence that the deployed source still satisfies the accepted invariants.

- [ ] **Step 1: Run the complete Functions gate**

```powershell
Set-Location functions
npm test
npm run build
npm run lint
npm run test:firestore-emulator
Set-Location ..
git diff --check
```

Expected: all Vitest files pass, TypeScript build/lint exit 0, Emulator concurrency tests 2/2 and diff check clean.

- [ ] **Step 2: Audit the composition root**

Verify in `functions/src/index.ts` that both callables share `AI_BOUNDARY`, including
`region: "us-south1"`, `enforceAppCheck: true`, `maxInstances: 1`, `concurrency: 1`, 15 seconds and
256 MiB; confirm `secrets: [geminiApiKey]` appears only on `interpretAiRequest`.

- [ ] **Step 3: Stop on any defect**

If the gate fails, write a focused failing regression test before modifying production code. Commit each verified correction separately. If no defect appears, Task 2 creates no commit.

### Task 3: Sol deployment gate and baseline connected proof

**Files:** none expected.

**Interfaces:**
- Consumes: commits approved from Tasks 1–2.
- Produces: real deployed callable and baseline/replay/App Check evidence.

- [ ] **Step 1: Sol audits the complete diff and reruns Task 2 gates**

The implementer report is not evidence. XML/results must be generated after the audited commit and Android commands use `--rerun-tasks`.

- [ ] **Step 2: Verify Firebase target and authentication**

```powershell
$env:NODE_OPTIONS='--use-system-ca'
npx -y firebase-tools@latest --version
npx -y firebase-tools@latest login:list
npx -y firebase-tools@latest use
```

Expected project: `organitazionlabs`. Stop if the account/project differs.

- [ ] **Step 3: Deploy only the authorization callable**

```powershell
$env:NODE_OPTIONS='--use-system-ca'
npx -y firebase-tools@latest deploy --only functions:authorizeAiAction --project organitazionlabs
```

Expected deployment output: `functions[authorizeAiAction(us-south1)] Successful ...`. It must not update `interpretAiRequest`, rules or Hosting.

- [ ] **Step 4: Run baseline/replay on A059P**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat connectedDebugAndroidTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.FirebaseCallableGatewayInstrumentedTest
```

Expected: authenticated/App Check call and replay both `AI_AUTHORIZED`; raw call without App Check receives HTTP 401.

### Task 4: Reversible live quota, rate and kill-switch proof

**Files:**
- Create: `docs/superpowers/operations/2026-08-25-cuentivoai-phase-2-live-validation.md`

**Interfaces:**
- Consumes: deployed callable, existing `aiControl/global`, current test-user entitlement and Firestore Console.
- Produces: redacted evidence of decisions and restored configuration.

- [ ] **Step 1: Record safe before-state**

In the operations document record only these control values: `enabled`, `geminiEnabled`,
`monthlyActionLimit`, `perMinuteLimit`, plus the current month/minute `usedActions`. Do not record uid,
document IDs, request IDs or entitlement values.

- [ ] **Step 2: Prove monthly quota**

Temporarily set `monthlyActionLimit` equal to the existing monthly `usedActions` (if zero, first run one authorized probe). Keep `perMinuteLimit` above current use. Run the connected class with:

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ai.FirebaseCallableGatewayInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.cuentivoAiExpectedDecision=AI_QUOTA_EXHAUSTED
```

Inspect Firestore: no new ledger for the denied request and neither counter increased.

- [ ] **Step 3: Prove per-minute limit**

Restore the original monthly limit. Set `perMinuteLimit` equal to the current minute `usedActions`, run the same class expecting `AI_RATE_LIMITED`, and verify zero ledger/counter writes for the denial. Perform the probe within the same minute as the inspected rate-window document.

- [ ] **Step 4: Prove global kill switch and Cuentivo independence**

Set `enabled=false`, run the class expecting `AI_DISABLED`, then open the installed app and verify the AI lab reports unavailable while a normal local screen (customers/products/dashboard) remains usable. No financial write is required for this check.

- [ ] **Step 5: Restore and verify recovery**

Restore all four exact before-values. Run one final baseline expecting `AI_AUTHORIZED`. Record the restoration and result in the operations document.

- [ ] **Step 6: Commit only the redacted operations evidence**

```powershell
git add docs/superpowers/operations/2026-08-25-cuentivoai-phase-2-live-validation.md
git commit -m "docs(ai): record phase 2 live control plane gate"
```

### Task 5: Final audit and canonical checkpoint

**Files:**
- Modify: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`
- Modify: `PROJECT_MEMORY.md`
- Modify: this plan (checkboxes/evidence only)

- [ ] **Step 1: Sol reviews real Firestore effects**

Confirm one authorized ledger/counter increment, replay without another increment, three denials with zero writes and restored configuration. Any mismatch keeps Phase 2 open.

- [ ] **Step 2: Run final local gates**

Run Functions test/build/lint/Emulator, Android JVM/assemble/lint, language parity if resources changed and `git diff --check`.

- [ ] **Step 3: Update canonical truth**

Record exact commits, deployment output, connected-test results and rollback restoration. Mark only the personal debug-cohort live control plane complete. Explicitly leave Play entitlement automation, App Check production enforcement, voice, beta, AAB and public activation NOT DONE.

- [ ] **Step 4: Commit**

```powershell
git add docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md PROJECT_MEMORY.md docs/superpowers/plans/2026-08-25-cuentivoai-phase-2-live-control-plane.md
git commit -m "docs(ai): close personal phase 2 control plane gate"
```

## Rollback

Operational rollback is `aiControl/global.enabled=false`. Deployment rollback removes only
`authorizeAiAction`; never delete Firestore financial data or modify the stable Android release.
