# CuentivoAI Operational Chat Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the debug text form with a temporary, business-only chat shell while preserving the non-executable AI safety boundary.

**Architecture:** Keep `AiDebugLabMachine` as the sole pure state machine and extend it with immutable chat messages. `AiDebugLabScreen` renders that state inside its existing fullscreen opaque `Dialog`; it can capture/edit text and render safe typed proposals but cannot confirm/save an operation. `CuentivoApp` owns the entry state and passes an AI-header callback into `NeoFintechShell`, so the entry lives beside language rather than over dashboard content.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, existing Firebase debug transport, ML Kit OCR local, JUnit JVM tests.

## Global Constraints

- Gemini 2.5 Flash-Lite remains server-side only; no key, SDK, prompt or direct network call from Android.
- No Room/DAO/Firestore/financial callback imports in the chat package; no confirm/save/execute affordance.
- Proposals are candidates only: centavos, totals, balances and entity resolution remain domain concerns for later increments.
- The chat is session-only and must be discarded on close; late transport/OCR results are invalidated by generation before close.
- Existing local OCR capture, MIME/byte/dimension guards and temporary-file cleanup remain unchanged.
- Cuentivo Free/Pro remains fully usable when the debug transport is unavailable; release bridge stays null and has no visible AI entry.
- Do not modify Firebase Functions, Firestore rules, billing, Room schema, app version, application id, secrets or untracked `docs/superpowers/handoffs/`.

## File Structure

- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`: immutable chat-message types and pure state transitions.
- Modify `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`: fullscreen chat transcript, bottom composer, camera/gallery access reuse and typed proposal/status cards.
- Modify `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`: remove overlay entry and pass the optional header action into the shell.
- Modify `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt`: render optional AI icon next to language only when debug provides it.
- Modify `app/src/main/res/values/strings.xml` and `app/src/main/res/values-es/strings.xml`: business-chat copy/accessibility labels.
- Modify `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`: pure transcript transition tests.
- Modify `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`: no-execution and temporary-session source guards.
- Modify `app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt`: header placement source contract.

---

### Task 1: Make chat transcript state pure and non-executable

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt`

**Interfaces:**
- Consumes: existing `AiDebugLabModel`, `AiDebugLabState`, `AiProposal`, `CallableAiProposalResult`.
- Produces: `AiChatMessage` and `AiDebugLabModel.messages: List<AiChatMessage>` for the screen.

- [ ] **Step 1: Write failing state-machine tests**

```kotlin
@Test
fun `submitted text becomes one user message and a pending assistant message`() {
    val outcome = AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "María abonó 150"))
    val submitted = assertIs<AiDebugLabSubmitOutcome.Submitted>(outcome).model

    assertEquals(
        listOf(
            AiChatMessage.UserText("María abonó 150"),
            AiChatMessage.AssistantPending,
        ),
        submitted.messages,
    )
}

@Test
fun `unavailable result replaces pending message without creating an executable action`() {
    val submitted = assertIs<AiDebugLabSubmitOutcome.Submitted>(
        AiDebugLabMachine.onSubmit(AiDebugLabModel(text = "hola")),
    )
    val result = AiDebugLabMachine.onResult(
        submitted.model,
        CallableAiProposalResult.Unavailable,
        submitted.generation,
    )

    assertEquals(listOf(AiChatMessage.UserText("hola"), AiChatMessage.AssistantUnavailable), result.messages)
    assertFalse(result.messages.any { it is AiChatMessage.Confirmation })
}
```

- [ ] **Step 2: Run the focused test and observe RED**

Run:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.ai.AiDebugLabMachineTest --no-daemon --no-configuration-cache --no-watch-fs
```
Expected: compilation/test failure because `AiChatMessage` and `messages` do not yet exist.

- [ ] **Step 3: Add the minimal immutable transcript model and transitions**

```kotlin
sealed interface AiChatMessage {
    data class UserText(val text: String) : AiChatMessage
    data object AssistantPending : AiChatMessage
    data object AssistantUnavailable : AiChatMessage
    data class AssistantClarification(val reason: ClarificationReason, val slots: Set<UnresolvedSlot>) : AiChatMessage
    data class AssistantProposal(val proposal: AiProposal) : AiChatMessage
}

data class AiDebugLabModel(
    val state: AiDebugLabState = AiDebugLabState.TextEditing,
    val text: String = "",
    val messages: List<AiChatMessage> = emptyList(),
    internal val generation: Long = 0L,
)
```

`onSubmit` appends trimmed `UserText` and `AssistantPending`; `onResult` replaces only the final pending message with `AssistantUnavailable`, `AssistantClarification`, or `AssistantProposal`.
`onReset` clears draft text and transcript. `onCancel` advances generation but does not persist the transcript anywhere. Do not add a confirmation message type, operation callback or domain dependency.

- [ ] **Step 4: Run the focused tests and observe GREEN**

Run the command from Step 2. Expected: all `AiDebugLabMachineTest` tests pass.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabMachineTest.kt
git commit -m "feat(ai-ui): model temporary operational chat"
```

### Task 2: Render a fullscreen business chat shell using the safe transcript

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`

**Interfaces:**
- Consumes: `AiDebugLabModel.messages`, `AiChatMessage`, existing `AiDebugLabMachine`, existing picker/OCR bridge.
- Produces: composable-only transcript and composer; no callback other than `AiDebugTransport.interpret`.

- [ ] **Step 1: Write failing UI/source guards**

```kotlin
@Test
fun `chat UI renders a transcript and bottom composer but no confirmation action`() {
    val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
    assertTrue(source.contains("LazyColumn"))
    assertTrue(source.contains("AiChatMessage.UserText"))
    assertTrue(source.contains("ai_debug_chat_camera"))
    assertTrue(source.contains("ai_debug_chat_gallery"))
    assertFalse(source.contains("Confirmar operación"))
    assertFalse(source.contains("onConfirm"))
}
```

- [ ] **Step 2: Run the focused guard test and observe RED**

Run:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.ai.AiDebugLabGuardsTest --no-daemon --no-configuration-cache --no-watch-fs
```
Expected: failure because transcript/composer identifiers do not yet exist.

- [ ] **Step 3: Render the transcript and composer**

Use `LazyColumn` for `model.messages`; user entries render as a user bubble and assistant states as neutral cards. `AssistantProposal` reuses candidate-only `ProposalPreviewContent`; it must state that it is a draft and has no confirmation control. Place an `OutlinedTextField` and text-send button in a bottom-aligned composer. `Nuevo` clears the temporary session.

Keep existing `ModalBottomSheet` but present two distinct actions:

```kotlin
TextButton(onClick = { takePhotoLauncher.launch(...) }) {
    Text(stringResource(R.string.ai_debug_chat_camera))
}
TextButton(onClick = { pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
    Text(stringResource(R.string.ai_debug_chat_gallery))
}
```

Camera may initially show the explicit unavailable/local-capture message if the capture contract is not implemented in this task; it must not silently route through gallery or request an unrelated permission. Preserve gallery OCR exactly as it exists. Do not add `RECORD_AUDIO` or voice yet.

- [ ] **Step 4: Run focused tests and manually inspect debug build**

Run the Task 2 focused guard test. Then build with `assembleDebug`, install on the disposable USB device only if requested, and verify: no dashboard under the dialog; a text submission produces user + unavailable assistant messages; `Nuevo` clears the transcript; gallery still inserts OCR text; camera never sends data to network.

- [ ] **Step 5: Commit Task 2**

```powershell
git add -- app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt
git commit -m "feat(ai-ui): render non-executable operational chat"
```

### Task 3: Move AI entry beside language and keep it debug-only

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt`
- Modify: `app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`

**Interfaces:**
- Consumes: `debugAiTransport(): AiDebugTransport?` and existing header callbacks.
- Produces: optional `onOpenAi: (() -> Unit)? = null` parameter on `NeoFintechShell`.

- [ ] **Step 1: Write failing header source tests**

```kotlin
@Test
fun `shell renders optional AI action before language action`() {
    val source = File("src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt").readText()
    assertTrue(source.contains("onOpenAi: (() -> Unit)? = null"))
    assertTrue(source.indexOf("onOpenAi?.let") < source.indexOf("onClick = onOpenLanguage"))
}

@Test
fun `app passes AI header callback only while debug transport exists`() {
    val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()
    assertTrue(source.contains("onOpenAi = debugAiLabTransport?.let"))
    assertFalse(source.contains("AiDebugLabEntryButton"))
}
```

- [ ] **Step 2: Run focused tests and observe RED**

Run:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.NeoFintechShellSourceContractTest --tests com.cuentivo.app.ui.ai.AiDebugLabGuardsTest --no-daemon --no-configuration-cache --no-watch-fs
```
Expected: failure because the shell has no optional AI header callback and `CuentivoApp` still renders the floating entry.

- [ ] **Step 3: Implement the optional header action**

Add the nullable callback to `NeoFintechShell`. In the top `Row`, render an `IconButton` with a localized content description and a standard existing Material icon before the language icon only when non-null. In `CuentivoApp`, pass:

```kotlin
onOpenAi = debugAiLabTransport?.let { { showAiDebugLab = true } },
```

Remove the `AiDebugLabEntryButton` branch completely. Keep `AiDebugLabScreen` gated by the same non-null transport so release still emits no entry and no dialog.

- [ ] **Step 4: Execute full gate and verify release isolation**

Run:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug --no-daemon --no-configuration-cache --no-watch-fs --rerun-tasks
git diff --check
```

Expected: exit code 0. Independently inspect release source factory: it returns null. Install debug APK on authorized disposable device and verify AI is beside language, opens chat, and closes back to a normal Cuentivo screen.

- [ ] **Step 5: Commit Task 3 and update documentation**

```powershell
git add -- app/src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt app/src/test/java/com/cuentivo/app/ui/NeoFintechShellSourceContractTest.kt app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt
git commit -m "feat(ai-ui): place debug AI action in header"
```

Add a source-of-truth changelog entry that records this shell as debug-only/non-executable and states camera capture, voice, domain resolution and financial confirmation remain pending.

## Plan Self-Review

- Spec coverage: entry placement, temporal history, business-only output rendering, gallery/camera affordance, safety boundary and debug/release isolation map to Tasks 1–3. Voice, financial confirmation, fallback media, billing and deployment are intentionally excluded and remain future increments.
- Placeholder scan: no TBD/TODO or implicit error handling; camera explicitly fails locally in this increment until its capture contract is separately implemented.
- Type consistency: `AiChatMessage`, `AiDebugLabModel.messages`, and `onOpenAi` are defined in Task 1/3 before their consumers; no financial execution interface is introduced.

