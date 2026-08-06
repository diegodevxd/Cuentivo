# Claridad y seguridad de correcciones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make account corrections unambiguous, express signed ledger credit accurately, and prevent cancelling a payment that would recreate artificial debt after a sale cancellation.

**Architecture:** Room continues to own all financial state. A read-only Room projection marks payments that have allocations on a cancelled sale; presentation receives that structured fact and disables only the unsafe correction action. A pure balance presentation mapper converts the signed ledger balance into user-facing semantic states while Compose resolves every visible string from resources.

**Tech Stack:** Kotlin, Room, Jetpack Compose Material 3, AndroidX Compose UI tests, JUnit, Firebase Auth.

## Global Constraints

- Money is always `Long` cents. Never use `Double` or `Float`.
- Sales, payments, allocations and movements remain append-only; corrections create compensating movements only.
- Every Room query includes `businessId` when an entity is business-scoped.
- Do not log PII, balances or tokens.
- Do not change `OrganizationLabs`, `main`, `app/google-services.json`, Firestore rules, Firebase remote configuration or Play Console.
- Do not commit: this workspace contains pre-existing uncommitted work and the user did not request a commit.
- Run Gradle with JBR, the Norton trust store, `--no-watch-fs`, `--no-daemon` and `--no-configuration-cache`.
- In PowerShell quote the emulator flag as `'-Pcuentivo.useEmulators=true'`.

---

## File map

- **[MODIFY]** `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt` — projection/query for correction-payment eligibility.
- **[MODIFY]** `app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt` — Room proof that the eligibility projection is business-scoped and detects a cancelled-sale allocation.
- **[CREATE]** `app/src/main/java/com/cuentivo/app/ui/customer/BalancePresentation.kt` — pure signed-balance presentation contract.
- **[CREATE]** `app/src/test/java/com/cuentivo/app/ui/customer/BalancePresentationTest.kt` — unit proof for owed, zero and customer-credit states.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/ui/UiModels.kt` — structured correction candidates without user-facing text assembled in `MainActivity`.
- **[MODIFY]** `app/src/main/java/com/example/organizationlabs/MainActivity.kt` — map Room rows into correction candidates, deterministically number duplicate payments, enforce the blocked callback guard and localize messages in this flow.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt` — carry typed correction callbacks to the detail screen.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/ui/customer/CustomerDetailSheet.kt` — saldo a favor, accessible correction rows and partial-sale confirmation.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/ui/customer/CustomersScreen.kt` and `CustomerDirectoryPresentation.kt` — distinguish customer credit from settled status in the directory.
- **[MODIFY]** `app/src/androidTest/java/com/cuentivo/app/ui/CustomerFlowTest.kt` — Compose coverage for credit, duplicates, blocked payment and partial sale confirmation.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/ui/auth/AuthenticationScreen.kt` — localized validation errors.
- **[MODIFY]** `app/src/main/java/com/cuentivo/app/auth/AuthenticationGateway.kt` and `app/src/main/java/com/example/organizationlabs/MainActivity.kt` — generic auth failures mapped to localized UI messages, without Firebase exception text shown to the user.
- **[MODIFY]** `app/src/androidTest/java/com/cuentivo/app/ui/AuthenticationFlowTest.kt` — English validation/failure rendering coverage.
- **[MODIFY]** `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml` — matching Spanish/English keys used by the tasks below.

### Task 1: Payment correction eligibility projection

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt:42-50,215-217`
- Test: `app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt`

**Interfaces:**
- Produces `data class CancelablePaymentRow(id: String, businessId: String, customerId: String, amountCents: Long, paymentMethod: String, paymentDateEpochDay: Long, reference: String?, createdAtEpochMillis: Long, blockedByCanceledSale: Boolean)`.
- Produces `suspend fun correctionPaymentsForCustomer(businessId: String, customerId: String): List<CancelablePaymentRow>`.
- Later tasks consume `blockedByCanceledSale` only to block the UI; it never changes the payment, allocation or movement.

- [ ] **Step 1: Write the failing Room test**

Add a test that creates `sale-1` for `business-1`, registers `payment-1` for 300 cents allocated to `sale-1`, cancels `sale-1` with the remaining 700-cent `CANCELLATION/CREDIT`, then asserts:

```kotlin
val rows = dao.correctionPaymentsForCustomer("business-1", "customer-1")
assertEquals(listOf("payment-1"), rows.map { it.id })
assertEquals(true, rows.single().blockedByCanceledSale)
```

In the same test, add a `payment-foreign` for `business-2` and assert it is absent. This proves the new read is scoped by `businessId`.

- [ ] **Step 2: Run the targeted test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.data.local.OperationDaoTest' --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: compilation fails because `correctionPaymentsForCustomer` and/or `CancelablePaymentRow` do not exist.

- [ ] **Step 3: Add the projection and query**

Add the projection adjacent to `CancelableSaleRow` and implement this Room query:

```kotlin
@Query(
    "SELECT payment.id AS id, payment.businessId AS businessId, payment.customerId AS customerId, " +
        "payment.amountCents AS amountCents, payment.paymentMethod AS paymentMethod, " +
        "payment.paymentDateEpochDay AS paymentDateEpochDay, payment.reference AS reference, " +
        "payment.createdAtEpochMillis AS createdAtEpochMillis, " +
        "EXISTS(SELECT 1 FROM payment_allocations AS allocation " +
        "INNER JOIN sales AS sale ON sale.id = allocation.saleId " +
        "WHERE allocation.paymentId = payment.id AND sale.businessId = payment.businessId " +
        "AND sale.isCanceled = 1) AS blockedByCanceledSale " +
        "FROM payments AS payment " +
        "WHERE payment.businessId = :businessId AND payment.customerId = :customerId " +
        "AND payment.canceledAtEpochMillis IS NULL " +
        "ORDER BY payment.paymentDateEpochDay DESC, payment.createdAtEpochMillis ASC, payment.id ASC"
)
suspend fun correctionPaymentsForCustomer(
    businessId: String,
    customerId: String,
): List<CancelablePaymentRow>
```

- [ ] **Step 4: Run the targeted test and verify GREEN**

Run the command from Step 2. Expected: the complete `OperationDaoTest` class passes.

- [ ] **Step 5: Preserve workspace state**

Run `git diff -- app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt`; do not commit or stage files.

### Task 2: Pure signed-balance presentation

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/customer/BalancePresentation.kt`
- Test: `app/src/test/java/com/cuentivo/app/ui/customer/BalancePresentationTest.kt`

**Interfaces:**
- Produces `internal enum class BalanceKind { OWED, SETTLED, CUSTOMER_CREDIT }`.
- Produces `internal data class BalancePresentation(val kind: BalanceKind, val amountCents: Long)`.
- Produces `internal object BalancePresenter { fun from(balanceCents: Long): BalancePresentation }`.
- The UI receives a non-negative `amountCents`; Room and `CustomerStatementSummary` retain the signed ledger value.

- [ ] **Step 1: Write failing unit tests**

Create three focused tests:

```kotlin
@Test fun positive_balance_is_money_owed() {
    assertEquals(BalancePresentation(BalanceKind.OWED, 250_00), BalancePresenter.from(250_00))
}

@Test fun zero_balance_is_settled() {
    assertEquals(BalancePresentation(BalanceKind.SETTLED, 0), BalancePresenter.from(0))
}

@Test fun negative_balance_is_customer_credit_with_absolute_amount() {
    assertEquals(BalancePresentation(BalanceKind.CUSTOMER_CREDIT, 250_00), BalancePresenter.from(-250_00))
}
```

- [ ] **Step 2: Run the focused unit test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.ui.customer.BalancePresentationTest" --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: test compilation fails because the balance presentation types do not exist.

- [ ] **Step 3: Implement the minimal pure mapper**

Create the implementation:

```kotlin
package com.cuentivo.app.ui.customer

internal enum class BalanceKind { OWED, SETTLED, CUSTOMER_CREDIT }

internal data class BalancePresentation(
    val kind: BalanceKind,
    val amountCents: Long,
)

internal object BalancePresenter {
    fun from(balanceCents: Long): BalancePresentation = when {
        balanceCents > 0L -> BalancePresentation(BalanceKind.OWED, balanceCents)
        balanceCents < 0L -> BalancePresentation(BalanceKind.CUSTOMER_CREDIT, -balanceCents)
        else -> BalancePresentation(BalanceKind.SETTLED, 0L)
    }
}
```

- [ ] **Step 4: Run the focused unit test and verify GREEN**

Run the command from Step 2. Expected: all three tests pass.

- [ ] **Step 5: Preserve workspace state**

Run `git diff -- app/src/main/java/com/cuentivo/app/ui/customer/BalancePresentation.kt app/src/test/java/com/cuentivo/app/ui/customer/BalancePresentationTest.kt`; do not commit or stage files.

### Task 3: Correction UI, customer credit and deterministic payment labels

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/UiModels.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt:300-350,633-683`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/customer/CustomerDetailSheet.kt:94-238,263-345,465-569`
- Modify: `app/src/main/java/com/cuentivo/app/ui/customer/CustomerDirectoryPresentation.kt` and `CustomersScreen.kt`
- Modify: `app/src/main/res/values/strings.xml:178-217`
- Modify: `app/src/main/res/values-en/strings.xml:178-217`
- Test: `app/src/androidTest/java/com/cuentivo/app/ui/CustomerFlowTest.kt`

**Interfaces:**
- Replace `CancelableItem` with `sealed interface CorrectionCandidate`:

```kotlin
sealed interface CorrectionCandidate {
    val id: String

    data class Sale(
        override val id: String,
        val saleNumber: String,
        val saleDateEpochDay: Long,
        val totalCents: Long,
        val activePaidCents: Long,
        val outstandingCents: Long,
    ) : CorrectionCandidate

    data class Payment(
        override val id: String,
        val amountCents: Long,
        val paymentDateEpochDay: Long,
        val createdAtEpochMillis: Long,
        val paymentMethod: String,
        val reference: String?,
        val ordinal: Int,
        val groupSize: Int,
        val blockedByCanceledSale: Boolean,
    ) : CorrectionCandidate
}
```

- `CustomerDetailSheet` accepts `List<CorrectionCandidate>`, `onCancelSale: (CorrectionCandidate.Sale) -> Unit` and `onCancelPayment: (CorrectionCandidate.Payment) -> Unit`.
- `MainActivity` obtains payments only through `correctionPaymentsForCustomer`; it repeats the block check before calling `cancelPaymentIdempotently`.

- [ ] **Step 1: Write failing Compose tests**

Extend `CustomerFlowTest` with four cases using a harness statement customer:

1. `balanceCents = -20_000` renders “Saldo a favor” and `$200.00`, and does not render `-$200.00`.
2. Two `CorrectionCandidate.Payment` values with same amount/date and ordinals `1/2`, `2/2` render distinct accessible labels; tapping the second and confirming records only `payment:p2`.
3. `CorrectionCandidate.Payment(blockedByCanceledSale = true)` renders its reason and has no cancel action.
4. `CorrectionCandidate.Sale(totalCents = 100_000, activePaidCents = 30_000, outstandingCents = 70_000)` opens a dialog containing all three amounts and text that payments stay registered.

- [ ] **Step 2: Run the focused Compose test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CustomerFlowTest' --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: test compilation fails because `CorrectionCandidate` and the credit/block/partial-sale UI do not exist.

- [ ] **Step 3: Add structured candidates and create deterministic labels**

Replace the string-oriented `CancelableItem` model with `CorrectionCandidate`. In `loadStatement`:

1. map each `CancelableSaleRow` into `CorrectionCandidate.Sale`, with `activePaidCents = totalCents - outstandingCents`;
2. load `correctionPaymentsForCustomer`;
3. group rows by `(amountCents, paymentDateEpochDay)`;
4. sort each group by `createdAtEpochMillis` then `id`;
5. assign `ordinal` from one and `groupSize` from the sorted group size;
6. map each original row into `CorrectionCandidate.Payment`;
7. never use a UUID in a visible resource string.

Before writing a payment refund, repeat `correctionPaymentsForCustomer` and return the localized blocked message if the matching row has `blockedByCanceledSale == true`. This is defense in depth; the disabled UI alone is insufficient.

- [ ] **Step 4: Implement the approved visual behavior**

In `CustomerDetailSheet`:

1. derive `BalancePresenter.from(customer.balanceCents)` for the hero, totals and running balance;
2. render resource-based “Saldo pendiente”, “Saldo en cero” or “Saldo a favor” with `presentation.amountCents`;
3. render payment corrections with date, time, method when available and `Abono %1$d de %2$d`;
4. render blocked payment corrections without a `Cancelar` button and show `detail_payment_cancel_blocked`;
5. render a sale confirmation with total, active payments and outstanding amount; show the partial explanation when `activePaidCents > 0L` and retain a clear full-sale variant when it is zero;
6. change directory treatment of negative balances to a credit state rather than `Al día`.

Add matching keys to both string files. Keep `Money.format` unchanged; pass only the non-negative presentation amount to it.

- [ ] **Step 5: Run the focused Compose test and verify GREEN**

Run the command from Step 2. Expected: all `CustomerFlowTest` cases pass.

- [ ] **Step 6: Preserve workspace state**

Run `git diff -- app/src/main/java/com/cuentivo/app/ui app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/androidTest/java/com/cuentivo/app/ui/CustomerFlowTest.kt`; do not commit or stage files.

### Task 4: Localized authentication validation and failures

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/auth/AuthenticationScreen.kt:102-159`
- Modify: `app/src/main/java/com/cuentivo/app/auth/AuthenticationGateway.kt:34-51`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt:187-207`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Test: `app/src/androidTest/java/com/cuentivo/app/ui/AuthenticationFlowTest.kt`

**Interfaces:**
- `AuthenticationScreen` obtains validation text exclusively through `stringResource`.
- `MainActivity` renders fixed app-owned auth failure resources; it must not surface raw Firebase exception text.
- `FirebaseAuthenticationGateway` can retain `Result<String>` but may not create fallback exceptions with Spanish text.

- [ ] **Step 1: Write failing English UI tests**

In `AuthenticationFlowTest`, set the composition locale to English and assert the visible messages are:

```kotlin
"Enter a valid email."
"Password must be at least 8 characters."
"Passwords do not match."
```

Add a callback failure harness assertion that the UI shows `"Could not sign in."` rather than a thrown Firebase/Spanish exception message.

- [ ] **Step 2: Run the focused authentication test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.AuthenticationFlowTest' --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: assertions fail because the three validation strings are currently Spanish literals and fallback failures are not localized.

- [ ] **Step 3: Localize validation and generic failure messages**

Add these paired resource keys:

```xml
<string name="auth_invalid_email">Escribe un correo válido.</string>
<string name="auth_password_too_short">La contraseña debe tener al menos 8 caracteres.</string>
<string name="auth_password_mismatch">Las contraseñas no coinciden.</string>
<string name="auth_sign_in_failed">No fue posible iniciar sesión.</string>
<string name="auth_sign_up_failed">No fue posible crear la cuenta.</string>
```

Use their English equivalents in `values-en`. Replace the three `FieldError` literals with `stringResource`. Replace gateway-only Spanish fallback exception messages with empty `IllegalStateException()` fallbacks, then have `MainActivity` map every failed sign-in/sign-up result to the fixed app resource appropriate to the requested operation.

- [ ] **Step 4: Run the focused authentication test and verify GREEN**

Run the command from Step 2. Expected: `AuthenticationFlowTest` passes in English and Spanish test coverage remains valid.

- [ ] **Step 5: Verify resource parity and preserve workspace state**

Run:

```powershell
(Select-String -Path app/src/main/res/values/strings.xml -Pattern '<string name=').Count
(Select-String -Path app/src/main/res/values-en/strings.xml -Pattern '<string name=').Count
```

Expected: equal counts. Then run `git diff -- app/src/main/java/com/cuentivo/app/ui/auth app/src/main/java/com/cuentivo/app/auth app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/res`; do not commit or stage files.

### Task 5: Integrated verification with the open emulator account

**Files:**
- Modify: `PROJECT_MEMORY.md` only after every command and manual result below is observed.

**Interfaces:**
- Consumes the outputs of Tasks 1–4.
- Produces a factual record: passed commands, manual observations and remaining gaps.

- [ ] **Step 1: Run focused suites**

Run the three targeted commands from Tasks 1, 3 and 4. Expected: all selected classes pass.

- [ ] **Step 2: Run the unit suite**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: exit code zero.

- [ ] **Step 3: Build and install the isolated debug APK**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat installDebug '-Pcuentivo.useEmulators=true' --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Expected: `BUILD SUCCESSFUL` and the AVD keeps using the local Auth/Firestore Emulator Suite.

- [ ] **Step 4: Execute the manual acceptance checks with fictitious data only**

Confirm in the open test account:

1. an overpayment displays `Saldo a favor` with a positive amount and no negative-debt wording;
2. duplicate same-day payments show distinct ordinal labels and only the selected one reaches its confirmation;
3. a partial sale cancellation displays original total, payments retained and the exact remanent compensation;
4. a payment linked to a cancelled sale displays the blocked reason and has no cancel action;
5. Account → English keeps pesos formatted as Mexican currency and authentication validation errors use English.

- [ ] **Step 5: Record observed evidence**

Append only facts actually observed to `PROJECT_MEMORY.md`: exact command results, manual checks passed/failed and any remaining blocker. Do not state a release is ready unless all five checks above passed.
