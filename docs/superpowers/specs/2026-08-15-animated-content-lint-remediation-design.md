# AnimatedContent Lint Remediation Design

## Goal

Remove the blocking Compose lint error in `NeoFintechShell` without changing the existing entrance-only navigation animation.

## Decision

`AnimatedContent` will use its `targetState` as a Compose key for the supplied screen content. The content therefore remains associated with the target destination while retaining the existing fade-and-lift entrance transition and `ExitTransition.None`.

## Scope

- Modify only `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt` and its JVM regression test.
- Keep the CuentivoAI contracts, provider, validator, money logic, Room, Firebase, Billing, network, permissions and dependencies unchanged.
- This is an explicitly authorized lint-remediation exception needed to make the Phase 0A verification gate green; it does not begin preview, confirmation, or another CuentivoAI phase.

## Verification

Add a JVM source-contract test that fails while the target-state lambda parameter is ignored and passes only when that parameter keys the rendered content. Then run unit tests, `assembleDebug`, `lintDebug`, and `git diff --check`.
