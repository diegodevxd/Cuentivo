export type InterpretAiRequest = Readonly<{
  contractVersion: 1;
  requestId: string;
  action: "interpret_text";
}>;

export type InterpretAiResponse = Readonly<{
  contractVersion: 1;
  requestId: string;
  status: "AI_NOT_CONFIGURED";
}>;

export type ContractValidation =
  | Readonly<{ ok: true; value: InterpretAiRequest }>
  | Readonly<{ ok: false; reason: "invalid-request" }>;

const ALLOWED_KEYS = ["contractVersion", "requestId", "action"] as const;

const UUID_V1_TO_V5 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Accepts exactly the v1 allow-list: a plain non-null object whose only keys are
 * contractVersion, requestId and action, with version 1, action "interpret_text" and a
 * 36-character UUID (versions 1-5). Every failure returns the same fixed rejection.
 * Never throws, normalizes, generates ids, stores or logs.
 */
export function validateInterpretAiRequest(input: unknown): ContractValidation {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return { ok: false, reason: "invalid-request" };
  }
  const record = input as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== ALLOWED_KEYS.length || !ALLOWED_KEYS.every((key) => key in record)) {
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
  return {
    ok: true,
    value: { contractVersion: 1, requestId, action: "interpret_text" },
  };
}

export function createNotConfiguredResponse(request: InterpretAiRequest): InterpretAiResponse {
  return {
    contractVersion: 1,
    requestId: request.requestId,
    status: "AI_NOT_CONFIGURED",
  };
}
