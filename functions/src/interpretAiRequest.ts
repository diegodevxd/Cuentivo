import {
  createNotConfiguredResponse,
  validateInterpretAiRequest,
  type InterpretAiResponse,
} from "./contract";

export type CallableLikeRequest = Readonly<{ auth?: Readonly<{ uid: string }>; data: unknown }>;

export type CallableFailure = Readonly<{ code: "unauthenticated" | "invalid-argument" }>;

/**
 * Pure adapter boundary: requires a Firebase identity, then validates the payload with the
 * allow-list contract. Every rejection uses the fixed callable-safe code; the valid path
 * returns only the not-configured response. No storage, no logging, no external IO.
 */
export function createInterpretAiHandler(
  request: CallableLikeRequest,
): InterpretAiResponse | CallableFailure {
  if (!request.auth?.uid) {
    return { code: "unauthenticated" };
  }
  const validation = validateInterpretAiRequest(request.data);
  if (!validation.ok) {
    return { code: "invalid-argument" };
  }
  return createNotConfiguredResponse(validation.value);
}
