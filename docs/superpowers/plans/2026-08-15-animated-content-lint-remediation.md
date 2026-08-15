# AnimatedContent Lint Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `UnusedContentLambdaTargetStateParameter` lint error while preserving entrance-only destination animation.

**Architecture:** `NeoFintechShell` will bind the `AnimatedContent` lambda's target destination to the rendered subtree with Compose `key`. A focused JVM source-contract test prevents reintroducing an ignored target state. No app or AI behavior is otherwise changed.

**Tech Stack:** Kotlin, Jetpack Compose animation, JUnit 4, Android Gradle Plugin lint.

## Global Constraints

- Work only on `diegodevxd/feature-cuentivo-ai` in the isolated worktree.
- Do not touch the release worktree.
- Do not introduce Firebase, Room, Billing, network, secrets, dependencies, version changes, AAB, Play, money mutation, preview or confirmation.
- This is a one-file UI lint-remediation exception explicitly authorized by the owner to close the Phase 0A verification gate.

---

### Task 1: Bind animated content to its target destination

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt:198-207`
- Create: `app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt`

**Interfaces:**
- Consumes: `AnimatedContent(targetState = destination)` and the existing `content: @Composable (PaddingValues) -> Unit` callback.
- Produces: target-state-aware `AnimatedContent` content and a regression test for the lint contract.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `animated content keys its subtree with target destination`() {
    val source = File("src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt").readText()
    assertTrue(source.contains(") { targetDestination -> key(targetDestination) { content(padding) } }"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.cuentivo.app.ui.NeoFintechShellSourceContractTest`

Expected: FAIL because the source contains `) { _ -> content(padding) }`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
) { targetDestination ->
    key(targetDestination) { content(padding) }
}
```

Import `androidx.compose.runtime.key` only if it is absent.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.cuentivo.app.ui.NeoFintechShellSourceContractTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify the phase gate**

Run: `./gradlew.bat :app:testDebugUnitTest`, `./gradlew.bat :app:assembleDebug`, `./gradlew.bat :app:lintDebug`, and `git diff --check`.

Expected: all commands exit 0.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt docs/superpowers/specs/2026-08-15-animated-content-lint-remediation-design.md docs/superpowers/plans/2026-08-15-animated-content-lint-remediation.md
git commit -m "fix(ui): bind animated content to destination state"
```
