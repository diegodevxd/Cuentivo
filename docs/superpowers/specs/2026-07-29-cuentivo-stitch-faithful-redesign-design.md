# Cuentivo Stitch-Faithful Redesign

**Date:** 2026-07-29

**Status:** Approved design
**Scope:** Complete Android presentation redesign without changing financial or integration logic

## Objective

Rebuild Cuentivo's interface as a faithful Android adaptation of the approved Stitch screens. The
screens must reproduce Stitch's compact exchange-like density, hierarchy, proportions, navigation,
cards, and functional neon accents. This is not a reskin of the current Material interface.

The implementation must continue to support Android phones from 320 dp through 480 dp and Android 7
or newer. Room, Firebase Auth, Firestore synchronization, Play Billing, entitlement handling,
integer-cent money calculations, and compensating financial operations remain unchanged.

## Source of truth

These approved captures are the visual source of truth:

- `cuentivo-stitch-neo-final-dashboard.png`
- `cuentivo-stitch-neo-final-venta.png`
- `cuentivo-stitch-neo-v2-clientes.png`
- `cuentivo-stitch-neo-v2-detail-fixed.png`

When this document and an incidental Material default differ, the captures win unless doing so would
break accessibility, supported phone widths, or an existing business rule.

## Information architecture

### Global shell

The top-left Cuentivo mark opens the Account sheet. The top-right bell opens Alerts. The bottom bar
has four destinations and one central action:

1. Inicio
2. Clientes
3. Central `+` action: Nueva venta
4. Productos
5. Abonos

Nueva venta is an action and a dedicated screen, not a permanently selected navigation tab. The
central `+` is the only global sale action.

### Inicio

The dashboard follows the Stitch order:

1. Compact brand/account and alert header.
2. Total receivable hero.
3. Due-today and overdue supporting metrics.
4. Primary `Registrar abono` action.
5. Secondary `Nueva venta` action.
6. Critical customers.
7. Recent activity.

Critical customers are ordered by overdue debt first and then descending balance. Recent activity is
derived from existing ledger movements. No placeholder financial events may be invented.

Plan, sign-out, and account deletion do not appear in the dashboard. They move to Account.

### Cuenta

Account is a modal bottom sheet containing:

- current plan and upgrade entry;
- authenticated-account context;
- sign out;
- account deletion.

Account deletion retains its existing explicit destructive confirmation and Google Play compliance.

### Clientes

Customers uses the approved compact directory:

- screen title and compact summary;
- search field;
- rows with identity, contact context, balance, and status;
- one clear add-customer action;
- tapping a row opens Customer Detail.

Editing and archiving remain available without crowding every row. They move to a row overflow/action
sheet where needed.

### Customer detail

The customer detail experience reproduces the approved Stitch hierarchy:

- identity and status;
- prominent balance;
- Nueva venta and Registrar abono actions;
- compact ledger/history;
- sharing actions;
- correction section for cancellable sales and payments.

Corrections remain compensating movements. The interface must never imply that financial history is
deleted.

### Productos

Products adopts the same compact directory system as Customers:

- search and add/import actions;
- product image, name, sale price, and compact metadata;
- edit/archive actions;
- existing image and CSV import behavior.

### Nueva venta

The sale screen follows the approved Stitch composition:

- compact title and exit/back affordance;
- customer selector;
- product selector and quantity;
- cart rows;
- discount and due-date controls;
- persistent visual total near the confirmation action;
- one primary register-sale action.

Existing `SaleDraft`, integer-cent totals, validation, due-date calculation, and callbacks remain the
single source of truth.

### Abonos

Payments uses the same visual grammar as Nueva venta:

- customer with highest debt prioritized;
- current balance context;
- amount entry and resulting-balance preview;
- one primary confirmation action;
- existing overpayment protection.

### Authentication and empty states

Sign-in, account creation, no-business, no-customers, no-products, and empty-ledger states use the
same compact Neo-Fintech language. Empty states occupy their natural section and must not turn the
entire app into an oversized welcome page.

## Visual system

### Density and sizing

The approved Stitch captures define the density. Body copy, labels, amounts, cards, and navigation
must not inherit oversized Material defaults.

- 320–359 dp: 14–16 dp horizontal gutter, narrow cards, stacked only when necessary.
- 360–411 dp: 16 dp horizontal gutter and the reference composition.
- 412–480 dp: 18–20 dp gutter with content width constrained so it does not become sparse.
- Minimum interactive target: 48 dp, even when the visible control is smaller.
- No horizontal clipping or scrolling in standard application screens.

### Color roles

- Near-black background.
- Graphite containers with subtle separation and hairline borders.
- Neon cyan for primary actions, selection, and charge/receivable emphasis.
- Acid green only for payments, collected money, and positive status.
- Soft red only for overdue debt, destructive actions, and errors.
- Violet only as a restrained secondary aura.

Color is never the sole carrier of meaning.

### Shape and typography

Cards use the moderate radii shown by Stitch rather than large pill-like Material surfaces. Money
uses a monospaced family and tight spacing. Labels are compact and uppercase only where the captures
use an eyebrow or financial label. Screen titles remain strong without dominating the viewport.

### Shaders and depth

A restrained GPU gradient/aurora may appear inside the receivable hero. The full application
background remains stable and near-black. The implementation must work on API 24; no unconditional
API 33 `RuntimeShader` call is allowed.

## Motion

- Press response: scale to approximately 0.97.
- Standard transition duration: 160–220 ms.
- Active bottom-navigation indicator animates between destinations.
- Sheets and dedicated task screens use short enter/exit transitions.
- Lists may stagger only on their first appearance and only subtly.
- No perpetual decorative animation.
- The system animator-duration setting is respected.

## State and data flow

The redesign remains a pure presentation layer over existing state and callbacks.

- Monetary values remain `Long` cents.
- Dashboard totals continue to come from `DashboardUiState`.
- Customer and product lists continue to use their existing Room-backed models.
- Sale and payment submission continue through existing validated callbacks.
- Billing, entitlement, authentication, and synchronization state are not duplicated in UI code.
- Business scoping and `businessId` isolation remain untouched.

Presentation helpers may calculate ordering, labels, and layout policies. They may not recalculate or
persist financial truth.

## Errors and feedback

- Field validation appears beside the relevant input.
- Operation-wide results use the existing snackbar channel.
- Destructive or compensating actions retain explicit confirmation dialogs.
- Loading prevents interaction with stale account data.
- Empty and offline states explain what is unavailable without claiming data was lost.

## Component boundaries

The redesign will split the current monolithic UI into focused presentation files:

- `NeoFintechShell`: top bar, compact bottom navigation, central action, account and alert entry.
- `NeoFintechTokens`: colors, shapes, spacing, typography, and motion values.
- `NeoFintechComponents`: hero, metric, ledger row, directory row, section heading, actions, and
  empty-state primitives.
- One screen file for Dashboard, Customers, Customer Detail, Products, New Sale, Payments, Account,
  and Authentication.
- Pure layout and ordering policies remain independently unit-testable.

Existing public screen entry points and callbacks will be preserved during extraction so
`MainActivity` and domain code do not need a simultaneous rewrite.

## Verification

Completion requires current evidence for all of the following:

1. Unit tests for breakpoints, critical-customer ordering, navigation state, and any new pure policy.
2. Compose/instrumented tests covering the dashboard, each bottom destination, central sale action,
   Account, and Customer Detail.
3. Visual captures at 320, 360, 412, and 480 dp.
4. Side-by-side inspection against all four approved Stitch references.
5. Manual emulator flow: create customer, create product, record sale, record payment, open and
   share statement, and reach correction actions.
6. `testDebugUnitTest`, normal `connectedDebugAndroidTest`, `lintDebug`, `assembleDebug`, and
   `assembleRelease`.
7. Confirmation that debug-only Norton trust configuration remains absent from release.

The next test APK is a debug artifact for the emulator. A Play-ready AAB is generated only after
visual approval and must use a new `versionCode` because version 6 is already used in Play Console.

## Non-goals

- No billing, sync, Firestore, Firebase Auth, Room, or financial-domain rewrite.
- No new financial feature.
- No third-party exchange branding, logos, names, or copied proprietary assets.
- No Fase D permissions work.
- No Play Console upload during visual implementation.
