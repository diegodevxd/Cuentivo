# v44 Tanda 6: Correcciones antes del release
> For agentic workers: follow the task sequence below. The OpenCode implementer must not delegate; Codex performs the independent review, gate, commit, and release preparation after the user signals completion.

**Goal:** Close the three release-readiness issues from the v44 audit: bulk year correction must affect only `IMPLAUSIBLE_DATE` rows, the “Precio habitual” regression must be tested through rendered UI behavior, and closing customer detail after a registered sale must not reopen an empty sale form.

**Architecture:** Keep correction eligibility in the pure ledger domain policy and make the review UI call that policy with full typed objection data. Keep the price assertion on the production Compose preview path. Keep navigation state changes in the real Cuentivo shell flow and verify them through UI interaction.

**Tech Stack:** Kotlin, Android Jetpack Compose, JUnit, Compose UI tests, existing Gradle release gate, connected Android 16 phone A059P.

## Global Constraints

- Work in the current Orca worktree and branch `diegodevxd/v44-libreta`; do not create another worktree.
- OpenCode/DeepSeek implements T6 without delegating, committing, changing release versions, building a release bundle, or touching `docs/superpowers/handoffs/`.
- Do not push, tag, deploy, publish to Play, or change account data.
- Codex must not start Gradle while OpenCode is active in this shared worktree. After Diego signals completion, Codex independently reviews and runs the required verification.
- Never put source-reading assertions in place of the “Precio habitual” behavior test. Tests must execute production code and fail if the production behavior regresses.
- Keep the phone app installed: any connected instrumented Gradle invocation must include `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
- Do not use real customer/booklet data in code, tests, or captures. Never expose signing secrets.
- Do not include `docs/superpowers/handoffs/` in a commit.

## Task 1 — Scope bulk year correction to date objections

**Files:** `app/src/main/java/com/cuentivo/app/domain/LedgerYearCorrection.kt`, `app/src/main/java/com/cuentivo/app/ui/ai/LedgerReviewScreen.kt`, `app/src/test/java/com/cuentivo/app/domain/LedgerYearCorrectionTest.kt`.

1. Write a failing regression test using the production year-correction planner and typed objection models. Include at least two `IMPLAUSIBLE_DATE` rows that create a bulk plan and one different objection type such as `PRICE_MISMATCH_SUSPECT` whose date differs from the target year but is otherwise recent/plausible.
2. Assert that “Cambiar todos” returns/updates only the date-objection line indices. Demonstrate that removing the production type filter makes the test fail because the price-objection row enters the bulk plan.
3. Change the planner API or its production caller so it filters by objection type itself (prefer passing typed objections into the planner). Do not rely only on the UI caller remembering to pre-filter a raw set of indices.
4. Confirm the `LedgerReviewScreen` uses that same production planner output when applying “Cambiar todos”.
5. Correct the card wording/count if mixed source years could make it claim that minority-year rows “say” the dominant year. Keep the action and its count truthful.
6. Run the focused test class and report the red-before/green-after results.

## Task 2 — Replace the “Precio habitual” source guard

**Files:** `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`, production preview code in `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`, and a suitable Compose UI test under `app/src/androidTest/java/com/cuentivo/app/ui/`.

1. Remove the test that reads `AiDebugLabScreen.kt` as a file and searches for a resource identifier.
2. Add a Compose behavior test that renders the real production sale-preview composable with a product whose `resolutionMode` is `NEW` and verifies the product/price preview is rendered while the localized “Precio habitual” label/value is absent.
3. The test must call the same composable or extracted production component used by `AiDebugLabScreen`; do not reproduce its condition in test-only code. Extract a reusable production composable if visibility or setup requires it.
4. Verify the test would fail if the habitual-price row were added back to the production render path.
5. Run the focused test and report red-before/green-after evidence. Resource-key parity remains a separate guard.

## Task 3 — Close the empty-sale return path after registration

**Files:** `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt` and an appropriate Compose UI test, preferably extending `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt` or an existing sale-flow suite.

1. Reproduce the user path through the production shell: open a new sale, reach its successful registration callback, open that customer’s detail as the post-registration destination, then close the detail using the visible close action or Back.
2. Fix the state transition so closing the detail lands on Inicio or Clientes with the new-sale task closed. Do not make the test pass by constructing an already-open detail as the initial state or by directly clearing internal state only from test code.
3. Assert that the shell shows Inicio or Clientes selected, that the new-sale action is not selected, and that the empty sale form is absent after detail closes.
4. Keep edit/correction flows that intentionally return to the statement intact; add or retain a regression assertion if the changed close path could affect them.
5. Run the focused test and report red-before/green-after evidence.

## Handoff to Codex

1. OpenCode writes `C:/tmp/oc-v44-tanda6-reporte.md` with changed files, actual commands/results, test counts, skips and reasons, and any limitation. Do not commit.
2. Diego signals that OpenCode has finished. Codex then reviews the full diff, applies `.claude/agents/test-honesty-reviewer.md`, and runs the full gate and device instrumented suite independently.
3. Codex fixes any defect found, commits the verified T6 changes locally, and only then starts release preparation with `versionCode = 44` and `versionName = "1.23.0"`.
4. The release gate includes all connected instrumented tests on A059P with `leaveApksInstalledAfterRun=true`, `bundleRelease`, AAB signature verification (`jarsigner -verify`), and signing-key SHA1 verification with `keytool -printcert -jarfile`. Expected SHA1: `25:05:8A:9C:F3:1F:D7:B4:1E:8E:B3:2D:9A:AB:8F:9F:7A:E8:1D:BF`.
5. Copy the signed bundle to `dist/Cuentivo-v44-1.23.0.aab`. Do not push or tag until Diego says. After successful release preparation, update the `PROJECT_MEMORY.md` header by replacing its current-state section and keeping the file under roughly 100 lines.
