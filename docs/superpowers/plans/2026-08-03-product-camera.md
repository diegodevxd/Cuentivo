# Product Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe camera capture next to the existing product-photo gallery flow.

**Architecture:** A narrow camera-temp store creates and deletes files exposed solely by a `FileProvider`; `ProductPhoto` offers a choice sheet and sends a confirmed camera URI through the existing private image store and draft policy.

**Tech Stack:** Kotlin, Activity Result `TakePicture`, Android `FileProvider`, Jetpack Compose, JUnit and Compose Android tests.

## Global Constraints

- Do not request broad storage permission or retain external gallery URIs.
- Keep product `imageUri` as an internal filename and preserve the existing `ProductPhotoDraftPolicy` semantics.
- Camera cancellation/failure must not delete the original or current draft image.
- No financial, Firebase, Play Console or signing changes.
- Run Gradle with JBR, the Norton trust store, `--no-daemon`, `--no-configuration-cache`, and `--no-watch-fs`.

---

### Task 1: Camera temporary URI boundary

**Files:** `AndroidManifest.xml`, new FileProvider paths XML, new camera-temp store, unit tests.

- [x] Write failing tests for create/delete temporary camera files and cleanup on cancelled capture.
- [x] Add a FileProvider limited to the camera-temp private subdirectory.
- [x] Implement the minimum temp URI/file API.
- [x] Run the focused tests and confirm they pass.

### Task 2: Product photo source selector

**Files:** `ProductPhoto.kt`, product UI tests.

- [x] Write a failing Compose test that opens the source selector and finds Camera and Gallery actions.
- [x] Add a compact source selector with accessible 48 dp actions.
- [x] Launch `TakePicture` with a URI from Task 1; on true, import via `ProductImageStore`; on false/cancel, delete only the temp.
- [x] Run focused product flow tests and confirm they pass.

### Task 3: Integrated verification

- [x] Run unit tests, focused product instrumentation, lint and debug build.
- [ ] Verify camera cancel and gallery selection preserve the current image on the AVD.
