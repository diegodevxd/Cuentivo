# Ciclo de confiabilidad y cobro Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir observabilidad de crashes, recordatorios locales configurables de cobros vencidos y una puerta de publicación/verificación física reproducible.

**Architecture:** Los recordatorios se calculan exclusivamente desde Room mediante una política de dominio pura. Un worker local de WorkManager consulta esa política y publica una única notificación diaria; un deep link explícito abre Clientes con el filtro de deuda. Las preferencias son locales, se cancelan al cerrar/eliminar sesión y no cambian Firestore, Billing ni el libro mayor.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, Android notifications, SharedPreferences, Firebase Crashlytics, JUnit, Compose UI tests.

## Global Constraints

- `minSdk = 24`, `targetSdk = 36`; pedir `POST_NOTIFICATIONS` únicamente en API 33+ y sólo después de que el usuario active el ajuste.
- Valor inicial: recordatorios apagados y hora 09:00 local. Sin red, Firebase ni permisos de contactos.
- Nunca enviar WhatsApp, SMS, correo, push remoto ni mensajes automáticos a clientes.
- No tocar `firestore.rules`, datos remotos, esquema Room, cálculo financiero, Billing ni App Check enforcement.
- No enviar a Crashlytics PII, montos/saldos, ids de usuario/negocio, correos, teléfonos, tokens ni cuerpos de documentos.
- Todo texto nuevo debe existir en `values/strings.xml` y `values-en/strings.xml`; no introducir literales visibles.
- Conserva el árbol de trabajo existente: no reviertas ni formatees cambios ajenos.

---

### Task 1: Política pura y preferencias de recordatorio

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/domain/OverdueReminderPolicy.kt`
- Create: `app/src/main/java/com/cuentivo/app/settings/CollectionReminderPreferences.kt`
- Create: `app/src/test/java/com/cuentivo/app/domain/OverdueReminderPolicyTest.kt`
- Create: `app/src/test/java/com/cuentivo/app/settings/CollectionReminderPreferencesTest.kt`

**Interfaces:**
- Consumes: `DueSale(saleId, customerId, customerName, dueDateEpochDay, outstandingCents)`.
- Produces: `OverdueReminderPolicy.summarize(dueSales: List<DueSale>, today: LocalDate): OverdueReminder?`; `CollectionReminderPreferences(context).enabled`, `.hourOfDay`, `.update(enabled, hourOfDay)`.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test fun `future and settled rows never produce a reminder`() {
    assertNull(OverdueReminderPolicy.summarize(listOf(future, settled), LocalDate.of(2026, 8, 12)))
}
@Test fun `one overdue sale names the customer and amount`() {
    assertEquals(OverdueReminder.Single("Ana", 85_000),
        OverdueReminderPolicy.summarize(listOf(overdueAna), LocalDate.of(2026, 8, 12)))
}
@Test fun `many overdue sales are grouped with total and count`() {
    assertEquals(OverdueReminder.Grouped(2, 125_000),
        OverdueReminderPolicy.summarize(listOf(overdueAna, overdueLuis), LocalDate.of(2026, 8, 12)))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.domain.OverdueReminderPolicyTest"`

Expected: failure because the policy does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
sealed interface OverdueReminder {
    data class Single(val customerName: String, val outstandingCents: Long) : OverdueReminder
    data class Grouped(val count: Int, val totalCents: Long) : OverdueReminder
}
object OverdueReminderPolicy {
    fun summarize(dueSales: List<DueSale>, today: LocalDate): OverdueReminder? {
        val overdue = dueSales.filter { it.dueDateEpochDay < today.toEpochDay() && it.outstandingCents > 0 }
        return when (overdue.size) {
            0 -> null
            1 -> OverdueReminder.Single(overdue.single().customerName, overdue.single().outstandingCents)
            else -> OverdueReminder.Grouped(overdue.size, overdue.sumOf { it.outstandingCents })
        }
    }
}
```

Store only `enabled` and a validated hour (`0..23`) in a private preference file; defaults are false/9 and no debt/customer data enters preferences.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.domain.OverdueReminderPolicyTest" --tests "com.cuentivo.app.settings.CollectionReminderPreferencesTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/domain/OverdueReminderPolicy.kt app/src/main/java/com/cuentivo/app/settings/CollectionReminderPreferences.kt app/src/test/java/com/cuentivo/app/domain/OverdueReminderPolicyTest.kt app/src/test/java/com/cuentivo/app/settings/CollectionReminderPreferencesTest.kt
git commit -m "feat: define local overdue reminder policy"
```

### Task 2: Worker, notification channel, schedule and cancellation

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/notifications/CollectionReminderWorker.kt`
- Create: `app/src/main/java/com/cuentivo/app/notifications/CollectionReminderScheduler.kt`
- Create: `app/src/test/java/com/cuentivo/app/notifications/CollectionReminderSchedulerTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `FinancialDao.salesWithDueDate(businessId)`, preferences, policy.
- Produces: `CollectionReminderScheduler.sync(context)`, `.cancel(context)`, and `CollectionReminderWorker : CoroutineWorker`.

- [ ] **Step 1: Write failing scheduler tests**

```kotlin
@Test fun `disabled preference cancels unique reminder work`() { /* fake enqueuer observes cancel */ }
@Test fun `enabled preference schedules one unique daily job`() { /* observes work name and 24 hour period */ }
@Test fun `initial delay never precedes configured local hour`() { /* 09:00 today or tomorrow */ }
```

Use a tiny injected `ReminderWorkEnqueuer` interface, so timing and cancellation are JVM-tested.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.notifications.CollectionReminderSchedulerTest"`

Expected: failure because the scheduler does not exist.

- [ ] **Step 3: Implement**

- Schedule one `PeriodicWorkRequest` named `cuentivo-overdue-reminder`, 24-hour interval, with next initial delay from local wall time and selected hour.
- Worker returns success when disabled, no signed-in owner/business, or no overdue summary; it opens Room with existing migrations, maps `DueSaleRow` to `DueSale`, and always closes Room.
- Create low-priority `collection_reminders` channel on API 26+.
- Build immutable pending intent for MainActivity action `com.cuentivo.app.OPEN_OVERDUE_CUSTOMERS`; do not put PII or amounts in extras.
- Single debt body uses customer name + `Money.format`; grouped debt shows count/total. All copy is localized.
- Declare `android.permission.POST_NOTIFICATIONS` in the manifest.

- [ ] **Step 4: Verify**

Run: `./gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.domain.OverdueReminderPolicyTest" --tests "com.cuentivo.app.notifications.CollectionReminderSchedulerTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/notifications app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/test/java/com/cuentivo/app/notifications
git commit -m "feat: schedule local overdue collection reminders"
```

### Task 3: Ajuste accesible y navegación desde notificación

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/account/AccountSheet.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/customer/CustomersScreen.kt`
- Modify: strings in both locales
- Modify: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Produces an AccountSheet reminder callback, `CustomersScreen(initialFilter: CustomerFilter?)`, and a one-shot Activity intent handler.

- [ ] **Step 1: Write failing UI tests**

```kotlin
@Test fun reminder_setting_is_accessible_and_disabled_by_default() { /* switch semantics */ }
@Test fun reminder_deep_link_opens_customers_with_debt_filter() { /* launch action, assert selected debt chip */ }
```

- [ ] **Step 2: Confirm test/compile failure**

Run: `./gradlew.bat compileDebugAndroidTestKotlin -Pcuentivo.useEmulators=true`

Expected: failure until callbacks and semantics exist.

- [ ] **Step 3: Implement**

Place a switch, explanation and hour picker above sign-out/delete. Every control is at least 48dp and resource-labelled. On enabling, ask notification permission only on API 33+; grant persists + schedules, denial retains disabled setting + localized explanation. On change, reschedule; on disable, cancel. Handle the explicit action in both `onCreate` and `onNewIntent`, consume once and navigate Customers to the existing debt filter. Cancel in sign-out and successful deletion. No customer or balance must cross the Intent boundary.

- [ ] **Step 4: Verify**

Run: `./gradlew.bat connectedDebugAndroidTest -Pcuentivo.useEmulators=true -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.CuentivoAppTest`

Expected: `BUILD SUCCESSFUL`. If unavailable, record the environment blocker; do not silently skip.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/cuentivo/app/ui/account/AccountSheet.kt app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/java/com/cuentivo/app/ui/customer/CustomersScreen.kt app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt app/src/main/res/values
git commit -m "feat: configure and open overdue reminders"
```

### Task 4: Crashlytics privacy-safe integration and release playbook

**Files:**
- Modify: `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Create: `app/src/main/java/com/cuentivo/app/observability/CrashReporter.kt`
- Create: `app/src/test/java/com/cuentivo/app/observability/CrashReporterContractTest.kt`
- Create: `docs/checklist-publicacion.md`, `docs/prueba-restore-pro-sin-red.md`

- [ ] **Step 1: Write failing contract test**

```kotlin
@Test fun reporter_only_accepts_fixed_technical_events() {
    assertEquals(setOf(AUTH_FAILURE, CLOUD_RESTORE_FAILURE, BILLING_FAILURE), CrashEvent.entries.toSet())
}
```

The production API accepts only that enum, never arbitrary strings, values or exceptions with message content.

- [ ] **Step 2: Confirm failure**

Run: `./gradlew.bat testDebugUnitTest --tests "com.cuentivo.app.observability.CrashReporterContractTest"`

Expected: missing type failure.

- [ ] **Step 3: Implement**

Add official Crashlytics Gradle plugin/SDK using current Firebase BOM. Implement no-op debug reporter and release delegate with only fixed event names. Replace only existing technical failure paths that cannot include PII. Do not add document values, user identifiers, messages, financial values or Firebase exception text. Write checklist with version/signature/tests/AAB/OAuth-App Check/Play upload steps. Write restore manual test that uses disposable test data and explicitly forbids deleting production tester data.

- [ ] **Step 4: Verify**

Run: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease`

Expected: `BUILD SUCCESSFUL`; inspect release dependency configuration and debug behavior.

- [ ] **Step 5: Commit**

```powershell
git add build.gradle.kts app/build.gradle.kts app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/java/com/cuentivo/app/observability app/src/test/java/com/cuentivo/app/observability docs/checklist-publicacion.md docs/prueba-restore-pro-sin-red.md
git commit -m "feat: add privacy safe crash reporting and release playbook"
```

### Task 5: Auditor gate and physical device acceptance

**Files:**
- Modify: `PROJECT_MEMORY.md` only after all results are real.

- [ ] **Step 1: Audit scope**

Run: `git diff --check; git diff -- app/src/main/java app/src/main/res app/build.gradle.kts build.gradle.kts firestore.rules`

Expected: no whitespace error; no Firestore/schema/financial-algorithm change; no PII forwarded to Crashlytics.

- [ ] **Step 2: Run gates**

Run: `./gradlew.bat testDebugUnitTest --rerun-tasks; ./gradlew.bat lintDebug; ./gradlew.bat compileDebugAndroidTestKotlin; ./gradlew.bat assembleRelease; ./gradlew.bat bundleRelease`

Expected: all `BUILD SUCCESSFUL`.

- [ ] **Step 3: Physical phone test**

Install the debug APK. On disposable data: enable permission/settings; create overdue debt; trigger worker through debug-safe path; confirm exactly one alert; tap → Clientes/Con deuda; pay/cancel → rerun worker → no alert; disable → scheduled job canceled. Never use real tester customer data.

- [ ] **Step 4: Restore network-interruption test**

Follow `docs/prueba-restore-pro-sin-red.md` on disposable Pro data. Confirm that a failed server download preserves existing Room data and shows a recoverable failure.

- [ ] **Step 5: Record evidence**

Update PROJECT_MEMORY with exact test count, device/API, permission outcome, restore result, AAB path/version/hash and unresolved risks. Do not claim completion without the physical result.
