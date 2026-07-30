# Task 4 report: Stitch dashboard

## Files

- Added `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardScreen.kt`.
- Updated `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt` to route dashboard actions to the existing sale, payment, customer statement, and customer-list flows.
- Updated `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt` with Stitch hierarchy and payment-routing semantics.

## Red / green evidence

- RED: `connectedDebugAndroidTest` failed the new hierarchy assertion because the legacy dashboard did not expose `Saldo total por cobrar`.
- GREEN: `connectedDebugAndroidTest --no-watch-fs --no-daemon --no-configuration-cache --console=plain` completed 33 tests with 6 configured sync E2E skips and no failures.
- GREEN at 320 dp: with `wm size 720x1600` and `wm density 360`, the same suite completed 33 tests with 6 configured sync E2E skips and no failures; dimensions and density were reset in `finally`.

## Visual fidelity decisions

- Preserved the approved compact hierarchy: receivable hero, inline due-today/overdue values, cyan payment action, outlined sale action, critical customers, then real recent activity.
- Used the Task 2 gutter policy, 126–142 dp hero bounds, 48 dp actions, three critical customers maximum, five activity rows maximum, and compact 56 dp empty rows.
- Activity is rendered only from Task 1's `recentActivity` feed; no synthetic rows are introduced.

## Commit

`feat: rebuild Stitch dashboard` (commit hash is included in the task handoff).

## Concerns

- The installed Gradle environment emits the pre-existing experimental `android.disallowKotlinSourceSets` warning.
