# CuentivoAI Internal Debug Track Design

**Status:** Approved by the owner on 2026-08-15.

## Purpose

Allow CuentivoAI to be developed and manually tested by the owner through a local debug APK, while preserving the public-release NO-GO. The current `31 / 1.16.6` AAB is in Play closed testing (12 testers, 13 days) and is not a public release or an immutable Git baseline.

## Scope and boundaries

- Work only in `diegodevxd/feature-cuentivo-ai`; never modify the protected release worktree.
- Phase 0 may create documentation and provider-free contracts only. It does not create a production endpoint, deploy cloud infrastructure, call an AI provider, or add an API key.
- No CuentivoAI component may write Room, Firestore, sales, payments, balances, debt, audit records, or sync state from model output.
- No AAB, Play upload, public feature activation, version change, or `applicationId` change is authorized.
- A future debug APK must use a dedicated test account and fictitious data. Coexistence with the stable app is a later design decision, not a present Gradle change.

## Development flow

1. Fase 0 records the closed-test evidence available, designs privacy-minimised observability, threat controls, rollback, and the physical-device test strategy.
2. Each later phase is separately proposed, approved by the owner, delegated through Orca to the appropriate implementer, independently audited by Sol, and closed only with fresh tests and evidence.
3. V4 Pro is reserved for gateway, backend, secrets, Auth/App Check enforcement, quota, idempotency, and other high-risk work. GLM is required for adversarial review whenever security, money, concurrency, quota, idempotency, sync, or Billing is in scope.
4. A debug APK is delivered only after a safe testable capability exists, with its exact path, checksum, USB-install instructions, rollback steps, and manual checklist.

## Safety model

The only permitted model path in later approved work is input -> provider interpretation -> versioned allow-listed draft -> deterministic validation against Cuentivo data -> human preview -> explicit confirmation -> existing idempotent domain operation. The model never gains authority to calculate or mutate money.

Secrets stay in server-side secret storage once a separately approved gateway exists; they never reside in source control, Android configuration, APK, Crashlytics, analytics, or logs. Disabling AI must leave Gratis and Pro functional.

## Release and compatibility gate

Before a phase changes persistence, serialization, sync, Billing, Auth, Firebase, or financial workflows, the `31 / 1.16.6` candidate must be represented by a reviewed commit/tag. The work then requires upgrade and regression tests from that baseline. Until then, the internal track can do only isolated work that does not alter those surfaces.

## Success criteria for this track

- The public release path remains isolated and recoverable.
- Every phase has explicit scope, acceptance criteria, rollback, implementer, and evidence.
- No provider call, secret, public endpoint, AAB, or user activation occurs before its dedicated gate.
- The eventual owner-only APK debug test uses non-production data and can be removed without affecting the stable installation.
