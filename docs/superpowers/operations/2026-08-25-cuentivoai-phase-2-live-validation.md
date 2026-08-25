# CuentivoAI Phase 2 live control-plane validation

Date: 2026-08-25  
Scope: personal authenticated debug cohort only  
Project: `organitazionlabs`  
Function region: `us-south1`

This record intentionally omits user identifiers, document identifiers, request identifiers, App Check tokens, customer data, amounts and secrets.

## Deployed boundary

- Deployed only `authorizeAiAction`; `interpretAiRequest`, Firestore rules and Hosting were not part of this deployment.
- Firebase reported a successful create of `authorizeAiAction(us-south1)`.
- The deployed callable has App Check enforcement enabled and is bounded to one instance, concurrency one, 15 seconds and 256 MiB.
- The Gemini API secret is not bound to `authorizeAiAction`.

## Restored control configuration

The exact safe before-state was restored after every denial probe and verified again after the kill-switch exercise:

- `enabled = true`
- `geminiEnabled = true`
- `monthlyActionLimit = 50`
- `perMinuteLimit = 3`

## Live A059P evidence

The Android test packages were installed once and the focused class was run directly with `adb shell am instrument`. This preserves the target application's authenticated session; Gradle's connected-test lifecycle uninstalls the target application and therefore was not used for repeated live control-plane probes.

### Baseline, replay and App Check

- One authenticated request returned `AI_AUTHORIZED`.
- Replaying the same request returned an identical five-field response.
- The replay did not produce a second monthly usage or ledger increment.
- A valid Auth request without App Check was rejected with HTTP 401.
- Final recovery after all denial probes passed both instrumented tests (`OK (2 tests)`).

### Monthly quota

- The temporary monthly limit was set equal to existing monthly usage.
- The callable returned `AI_QUOTA_EXHAUSTED`.
- Monthly usage, request ledger and rate-window counts were unchanged by the denial.
- The original monthly limit was restored.

### Per-minute rate limit

- A current-minute authorized request established the rate window.
- The temporary per-minute limit was set equal to current-minute usage.
- The callable returned `AI_RATE_LIMITED` within that same minute.
- Monthly usage, request ledger and current rate-window usage were unchanged by the denial.
- The original per-minute limit was restored.

### Global kill switch

- `enabled` was temporarily changed to `false` with an update-time precondition.
- The callable returned `AI_DISABLED` and both instrumented checks passed.
- Monthly usage and request ledger counts were unchanged by the denial.
- Launching `com.cuentivo.app` resumed a normal Cuentivo activity, demonstrating that the normal app remains reachable while AI is disabled.
- The original `enabled = true` value was restored in a `finally` path and read back from Firestore.
- A fresh post-restoration authorization and replay passed (`OK (2 tests)`).

## Local and emulator evidence preceding deployment

- Functions unit suite: 225/225 tests passed across 10 files.
- Functions TypeScript build: passed.
- Functions lint: passed.
- Firestore emulator concurrency suite: 2/2 passed against the isolated demo project.
- Android connected probe compiled after the audited commit.
- `git diff --check`: clean.

Relevant commits:

- `ae82078` - initial live authorization probe.
- `18d1b11` - aligned the probe with the real `authorize_text` contract.
- `b331ee3` - corrected the safe Firestore emulator gate.

## Scope boundary

This validates the live authorization control plane for the single personal debug cohort. It does not close Play entitlement automation, production App Check enforcement, voice, beta rollout, AAB generation or public CuentivoAI activation.
