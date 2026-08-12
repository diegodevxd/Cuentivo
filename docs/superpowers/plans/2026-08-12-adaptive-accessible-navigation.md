# Adaptive Accessible Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Cuentivo's global navigation readable and actionable at any supported Android screen width and user-selected font scale without shrinking user text.

**Architecture:** Extract a small, pure navigation layout policy that selects a labelled or compact shell from available width and `fontScale`. `NeoFintechShell` renders the selected layout: the compact layout preserves four semantic destinations and gives Nueva venta its own extended, readable action. Compose tests prove policy boundaries and the accessibility contract.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Compose UI tests, JUnit; no new dependencies.

## Global Constraints

- Preserve Android's requested `fontScale`; never counter-scale text or lower its visual size to force a fit.
- Support `minSdk = 24` and Material 3 Compose without new dependencies.
- Keep all touch targets at least 48 dp.
- Use Spanish (Mexico) strings already present in `app/src/main/res/values/strings.xml`.
- Nueva venta remains an action, not a `MainDestination`.
- Do not alter Firebase, Firestore rules, business data, or sale logic.
- Keep existing user work unrelated to navigation untouched.

---

## File Structure

- `app/src/main/java/com/cuentivo/app/ui/NavigationLayoutPolicy.kt`: pure selector for labelled/compact shell variants.
- `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt`: renders the selected variant and stable semantics.
- `app/src/test/java/com/cuentivo/app/ui/NavigationLayoutPolicyTest.kt`: policy boundary tests.
- `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`: high-font-scale integration coverage.

### Task 1: Define the responsive layout policy

**Files:**

- Create: `app/src/main/java/com/cuentivo/app/ui/NavigationLayoutPolicy.kt`
- Create: `app/src/test/java/com/cuentivo/app/ui/NavigationLayoutPolicyTest.kt`

**Interfaces:**

- Produces `enum class NavigationLayoutMode { LABELLED, COMPACT }`.
- Produces `object NavigationLayoutPolicy { fun modeFor(availableWidth: Dp, fontScale: Float): NavigationLayoutMode }`.
- Consumed by `CompactBottomNavigation` in Task 2.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
package com.cuentivo.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationLayoutPolicyTest {
    @Test fun labelled_layout_is_used_when_width_and_font_scale_are_comfortable() {
        assertEquals(NavigationLayoutMode.LABELLED, NavigationLayoutPolicy.modeFor(411.dp, 1f))
    }

    @Test fun compact_layout_is_used_on_a_narrow_phone() {
        assertEquals(NavigationLayoutMode.COMPACT, NavigationLayoutPolicy.modeFor(320.dp, 1f))
    }

    @Test fun compact_layout_is_used_for_large_system_text() {
        assertEquals(NavigationLayoutMode.COMPACT, NavigationLayoutPolicy.modeFor(411.dp, 1.3f))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.NavigationLayoutPolicyTest`

Expected: compilation failure referencing `NavigationLayoutMode` and `NavigationLayoutPolicy`.

- [ ] **Step 3: Implement the smallest explicit policy**

```kotlin
package com.cuentivo.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NavigationLayoutMode { LABELLED, COMPACT }

object NavigationLayoutPolicy {
    private val compactWidthThreshold = 360.dp
    private const val compactFontScaleThreshold = 1.3f

    fun modeFor(availableWidth: Dp, fontScale: Float): NavigationLayoutMode =
        if (availableWidth < compactWidthThreshold || fontScale >= compactFontScaleThreshold) {
            NavigationLayoutMode.COMPACT
        } else {
            NavigationLayoutMode.LABELLED
        }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.NavigationLayoutPolicyTest`

Expected: `BUILD SUCCESSFUL` and 3 tests passed.

- [ ] **Step 5: Commit the policy and tests**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/NavigationLayoutPolicy.kt app/src/test/java/com/cuentivo/app/ui/NavigationLayoutPolicyTest.kt
git commit -m "feat(ui): choose navigation layout by space and font scale"
```

### Task 2: Render labelled and compact navigation without truncation

**Files:**

- Modify: `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt` (imports, `CompactBottomNavigation`, `ShellDestination.item`, `NewSaleNavigationAction`)
- Test: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**

- Consumes `NavigationLayoutPolicy.modeFor(Dp, Float)` and `NavigationLayoutMode` from Task 1.
- Produces labelled and compact navigation with the existing `main-destination-<name>` tags and one semantic Nueva venta action.
- Keeps the public `NeoFintechShell` API unchanged.

- [ ] **Step 1: Add a failing high-font-scale Compose test**

Add these imports and test to `CuentivoAppTest.kt`:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Test fun large_system_text_keeps_every_navigation_action_accessible() {
    composeRule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }
    }

    listOf("Inicio", "Clientes", "Productos", "Abonos", "Nueva venta").forEach { description ->
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }
    composeRule.onAllNodesWithText("Nueva venta").assertCountEquals(1)
}
```

- [ ] **Step 2: Run the instrumentation test and verify it fails on the current shell**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest#large_system_text_keeps_every_navigation_action_accessible`

Expected: failure because destinations do not expose stable content descriptions and the single-row labelled layout remains selected.

- [ ] **Step 3: Implement responsive shell rendering**

In `CompactBottomNavigation`, use `BoxWithConstraints`, read `LocalDensity.current.fontScale`, and select the layout:

```kotlin
when (NavigationLayoutPolicy.modeFor(maxWidth, LocalDensity.current.fontScale)) {
    NavigationLayoutMode.LABELLED -> LabelledNavigationRow(destination, onDestination, onNewSale)
    NavigationLayoutMode.COMPACT -> CompactNavigationColumn(destination, onDestination, onNewSale)
}
```

Keep the current five-item row only in `LABELLED`. In `COMPACT`, render `NewSaleNavigationAction` above a four-item destination row; its text has dedicated width and remains visible at one line. Set each destination label as merged semantic content with `Role.Tab`, selection state and the existing test tag. Compact destinations render their 22 dp icon inside the existing 56 dp target. Expose exactly one `Nueva venta` description on its clickable parent and set its child icon description to null.

- [ ] **Step 4: Run the focused instrumentation test and verify it passes**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest#large_system_text_keeps_every_navigation_action_accessible`

Expected: `BUILD SUCCESSFUL`; all five actions are displayed and Nueva venta renders once.

- [ ] **Step 5: Run existing shell regression tests**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest`

Expected: `BUILD SUCCESSFUL`; destination selection and return-from-sale behavior remain unchanged.

- [ ] **Step 6: Commit responsive shell and regression test**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat(ui): adapt bottom navigation for large text"
```

### Task 3: Audit critical single-line controls and validate the AVD

**Files:**

- Modify only if evidence identifies a critical action: `app/src/main/java/com/cuentivo/app/ui/**/*.kt`
- Test: the existing focused test beside each changed component.

**Interfaces:**

- Consumes the policy in `docs/superpowers/specs/2026-08-12-adaptive-accessible-navigation-design.md`.
- Produces an evidence-backed list of changed critical controls; no broad visual refactor.
- Hands implementation and evidence to Terra in Task 4.

- [ ] **Step 1: Inventory candidate truncation sites without edits**

Run: `rg -n "maxLines\\s*=\\s*1|TextOverflow\\.(Clip|Ellipsis)|width\\([^)]*\\.dp\\)" app/src/main/java/com/cuentivo/app/ui`

Classify every result as a critical action, secondary data, or descriptive copy. Do not change secondary data solely because it has one line.

- [ ] **Step 2: For each critical action, write a focused failing test**

Use its existing Compose test file. Set `LocalDensity` to `Density(1f, 1.3f)`, assert the full action content description, and assert display after the relevant navigation/open action.

- [ ] **Step 3: Apply and verify only the minimum correction**

Use a two-line control with a 48 dp minimum touch target or an explicit responsive row/column variant. Do not reduce `sp`, override `fontScale`, or add a dependency.

Run the focused test from Step 2. Expected: `BUILD SUCCESSFUL` and the action is accessible at font scale 1.3.

- [ ] **Step 4: Install only the debug APK on the AVD**

Run: `./gradlew.bat installDebug`

Expected: `BUILD SUCCESSFUL`; ensure the install target is `emulator-5554`, never the physical A059P.

- [ ] **Step 5: Visually inspect default and large text, restoring AVD settings**

```powershell
$adb = "$env:LOCALAPPDATA\\Android\\Sdk\\platform-tools\\adb.exe"
& $adb -s emulator-5554 shell settings put system font_scale 1.3
& $adb -s emulator-5554 shell am force-stop com.cuentivo.app
& $adb -s emulator-5554 shell monkey -p com.cuentivo.app 1
# Inspect Inicio, Clientes, Productos, Abonos and Nueva venta.
& $adb -s emulator-5554 shell settings put system font_scale 1.0
```

- [ ] **Step 6: Run the relevant suite and commit only intentional UI/test files**

Run: `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

Inspect `git status --short`; stage only intentional UI/test files. Do not stage account-deletion, Firebase, skill, or unrelated documentation changes.

### Task 4: Independent Terra audit and handoff

**Files:**

- Review: all files changed by Tasks 1-3 and `docs/superpowers/specs/2026-08-12-adaptive-accessible-navigation-design.md`

**Interfaces:**

- Consumes OpenCode implementation commits and test outputs.
- Produces an audit result: `approved`, `approved with non-blocking notes`, or `blocked`.

- [ ] **Step 1: Confirm scope**

Run: `git diff <pre-implementation-commit>..HEAD -- app/src/main/java/com/cuentivo/app/ui app/src/test app/src/androidTest`

Expected: no Firebase, Firestore, database, billing, or business-logic changes.

- [ ] **Step 2: Reproduce the compact variant**

Run Task 2's focused Compose test and inspect the AVD at `font_scale 1.3`. Confirm full Nueva venta text, no visibly cut destination text, all five controls discoverable through semantics, and 48 dp-or-larger hit targets.

- [ ] **Step 3: Report through Orca**

Return `approved`, `approved with non-blocking notes`, or `blocked`. For every issue, include exact file/line and reproduction. Do not edit implementation files; route blocked remediation back to OpenCode.

## Plan Self-Review

- Spec coverage: Tasks 1-2 implement adaptive layout, font scaling, Nueva venta visibility and TalkBack semantics. Task 3 applies the global critical-control policy and validates the AVD. Task 4 independently verifies scope and accessibility.
- Placeholder scan: primary changes have exact paths, types, code and commands. Task 3 permits extra files only when the audit produces evidence of a critical-action violation.
- Type consistency: Task 1 defines `NavigationLayoutMode` and `NavigationLayoutPolicy.modeFor(Dp, Float)`; Task 2 consumes those exact names.

