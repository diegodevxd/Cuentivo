# CuentivoAI Phase 0 Internal Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Close the provider-free internal readiness gate without changing runtime behavior, release configuration, financial data, or public distribution.

**Architecture:** Phase 0 produces three reviewable operational documents: closed-test/release-baseline gate, privacy-minimised observability and threat model, and a future USB debug-APK protocol. It binds later work to the existing AiExperiment off-by-default seam but creates no Remote Config, backend, endpoint, provider call, or Android UI.

**Tech Stack:** Markdown documentation, Git evidence, existing Android Gradle verification only.

## Global Constraints

- Work only in diegodevxd/feature-cuentivo-ai; do not modify C:\Users\pibed\AndroidStudioProjects\OrganizationLabs-neo-fintech.
- Public release and AAB/Play activation remain NO-GO; this plan supports only a future owner-only debug APK.
- Do not add DeepSeek, API keys, Cloud Functions/Run, Storage, Firebase configuration, Remote Config, Billing product, dependency, permission, applicationId, versionCode, or versionName.
- No model output may write Room, Firestore, sales, payments, debt, balances, audit records, sync state, or entitlements.
- Do not collect prompts, OCR text, transcripts, names, email, uid, business ID, individual amounts, images, purchase tokens, tokens, or secrets.
- 31 / 1.16.6 is a closed-test AAB, not an immutable Git baseline. Compatibility-sensitive work remains blocked until a reviewed commit/tag exists.
- Preserve the untracked docs/superpowers/handoffs/ directory; it is not part of this phase.

---

## File Structure

- Create: docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md — factual closed-test evidence and release-baseline gate.
- Create: docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md — event allow-list, prohibited data, threat boundaries, kill-switch requirements.
- Create: docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md — physical-device test, test-data, rollback and bug-reporting protocol.
- Modify: docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md — closure evidence only after the audit.

## Task 1: Record closed-test evidence and release-baseline gate

**Files:**
- Create: docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md

**Consumes:** Owner-provided facts: AAB 31 / 1.16.6, 12 testers, 13 elapsed days; Git audit that no reviewed commit/tag represents that AAB.

**Produces:** A factual gate that never equates closed testing with public launch.

- [ ] **Step 1: Write the failing required-content check**

~~~powershell
$gate = 'docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md'
$required = @('31 / 1.16.6', '12 testers', '13 days', 'Crash-free', 'ANR', 'Billing', 'sync/restore', 'commit/tag')
$missing = $required | Where-Object { -not (Select-String -LiteralPath $gate -Pattern $_ -Quiet) }
if ($missing) { throw "Missing gate terms: $($missing -join ', ')" }
~~~

Expected: FAIL because the document is absent.

- [ ] **Step 2: Create the document with these sections**

~~~markdown
# CuentivoAI Closed-Test Evidence Gate
## Known facts
- Candidate: 31 / 1.16.6 AAB in Play closed testing.
- Cohort: 12 testers; 13 elapsed days as of 2026-08-15.
- Classification: not public launch and not immutable Git baseline.
## Required aggregate evidence
Crash-free users/sessions and ANR; Billing failures/unlocks/cancellations; Auth, sync/restore, Room and WorkManager error categories; anonymised rapid-capture feedback.
## Baseline gate
Before compatibility-sensitive work, release ownership provides a reviewed commit/tag for 31 / 1.16.6.
~~~

- [ ] **Step 3: Rerun the check and commit**

Run the Step 1 command; expected PASS. Then:

~~~powershell
git add -- docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md
git commit -m "docs(ai): record closed-test readiness gate"
~~~

## Task 2: Define observability, threats and kill-switch contract

**Files:**
- Create: docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md

**Consumes:** Source-of-truth invariants and app/src/main/java/com/cuentivo/app/ai/AiExperiment.kt.

**Produces:** Future aggregate telemetry vocabulary and trust-boundary controls, without runtime telemetry.

- [ ] **Step 1: Write the failing required-content check**

~~~powershell
$model = 'docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md'
$required = @('request_type', 'outcome', 'latency_bucket', 'kill switch', 'fail closed', 'App Check', 'idempotency')
$missing = $required | Where-Object { -not (Select-String -LiteralPath $model -Pattern $_ -Quiet) }
if ($missing) { throw "Missing: $($missing -join ', ')" }
~~~

Expected: FAIL because the document is absent.

- [ ] **Step 2: Create the document with these sections**

~~~markdown
# CuentivoAI Observability and Threat Model
## Allowed future telemetry vocabulary
request_type, outcome, latency_bucket, input_size_bucket, output_size_bucket, retry_count, schema_result, quota_result, feature_state and server-side cost buckets.
## Explicitly prohibited data
No raw prompts, OCR text, transcript, customer name, product name, email, uid, business identifier, individual amount, image, purchase token, Auth token, App Check token, API key or raw provider response.
## Trust boundaries and controls
Android input is untrusted and draft-only. The gateway requires Auth, App Check, role, entitlement, quota, rate limit and idempotency before provider invocation. Provider output is schema-allow-listed and deterministically validated. Invalid state must fail closed for AI while Gratis and Pro remain available.
## Kill switch
The future server-owned kill switch is global, per user and per capability, denies AI before provider invocation, and is independently testable. AiExperiment is not an authority.
~~~

- [ ] **Step 3: Rerun the check and commit**

Run the Step 1 command; expected PASS. Then:

~~~powershell
git add -- docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md
git commit -m "docs(ai): define observability threat controls"
~~~

## Task 3: Specify future owner-debug APK protocol

**Files:**
- Create: docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md

**Consumes:** Internal debug-track design and the current restriction against Gradle identity/version changes.

**Produces:** A future APK delivery and physical-testing protocol with isolation and rollback rules.

- [ ] **Step 1: Write the failing required-content check**

~~~powershell
$protocol = 'docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md'
$required = @('USB debugging', 'test account', 'fictitious data', 'SHA-256', 'uninstall', 'stable app', 'bug report')
$missing = $required | Where-Object { -not (Select-String -LiteralPath $protocol -Pattern $_ -Quiet) }
if ($missing) { throw "Missing APK protocol terms: $($missing -join ', ')" }
~~~

Expected: FAIL because the document is absent.

- [ ] **Step 2: Create the document with these sections**

~~~markdown
# CuentivoAI Owner Debug APK Protocol
## Preconditions
Provide the APK path and SHA-256 only after its phase is closed. Use USB debugging, a test account and fictitious data. Do not install over the stable app until coexistence or upgrade compatibility is designed and tested.
## Installation and observation
Record device/Android version, checksum, scenario, expected result and actual result. Test clean install, cancel, offline, retry and failure paths relevant to the delivered capability.
## Rollback
Uninstall the debug package and confirm the stable app remains installed with untouched data. An approved upgrade test starts with a verified local backup and dedicated migration plan.
## Bug report
Provide reproducible steps, expected/actual result, time window and non-sensitive diagnostic category. Do not attach PII, money values, prompts, tickets, tokens or secrets.
~~~

- [ ] **Step 3: Rerun the check and commit**

Run the Step 1 command; expected PASS. Then:

~~~powershell
git add -- docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md
git commit -m "docs(ai): define owner debug apk protocol"
~~~

## Task 4: Audit and close Fase 0

**Files:**
- Modify: docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md

**Consumes:** Tasks 1–3 and their commits.

**Produces:** A truthful closure record; it must not declare public GO or authorize Fase 1.

- [ ] **Step 1: Verify documentation and restricted diff**

~~~powershell
rg -n "31 / 1.16.6|12 testers|13 days|Crash-free|commit/tag" docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md
rg -n "request_type|outcome|latency_bucket|kill switch|fail closed|App Check|idempotency" docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md
rg -n "USB debugging|test account|fictitious data|SHA-256|uninstall|stable app|bug report" docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md
git diff --check e4815f2767fae0f9761e29be6835a878bb43e308..HEAD
~~~

Expected: every required term appears and no whitespace error occurs.

- [ ] **Step 2: Record evidence in the source of truth**

Update Fase 0 with the three document paths, the known closed-test facts, the absence of public GO without aggregate metrics, and the Fase 1 block pending explicit approval plus frozen baseline. Add a changelog row with closure date and commit SHAs.

- [ ] **Step 3: Run regression gates**

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\pibed\AppData\Local\Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
git diff --check
git status --short
~~~

Expected: all Gradle tasks pass, Git has no whitespace errors, and only the pre-existing untracked handoff directory is unrelated.

- [ ] **Step 4: Commit the closure**

~~~powershell
git add -- docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md
git commit -m "docs(ai): close phase 0 internal readiness"
~~~

## Acceptance Criteria

- The three operations documents exist and establish no PII or secret collection path.
- The source of truth records evidence without claiming public stability or authorizing Fase 1.
- No Android source, Gradle/Firebase configuration, release metadata, AAB or external system changes.
- Fresh testDebugUnitTest, assembleDebug, lintDebug and git diff --check evidence is green.
- Sol independently audits the final diff before declaring Fase 0 DONE.

## Rollback

Revert the four Fase 0 documentation commits in reverse order. No runtime state, artifact, secret, cloud resource or user data needs cleanup because this phase changes repository documentation only.
