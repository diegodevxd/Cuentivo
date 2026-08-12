# Google Sign-In Linking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google sign-in to Cuentivo while preserving existing email/password accounts and their Firebase UID.

**Architecture:** Credential Manager obtains a Google ID token; a focused authentication gateway converts it into a Firebase credential. The host chooses sign-in or link based on the current Firebase session and maps provider conflicts to safe, localized UI states. Firebase project configuration enables the provider and carries correct signing fingerprints.

**Tech Stack:** Kotlin, Jetpack Compose, Firebase Auth, AndroidX Credential Manager, Google ID, Firebase CLI/MCP.

## Global Constraints

- Preserve an existing Firebase UID; never migrate Firestore records or silently merge two UIDs.
- Automatic Google login is allowed for new users; existing manual accounts must reauthenticate once before linking Google.
- Use Credential Manager; do not use legacy Google Sign-In APIs.
- Keep Email/Password enabled and Firebase's one-account-per-email protection enabled.
- Never display raw Firebase/OAuth exceptions.
- No Firestore rules, data, billing or sale-flow changes.
- Use the Firebase project `organitazionlabs`; register debug and release/Play SHA-1 and SHA-256 fingerprints.

---

### Task 1: Provision Firebase Google authentication

**Files:**

- Modify: `app/google-services.json` only after programmatic SDK-config retrieval.
- Modify: `firebase.json` only if the CLI's generated Auth config requires it.

**Interfaces:**

- Produces an enabled Google provider and updated Android OAuth client configuration consumed by Task 2.
- Consumes project `organitazionlabs`, Android package `com.cuentivo.app`, debug/release signing certificate fingerprints.

- [ ] **Step 1: Verify Firebase MCP/CLI and active project**

Run after restarting Codex so the 120-second MCP timeout is active:

```powershell
npx -y firebase-tools@latest login:list
npx -y firebase-tools@latest use organitazionlabs
npx -y firebase-tools@latest apps:list ANDROID --project organitazionlabs
```

Expected: authenticated account, active project `organitazionlabs`, and an Android app for `com.cuentivo.app`.

- [ ] **Step 2: Obtain fingerprints without printing keystore secrets**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat signingReport
```

Record SHA-1 and SHA-256 for debug plus the configured release/Play signing identity. Register them in the existing Android app through Firebase MCP/CLI.

- [ ] **Step 3: Enable Google and refresh config**

Enable Google in Authentication for `organitazionlabs`, keeping Email/Password enabled and one-account-per-email protection enabled. Retrieve configuration, never manually copy OAuth IDs:

```powershell
npx -y firebase-tools@latest apps:sdkconfig ANDROID <android-app-id> --project organitazionlabs | Set-Content -Encoding utf8 app\google-services.json
```

- [ ] **Step 4: Verify the generated web client resource**

Run: `rg -n "default_web_client_id" app/build/generated/res/google-services/debug/values/values.xml`

Expected: exactly one nonempty `default_web_client_id`.

- [ ] **Step 5: Commit Firebase configuration**

```powershell
git add app/google-services.json firebase.json
git commit -m "chore(firebase): enable Google authentication"
```

### Task 2: Add a testable Google authentication gateway

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/cuentivo/app/auth/AuthenticationGateway.kt`
- Create: `app/src/main/java/com/cuentivo/app/auth/GoogleAuthenticationResult.kt`
- Test: `app/src/test/java/com/cuentivo/app/auth/GoogleAuthenticationResultTest.kt`

**Interfaces:**

- Produces `sealed interface GoogleAuthenticationResult { data class SignedIn(val userId: String); data object Cancelled; data object ManualAccountNeedsPassword; data object Failed }`.
- Adds `fun signInWithGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit)` and `fun linkGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit)`.
- Adds `fun clearGoogleCredentialState(onComplete: () -> Unit)`, called by sign-out.
- Task 3 consumes these exact methods.

- [ ] **Step 1: Write failing result-mapping tests**

```kotlin
@Test fun account_exists_with_different_credential_requires_manual_password() {
    assertEquals(
        GoogleAuthenticationResult.ManualAccountNeedsPassword,
        GoogleAuthenticationResult.fromFirebaseErrorCode("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"),
    )
}

@Test fun cancellation_is_not_an_error_message() {
    assertEquals(GoogleAuthenticationResult.Cancelled, GoogleAuthenticationResult.fromCredentialFailure(isCancellation = true))
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.auth.GoogleAuthenticationResultTest`

Expected: compile failure because result mapper does not exist.

- [ ] **Step 3: Add modern Android dependencies and minimal mapper**

Add version-catalog entries and module dependencies:

```kotlin
implementation("androidx.credentials:credentials:1.3.0")
implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
```

Map only cancellation, account-exists-different-credential, success with non-null UID, and generic failure. The gateway requests `GetGoogleIdOption` using `R.string.default_web_client_id`, exchanges the returned ID token using `GoogleAuthProvider.getCredential`, then uses `signInWithCredential` or current-user `linkWithCredential`.

- [ ] **Step 4: Run green**

Run: `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.auth.GoogleAuthenticationResultTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/cuentivo/app/auth app/src/test/java/com/cuentivo/app/auth/GoogleAuthenticationResultTest.kt
git commit -m "feat(auth): add Google credential gateway"
```

### Task 3: Connect the host and accessible authentication UI

**Files:**

- Modify: `app/src/main/java/com/example/organizationlabs/MainActivity.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/auth/AuthenticationScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Test: `app/src/androidTest/java/com/cuentivo/app/ui/AuthenticationFlowTest.kt`

**Interfaces:**

- Consumes Task 2's `GoogleAuthenticationResult` and gateway methods.
- Produces `AuthenticationScreen(onGoogleSignIn: () -> Unit)` and a localized manual-link conflict message.
- Keeps current email/password callbacks and validation unchanged.

- [ ] **Step 1: Write failing Compose tests**

```kotlin
@Test fun Google_action_is_accessible_and_calls_its_callback() {
    composeRule.setContent { Harness(onGoogleSignIn = { calls += "google" }) }
    composeRule.onNodeWithContentDescription("Continuar con Google").performClick()
    composeRule.runOnIdle { assertEquals(listOf("google"), calls) }
}

@Test fun Google_action_is_disabled_while_authentication_is_working() {
    composeRule.setContent { Harness(isWorking = true) }
    composeRule.onNodeWithContentDescription("Continuar con Google").assertIsNotEnabled()
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.AuthenticationFlowTest`

Expected: compilation failure for the missing Google callback or test failure for a missing control.

- [ ] **Step 3: Implement host state and UI**

Add an outlined 48 dp-or-larger Google action under the primary manual action, separated with localized “o”. It shares `isWorking`; cancellation clears only work state. On `SignedIn`, set `authenticatedUserId`; on `ManualAccountNeedsPassword`, show the approved explanation; on `Failed`, show localized generic Google failure. Call `clearGoogleCredentialState` after every sign-out path.

- [ ] **Step 4: Run focused UI tests**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.AuthenticationFlowTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify translations**

Run a parity check that every `auth_google*` key exists in both XML files. Expected: no missing keys.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/organizationlabs/MainActivity.kt app/src/main/java/com/cuentivo/app/ui/auth/AuthenticationScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/androidTest/java/com/cuentivo/app/ui/AuthenticationFlowTest.kt
git commit -m "feat(auth): offer Google sign-in"
```

### Task 4: Verify sign-in, linking, and release readiness

**Files:**

- Test only; add no production code unless a failing focused test identifies a defect.

- [ ] **Step 1: Run full relevant verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest lintDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run instrumented Auth tests on the AVD only**

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cuentivo.app.ui.AuthenticationFlowTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual physical-device acceptance with a disposable Google account**

Verify: new Google user; already signed-in manual user linking Google; signed-out manual conflict shows password-first copy; cancel leaves no session change. Do not use testers' data or delete a user.

- [ ] **Step 4: Build AAB only after manual Firebase setup is verified**

Run: `./gradlew.bat bundleRelease`

Expected: `BUILD SUCCESSFUL`; do not upload or publish without a separate user request.

## Plan Self-Review

- Tasks 1-4 cover provider/configuration, safe gateway results, UI/host integration, and tests/device/release evidence.
- The only automatic path is new Google sign-in and linking from an authenticated manual session; conflicts never silently merge UIDs.
- All names used across tasks are defined in Task 2 and consumed unchanged in Task 3.

