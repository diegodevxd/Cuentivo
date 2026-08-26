export type InterpretAiRequest = Readonly<{
  contractVersion: 2;
  requestId: string;
  action: "interpret_text_proposal";
  text: string;
}>;

export type AuthTokenClaims = Readonly<{
  firebase?: Readonly<{ sign_in_provider?: string; [key: string]: unknown }>;
  provider_id?: string;
  [key: string]: unknown;
}>;

export type CallableAuth = Readonly<{
  uid: string;
  token?: AuthTokenClaims;
}>;

/**
 * Checks whether an authenticated caller is using an anonymous Firebase Auth session.
 *
 * In Firebase ID tokens, anonymous authentication is indicated by
 * `token.firebase.sign_in_provider === "anonymous"` (or `token.provider_id === "anonymous"`).
 */
export function isAnonymousUser(auth: CallableAuth | undefined): boolean {
  if (!auth || !auth.token) {
    return false;
  }
  const provider = auth.token.firebase?.sign_in_provider ?? auth.token.provider_id;
  return provider === "anonymous";
}

export type ClarificationReason =
  | "AMBIGUOUS_CUSTOMER"
  | "AMBIGUOUS_PRODUCT"
  | "MISSING_CUSTOMER"
  | "MISSING_PRODUCT"
  | "MISSING_AMOUNT"
  | "UNKNOWN_INPUT";

export type UnresolvedSlot = "CUSTOMER" | "PRODUCT" | "AMOUNT" | "QUANTITY" | "PERIOD";

export type BusinessQueryType =
  | "CUSTOMER_BALANCE"
  | "CUSTOMERS_WITH_DEBT"
  | "SALES_FOR_PERIOD";

export type SafeDateCandidate = Readonly<{ year: number; month: number; day: number }>;

export type SafePeriodCandidate = Readonly<{
  startYear: number;
  startMonth: number;
  startDay: number;
  endYear: number;
  endMonth: number;
  endDay: number;
}>;

export type SafeSaleLine = Readonly<{
  productName: string;
  quantity: number;
  unitPriceCents: string;
}>;

/**
 * Typed, non-financial proposal (wire money is always canonical decimal centavo STRINGS, never
 * JSON numbers). No entity ids, computed balances, totals, SQL, Firestore paths, tool calls or
 * executable instructions. Only "kind" plus exact per-kind fields are allowed.
 */
export type SafeAiProposal =
  | Readonly<{
      kind: "requires_clarification";
      reason: ClarificationReason;
      unresolvedSlots: readonly UnresolvedSlot[];
    }>
  | Readonly<{
      kind: "create_sale";
      customerName: string;
      lines: readonly SafeSaleLine[];
      declaredInitialPaymentCents: string;
      promisedPaymentDate?: SafeDateCandidate;
    }>
  | Readonly<{ kind: "record_payment"; customerName: string; declaredAmountCents: string }>
  | Readonly<{
      kind: "business_query";
      queryType: BusinessQueryType;
      customerName?: string;
      period?: SafePeriodCandidate;
    }>;

export type InterpretAiResponse = Readonly<{
  contractVersion: 2;
  requestId: string;
  status: "AI_PROPOSAL" | "AI_UNAVAILABLE";
  proposal: SafeAiProposal | null;
}>;

export type ContractValidation =
  | Readonly<{ ok: true; value: InterpretAiRequest }>
  | Readonly<{ ok: false; reason: "invalid-request" }>;

const INTERPRET_KEYS = ["contractVersion", "requestId", "action", "text"] as const;

export const MAX_INTERPRET_TEXT_LENGTH = 1000;

const UUID_V1_TO_V5 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Accepts exactly the v2 interpret_text_proposal allow-list: a plain non-null object whose only
 * keys are contractVersion, requestId, action and text, with version 2, action
 * "interpret_text_proposal", a 36-character UUID and non-blank text of at most
 * MAX_INTERPRET_TEXT_LENGTH UTF-16 code units after trim. The original text is returned
 * unchanged. Every failure returns the same fixed rejection. Never throws, normalizes, generates
 * ids, stores or logs.
 */
export function validateInterpretAiRequest(input: unknown): ContractValidation {
  if (
    typeof input !== "object" ||
    input === null ||
    Array.isArray(input) ||
    Object.getOwnPropertySymbols(input).length > 0
  ) {
    return { ok: false, reason: "invalid-request" };
  }
  const record = input as Record<string, unknown>;
  const keys = Object.keys(record);
  if (
    keys.length !== INTERPRET_KEYS.length ||
    !INTERPRET_KEYS.every((key) => Object.prototype.hasOwnProperty.call(record, key))
  ) {
    return { ok: false, reason: "invalid-request" };
  }
  if (record["contractVersion"] !== 2 || record["action"] !== "interpret_text_proposal") {
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
    value: { contractVersion: 2, requestId, action: "interpret_text_proposal", text },
  };
}

export function createNotConfiguredResponse(request: InterpretAiRequest): InterpretAiResponse {
  return {
    contractVersion: 2,
    requestId: request.requestId,
    status: "AI_UNAVAILABLE",
    proposal: null,
  };
}

export { parseSafeAiProposal } from "./ai/interpretation.js";

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
  if (
    typeof input !== "object" ||
    input === null ||
    Array.isArray(input) ||
    Object.getOwnPropertySymbols(input).length > 0
  ) {
    return { ok: false, reason: "invalid-request" };
  }
  const record = input as Record<string, unknown>;
  const keys = Object.keys(record);
  if (
    keys.length !== AUTHORIZE_KEYS.length ||
    !AUTHORIZE_KEYS.every((key) => Object.prototype.hasOwnProperty.call(record, key))
  ) {
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
