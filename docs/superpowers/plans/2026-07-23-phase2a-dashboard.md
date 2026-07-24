# Fase 2a: navegación y dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar la plantilla de Compose por navegación Material 3 y un dashboard offline que lea métricas reales de Room.

**Architecture:** `DashboardViewModel` recibe una fuente de negocio local y un repositorio de métricas. La actividad crea la base Room y compone `CuentivoApp`; la UI solo consume `DashboardUiState`, sin ejecutar SQL ni recalcular dinero.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Lifecycle ViewModel, Room y pruebas JUnit/Compose.

## Global Constraints

- Montos como `Long` en centavos; formato `es-MX` solo en UI.
- Todas las lecturas financieras se filtran por `businessId`.
- No añadir Firebase, red, WorkManager, autenticación ni datos de demostración.
- Modo claro/oscuro y Material 3; rojo solo para errores, ámbar para vencimientos.

---

### Task 1: Fuente local de negocio y métricas del dashboard

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/data/local/BusinessDao.kt`
- Create: `app/src/main/java/com/cuentivo/app/domain/DashboardRepository.kt`
- Modify: `app/src/main/java/com/cuentivo/app/data/local/CuentivoDatabase.kt`
- Modify: `app/src/main/java/com/cuentivo/app/data/local/FinancialDao.kt`
- Test: `app/src/androidTest/java/com/cuentivo/app/data/local/OperationDaoTest.kt`

**Interfaces:**
- Produces `suspend fun BusinessDao.first(): BusinessEntity?`.
- Produces `suspend fun DashboardRepository.load(): DashboardSnapshot?` where `DashboardSnapshot` contains `businessName`, `receivableCents`, `soldCents`, `collectedCents`, `overdueCents`, and `customersWithDebt`.

- [ ] **Step 1: Write the failing Room test**

```kotlin
assertEquals(1, database.financialDao().customersWithDebtCount("business-1"))
assertEquals("Mi negocio", database.businessDao().first()?.name)
```

- [ ] **Step 2: Verify it fails**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat connectedDebugAndroidTest`

Expected: compilation failure because `businessDao` and `customersWithDebtCount` do not exist.

- [ ] **Step 3: Implement the smallest DAO surface**

```kotlin
@Query("SELECT * FROM businesses ORDER BY createdAtEpochMillis LIMIT 1")
suspend fun first(): BusinessEntity?

@Query("SELECT COUNT(*) FROM (SELECT customerId FROM account_movements WHERE businessId = :businessId GROUP BY customerId HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END) > 0)")
suspend fun customersWithDebtCount(businessId: String): Long
```

- [ ] **Step 4: Add `DashboardRepository.load`**

```kotlin
suspend fun load(): DashboardSnapshot? {
    val business = businessDao.first() ?: return null
    val today = LocalDate.now().toEpochDay()
    val start = LocalDate.now().withDayOfMonth(1).toEpochDay()
    return DashboardSnapshot(
        business.name,
        financialDao.businessReceivableCents(business.id),
        financialDao.salesTotalCents(business.id, start, today),
        financialDao.collectedTotalCents(business.id, start, today),
        financialDao.overdueBalanceCents(business.id, today),
        financialDao.customersWithDebtCount(business.id),
    )
}
```

- [ ] **Step 5: Run the Room test and commit**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

Commit: `git add app/src && git commit -m "feat: add local dashboard metrics"`

### Task 2: Estado y ViewModel del dashboard

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardViewModel.kt`
- Create: `app/src/test/java/com/cuentivo/app/ui/dashboard/DashboardViewModelTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes `DashboardRepository.load(): DashboardSnapshot?`.
- Produces `StateFlow<DashboardUiState>` with `Loading`, `NoBusiness`, and `Content` states.

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
assertEquals(DashboardUiState.NoBusiness, viewModel.state.value)
assertEquals(12_500L, (viewModel.state.value as DashboardUiState.Content).receivableCents)
```

- [ ] **Step 2: Verify the unit-test compile failure**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat testDebugUnitTest`

Expected: unresolved `DashboardViewModel` and `DashboardUiState`.

- [ ] **Step 3: Add Lifecycle dependencies and state**

```kotlin
sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data object NoBusiness : DashboardUiState
    data class Content(val businessName: String, val receivableCents: Long, val soldCents: Long, val collectedCents: Long, val overdueCents: Long, val customersWithDebt: Long) : DashboardUiState
}
```

`DashboardViewModel` loads once in `viewModelScope`, turns `null` into `NoBusiness`, and maps a snapshot to `Content`.

- [ ] **Step 4: Run tests and commit**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

Commit: `git add app/build.gradle.kts gradle/libs.versions.toml app/src && git commit -m "feat: expose dashboard ui state"`

### Task 3: Tema, navegación y pantallas Compose

**Files:**
- Create: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Create: `app/src/main/java/com/cuentivo/app/ui/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/organizationlabs/ui/theme/Theme.kt`
- Test: `app/src/androidTest/java/com/cuentivo/app/ui/CuentivoAppTest.kt`

**Interfaces:**
- Consumes `DashboardUiState` and `onNewSale: () -> Unit`.
- Produces `@Composable fun CuentivoApp(viewModel: DashboardViewModel)` with destinations `HOME`, `CUSTOMERS`, `NEW_SALE`, `MOVEMENTS`, `MORE`.

- [ ] **Step 1: Write failing Compose tests**

```kotlin
composeRule.onNodeWithText("Nueva venta").assertExists()
composeRule.onNodeWithText("Clientes").performClick()
composeRule.onNodeWithText("Clientes próximamente").assertExists()
```

- [ ] **Step 2: Verify the instrumentation-test compile failure**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat compileDebugAndroidTestKotlin`

Expected: unresolved `CuentivoApp`.

- [ ] **Step 3: Implement the composables**

```kotlin
enum class AppDestination(val label: String) { HOME("Inicio"), CUSTOMERS("Clientes"), NEW_SALE("Nueva venta"), MOVEMENTS("Movimientos"), MORE("Más") }
```

Use `NavigationBar`, `NavigationBarItem`, a prominent `FilledTonalButton` for `Nueva venta`, and a dashboard that renders either an explanatory empty state or the two alert cards and sold/collected metric cards. Use `NumberFormat.getCurrencyInstance(Locale("es", "MX"))` on cents divided by `100` using `BigDecimal`.

- [ ] **Step 4: Wire the activity**

```kotlin
val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db").build()
setContent { CuentivoTheme { CuentivoApp(DashboardViewModel(DashboardRepository(database.businessDao(), database.financialDao()))) } }
```

Replace the current `Greeting` composable entirely.

- [ ] **Step 5: Run Compose tests and commit**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

Commit: `git add app/src && git commit -m "feat: add dashboard navigation"`

### Task 4: Final verification

**Files:** None beyond corrections required by failures.

- [ ] **Step 1: Run all tests**

Run: `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; .\\gradlew.bat testDebugUnitTest; .\\gradlew.bat connectedDebugAndroidTest`

Expected: both commands return `BUILD SUCCESSFUL`.

- [ ] **Step 2: Inspect the final diff**

Run: `git diff main...HEAD --check; git status --short`

Expected: no whitespace errors and no untracked build artifacts.

- [ ] **Step 3: Commit any verification corrections**

Run: `git add app/src app/build.gradle.kts gradle/libs.versions.toml && git commit -m "test: verify phase 2a dashboard"`

Expected: commit only if the verification required source corrections.
