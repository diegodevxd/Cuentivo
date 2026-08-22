export type InterpretAiRequest = Readonly<{
  contractVersion: 1;
  requestId: string;
  action: "interpret_text";
  text: string;
}>;

export type SafeInterpretation =
  | Readonly<{ kind: "requires_clarification"; code: "NEEDS_CONTEXT" | "AMBIGUOUS" }>
  | Readonly<{ kind: "unsupported"; code: "UNSUPPORTED" | "UNSAFE" }>;

export type InterpretAiResponse =
  | Readonly<{
      contractVersion: 1;
      requestId: string;
      status: "AI_INTERPRETED" | "AI_UNAVAILABLE";
      interpretation: SafeInterpretation | null;
    }>
  | Readonly<{
      contractVersion: 1;
      requestId: string;
      status: "AI_NOT_CONFIGURED";
    }>;

export type ContractValidation =
  | Readonly<{ ok: true; value: InterpretAiRequest }>
  | Readonly<{ ok: false; reason: "invalid-request" }>;

const INTERPRET_KEYS = ["contractVersion", "requestId", "action", "text"] as const;

export const MAX_INTERPRET_TEXT_LENGTH = 1000;

const UUID_V1_TO_V5 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Accepts exactly the v1 interpret allow-list: a plain non-null object whose only keys are
 * contractVersion, requestId, action and text, with version 1, action "interpret_text", a
 * 36-character UUID and non-blank text of at most MAX_INTERPRET_TEXT_LENGTH UTF-16 code units
 * after trim. The original text is returned unchanged. Every failure returns the same fixed
 * rejection. Never throws, normalizes, generates ids, stores or logs.
 */
export function validateInterpretAiRequest(input: unknown): ContractValidation {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return { ok: false, reason: "invalid-request" };
  }
  const record = input as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== INTERPRET_KEYS.length || !INTERPRET_KEYS.every((key) => key in record)) {
    return { ok: false, reason: "invalid-request" };
  }
  if (record["contractVersion"] !== 1 || record["action"] !== "interpret_text") {
    return { ok: false, reason: "invalid-request" };
  }
  const requestId = record["requestId"];
  if (
    typeof requestId !== "string" ||
    requestId.length !== 36 ||
    !UUID_V1_TO_V5.test(requestId)
  ) {
    return { ok: false, reason: "invalid-request" };
  }
  const text = record["text"];
  if (typeof text !== "string") {
    return { ok: false, reason: "invalid-request" };
  }
  const trimmed = text.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_INTERPRET_TEXT_LENGTH) {
    return { ok: false, reason: "invalid-request" };
  }
  return {
    ok: true,
    value: { contractVersion: 1, requestId, action: "interpret_text", text },
  };
}

export function createNotConfiguredResponse(request: InterpretAiRequest): Readonly<{
  contractVersion: 1;
  requestId: string;
  status: "AI_NOT_CONFIGURED";
}> {
  return {
    contractVersion: 1,
    requestId: request.requestId,
    status: "AI_NOT_CONFIGURED",
  };
}

export { parseSafeInterpretation } from "./ai/interpretation.js";

import type { AiAuthorizationDecision, AiProviderId } from "./ai/controlPlane";

export type AuthorizeAiActionRequest = Readonly<{
  contractVersion: 1;
  requestId: string;
  action: "authorize_text";
}>;

export type AuthorizeAiActionResponse = Readonly<{
  contractVersion: 1;
  requestId: string;
  decision: AiAuthorizationDecision;
  provider: AiProviderId | null;
  fallbackUsed: false;
}>;

export type AuthorizeAiActionValidation =
  | Readonly<{ ok: true; value: AuthorizeAiActionRequest }>
  | Readonly<{ ok: false; reason: "invalid-request" }>;

const AUTHORIZE_ACTION = "authorize_text";

const AUTHORIZE_KEYS = ["contractVersion", "requestId", "action"] as const;

/**
 * Same allow-list validation pattern as the interpret contract: a plain non-null object whose only
 * keys are contractVersion, requestId and action, with version 1, action "authorize_text" and a
 * 36-character UUID. Every failure returns the same fixed rejection; never throws, never logs.
 */
export function validateAuthorizeAiActionRequest(input: unknown): AuthorizeAiActionValidation {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return { ok: false, reason: "invalid-request" };
  }
  const record = input as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== AUTHORIZE_KEYS.length || !AUTHORIZE_KEYS.every((key) => key in record)) {
    return { ok: false, reason: "invalid-request" };
  }
  if (record["contractVersion"] !== 1 || record["action"] !== AUTHORIZE_ACTION) {
    return { ok: false, reason: "invalid-request" };
  }
  const requestId = record["requestId"];
  if (
    typeof requestId !== "string" ||
    requestId.length !== 36 ||
    !UUID_V1_TO_V5.test(requestId)
  ) {
    return { ok: false, reason: "invalid-request" };
  }
  return {
    ok: true,
    value: { contractVersion: 1, requestId, action: AUTHORIZE_ACTION },
  };
}
