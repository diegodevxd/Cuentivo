# Cuentivo Neo-Fintech Implementation Plan

**Goal:** Adapt the approved Neo-Fintech visual direction to Cuentivo across Android phones from
320 dp to 480 dp without changing financial, billing, authentication, or synchronization behavior.

**Architecture:** Keep the existing screen callbacks and state ownership intact. Add a small pure
layout policy for deterministic breakpoints, reusable Compose presentation components, and a dark
graphite Material theme. Restyle screens in place so navigation and business behavior remain
unchanged. Motion must be decorative, short, and disabled when the system animation scale is zero.

**Technology:** Kotlin, Jetpack Compose Material 3, Compose animation APIs, Android Gradle Plugin,
JUnit, Android instrumented Compose tests.

---

## Task 1: Add a tested responsive layout policy

**Files:**
- Create: `app/src/test/java/com/cuentivo/app/ui/NeoFintechLayoutPolicyTest.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/NeoFintechLayoutPolicy.kt`

1. Write unit tests for compact, standard, and large phone widths.
2. Assert horizontal gutters, action stacking, and ledger amount stacking at boundary widths.
3. Run the focused test and confirm it fails because the policy does not exist.
4. Implement the minimum pure Kotlin policy.
5. Run the focused test and confirm it passes.

## Task 2: Build the visual system

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/NeoFintechComponents.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Type.kt`
- Modify: `app/src/main/java/com/cuentivo/app/MainActivity.kt`

1. Define graphite surfaces and functional cyan, acid-green, violet, and overdue-red roles.
2. Add responsive screen padding, hero balance card, ledger row, section title, and animated action
   components.
3. Use a safe Compose gradient fallback on all supported Android versions; isolate any API 33
   shader path behind a runtime API check.
4. Respect the system animator scale and maintain a minimum 48 dp touch target.
5. Force the app shell into the approved dark Neo-Fintech presentation.

## Task 3: Restyle navigation and key screens

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`

1. Restyle the five-tab bottom navigation with a clear active state and central sale action.
2. Restyle Dashboard with a balance hero, compact metrics, insights, and readable debtor ledger.
3. Restyle Customers with responsive search and organic ledger cards.
4. Restyle the customer statement sheet as a customer-detail experience.
5. Restyle New Sale with clear customer, product, totals, and confirmation sections.
6. Preserve every existing callback, amount type, validation rule, and compensating-operation flow.

## Task 4: Regression verification and device QA

**Files:**
- Modify only tests that need new semantic assertions.

1. Run `testDebugUnitTest --rerun-tasks`.
2. Run `connectedDebugAndroidTest` on the available AVD.
3. Run `lintDebug`.
4. Run `assembleDebug` and `assembleRelease`.
5. Install the debug APK, launch the app, and inspect screenshots at a narrow phone width.
6. Correct any clipping, touch-target, contrast, or runtime issue found.

## Task 5: Deliver a testable APK

**Files:**
- Modify: `PROJECT_MEMORY.md`

1. Record the design architecture and exact verification results.
2. Copy the final debug APK to a clearly named artifact path.
3. Confirm APK package, version, timestamp, and installability.
4. Commit the scoped source changes on `codex/neo-fintech`.
