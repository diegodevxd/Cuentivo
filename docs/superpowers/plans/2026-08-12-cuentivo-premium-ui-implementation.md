# Cuentivo Premium UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize Cuentivo's native Compose UI incrementally while preserving every business, Firebase, Room, authentication, billing, sync, reminder, and navigation contract.

**Architecture:** Keep the existing `Neo*` design system and four-destination shell. First remove unreachable visual legacy and fix system-window/accessibility defects; then make Material 3 color roles genuinely light/dark, improve shared primitives, Dashboard, Products, Clients, and forms using only existing data. No new backend, model, Room migration, dependency, chart, Rive, Lottie, AGSL, or navigation library is permitted.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, AndroidX Activity/WorkManager, JVM unit tests, Compose instrumented tests.

## Global Constraints

- This is a post-release code change: do not alter the uploaded AAB 30/1.16.5 and do not upload/publish anything.
- Preserve existing test tags, strings, touch targets (>=48dp), responsive policies, transaction semantics and all working flows.
- Dark remains the initial default because it was device-approved; `CuentivoTheme(darkTheme = false)` must render a complete light alternative. Follow-system is supplied by the existing caller/system value; do not add settings persistence unless it already exists.
- Use only real current data. Do not display stock, margin, product trends, low-stock, charts, predictions or fields that the app does not currently write.
- No hard-coded user-visible Spanish; add both `values/strings.xml` and `values-en/strings.xml` entries together.
- Implement TDD per task. Preserve unrelated dirty files and never commit mixed existing work.

---

## File map

- `app/src/main/java/com/example/organizationlabs/MainActivity.kt`: system bar appearance only.
- `app/src/main/java/com/example/organizationlabs/ui/theme/{Color,Theme}.kt`: semantic light/dark palettes and Material color schemes.
- `app/src/main/res/values/{themes,colors}.xml`: cold-start window background.
- `app/src/main/java/com/cuentivo/app/ui/{NeoFintechTokens,NeoFintechComponents,NeoFintechShell,CuentivoApp}.kt`: shared tokens/primitives, motion, legacy removal, shell semantics.
- `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardScreen.kt`: hierarchy-only Dashboard refresh using existing `DashboardUiState.Content`.
- `app/src/main/java/com/cuentivo/app/{data/local/ProductImageStore,ui/product/{ProductPhoto,ProductsScreen}.kt`: image sampling/cache and Product Retail CRM presentation using only name/photo/category/SKU/price.
- `app/src/main/java/com/cuentivo/app/ui/{customer/CustomersScreen,transaction/NeoFormComponents}.kt`: visual consistency, headings and IME/navigation only.
- `app/src/{test,androidTest}/java/com/cuentivo/app/...`: contrast/policy/primitives and semantic regression tests.

## Wave 1: Safe foundations and visual debt

### Task 1: Fix system windows and remove unreachable legacy UI

**Files:**
- Modify: `MainActivity.kt`, `res/values/themes.xml`, `ui/CuentivoApp.kt`, `ui/NeoFintechComponents.kt`, `ui/theme/Color.kt`, related imports.
- Test: existing unit/Android UI test suites; add no behavior-changing test for dead code.

- [ ] Write focused assertions or inspection checks that `LegacyDashboardScreen`, `NeoBalanceHero`, `NeoMoneyPill`, `NeoDirectoryRow`, `NeoPrimaryButton`, `MetricCard`, `InsightCard`, `drawNeoHeroAura`, and `NeoViolet` have no live consumers.
- [ ] Confirm the checks fail before removal only because the unreachable symbols exist.
- [ ] Set explicit dark system-bar styles in `MainActivity.onCreate`; retain edge-to-edge and do not alter activity architecture.
- [ ] Give `Theme.Cuentivo` a graphite `windowBackground`; do not change its parent.
- [ ] Delete only the confirmed unreachable legacy composables, aliases, hard-coded Spanish contained exclusively in them, their colors and imports.
- [ ] Run `testDebugUnitTest`, `assembleDebug`, string parity, and a grep proving all removed symbols are absent.

### Task 2: Build accessible semantic light/dark themes

**Files:**
- Modify: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/NeoFintechComponents.kt`, `ui/NeoFintechShell.kt`, auth hard-coded color consumers.
- Create: `src/test/java/com/cuentivo/app/ui/theme/ColorContrastTest.kt`.

- [ ] Write failing pure JVM contrast tests for control outline/surface >=3:1 and text/surface >=4.5:1 in both schemes.
- [ ] Define named semantic palette values and two explicit `ColorScheme`s. Keep dark primary cyan; use accessible teal primary for light. Separate `outline` (control boundary) from soft `outlineVariant` (decorative grouping).
- [ ] Make `CuentivoTheme(darkTheme)` select its scheme, background and `LocalContentColor`; remove the ignored parameter suppression.
- [ ] Replace UI hard-coded colors and `Color.Black` with Material/token roles. Do not globally replace every `outlineVariant`; migrate control primitives deliberately.
- [ ] Run focused contrast tests, `testDebugUnitTest`, `compileDebugAndroidTestKotlin`, and strings parity.

### Task 3: Shared primitives, accessibility and motion

**Files:**
- Modify: `NeoFintechTokens.kt`, `NeoFintechComponents.kt`, `NeoFintechShell.kt`, `transaction/NeoFormComponents.kt`.
- Test: `src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt` and focused component tests.

- [ ] Add failing semantic tests for headings and selected navigation where applicable.
- [ ] Add a heading-capable section header; use it only on structural screen/form sections.
- [ ] Keep `NeoSpacing`, `NeoDisabled`, `NavigationLayoutPolicy`, adaptive labels and 48dp targets intact. Standardize panel/control shapes and border roles without nested-card proliferation.
- [ ] Provide motion preference once through the theme/primitives and ensure press/navigation transitions respect disabled system animation.
- [ ] Add IME actions/focus progression to existing long forms without changing validation, field values or submit behavior.
- [ ] Run focused instrumented tests and `testDebugUnitTest`.

## Wave 2: High-impact screens

### Task 4: Refresh Dashboard hierarchy without changing its data source

**Files:**
- Modify: `ui/dashboard/DashboardScreen.kt`, strings files only if a new already-supported label is essential.
- Test: `androidTest/ui/CuentivoAppTest.kt` and dashboard presentation unit tests.

- [ ] Add/adjust a failing Compose semantic test that verifies the receivable hero, payment action, critical-customer section and recent activity remain reachable.
- [ ] Keep `LazyColumn`; do not force a web-like grid. Use a moderate bento relationship inside the existing mobile rail: one dominant receivable hero, paired secondary monthly figures, then task-oriented sections.
- [ ] Remove the hero maximum height, retain only a minimum, reserve primary accent for receivable/action, and demote repeated eyebrows to secondary text.
- [ ] Keep only the Dashboard action that is not already permanently offered by bottom navigation; do not alter `onNewSale` or payment handlers.
- [ ] Localize critical-status labels currently hard-coded in the live Dashboard.
- [ ] Preserve existing keys/test tags; run Dashboard and full Android UI regression on the emulator.

### Task 5: Make product images safe before upgrading product presentation

**Files:**
- Modify: `data/local/ProductImageStore.kt`, `ui/product/ProductPhoto.kt`.
- Create/modify: focused image-store JVM tests.

- [ ] Write failing tests for bounded decode dimensions/cache behavior using local test image fixtures or injectable decoder seams.
- [ ] Implement a small bounded `LruCache` and sampled decode in `ProductImageStore`; retain local URI/photo save/delete behavior.
- [ ] Keep image decoding off the main thread and placeholders/rounded crop behavior unchanged.
- [ ] Run image tests, product Compose tests, `assembleDebug`, and inspect scroll behavior on the emulator.

### Task 6: Upgrade Products to Retail CRM using only populated fields

**Files:**
- Modify: `ui/product/ProductsScreen.kt`, `ui/product/ProductPhoto.kt`, shared primitive files as needed, both string resources.
- Test: `androidTest/ui/ProductFlowTest.kt`, `ProductCreationFlowTest.kt`, `ProductPhotoSourceTest.kt` and new semantic tests.

- [ ] Write a failing test that a product row exposes name, price, image/fallback and category/SKU only when present, without breaking active/archived search and selection mode.
- [ ] Retain `LazyColumn` and the existing dense list: no grid toggle until a separately approved product-view preference exists.
- [ ] Give search a prominent but compact treatment; preserve current query and active/archived filter semantics. Do not add category, price, availability or stock filters without a domain/UI decision task.
- [ ] Present category/SKU as secondary metadata when imported; show no stock/margin/status badges while those fields are not written.
- [ ] Retain existing add, edit, archive, multi-select, import and photo flows. Improve hierarchy/shapes/feedback only.
- [ ] Execute product instrumented suite and manually test an image, a product without image, a long name and font scale 1.3.

## Wave 3: Consistent operational flows

### Task 7: Align Customers, detail surfaces, forms, empty/loading/feedback

**Files:**
- Modify: `ui/customer/CustomersScreen.kt`, `CustomerDetailSheet.kt`, `ui/transaction/{NeoFormComponents,NewSaleScreen,PaymentScreen}.kt`, `ui/AlertsSheet.kt`, account sheets only where a shared primitive requires it.
- Test: existing Customers/NewSale/Payment/Alerts instrumented tests plus new heading/form traversal tests.

- [ ] Write focused tests for all existing primary actions and empty-state CTAs before visual edits.
- [ ] Reuse shared rows and semantic status treatment: identity/contact only if present, real balance/status, no invented purchase counts or email.
- [ ] Group existing form fields under visual section headings. Do not add/remove fields, relax validation, or change sale/payment calculations.
- [ ] Upgrade empty states to icon/heading/detail/CTA only where an existing CTA already exists. Use skeletons only for data surfaces with known final geometry; do not add perpetual shimmer.
- [ ] Use progress/snackbar feedback for already asynchronous saves/imports without treating every action as a toast.
- [ ] Run `testDebugUnitTest`, full connected Android tests, and strings parity.

## Wave 4: Release-quality verification

### Task 8: Responsive, performance and release audit

**Files:**
- Modify: only defects proven during verification; test/docs files as evidence requires.

- [ ] Run `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `compileDebugAndroidTestKotlin`, and `connectedDebugAndroidTest` on the open emulator.
- [ ] Execute existing large-font navigation test plus 2.0 font-scale smoke test; verify Dashboard, Products, Customers, Sale and Payment at compact and large emulator widths.
- [ ] Inspect theme light/dark and mode switch, system bars, first cold start, search/filter, photos, Google sign-in entry, account deletion entry, Pro entry and reminder settings. Do not use a real user account or delete data.
- [ ] Verify no control depends on color alone; headings and content descriptions remain correct; no hard-coded visible Spanish remains.
- [ ] Compare product scrolling before/after cache with `gfxinfo` if an emulator data set permits; otherwise record this limitation rather than claim a measurement.
- [ ] If UI changed materially, produce a new versioned AAB only after owner approval; never overwrite the existing Play artifact or upload it automatically.

## Handoff order

1. OpenCode / DeepSeek V4 Flash implements Tasks 1–7 sequentially in the active worktree, reporting tests after each task and asking before any scope expansion.
2. Terra reviews diffs/contracts and runs Task 8 on the emulator.
3. Claude/Opus reviews the completed UI changes only after DeepSeek settles, focusing on visual consistency, Compose/performance and accessibility; it does not edit.
