# CuentivoAI Local Camera Capture Design

## Scope

The debug-only operational chat gains a real camera option in addition to the
existing gallery picker.  It captures a photo of a notebook, ticket, or note,
runs the existing on-device OCR path, and places recognized text in the chat
composer for editing.  This increment ends there.

## Design

Use the existing `CameraCaptureStore` and its already-declared `FileProvider`
to issue a unique, cache-backed JPEG URI to Android's `TakePicture` contract.
After a successful capture, validate the file using the existing AI capture
MIME, byte, and dimension policy, then invoke the existing local
`AiOcrEngine`.  The temporary file is deleted after OCR, on cancellation,
capture-launch failure, close, and reset.  No image is persisted, uploaded,
logged, or sent to Gemini.

The gallery path remains unchanged.  Camera and gallery are disabled while a
request is sending so there is one active chat operation.  A failed or
cancelled camera attempt only shows a local, non-sensitive error and leaves
the current draft/transcript intact.

## Safety boundaries

- No camera permission is requested: `TakePicture` delegates capture to an
  installed camera app through a temporary content URI.
- No network, Firebase, Gemini SDK, Room, Firestore, financial resolver,
  confirmation or execute action is introduced.
- The feature is debug-only because the chat entry itself is gated by the
  debug transport; release continues to expose no AI entry.
- All financial text remains a candidate draft only.  It cannot write sales,
  payments, balances, inventory, or sync state.

## Acceptance criteria

1. Camera selection opens the system camera and processes only a successful
   capture through local OCR.
2. Cancellation, launch failure, invalid image, OCR failure, reset and close
   leave no `ai-capture` temporary file and do not mutate the transcript.
3. A successful OCR result is editable and can be sent as a normal next chat
   turn; camera use never sends a photo to the gateway.
4. JVM tests cover source safety and relevant store behaviour; debug build,
   lint, and diff check pass before physical testing.
