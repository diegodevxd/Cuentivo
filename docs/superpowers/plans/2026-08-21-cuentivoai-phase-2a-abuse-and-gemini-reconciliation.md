# CuentivoAI Phase 2A Abuse Control and Gemini Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` task-by-task. No task authorizes a deploy.

**Goal:** Remove the rejected Firestore-only abuse claim, add a bounded warm-instance request guard that avoids Firestore reads after its cap, and make the local control plane Gemini-only without introducing a Gemini SDK, secret, prompt, UI, or financial mutation.

**Architecture:** Firestore remains the durable authority for entitlement, monthly quota, authorization idempotency and provider-call admission. A small in-memory edge guard runs after Auth/App Check and request validation but before Firestore; it limits *new request IDs* on a warm callable instance and returns `AI_RATE_LIMITED` without repository access once full. It is a cost mitigation, not a durable security boundary: cold starts reset it. A future deployed API Gateway/Cloud Armor design remains required for a globally durable ingress limit.

**Implemented and accepted on 2026-08-22:** Tasks 1-2 and their evidence/adversarial gate are complete locally in `57bc5bb`, `7424259`, and `8654b1b`. The last commit applies the verified GLM remediation: expired UID windows are swept before a new UID is denied for capacity, and replays may be remembered only for IDs the guard admitted. Codex independently verified Functions 75/75, lint, build, both diff checks, and the Firestore Emulator 2/2; GLM rechecked the remediation with no blocker. This marks only the abuse/cost increment accepted. **Task 3 and therefore Phase 2A/2 remain NOT DONE; nothing here is deployed.**

**Tech Stack:** Node 22, TypeScript, Firebase Functions v2, Firebase Admin SDK, Vitest, Firestore Emulator.

## Global Constraints

- Production provider is **Gemini 2.5 Flash-Lite only**. DeepSeek is not an app provider, dependency, URL, key, config flag or runtime fallback.
- Keep the Android `AiProvider` abstraction; do not add Android Gemini code, UI, Room writes, money calculations, sync/outbox, Billing, Firestore rules, version changes, APK or AAB work.
- No prompt, text, image, audio, customer, amount, provider output or secret may be written to Firestore, logs or telemetry in this increment.
- `authorizeAiAction` remains local only. Do not deploy or invoke production Firebase.
- The guard must bound its own memory. If saturated with tracked UIDs it fails closed for AI without touching Firestore.
- A hot idempotent result may be replayed from memory. A cold retry still reaches the durable ledger; it must never create a second authorization or monetary operation.
- Every denial from the edge guard must return no provider and `fallbackUsed: false`.

---

### Task 1: Remove the rejected Firestore-only throttle semantics

**Files:**
- Modify: `functions/src/ai/firestoreControlPlane.ts`
- Modify: `functions/test/firestoreControlPlane.test.ts`
- Modify: `functions/test/firestoreEmulator.gate.ts`
- Modify: `docs/superpowers/plans/2026-08-16-cuentivoai-phase-2a-control-plane.md`

**Do not touch:** `functions/src/index.ts`, Android, rules, Firebase config.

- [ ] Write a failing unit test: an exhausted monthly quota returns `AI_QUOTA_EXHAUSTED` and writes neither rate, usage nor ledger.
- [ ] Run `npm --prefix functions test -- --run test/firestoreControlPlane.test.ts`; confirm RED against `4744fb2` because it increments the rate window on quota denial.
- [ ] Restore the durable control-plane invariant: only `AI_AUTHORIZED` writes ledger, monthly usage and minute usage, once and in the same Firestore transaction; a rate/quota denial writes nothing.
- [ ] Remove `maxAttempts: 50`; use the Firebase Admin default retry policy unless a future measured contention requirement justifies a bounded override.
- [ ] Replace the false statement that Firestore stops unlimited ingress reads with an explicit statement that it limits provider admissions only.
- [ ] Run the unit suite, emulator gate, lint, build and `git diff --check`; commit only these files as `fix(ai): restore durable control plane denial semantics`.

### Task 2: Bounded warm-instance edge request guard

**Files:**
- Create: `functions/src/ai/edgeRequestGuard.ts`
- Create: `functions/test/edgeRequestGuard.test.ts`
- Modify: `functions/src/authorizeAiAction.ts`
- Modify: `functions/src/index.ts`
- Modify: `functions/test/authorizeAiAction.test.ts`

**Interfaces:**

```ts
export type EdgeGuardDecision =
  | Readonly<{ kind: "ALLOW_NEW" }>
  | Readonly<{ kind: "REPLAY"; result: AuthorizeAiResult }>
  | Readonly<{ kind: "RATE_LIMITED" }>;

export interface AiEdgeRequestGuard {
  beforeRepository(uid: string, requestId: string, nowEpochMillis: number): EdgeGuardDecision;
  remember(uid: string, requestId: string, result: AuthorizeAiResult, nowEpochMillis: number): void;
}

export function createAiEdgeRequestGuard(options: Readonly<{
  maxNewRequestsPerMinute: number;
  maxTrackedUids: number;
}>): AiEdgeRequestGuard;
```

- [ ] Write RED tests for: first new ID allows; cap exhaustion returns `RATE_LIMITED`; exhausted warm guard never calls a repository; a remembered authorized result replays exactly without repository access; minute rollover permits a new request; a full UID map fails closed without growing memory.
- [ ] Run `npm --prefix functions test -- --run test/edgeRequestGuard.test.ts`; confirm the module is absent.
- [ ] Implement a synchronous, bounded map. Count new request IDs per uid/minute; do not use input text, provider output or Firestore data. Store only authorized results keyed by uid/requestId until the current minute expires. Evict expired windows before admitting a UID; when still full, deny without insertion.
- [ ] Inject the guard into `createAuthorizeAiHandler`. Sequence: authenticated uid → strict v1 request validation → guard → durable repository only for `ALLOW_NEW` → `remember` only after `AI_AUTHORIZED`. `REPLAY` returns the stored durable result; `RATE_LIMITED` returns the fixed denial response.
- [ ] At callable composition, use one process-level guard and set Functions `concurrency: 1` together with existing `maxInstances: 1`. This does not claim global durability and remains undeployed.
- [ ] Run targeted tests first, then complete Functions suite/lint/build/diff-check. Commit as `feat(ai): add bounded warm edge abuse guard`.

### Task 3: Gemini-only authorization policy

**Files:**
- Modify: `functions/src/ai/controlPlane.ts`
- Modify: `functions/src/ai/firestoreControlPlane.ts`
- Modify: `functions/src/contract.ts`
- Modify: `functions/test/controlPlane.test.ts`
- Modify: `functions/test/firestoreControlPlane.test.ts`
- Modify: `functions/test/authorizeAiAction.test.ts`
- Modify: `functions/test/firestoreEmulator.gate.ts`

**Do not touch:** Android `AiProvider`, Functions dependencies, deploy config, provider SDKs/secrets.

- [ ] Write RED tests that accept only a well-formed `geminiEnabled: true` control configuration and return provider `GEMINI`; malformed/false configuration returns `AI_DISABLED`.
- [ ] Remove `DEEPSEEK` from `AiProviderId`, persisted ledger validation, control configuration parsing, tests and emulator seeds. The provider enum has exactly `GEMINI`; no routing or fallback branch exists.
- [ ] Preserve every denial and authorization invariant from Tasks 1–2. Existing stored ledgers with a non-Gemini provider must fail closed rather than be reinterpreted.
- [ ] Run Functions suite, lint, build and Firestore Emulator gate. Commit as `refactor(ai): make local authorization gemini-only`.

### Task 4: Evidence and adversarial gate

**Files:**
- Modify: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`
- Create or modify: a tracked Phase 2A handoff outside user-owned `docs/superpowers/handoffs/` only if a report is needed.

- [ ] Codex independently runs `npm --prefix functions test`, lint, build, the dedicated Firestore Emulator gate, Android JVM/assemble/lint if Android files changed, and both diff checks.
- [ ] Send GLM/Gemini reviewer a read-only packet: changed commits, edge guard source/tests, transaction ordering, callable boundary and source-of-truth. Ask it to challenge Auth/App Check ordering, memory DoS, cold-start bypass, replay semantics, concurrency, costs, secrets and financial isolation.
- [ ] Apply only verified findings; rerun all affected tests.
- [ ] Update source-of-truth with the exact bounded-guarantee wording. Mark only the completed increment; do not mark Phase 2A or Phase 2 DONE unless every acceptance criterion is met and Codex independently approves it.

## Non-goals / later roadmap

- Durable global ingress rate limit: API Gateway/Cloud Armor or equivalent, separately designed and deployed.
- Gemini SDK, API key, Secret Manager binding, provider call, Structured Output request/response schema, text payload, prompt or telemetry.
- Entitlement issuance / Play Developer API verification / roles enforcement.
- Draft resolution, preview, confirmation, Room mutation, OCR, voice, barcode, Free/Pro commercial changes.
