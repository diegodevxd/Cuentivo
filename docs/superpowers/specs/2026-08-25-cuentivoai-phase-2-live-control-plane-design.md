# CuentivoAI Phase 2 Live Control Plane Design

**Status:** approved design for the personal debug cohort; not a public beta authorization.

## Objective

Promote the already-audited Gemini-only control plane from local-only code to a narrowly deployed
and physically verified server-side safety boundary. The increment proves Auth, App Check,
entitlement, monthly quota, per-minute rate limit, request idempotency and the global kill switch
against the real `organitazionlabs` Firebase project without integrating Play Billing or exposing a
new CuentivoAI release.

## Scope boundary

This increment deploys only the existing `authorizeAiAction` callable in `us-south1`. It keeps the
current manually created entitlement for the single test user. It does not issue entitlements,
verify Play purchases, change Free/Pro limits, bind the Gemini secret to this callable, alter
Android production UI, or write any financial collection.

The deployed `interpretAiRequest` continues to enforce the same repository internally before a
Gemini call. `authorizeAiAction` is a no-provider preflight and diagnostic boundary: it never
receives text, customer information, amounts, Gemini output or secrets.

## Existing architecture retained

The request sequence remains:

1. Firebase callable boundary enforces Auth and App Check.
2. Strict v1 request validation accepts only `contractVersion`, UUID `requestId` and the allow-listed
   text capability action.
3. The bounded warm-instance edge guard rejects excess new request IDs before Firestore when its
   local cap is reached. It is a cost mitigation, not a globally durable rate limiter.
4. One Firestore transaction reads `aiControl/global`, then `aiEntitlements/{uid}`, then the request
   ledger and finally the monthly/minute counters.
5. Only `AI_AUTHORIZED` writes ledger and increments both counters, atomically and once.
6. Every denial writes nothing and returns no provider. Storage failure fails closed as
   `AI_DISABLED`.

The server-only collections remain `aiControl`, `aiEntitlements`, `aiUsage`, `aiRateWindows` and
`aiRequestLedger`; client Firestore rules remain unchanged and deny direct access.

## Live verification strategy

Before deployment, Functions unit tests, TypeScript build/lint and the dedicated Firestore Emulator
concurrency gate must pass. A debug-only connected test on A059P will then call
`authorizeAiAction` through the Firebase Functions SDK using the already authenticated test user
and registered App Check debug token. It sends no business text or financial data.

The live sequence is deliberately reversible:

1. Baseline authorization with a unique request ID returns `AI_AUTHORIZED`.
2. Replaying the identical request ID returns the same decision without a second durable increment.
3. A bounded temporary configuration proves exact monthly quota and per-minute rate denials with
   new request IDs; denials must not create ledger rows or increment counters.
4. Setting `aiControl/global.enabled` to `false` proves the global kill switch before provider
   invocation. The AI lab becomes unavailable while normal Cuentivo screens and local financial
   operations remain functional.
5. The original control values are restored immediately and a final authorization proves recovery.

Remote control-document changes are operator actions. Their exact before-values must be recorded
before the test and restored in a `finally`-style checklist even if a probe fails. No automation may
list, delete or rewrite unrelated documents.

## Security and cost invariants

- `CUENTIVOAI_GEMINI_API_KEY` remains bound only to `interpretAiRequest`.
- `authorizeAiAction` has `enforceAppCheck: true`, `maxInstances: 1`, `concurrency: 1`, a 15-second
  timeout and 256 MiB memory in `us-south1`.
- No raw uid, request ID, prompt, customer, amount, token or exception is logged.
- The warm guard cannot be described as a global rate limit; cold starts reset it.
- Firestore is the durable authority for quota and idempotency, not Android.
- No Room, sales, payments, balances, outbox, sync, Billing or financial audit path is touched.
- Cuentivo normal and Pro remain usable when AI is disabled or unavailable.

## Failure handling and rollback

Any unexpected response, App Check rejection, transaction error, counter mismatch or extra write
keeps Phase 2 open. The immediate operational rollback is `aiControl/global.enabled = false`.
If the callable itself is defective, remove only `authorizeAiAction`; `interpretAiRequest` can also
be stopped by the same global switch. Neither rollback changes the Android database or the public
Cuentivo release.

## Acceptance criteria

- Independent gates pass after the audited commit: Functions tests/build/lint, Firestore Emulator
  concurrency gate and `git diff --check`.
- Deployment output names only `authorizeAiAction(us-south1)` as the updated/new function.
- Physical debug invocation proves Auth + App Check success and exact response shape.
- Replay, quota, rate and kill-switch behavior are observed against real Firestore, with counters
  and ledgers inspected without PII in the report.
- Original live configuration is restored and Cuentivo normal remains operational while AI is off.
- Source of truth records only this controlled debug-cohort completion. Play entitlement, App Check
  production enforcement, voice, beta, AAB and public CuentivoAI remain NOT DONE.

## Deferred work

Play Developer API purchase verification and automatic CuentivoAI entitlement issuance/revocation
are a separate security/Billing increment. A globally durable ingress layer, public-cohort
management, production App Check enforcement, voice and beta observability also remain separate.
