# CuentivoAI Local Camera Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Each task must use TDD and stop for a Codex audit.

**Goal:** Allow the debug operational chat to capture a local photo and insert its on-device OCR text into the editable composer.

**Architecture:** `AiDebugLabScreen` owns a `CameraCaptureStore` and an Android `TakePicture` launcher. It hands only a temporary JPEG URI to the camera app, validates and OCRs the resulting local file with the existing AI capture policy/engine, and always removes the file. The existing gallery OCR path is unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Activity Result API, existing `FileProvider`, `CameraCaptureStore`, ML Kit local OCR, JUnit JVM tests.

## Global Constraints

- No Gemini key, Gemini SDK, direct network, Firebase, Functions, Room, Firestore, billing, financial execution, schema, migration or version change.
- No camera permission or new dependency; use the existing external-camera `TakePicture` contract and `FileProvider`.
- Never persist/upload/log an image; cleanup is required on every terminal path.
- Keep the operational chat non-executable and session-only; release remains without an AI entry.
- Do not touch untracked `docs/superpowers/handoffs/`.

### Task 1: Add safe camera capture to the debug chat

**Files:**
- Modify: `app/src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt`
- Modify: `app/src/test/java/com/cuentivo/app/ui/ai/AiDebugLabGuardsTest.kt`

**Consumes:** `CameraCaptureStore(context)`, `ActivityResultContracts.TakePicture`, `AiCapturePolicy`, `AiCaptureStore`, `AiOcrEngine`, and `AiDebugLabMachine.onOcrResult`.

**Produces:** a camera sheet action that locally turns a successful capture into editable OCR text, with no other system effects.

- [ ] Write source guards first asserting use of `CameraCaptureStore`, `TakePicture`, `createCaptureUri`, cleanup after callback, and no `CAMERA` permission/network/Gemini client in `AiDebugLabScreen`.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ui.ai.AiDebugLabGuardsTest --no-daemon --no-configuration-cache --no-watch-fs`; confirm RED.
- [ ] In `AiDebugLabScreen`, remember `CameraCaptureStore(context)`, `pendingCaptureUri`, and an `ActivityResultContracts.TakePicture` launcher.  On camera selection: clear only the prior capture error, create a URI under `runCatching`, store it, and launch.  If creation/launch fails, delete the issued URI and show the existing local error.
- [ ] On camera result: clear `pendingCaptureUri`; if success, process its corresponding file on `Dispatchers.IO` with fixed JPEG MIME, existing byte/dimension checks and `ocrEngine?.recognize`; always delete it in `finally`; pass the text to `onOcrResult` using the current generation token.  If cancelled, delete it and preserve draft/transcript.
- [ ] Include camera-store cleanup in close/reset and call `deleteStale` before issuing a new URI.  Keep all capture actions disabled while `Sending`.
- [ ] Run the focused guard test GREEN, then `testDebugUnitTest assembleDebug lintDebug` and `git diff --check`.
- [ ] Commit only listed source/test files as `feat(ai-ui): add local camera OCR capture`.

### Codex gate

Inspect the committed diff and verify the release source-set still returns a null AI transport.  On the authorized disposable USB phone verify: open AI next to language; Camera opens the system app; cancel returns to chat; a notebook photo yields editable text; gallery remains functional; close/reopen removes the session.  Do not test real financial writes because none must exist.
