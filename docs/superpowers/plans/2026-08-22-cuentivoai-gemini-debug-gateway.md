# CuentivoAI Gemini Debug Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` task-by-task. No task authorizes deployment or setting a secret value.

**Goal:** Add a server-only Gemini 2.5 Flash-Lite text interpretation callable that returns a strictly validated non-financial structured result for the owner's debug APK.

**Architecture:** `interpretAiRequest` becomes the sole interpretation callable. It authenticates through Firebase Auth/App Check, validates a bounded text request, asks the existing Gemini-only control plane for authorization, then calls an injected `GeminiTextProvider` only on `AI_AUTHORIZED`. Gemini is reached from a Function with an individually bound Secret Manager secret; the secret is never available to Android. Output is parsed against a local allow-list after the provider's JSON Schema response mode, and never mutates financial state.

**Tech Stack:** Node 22, TypeScript, Firebase Functions v2, Firebase Admin SDK, `@google/genai`, Firebase Functions `defineSecret`, Vitest, Firestore Emulator.

## Global Constraints

- Provider is exactly `gemini-2.5-flash-lite`; no DeepSeek runtime/config/secret/fallback.
- Android must never call Gemini or see a secret. No secret values in Git, logs, Firestore, Crashlytics or chat.
- Preserve `enforceAppCheck: true`, Auth, `us-south1`, maxInstances/concurrency controls, the Gemini-only control plane, quota, rate limit and idempotency.
- Provider authorization occurs before provider invocation. Before the first provider call, a durable ledger claim containing no text/output is recorded; any retry of that claimed request ID returns `AI_UNAVAILABLE` without another provider call. All denials/error/timeout/schema failures produce no financial mutation and no provider retry.
- Input text is a maximum of 1,000 UTF-16 code units after trim. No raw input/output is logged or persisted.
- Initial output is only `{ kind: "requires_clarification" | "unsupported", code: string }`; it cannot contain customer/product IDs, amounts, sales, payments, balances or executable instructions.
- No Android UI, Room, Firestore rules, billing, AAB, Play, OCR, voice, barcode, preview or confirmation in this plan.
- `docs/superpowers/handoffs/` is user-owned and must stay untouched.

---

### Task 1: Strict real-interpretation contract and provider-neutral parser

**Files:**
- Modify: `functions/src/contract.ts`
- Create: `functions/src/ai/interpretation.ts`
- Create: `functions/test/interpretation.test.ts`
- Modify: `functions/test/contract.test.ts`

**Interfaces:**

```ts
export type InterpretAiRequest = Readonly<{
  contractVersion: 1;
  requestId: string;
  action: "interpret_text";
  text: string;
}>;

export type SafeInterpretation =
  | Readonly<{ kind: "requires_clarification"; code: "NEEDS_CONTEXT" | "AMBIGUOUS" }>
  | Readonly<{ kind: "unsupported"; code: "UNSUPPORTED" | "UNSAFE" }>;

export type InterpretAiResponse = Readonly<{
  contractVersion: 1;
  requestId: string;
  status: "AI_INTERPRETED" | "AI_UNAVAILABLE";
  interpretation: SafeInterpretation | null;
}>;

export function parseSafeInterpretation(value: unknown): SafeInterpretation | null;
```

- [ ] Write RED tests: valid request with short non-blank text succeeds; text at 1,001 code units, blank text, unknown keys and non-string text fail validation; each allowed safe interpretation parses; extra keys, amounts, nested objects and unknown codes return `null`.
- [ ] Run `npm --prefix functions test -- --run test/contract.test.ts test/interpretation.test.ts`; confirm RED because `text` and `parseSafeInterpretation` do not exist.
- [ ] Extend the exact-key allow-list with `text`; use `text.trim()` only for validation, but return the original accepted text unchanged. Define `MAX_INTERPRET_TEXT_LENGTH = 1000`.
- [ ] Implement `parseSafeInterpretation` as an exact-object parser: only keys `kind` and `code`; allowed pairs are `requires_clarification/NEEDS_CONTEXT`, `requires_clarification/AMBIGUOUS`, `unsupported/UNSUPPORTED`, and `unsupported/UNSAFE`.
- [ ] Run the targeted suite and commit only Task 1 files as `feat(ai): add safe interpretation contract`.

### Task 2: Gemini provider adapter with strict response parsing

**Files:**
- Modify: `functions/package.json`, `functions/package-lock.json`
- Create: `functions/src/ai/geminiTextProvider.ts`
- Create: `functions/test/geminiTextProvider.test.ts`

**Interfaces:**

```ts
export interface TextInterpretationProvider {
  interpret(input: Readonly<{ text: string }>): Promise<SafeInterpretation | null>;
}

export function createGeminiTextProvider(options: Readonly<{
  apiKey: string;
  timeoutMillis: number;
}>): TextInterpretationProvider;
```

- [ ] Add RED tests through a constructor-injected narrow client seam: it requests model `gemini-2.5-flash-lite`, JSON response MIME/schema, temperature 0 and maximum output tokens 64; valid JSON is parsed locally; missing text, malformed JSON, schema-invalid JSON, client rejection and timeout resolve to `null`; provider never retries.
- [ ] Run `npm --prefix functions test -- --run test/geminiTextProvider.test.ts`; confirm RED because the adapter/module does not exist.
- [ ] Add the current official `@google/genai` package. Create the client only from the injected server secret. Use `AbortSignal.timeout(8000)` or equivalent accepted SDK timeout support; catch all provider failures and return `null`; do not log exception contents.
- [ ] Supply the local JSON Schema matching `SafeInterpretation` and parse response text again with `parseSafeInterpretation`; SDK structured output is not trusted by itself.
- [ ] Run targeted tests, full Functions test/lint/build and `git diff --check`; commit as `feat(ai): add gemini text provider adapter`.

### Task 3: Callable orchestration with server secret binding

**Files:**
- Modify: `functions/src/interpretAiRequest.ts`
- Modify: `functions/src/index.ts`
- Modify: `functions/src/ai/firestoreControlPlane.ts`
- Modify: `functions/test/interpretAiRequest.test.ts`
- Modify: `functions/test/firestoreControlPlane.test.ts`
- Modify: `functions/test/index.test.ts` if such a boundary test is needed; otherwise create `functions/test/interpretAiGateway.test.ts`

**Interfaces:**

```ts
export function createInterpretAiHandler(deps: Readonly<{
  authorize: (input: AuthorizeAiInput) => Promise<AuthorizeAiResult>;
  claimProviderInvocation: (input: Readonly<{ uid: string; requestId: string; nowEpochMillis: number }>) => Promise<"CLAIMED" | "ALREADY_CLAIMED">;
  provider: TextInterpretationProvider | null;
  now: () => number;
}>): (request: CallableLikeRequest) => Promise<InterpretAiResponse | CallableFailure>;
```

- [ ] Write RED tests: unauthenticated/invalid input fail at the boundary; `AI_DISABLED`, entitlement/quota/rate denials and missing provider return `AI_UNAVAILABLE` without `provider.interpret`; authorization success durably claims then calls provider once and returns parsed data; provider `null`/failure returns `AI_UNAVAILABLE`; an already-claimed request ID does not call the provider and returns `AI_UNAVAILABLE`.
- [ ] Run the targeted test and confirm RED because the handler is still synchronous and fail-closed-only.
- [ ] Extend the repository with an atomic claim over the existing `aiRequestLedger/{uid}_{requestId}` row, containing only an invocation state/timestamp. Make the handler asynchronous and dependency-injected. Sequence is auth → strict contract → authorize control plane (`TEXT`) → durable claim → provider only for `CLAIMED` → typed non-financial response. Do not expose authorization internals to Android.
- [ ] In `index.ts`, declare `const geminiApiKey = defineSecret("CUENTIVOAI_GEMINI_API_KEY")`; bind it only to `interpretAiRequest` via `{ ...AI_BOUNDARY, secrets: [geminiApiKey] }`. If `.value()` is empty, pass `null` provider. Do not bind the secret to `authorizeAiAction`.
- [ ] Preserve existing deployed behavior until explicit deployment: local tests must not call `.value()` or Gemini. Add a boundary-source test asserting no HTTP URL, `DEEPSEEK`, logging, secret value or provider SDK exists in Android code.
- [ ] Run Functions suite/lint/build/diff checks and Firestore Emulator gate. Commit as `feat(ai): orchestrate secret-backed gemini interpretation`.

### Task 4: Security evidence and deploy preflight

**Files:**
- Modify: `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`
- Create: `docs/superpowers/plans/2026-08-22-cuentivoai-gemini-debug-gateway-deploy-checklist.md`

- [ ] Codex independently runs all Functions tests/lint/build, both diff checks and Firestore Emulator gate. Run Android gates only if Android files changed.
- [ ] Ask GLM 5.3 for read-only review of secret binding, App Check/Auth ordering, quota/idempotency before provider call, timeout/retry, schema parser, raw-data logging, control-plane/financial isolation and rollback.
- [ ] Apply only confirmed findings and rerun every affected gate.
- [ ] Write deploy checklist requiring the owner to set the secret interactively, confirm an aggregate budget alert, deploy only `interpretAiRequest`, verify `functions:list`, and test a disposable APK. The checklist must never contain a key value.
- [ ] Update the source of truth with the exact local status. **Do not deploy or set the secret without a new explicit owner authorization after this review.**

## Rollback

Before deployment, rollback is `git revert` of the Task commits. After an approved deployment, rollback is disabling `geminiEnabled` in server control configuration or deploying the previous `interpretAiRequest`; neither path alters Cuentivo financial data.
