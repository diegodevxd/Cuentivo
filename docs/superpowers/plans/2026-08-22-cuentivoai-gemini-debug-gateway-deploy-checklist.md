# CuentivoAI Gemini Debug Gateway — Deploy Preflight Checklist

> **OWNER-RUN ONLY** after a **NEW, explicit deployment authorization** from the project owner.
> This checklist is documentation, NOT an authorization. Do not deploy or set secrets without
> that new explicit authorization. The local Gemini Debug Gateway increment was accepted by
> Codex, but Tasks 1-4 of the plan explicitly forbid deployment or secret creation here.

## Reality before deployment (verify and record)

- Branch: `diegodevxd/feature-cuentivo-ai`.
- Latest local implementation commits (Gemini Debug Gateway Tasks 1-3):
  `9baefbd`, `12cb5ac`, `ab1f4fe`, `0753b07`, `8e659a3`, `4852510`, `ba5a615`, `430fe8b`, `4e04052`.
- Local increment accepted by Codex: strict 1000 UTF-16 unit text contract (after trim);
  non-financial `SafeInterpretation` (four allowed pairs only); server-only Gemini 2.5 Flash-Lite
  adapter with JSON Schema, no retry and timeout; Secret Manager parameter
  `CUENTIVOAI_GEMINI_API_KEY` bound only to `interpretAiRequest`; Auth/App Check preserved;
  durable Firestore claim before provider invocation; warm edge ingress guard; no raw
  prompt/output persistence or logging; no Android/Room/financial mutation.
- Codex independently verified: targeted 52 tests, full Functions 161 tests, lint, build and
  `git diff --check` all PASS; Firestore Emulator (Standard, demo project
  `demo-cuentivoai-phase2a`) concurrency gate 2/2 PASS. Emulator temporarily used Android Studio
  JBR because `java` is absent from the system PATH; no persistent system change and no
  production Firestore access.
- GLM review is **explicitly deferred** at owner instruction (token conservation). Do NOT claim a
  GLM review occurred. The local increment is accepted by Codex but must NOT be called a
  production or beta launch, nor a full phase closure.
- No deploy of this secret-backed implementation has occurred; no secret set; no Gemini call; no
  remote control-plane config/entitlement; no Android UI or caller; no real provider test. The
  currently deployed `interpretAiRequest` (Fase 1B) remains fail-closed (`AI_NOT_CONFIGURED`).
- Public release/AAB/beta remain **NO-GO**. Phase 2 production and Phase 3
  (text-to-financial-draft) remain **NOT DONE**.

## Hard rules (apply at every step)

- **Never show, request or record a key value.** Never use `.env`, APK/AAB, `BuildConfig`, Git,
  Markdown/docs, Firestore, logs or Crashlytics for the key or any part of it.
- All secret input must happen in the interactive hidden prompt of the CLI, never in chat,
  command logs or files.
- **Firebase CLI invocation:** the repo Firebase skill requires the on-demand wrapper
  `npx -y firebase-tools@latest ...` for every command, and the owner reported the global
  `firebase` CLI is unavailable on this machine. Never use a naked `firebase` executable in any
  command or text example here; always prefix with `npx -y firebase-tools@latest`.

## Preflight checks (owner)

1. Local Firebase credentials previously expired: run
   `npx -y firebase-tools@latest login --reauth` and re-verify.
2. Confirm active project is exactly `organitazionlabs` (e.g.
   `npx -y firebase-tools@latest projects:list` / active project display).
3. Confirm billing: Blaze plan enabled and an **aggregate budget alert** exists (Google Cloud
   billing console) before any deploy; note the alert threshold.
4. Confirm region expectation: functions must land in `us-south1`; Firestore remains `(default)`,
   Standard, `northamerica-south1` — AI control documents are Admin-only and must not be written
   by any client.
5. Confirm Git working tree is clean except user-owned untracked `docs/superpowers/handoffs/`
   (leave it untouched; never stage it).
6. Review the function source binding before deploy: `CUENTIVOAI_GEMINI_API_KEY` must be bound
   ONLY to `interpretAiRequest`; `authorizeAiAction` must never receive the secret.

## Secret set (owner, exact interactive command — do not automate)

```
npx -y firebase-tools@latest functions:secrets:set CUENTIVOAI_GEMINI_API_KEY --project organitazionlabs
```

- Only the owner runs this command; enter the value **only in the interactive hidden prompt**,
  never in chat or any file.
- Confirm the parameter name is exactly `CUENTIVOAI_GEMINI_API_KEY` and the project is exactly
  `organitazionlabs`.

## Deploy (owner, only after the new explicit authorization granted)

```
npx -y firebase-tools@latest deploy --only functions:interpretAiRequest --project organitazionlabs
```

- Deploy **only** `interpretAiRequest`. **Never deploy `authorizeAiAction`.**
- After deploy verify **from the source/boundary review plus the deploy result**: the deploy
  output lists the function `interpretAiRequest`; the source diff confirms Node 22, 256 MiB,
  `us-south1` and `secrets: [CUENTIVOAI_GEMINI_API_KEY]` bound only to `interpretAiRequest`
  (`.value()` read inside the interpret handler only). Note: `functions:list` may NOT reveal the
  secret binding or config; do not rely on it, and never require a secret value or an unavailable
  list field as evidence.
- Verify runtime behavior with a real client only when a separate authorized debug harness exists
  (see APK limitation below).

## Critical current limitation (do not prescribe direct writes)

- **Do NOT prescribe any direct client write of `aiControl/global` or `aiEntitlements/{uid}`.**
  Those documents are Admin SDK-only. Control-plane bootstrap (server-side config + entitlement)
  is a **separate authorized increment**, not part of this checklist.
- Without a valid server-side control config (Gemini enabled, limits) plus a valid entitlement,
  the deployed function **must stay `AI_UNAVAILABLE`** — that is the expected, safe, fail-closed
  behavior. Record it as evidence, not as a defect.

## APK / Android clarification

- There is **no Android UI or caller** in this increment. A physical APK **cannot yet prove a
  Gemini call**; it can only exercise regression/Auth/App Check rejection procedures in a
  dedicated debug build, and only after a separate authorized debug-harness/Android increment
  exists. The current disposable-phone permission does not change this scope.

## Rollback (owner, only if needed)

- There is **no financial rollback**; AI never mutates financial data.
- Disable AI configuration **only after the control-plane bootstrap exists**, or redeploy the
  previous fail-closed `interpretAiRequest` (Fase 1B) and confirm `AI_NOT_CONFIGURED`.
- Never touch Room or financial Firestore records.

## Post-deploy evidence template (no PII, no secret, no prompt body)

| Check | Evidence |
|---|---|
| `login --reauth` completed (npx wrapper) | ok / n/a |
| Project confirmed `organitazionlabs` | output excerpt (no credentials) |
| Blaze + aggregate budget alert confirmed | console note, threshold value only |
| Git clean except user-owned handoffs | `git status --short` excerpt |
| Secret parameter set (name only, no value) | confirm `CUENTIVOAI_GEMINI_API_KEY` set; never its value |
| Deploy command + result | `npx -y firebase-tools@latest deploy --only functions:interpretAiRequest ...` output excerpt |
| Function deployed `interpretAiRequest` (source/boundary review + deploy result) | deploy output excerpt plus source diff note; `functions:list` optional, may not show binding |
| App Check / Auth rejection checked | device/curl result text (no tokens) |
| Missing config fails closed | observed `AI_UNAVAILABLE` (expected until bootstrap) |
| `authorizeAiAction` NOT deployed | deploy output excerpt (only `interpretAiRequest`); `functions:list` optional |
| GLM review | NOT performed (deferred by owner) — state explicitly |
