# CuentivoAI — Gemini debug gateway design

## Goal

Enable a controlled, real Gemini 2.5 Flash-Lite interpretation path for the owner's disposable debug APK while keeping Cuentivo financial state local-first and unchanged until an explicit preview confirmation path exists.

## Chosen architecture

The Android app calls a Firebase Functions v2 callable in `us-south1`. Firebase Auth and App Check are mandatory at the callable boundary. The callable first uses the already-audited control plane (`authorizeAiAction`) for entitlement, per-minute quota, monthly quota, idempotency and the warm-instance abuse guard. Only an `AI_AUTHORIZED` result may reach an `AIProvider` implementation backed by Gemini 2.5 Flash-Lite.

The Gemini API key is a Firebase Functions Secret Manager binding, never an Android resource, BuildConfig field, repository file, log entry, Firestore document, Crashlytics value or terminal output. The Android app never calls Gemini directly.

Gemini response data is limited to a strict JSON Schema intent/draft envelope. The first real request supports text interpretation only. It must not write Room, Firestore financial data, sales, payments, balances, customers, products, inventory, outbox or audit history. It has no preview confirmation or financial execution in this increment; those are later work.

## Request flow

```
Android debug APK
  -> Firebase Auth + App Check callable
  -> strict request-size/action validation
  -> authorizeAiAction control plane
  -> Gemini AIProvider (server-side secret)
  -> strict JSON Schema validation
  -> draft/intent response only
```

Any failure before or during Gemini returns a typed fail-closed AI result and leaves normal Cuentivo/Pro flows operational. An authorized request ID must never invoke Gemini twice. A provider timeout is ambiguous only for interpretation, never for money; it produces no mutation and the user can retry with the same request ID.

## Capability and schema boundary

The only allowed action initially is `INTERPRET_TEXT`. Input is trimmed plain text with a small server-side character limit. The provider output uses an allow-listed JSON Schema with:

- `kind`: `requires_clarification` or `unsupported`;
- no customer IDs, product IDs, amounts or financial operations in the first live-debug capability;
- bounded clarification messages/codes only.

This deliberately proves the authenticated, secret-backed Gemini transport and structured-output parser without yet proposing a sale or payment. A later separately approved increment introduces a draft schema for `sale`, `payment`, and deterministic query intents; that design must model integer cents, ambiguity, real-entity resolution, preview and explicit confirmation.

## Controls

- Global server-side `enabled` plus `geminiEnabled` remains the kill switch.
- Auth/App Check, control-plane entitlement, quota and idempotency are required.
- Fixed input/output limits, a short provider timeout and no automatic provider retry.
- No fallback provider and no multi-provider routing.
- Aggregate operational metrics may record only action, provider/model, latency, token counts, outcome and fallback=false; not text, drafts, customer data, tokens, amounts or raw responses.
- A provider or schema failure fails closed. It cannot downgrade to an unstructured response.

## Deployment and rollback

The implementation is locally tested first. Deployment is a separate explicit owner authorization after independent Codex and GLM review. The secret is provisioned interactively through Firebase tooling; its value is neither requested nor pasted into chat. Rollback is either the existing global kill switch or removal of only the new callable; neither action affects Cuentivo's existing financial functions.

## Tests and evidence

Tests must cover: unauthenticated/App Check boundary preservation, disabled/no-entitlement/quota/rate denials with no provider invocation, idempotent replay without a second provider invocation, secret absence fail-closed, timeout/error fail-closed, malformed/oversized model JSON rejection, allowed schema acceptance, and absence of money/Room/Firestore financial writes. Gates are Functions tests, lint, build, Firestore Emulator where the control plane is exercised, diff checks, GLM adversarial review, then a disposable debug-APK physical test after deployment authorization.

## Out of scope

OCR/image/audio, barcode, Android UI, financial drafts, previews, confirmation, financial mutation, Firebase rules changes, Billing, commercial plan changes, AAB/Play release, Cloud Run and multi-provider routing.
