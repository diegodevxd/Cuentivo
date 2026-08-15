# CuentivoAI Baseline 31 Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` task-by-task.

**Goal:** Integrate frozen Cuentivo `31 / 1.16.6` (`e40eb4e`) into `diegodevxd/feature-cuentivo-ai` without changing `v1.16.6-31`.

**Architecture:** A no-rewrite merge makes the release baseline authoritative for existing Cuentivo behavior while retaining only local AI contracts/tests/docs and the tested Compose lint remediation.

## Constraints

- Never move, delete, amend or retag `v1.16.6-31` or `release/1.16.6-baseline`.
- Do not create a gateway, provider, secret, Functions/Run, quota, entitlement, AI network call, AAB, Play action, version or applicationId change.
- Stop and escalate any conflict in Room, DAO, migration, Firestore, sync, Billing, Auth, rules or financial logic.
- Preserve `app/src/main/java/com/cuentivo/app/ai/**`, its JVM tests, CuentivoAI docs and `NeoFintechShellSourceContractTest`.
- Do not stage `.agents/**`, `.claude/**`, `docs/superpowers/handoffs/**`, artifacts, `google-services.json` or secrets.

### Task 1: Create an auditable merge state

**Files:** baseline files changed by `e40eb4e`; local CuentivoAI paths above remain present.

- [ ] Check safety state: `git status --short`; `git branch --show-current`; `git rev-parse HEAD`; `git show-ref --verify refs/tags/v1.16.6-31`.
- [ ] Start only a non-committed merge: `git merge --no-commit --no-ff e40eb4e04404a63f9fa0d15e330834780961cd63`.
- [ ] List unmerged paths with `git diff --name-only --diff-filter=U`. Before resolving each path, report `<path> | baseline retained | AI retained | rationale`.
- [ ] If a prohibited path conflicts, run `git merge --abort` and stop for Sol. Otherwise baseline wins existing Cuentivo behavior; CuentivoAI wins only its exclusive paths.

### Task 2: Verify the Compose remediation

**Files:** `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt` only when merge requires it; preserve `app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt`.

- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.NeoFintechShellSourceContractTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain` before editing UI.
- [ ] Only if it fails, retain baseline `content(padding)` and associate its subtree with target state: `key(targetDestination) { content(padding) }`; add the `androidx.compose.runtime.key` import only if missing.
- [ ] Re-run the same source-contract command; expected result is `BUILD SUCCESSFUL` with zero failures.

### Task 3: Gate and commit

**Files:** resolved merge plus approved CuentivoAI paths only.

- [ ] Run separately: `./gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain`; `./gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain`; `./gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain`; `git diff --check`; `git diff --name-only --diff-filter=U`.
- [ ] Require all Gradle gates to report `BUILD SUCCESSFUL`, whitespace check silent and no unresolved path.
- [ ] Inspect `git diff --cached --name-status`, `git diff --cached --check` and `git status --short` before committing `merge: integrate 1.16.6 baseline into CuentivoAI`.
- [ ] Verify `git show --no-patch --format='%P%n%s' HEAD` has two parents, the feature pre-merge first and `e40eb4e04404a63f9fa0d15e330834780961cd63` second.
- [ ] Write `.superpowers/sdd/baseline-31-integration-report.md` with exact gates, conflict decisions, hashes, exclusions and residual risks; do not declare Fase 1 done.

## Rollback

- Before commit: `git merge --abort`.
- After commit: only Sol may choose `git revert -m 1 <merge-commit>`; the baseline tag is immutable.
