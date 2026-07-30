# Cuentivo Stitch-Faithful Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild every Cuentivo Android screen as a faithful, compact adaptation of the four
approved Stitch references while preserving all financial, billing, authentication, and sync
behavior.

**Architecture:** Keep `MainActivity` as the state/callback owner and split the monolithic Compose UI
into focused screen files. Add only the read models needed for real dashboard activity, then keep
layout, ordering, and navigation decisions in pure tested policies. Reusable tokens and compact
components enforce Stitch fidelity across 320–480 dp.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, Firebase Auth, JUnit 4, AndroidX Compose UI
tests, Gradle/AGP.

## Global Constraints

- Support Android API 24 and newer; no unconditional API 33 `RuntimeShader`.
- Support phone widths from 320 dp through 480 dp without horizontal clipping.
- Minimum interactive target is 48 dp even when the visible control is compact.
- Money remains `Long` cents; no `Double` or `Float` financial arithmetic.
- Do not change Play Billing, entitlement, Firebase Auth, Firestore sync, Room schema version, or
  compensating-operation behavior.
- Every query must retain explicit `businessId` isolation.
- Debug-only Norton trust must remain absent from release.
- The four local Stitch PNGs are the visual source of truth.
- Do not create an AAB or change `versionCode` until the redesigned APK is visually approved.

---

### Task 1: Provide real dashboard feed data and tested ordering

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`
- Modify: `app/src/main/java/com/cuentivo/app/domain/DashboardRepository.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardViewModel.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardPresentation.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/dashboard/DashboardViewModelTest.kt`
- Create: `app/src/test/java/com/cuentivo/app/ui/dashboard/DashboardPresentationTest.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt`

**Interfaces:**
- Produces:
  - `DashboardActivity(id, customerId, customerName, type, direction, amountCents,
    occurredAtEpochMillis)`
  - `CriticalCustomer(customer: CustomerBalanceRow, overdueCents: Long)`
  - `DashboardUiState.Content.dueTodayCents`
  - `DashboardUiState.Content.dueSales`
  - `DashboardUiState.Content.recentActivity`
  - `DashboardPresentation.criticalCustomers(customers, dueSales, limit)`
- Consumes: existing `CustomerBalanceRow`, `DueSale`, `AccountMovementEntity`, and
  `FinancialDao.salesWithDueDate`.

- [ ] **Step 1: Write failing DAO and presentation tests**

Add an instrumented DAO assertion that rows from another business never appear:

```kotlin
@Test fun recent_activity_is_newest_first_and_business_scoped() = runBlocking {
    val dao = database.financialDao()
    database.customerDao().insert(customer("ana", "business-1", "Ana"))
    database.customerDao().insert(customer("beto", "business-2", "Beto"))
    dao.insertMovement(movement("old", "business-1", "ana", 100, 10))
    dao.insertMovement(movement("new", "business-1", "ana", 200, 20))
    dao.insertMovement(movement("foreign", "business-2", "beto", 999, 30))

    val result = dao.recentDashboardActivity("business-1", 5)

    assertEquals(listOf("new", "old"), result.map { it.id })
    assertEquals(listOf("Ana", "Ana"), result.map { it.customerName })
}
```

Add pure critical-order assertions:

```kotlin
@Test fun overdue_customers_come_before_larger_current_debts() {
    val result = DashboardPresentation.criticalCustomers(
        customers = listOf(balance("large", 50_000), balance("late", 10_000)),
        dueSales = listOf(DueSale("sale", "late", "Late", 99, 4_000)),
        todayEpochDay = 100,
        limit = 3,
    )

    assertEquals(listOf("late", "large"), result.map { it.customer.customer.id })
}

private fun balance(id: String, cents: Long) = CustomerBalanceRow(
    customer = customer(id, "business-1", id.replaceFirstChar { it.uppercase() }),
    balanceCents = cents,
)
```

Use explicit test helpers so the DAO test does not depend on another fixture:

```kotlin
private fun customer(id: String, businessId: String, name: String) = CustomerEntity(
    id, businessId, name, name.lowercase(), null, null, null, null, null,
    true, 1, 1, null,
)

private fun movement(
    id: String,
    businessId: String,
    customerId: String,
    cents: Long,
    occurredAt: Long,
) = AccountMovementEntity(
    id, businessId, customerId, null, null, "CHARGE", cents, "DEBIT",
    occurredAt, "owner", null, null,
)
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.dashboard.DashboardPresentationTest `
  --no-watch-fs --no-daemon --no-configuration-cache --console=plain
```

Expected: compilation failure because `DashboardPresentation` does not exist.

- [ ] **Step 3: Add the read model and scoped DAO query**

Add to `FinancialDao.kt`:

```kotlin
data class DashboardActivityRow(
    val id: String,
    val customerId: String,
    val customerName: String,
    val type: String,
    val direction: String,
    val amountCents: Long,
    val occurredAtEpochMillis: Long,
)

@Query(
    "SELECT movement.id AS id, movement.customerId AS customerId, " +
        "customer.name AS customerName, movement.type AS type, " +
        "movement.direction AS direction, movement.amountCents AS amountCents, " +
        "movement.occurredAtEpochMillis AS occurredAtEpochMillis " +
        "FROM account_movements AS movement " +
        "INNER JOIN customers AS customer ON customer.id = movement.customerId " +
        "AND customer.businessId = movement.businessId " +
        "WHERE movement.businessId = :businessId " +
        "ORDER BY movement.occurredAtEpochMillis DESC, movement.id DESC LIMIT :limit"
)
suspend fun recentDashboardActivity(
    businessId: String,
    limit: Int,
): List<DashboardActivityRow>
```

- [ ] **Step 4: Extend dashboard domain/state without duplicating financial truth**

Create:

```kotlin
data class DashboardActivity(
    val id: String,
    val customerId: String,
    val customerName: String,
    val type: String,
    val direction: String,
    val amountCents: Long,
    val occurredAtEpochMillis: Long,
)
```

In `DashboardRepository.load`, load `dueRows` once, derive `dueTodayCents` by exact epoch-day
equality, map them to `DueSale`, and map at most five DAO activity rows. Add those values to
`DashboardSnapshot` and `DashboardUiState.Content`. Give every new collection/metric a default so
existing previews and positional test fixtures continue compiling:

```kotlin
val dueTodayCents: Long = 0L,
val dueSales: List<DueSale> = emptyList(),
val recentActivity: List<DashboardActivity> = emptyList(),
```

- [ ] **Step 5: Implement the pure critical-customer policy**

```kotlin
internal data class CriticalCustomer(
    val customer: CustomerBalanceRow,
    val overdueCents: Long,
)

internal object DashboardPresentation {
    fun criticalCustomers(
        customers: List<CustomerBalanceRow>,
        dueSales: List<DueSale>,
        todayEpochDay: Long,
        limit: Int = 3,
    ): List<CriticalCustomer> {
        val overdueByCustomer = dueSales
            .filter { it.dueDateEpochDay < todayEpochDay }
            .groupBy { it.customerId }
            .mapValues { (_, rows) -> rows.sumOf { it.outstandingCents } }
        return customers
            .filter { it.balanceCents > 0 }
            .map { CriticalCustomer(it, overdueByCustomer[it.customer.id] ?: 0L) }
            .sortedWith(
                compareByDescending<CriticalCustomer> { it.overdueCents > 0 }
                    .thenByDescending { it.overdueCents }
                    .thenByDescending { it.customer.balanceCents }
            )
            .take(limit)
    }
}
```

- [ ] **Step 6: Run focused unit and DAO tests**

Expected: presentation tests pass and the DAO query returns no cross-business row.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt `
  app/src/main/java/com/cuentivo/app/domain/DashboardRepository.kt `
  app/src/main/java/com/cuentivo/app/ui/dashboard `
  app/src/test/java/com/cuentivo/app/ui/dashboard `
  app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt
git commit -m "feat: provide real dashboard feed data"
```

---

### Task 2: Replace Material defaults with Stitch-faithful tokens and primitives

**Files:**
- Create by copying approved references: `design/stitch/cuentivo-dashboard.png`
- Create by copying approved references: `design/stitch/cuentivo-sale.png`
- Create by copying approved references: `design/stitch/cuentivo-customers.png`
- Create by copying approved references: `design/stitch/cuentivo-customer-detail.png`
- Create: `app/src/main/java/com/cuentivo/app/ui/NeoFintechTokens.kt`
- Rewrite: `app/src/main/java/com/cuentivo/app/ui/NeoFintechComponents.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Type.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/NeoFintechLayoutPolicyTest.kt`

**Interfaces:**
- Produces: `NeoSpacing`, `NeoShapes`, `NeoMotion`, `NeoScreen`, `NeoPanel`, `NeoAction`,
  `NeoMoney`, `NeoSectionHeader`, `NeoEmptyState`, `NeoDirectoryRow`.
- Consumes: `NeoFintechLayoutPolicy.forWidth(widthDp)`.

- [ ] **Step 1: Tighten breakpoint tests before visual code**

First copy the four approved PNGs from the local temporary paths recorded in the design spec into
`design/stitch/`. These committed references are design documentation, not runtime assets.

Assert:

```kotlin
assertEquals(14, NeoFintechLayoutPolicy.forWidth(320).horizontalPaddingDp)
assertEquals(16, NeoFintechLayoutPolicy.forWidth(360).horizontalPaddingDp)
assertEquals(20, NeoFintechLayoutPolicy.forWidth(480).horizontalPaddingDp)
assertEquals(480, NeoFintechLayoutPolicy.forWidth(600).contentMaxWidthDp)
```

- [ ] **Step 2: Run the policy test and verify it fails**

Expected: existing 16/20/24 gutter assertions fail.

- [ ] **Step 3: Define compact tokens**

Use a single source:

```kotlin
internal object NeoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
}

internal object NeoShapes {
    val row = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(14.dp)
    val hero = RoundedCornerShape(18.dp)
}

internal object NeoMotion {
    const val QUICK_MS = 160
    const val STANDARD_MS = 200
    const val PRESSED_SCALE = 0.97f
}
```

Extend `NeoFintechLayout` with `contentMaxWidthDp: Int` and return 480 for widths above the supported
phone range. `NeoScreen` centers that constrained content instead of stretching cards.

- [ ] **Step 4: Replace the animated full-screen backdrop**

`CuentivoTheme` must paint a stable near-black background. Keep the existing
`rememberMotionEnabled()` behavior, but remove the permanent infinite transition. The hero component
may use `Brush.radialGradient` inside its own bounds.

- [ ] **Step 5: Implement compact reusable primitives**

Every primitive must accept `Modifier`, use a visible radius matching Stitch, and keep touch targets
at 48 dp. `NeoMoney` must use `FontFamily.Monospace`; `NeoAction` must animate press scale only when
motion is enabled.

- [ ] **Step 6: Run policy tests and compile Compose**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.NeoFintechLayoutPolicyTest `
  compileDebugKotlin --no-watch-fs --no-daemon --no-configuration-cache --console=plain
```

Expected: all breakpoint tests pass and Compose compiles.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/NeoFintech* `
  app/src/main/java/com/example/organizationlabs/ui/theme `
  app/src/test/java/com/cuentivo/app/ui/NeoFintechLayoutPolicyTest.kt `
  design/stitch
git commit -m "feat: add Stitch-faithful visual system"
```

---

### Task 3: Rebuild the global shell, central sale action, and Account

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/account/AccountSheet.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces:
  - `enum class MainDestination { HOME, CUSTOMERS, PRODUCTS, PAYMENTS }`
  - `NeoFintechShell(destination, onDestination, onNewSale, onOpenAccount, onOpenAlerts, content)`
  - `AccountSheet(tier, onUpgrade, onSignOut, onDeleteAccount, onDismiss)`
- Consumes: current navigation state and existing account callbacks.

- [ ] **Step 1: Write shell semantics tests**

Add tests that assert four navigation labels, one central sale action, and Account reachability:

```kotlin
composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
composeRule.onNodeWithText("Vender").assertDoesNotExist()
composeRule.onNodeWithContentDescription("Cuenta").performClick()
composeRule.onNodeWithText("Plan Gratis").assertIsDisplayed()
```

- [ ] **Step 2: Run the Compose test and verify RED**

Expected: the old `Vender` destination still exists and Account is not reachable from the header.

- [ ] **Step 3: Implement the compact shell**

The bottom bar order is Home, Customers, central `+`, Products, Payments. The central action calls
`onNewSale` without changing the selected destination. Use `animateColorAsState` and
`animateDpAsState` for the active marker; do not animate the whole bar continuously.

- [ ] **Step 4: Move account actions into `AccountSheet`**

Move plan copy, upgrade, sign-out, and delete-account entry out of Dashboard. Preserve both existing
confirmation dialogs and their exact callbacks. The top-left brand uses content description
`Cuenta`; the bell uses `Avisos`.

- [ ] **Step 5: Run the Compose test**

Expected: one sale action, no Vender destination, Account opens, and all four destinations remain
visible.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt `
  app/src/main/java/com/cuentivo/app/ui/account `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: rebuild compact app shell and account"
```

---

### Task 4: Rebuild Dashboard pixel-faithfully

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces:
  - `DashboardScreen(state, customers, onNewSale, onRegisterPayment, onCustomer, onSeeAllCustomers)`
- Consumes: `DashboardUiState.Content`, `DashboardPresentation.criticalCustomers`, and shell header.

- [ ] **Step 1: Add dashboard hierarchy tests**

Assert the reference order using semantics:

```kotlin
composeRule.onNodeWithText("Saldo total por cobrar").assertIsDisplayed()
composeRule.onNodeWithText("Vence hoy").assertIsDisplayed()
composeRule.onNodeWithText("Vencido").assertIsDisplayed()
composeRule.onNodeWithText("Registrar abono").assertIsDisplayed()
composeRule.onNodeWithText("Clientes críticos").assertIsDisplayed()
composeRule.onNodeWithText("Actividad reciente").assertIsDisplayed()
composeRule.onNodeWithText("Tu cuenta").assertDoesNotExist()
```

- [ ] **Step 2: Run the dashboard test and verify RED**

Expected: old dashboard copy and oversized structure fail the assertions.

- [ ] **Step 3: Implement the exact Stitch composition**

Use a compact `LazyColumn`, 16 dp reference gutter, a hero no taller than 142 dp at 360 dp width,
two inline supporting metrics, a 48 dp cyan payment action, a 48 dp outlined sale action, up to three
critical customers, and up to five activity rows. Empty sections render one 56–72 dp row, not a
full-screen welcome.

- [ ] **Step 4: Add action wiring**

`Registrar abono` selects `MainDestination.PAYMENTS`; `Nueva venta` opens the dedicated sale task;
critical customer rows invoke the existing statement/detail callback; `Ver todos` selects Customers.

- [ ] **Step 5: Run Compose tests at normal and forced 320 dp**

Use the AVD with:

```powershell
adb shell wm size 720x1600
adb shell wm density 360
.\gradlew.bat connectedDebugAndroidTest
adb shell wm size reset
adb shell wm density reset
```

Expected: no failed semantics test or clipped primary action.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardScreen.kt `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: rebuild Stitch dashboard"
```

---

### Task 5: Rebuild Customers and Customer Detail

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/customer/CustomersScreen.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/customer/CustomerDetailSheet.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/UiModels.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces:
  - `CustomersScreen(customers, showCreate, onShowCreate, onCreate, onEdit, onArchive, onOpen)`
  - `CustomerDetailSheet(customerName, balanceCents, entries, cancelables, ...)`
- Consumes: existing customer callbacks, `StatementEntry`, and `CancelableItem`.

- [ ] **Step 1: Write customer navigation/detail tests**

Create two customers, navigate to Clientes, assert compact rows and then open one:

```kotlin
composeRule.onNodeWithContentDescription("Clientes").performClick()
composeRule.onNodeWithText("Ana López").performClick()
composeRule.onNodeWithText("Saldo pendiente").assertIsDisplayed()
composeRule.onNodeWithText("Historial").assertIsDisplayed()
composeRule.onNodeWithText("Nueva venta").assertIsDisplayed()
composeRule.onNodeWithText("Registrar abono").assertIsDisplayed()
```

- [ ] **Step 2: Run and verify RED**

Expected: current statement sheet lacks the approved detail hierarchy and direct actions.

- [ ] **Step 3: Extract `CancelableItem` and rebuild Customers**

Rows show initials/photo, name, secondary contact, monospaced balance, and status. Move edit/archive
into a row action sheet so the list retains Stitch density. Preserve settled-balance protection
before archive.

- [ ] **Step 4: Rebuild Customer Detail**

Add callbacks that route direct sale/payment actions with the chosen customer preselected. Keep
sharing and correction behavior. The correction copy must still state that nothing is deleted.

- [ ] **Step 5: Run tests and verify 320 dp**

Expected: Customers, detail actions, share actions, and correction entries remain reachable.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/customer `
  app/src/main/java/com/cuentivo/app/ui/UiModels.kt `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: rebuild customer directory and detail"
```

---

### Task 6: Rebuild Products without losing import or images

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/product/ProductsScreen.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/product/ProductPhoto.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces: compact product directory with create/edit/archive/import/template actions.
- Consumes: existing `ProductEntity`, `ProductImageStore`, URI callbacks, and form callbacks.

- [ ] **Step 1: Add a Products regression test**

Assert search, price, create, import, and edit actions remain present after navigation.

- [ ] **Step 2: Run and verify the test fails against the new expected compact labels**

- [ ] **Step 3: Extract the existing photo picker and product form unchanged in behavior**

Only layout and visual components change. Continue filtering numeric price input through the existing
money parser and keep photo loading on `Dispatchers.IO`.

- [ ] **Step 4: Implement compact product rows**

Use a 44 dp thumbnail, one-line name, monospaced price, optional SKU/category, and a trailing action
button with a 48 dp target.

- [ ] **Step 5: Run the Products test and compile**

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/product `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: rebuild compact product catalog"
```

---

### Task 7: Rebuild New Sale and Payments as focused tasks

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/transaction/NeoFormComponents.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/transaction/NewSaleScreen.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/transaction/PaymentScreen.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces:
  - `NewSaleScreen(initialCustomerId, customers, products, onClose, onCreateSale)`
  - `PaymentScreen(initialCustomerId, customers, onCreatePayment)`
- Consumes: existing `SaleDraft`, `SaleDraftLine`, `pesosToCents`, and callbacks.

- [ ] **Step 1: Add sale/payment flow tests**

Assert central `+` opens sale, detail action preselects its customer, and payment preview uses the
selected customer's balance.

- [ ] **Step 2: Run and verify RED**

- [ ] **Step 3: Extract selector and money fields**

Keep validation and parsing unchanged. Give selected rows compact 52–60 dp panels and use bottom
sheets for long option lists.

- [ ] **Step 4: Implement the Stitch sale hierarchy**

Keep `SaleDraft` as the only total source. The total panel and `Registrar venta` action stay adjacent
at the end of the scroll, with enough bottom inset for the system bar.

- [ ] **Step 5: Implement the payment hierarchy**

Show current balance and `balanceCents - paymentCents` preview. Continue disabling confirmation when
the amount is invalid. Do not introduce a new overpayment rule during the redesign; preserve the
current callback behavior.

- [ ] **Step 6: Run flow tests and existing `SaleDraftTest`/money tests**

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/transaction `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: rebuild sale and payment tasks"
```

---

### Task 8: Finish Authentication, Alerts, Plans, and all empty states

**Files:**
- Rewrite: `app/src/main/java/com/cuentivo/app/ui/auth/AuthenticationScreen.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/AlertsSheet.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/account/SubscriptionSheet.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Preserves public authentication callback signature and all subscription/billing callbacks.

- [ ] **Step 1: Add authentication and no-business semantics tests**

Assert compact brand header, fields, submit, switch-mode action, and a no-business card with one
create action rather than an oversized welcome layout.

- [ ] **Step 2: Run and verify RED**

- [ ] **Step 3: Rebuild Authentication**

Use the real launcher mark, compact 16 dp gutters, a moderate-radius panel, inline validation, and a
single primary action. Do not alter `CredentialsValidator` or Firebase callbacks.

- [ ] **Step 4: Restyle Alerts and subscription**

Use compact rows and functional severity accents. Preserve customer routing and plan copy from
`FeatureAccessManager`.

- [ ] **Step 5: Normalize all empty states**

Use `NeoEmptyState` inside Dashboard sections, Customers, Products, Activity, and statements. Each
state includes at most one title, one short explanation, and one relevant action.

- [ ] **Step 6: Run Compose tests and the debug Firebase TLS smoke manually**

The app must create an account in the Norton-intercepted AVD using the debug build. Do not add a
network-dependent test to the committed suite.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/auth `
  app/src/main/java/com/cuentivo/app/ui/AlertsSheet.kt `
  app/src/main/java/com/cuentivo/app/ui/account `
  app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt `
  app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt
git commit -m "feat: finish Stitch redesign across supporting screens"
```

---

### Task 9: Visual comparison, full regression, and emulator APK

**Files:**
- Modify: `PROJECT_MEMORY.md`
- Do not commit: generated PNGs and APKs.

**Interfaces:**
- Produces: final debug APK for local visual approval.
- Consumes: all preceding screen implementations.

- [ ] **Step 1: Run the full unit suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --rerun-tasks `
  --no-watch-fs --no-daemon --no-configuration-cache --console=plain
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run normal instrumented tests**

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  --no-watch-fs --no-daemon --no-configuration-cache --console=plain
```

Expected: all normal tests pass; the six emulator-suite E2E tests may skip unless explicitly enabled.

- [ ] **Step 3: Run lint and builds**

```powershell
.\gradlew.bat lintDebug assembleDebug assembleRelease `
  --no-watch-fs --no-daemon --no-configuration-cache --console=plain
```

Expected: lint has zero errors; both APK variants build successfully.

- [ ] **Step 4: Verify release isolation**

Inspect the merged release manifest and release APK. They must not contain
`debug_network_security_config`, `usesCleartextTraffic`, or
`norton_web_mail_shield_root`.

- [ ] **Step 5: Capture four widths**

Capture Dashboard, Customers, Customer Detail, and New Sale at 320, 360, 412, and 480 dp. Compare
each against the approved Stitch reference for hierarchy, density, card proportions, type size,
color roles, and navigation.

- [ ] **Step 6: Run the real emulator workflow**

Create a customer and product, record a sale, record a payment, open detail, share the statement,
and reach both sale and payment correction actions. Verify no callback or financial validation
changed.

- [ ] **Step 7: Package the debug APK**

Copy `app/build/outputs/apk/debug/app-debug.apk` to
`Cuentivo-Stitch-Fiel-debug-emulador.apk`. Record size and SHA-256.

- [ ] **Step 8: Update memory and commit**

Record exact test counts, lint result, visual captures, APK details, and the warning that the APK is
debug-only and not for Play.

```powershell
git add PROJECT_MEMORY.md
git commit -m "docs: record faithful Stitch redesign verification"
```
