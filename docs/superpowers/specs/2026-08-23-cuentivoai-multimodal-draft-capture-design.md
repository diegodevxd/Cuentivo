# CuentivoAI Multimodal Draft Capture Design

**Status:** Approved design; implementation requires a separate approved plan per increment.

**Source of truth:** This design extends, but does not replace, `2026-08-14-cuentivoai-source-of-truth.md`.

## Product outcome

CuentivoAI lets a user describe normal work in text, voice, or a photo of a notebook or ticket. It reduces form filling by preparing a structured operation, resolving it against the user's real local data, and showing an editable preview. Cuentivo—not Gemini—calculates money and executes the final operation only after explicit confirmation.

Examples:

- “María se llevó dos perfumes de 300 y me dio 200.”
- “María me dio otros 150.”
- A notebook image containing customers, products, quantities, prices, and payments.

## Non-negotiable trust boundary

```text
input (text / image / audio)
  -> Gemini proposes a typed intent or draft
  -> deterministic schema and domain validation
  -> local resolution against Room data
  -> editable preview
  -> explicit confirmation
  -> existing deterministic transaction, OperationEntity, AuditLog, outbox and sync
```

Gemini never writes Room, Firestore, inventory, sales, payments, balances, customers, audit records, or sync outbox. It has no business-data write credential and it never receives a direct mutation tool.

Every final financial confirmation carries a domain idempotency key. Retries, double taps, a timeout, or two devices cannot create a second sale or payment. Cancelling a preview produces no mutation. “Undo” and deletion are excluded until a deterministic reversal workflow exists.

## Common user flow

All modalities converge into the same editable text and draft pipeline:

```text
manual text -------------------------+
notebook/ticket photo -> OCR text ---+-> editable source text -> interpretation
voice recording -> transcript -------+                              |
                                                                   v
                                             requires clarification / typed proposed draft
                                                                   |
                                                                   v
                                      Room candidate resolution + deterministic monetary calculation
                                                                   |
                                                                   v
                                                    editable, explicit confirmation preview
                                                                   |
                                                                   v
                                                        existing business operation path
```

The first preview remains the centre of trust. A photo may yield multiple proposed operations; they appear as a review queue. Each operation is independently editable, rejected, or confirmed. Bulk confirmation is permitted only after every item remains individually visible and validated; it is not an automatic import.

## Modalities

### Text

The user writes a normal sentence. The client sends a bounded request through Firebase Auth and App Check to the server-side Gemini gateway. Gemini returns Structured Output using a versioned JSON Schema. The server applies a second exact parser before returning a proposal.

### Notebook and ticket photos

1. Camera or picker creates a temporary private image.
2. ML Kit OCR runs locally first and produces editable text.
3. The user can correct that text and continue through the text flow.
4. If local OCR is empty, materially insufficient, or the user explicitly chooses a fallback, the app asks for consent before uploading one bounded, compressed temporary image to the Gemini gateway.
5. Gemini image interpretation produces the same typed proposal schema—not a direct operation.
6. The image is not stored in Firestore, Crashlytics, analytics, prompt logs, or a product-photo store. Local temporary data is deleted after completion/cancellation according to the Android lifecycle policy.

The direct-image fallback is a capability to be implemented only after server limits, consent copy, MIME allow-list, dimensions, byte cap, timeout, and deletion behaviour have tests. It is not a dependency of initial local OCR.

### Voice

1. The user explicitly starts recording; `RECORD_AUDIO` is requested just-in-time.
2. Android on-device/device speech recognition is attempted first and produces editable transcript text.
3. If the transcript is insufficient and the user explicitly chooses the fallback, a bounded temporary audio clip is sent to the gateway for Gemini 2.5 Flash-Lite audio understanding.
4. Gemini returns text/structured intent, not an audio reply. The app follows the same preview and confirmation flow.

Gemini 2.5 Flash-Lite can understand audio input, but it is not a real-time spoken dialogue model. Conversational audio output/TTS is a separate future product decision and does not belong to this design.

## Proposed interpretation contracts

The currently accepted gateway returns only safe clarification/refusal results. Before any financial draft is enabled, a new versioned, strict schema must define separate proposed operations, at minimum:

- `requires_clarification` with machine-readable reason and unresolved candidate slots;
- `create_sale_proposal` with declared item descriptions, quantities, declared unit prices, declared initial payment and optional payment-promise text/date candidate;
- `record_payment_proposal` with customer candidate and declared payment amount;
- `business_query` from an allow-list, such as customer balance or sales for a period.

The schema does not include trusted customer IDs, computed balances, a Firestore path, arbitrary actions, SQL, tool calls, or permission changes. Amounts are declarations only; the domain uses existing integer cents and calculates totals, debt and allocations itself. Ambiguous customers/products and unknown products require clarification or explicit user selection.

## Server and privacy architecture

Android never calls Gemini directly. The only production path is:

```text
Android -> Firebase Auth + App Check -> Functions callable gateway
        -> server entitlement/quota/rate/idempotency/abuse controls
        -> AIProvider -> Gemini 2.5 Flash-Lite
```

The existing secret-backed gateway increment remains locally accepted but not deployed or configured. API key configuration, deployment, remote control-plane bootstrap, entitlement, budget alert, and a provider call require separate owner authorization. The secret is never an Android setting.

For every enabled capability, the server must enforce a bounded input type/size, output token cap, timeout, no automatic provider retry, request ledger claim before provider invocation, capability-level kill switch, and telemetry that contains only operational aggregates—not raw text, images, audio, model output, names, customer identifiers, or money.

## Increment decomposition and gates

This is intentionally not one implementation task.

1. **Draft protocol and deterministic resolver design.** Define typed proposals, ambiguity states, cents rules, test vectors, and pure validation. No UI, provider deployment, Room write, or finance mutation.
2. **Text debug flow.** Add a debug-only text entry and preview of non-financial safe responses. It may call the fail-closed gateway but cannot execute operations.
3. **Photo capture and local OCR.** Add private camera/picker handling, ML Kit OCR, editable text, cancellation/deletion tests, and no-cloud default. Direct Gemini image fallback is separately gated.
4. **Voice capture and local transcription.** Add permission, recording/transcription lifecycle, editable transcript, cancellation/error paths, and no-cloud default. Direct Gemini audio fallback is separately gated.
5. **Draft preview and deterministic confirmation.** Connect validated proposals to existing domain factories/DAOs only after Room migration/compatibility review, idempotency tests, audit/outbox tests, and a physical-device checklist. This is the first increment allowed to create a sale/payment, and only after confirmation.
6. **Production multimodal fallback and limited beta.** Add consented image/audio gateway paths, server caps, entitlement, configurable quota, cost telemetry, kill switch, privacy review, and a small cohort. No public rollout is implied.

Each increment needs its own plan, TDD evidence, compatibility analysis from stable `v1.16.6-31`, diff audit, relevant JVM/Functions/Android tests, and a Codex gate. High-risk server, entitlement, money, idempotency, synchronization, or direct-multimodal work requires an independent adversarial review when that review capacity is available.

## Explicit exclusions

- No automatic financial write from any model response.
- No direct Android-to-Gemini networking or API key in an APK/AAB.
- No cloud photo storage, Firebase Storage, Play product change, AAB, public release, or free/pro commercial change.
- No sending the full database to Gemini.
- No generic chatbot, autonomous agent, direct audio reply, or automatic undo/delete.

## Success criteria for the eventual debug APK

- The user can type, photograph a notebook, or speak; each flow reaches editable source text or a safe unavailable/clarification state.
- Bad OCR, a poor transcript, offline mode, denied permission, timeout, quota denial, killed AI, missing Auth/App Check, or gateway outage leaves Cuentivo normal fully usable and creates no financial data.
- A valid future draft only reaches existing business logic after the user sees and explicitly confirms the calculated result.
- The debug APK is installable beside or over the test app only according to a later approved Android packaging strategy; it uses disposable test data and has a documented rollback.
