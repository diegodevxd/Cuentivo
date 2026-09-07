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
 * Fail-closed security design:
 * In a real Firebase Callable runtime, authenticated callers always carry decoded token claims
 * (`request.auth.token`). A caller object that carries `uid` but lacks token claims cannot prove
 * a verified non-anonymous identity, so it fails closed as anonymous to prevent unverified
 * callers from bypassing AI gateway controls, quota limits, and costs.
 *
 * In Firebase ID tokens, anonymous authentication is indicated by
 * `token.firebase.sign_in_provider === "anonymous"` (or `token.provider_id === "anonymous"`).
 */
export function isAnonymousUser(auth: CallableAuth | undefined): boolean {
  if (!auth) {
    return false;
  }
  if (!auth.token) {
    // Fail-closed: unverified token claims cannot bypass the anonymous gate.
    return true;
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

export type InterpretAiStatus =
  | "AI_PROPOSAL"
  | "AI_UNAVAILABLE"
  | "AI_QUOTA_EXHAUSTED"
  | "AI_RATE_LIMITED"
  | "AI_DISABLED"
  | "AI_ENTITLEMENT_REQUIRED";

export type InterpretAiResponse = Readonly<{
  contractVersion: 2;
  requestId: string;
  status: InterpretAiStatus;
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

// ---------------------------------------------------------------------------
// Contract v3: extract_ledger_page
// ---------------------------------------------------------------------------

export type ExtractLedgerPageImageMimeType = "image/jpeg" | "image/png" | "image/webp";

export type ExtractLedgerPageRequest = Readonly<{
  contractVersion: 3 | 4;
  requestId: string;
  action: "extract_ledger_page";
  imageBase64: string;
  imageMimeType: ExtractLedgerPageImageMimeType;
}>;

export type ProposedLedgerKind = "PRIOR_DEBT" | "SALE" | "PAYMENT";

export type UncertainLedgerField = "AMOUNT" | "CUSTOMER" | "DATE" | "KIND";

export type UnreadableLedgerField = "AMOUNT" | "CUSTOMER" | "DATE" | "KIND";

export type LedgerRegion = Readonly<{
  x: number;
  y: number;
  width: number;
  height: number;
}>;

export type LedgerSource = "EXPLICIT" | "INHERITED_PREVIOUS_ROW" | "PAGE_DATE" | "INFERRED";

export type LedgerWarning =
  | "ITEMS_TOTAL_MISMATCH"
  | "INHERITED_CUSTOMER"
  | "LOW_CONFIDENCE_CUSTOMER"
  | "LOW_CONFIDENCE_CONCEPT"
  | "LOW_CONFIDENCE_AMOUNT"
  | "AMBIGUOUS_KIND"
  | "AMBIGUOUS_SEPARATOR"
  | "MULTIPLE_MOVEMENTS_IN_ROW"
  | "NOT_A_MOVEMENT";

export type LedgerConfidence = Readonly<{
  customer?: number;
  concept?: number;
  amount?: number;
  date?: number;
  kind?: number;
}>;

export type LedgerSources = Readonly<{
  customer?: LedgerSource;
  date?: LedgerSource;
}>;

export type LedgerItem = Readonly<{
  description: string;
  quantity?: number;
  subtotalCents?: string;
}>;

export type LedgerOperationV3 = Readonly<{
  lineIndex: number;
  proposedKind?: ProposedLedgerKind;
  customerName?: string;
  amountCents?: string;
  description?: string;
  date?: SafeDateCandidate;
  uncertainFields: readonly UncertainLedgerField[];
  unreadableFields: readonly UnreadableLedgerField[];
  region?: LedgerRegion;
}>;

export type LedgerOperationV4 = LedgerOperationV3 & Readonly<{
  confidence?: LedgerConfidence;
  sources?: LedgerSources;
  rawText?: string;
  items?: readonly LedgerItem[];
  warnings?: readonly LedgerWarning[];
}>;

/**
 * A candidate ledger operation parsed from raw model output.
 *
 * Invariant:
 * An operation with ANY unreadable fields (unreadableFields.length > 0) or missing customerName,
 * amountCents or proposedKind CANNOT be registered directly. It requires manual user completion
 * in the review UI before it can be submitted for atomic ledger booking in Step 5.
 */
export type LedgerOperation = LedgerOperationV4;

export const CANONICAL_LEDGER_CENTS_RE = /^(0|[1-9][0-9]{0,17})$/;
export const MAX_LEDGER_CUSTOMER_NAME_LENGTH = 80;

/**
 * An operation that is fully resolved, complete, and ready for atomic batch registration.
 *
 * Invariant:
 * - unreadableFields is empty (no unreadable fields).
 * - uncertainFields is empty (all doubtful fields have been resolved by the user).
 * - customerName is non-blank after trim and bounded to at most MAX_LEDGER_CUSTOMER_NAME_LENGTH code units.
 * - amountCents is a canonical non-negative integer string up to 18 digits (never JSON number, empty string, negative, etc.).
 * - proposedKind is one of the valid ProposedLedgerKind values.
 */
export type RegisterableLedgerOperation = Readonly<{
  lineIndex: number;
  proposedKind: ProposedLedgerKind;
  customerName: string;
  amountCents: string;
  description?: string;
  date?: SafeDateCandidate;
  uncertainFields: readonly [];
  unreadableFields: readonly [];
  region?: LedgerRegion;
}>;

/**
 * Checks whether an operation is registerable without requiring user resolution or completion.
 * Requires:
 * - unreadableFields and uncertainFields to both be empty.
 * - proposedKind to be a valid ProposedLedgerKind ("PRIOR_DEBT" | "SALE" | "PAYMENT").
 * - customerName to be a non-blank string of at most MAX_LEDGER_CUSTOMER_NAME_LENGTH (80) chars after trim.
 * - amountCents to match canonical decimal centavos format (1 to 18 digits, no leading zeros unless "0").
 */
export function isRegisterableLedgerOperation(op: LedgerOperation): op is RegisterableLedgerOperation {
  if (op.unreadableFields.length !== 0 || op.uncertainFields.length !== 0) {
    return false;
  }
  if (
    op.proposedKind !== "PRIOR_DEBT" &&
    op.proposedKind !== "SALE" &&
    op.proposedKind !== "PAYMENT"
  ) {
    return false;
  }
  if (
    typeof op.customerName !== "string" ||
    op.customerName.length === 0 ||
    op.customerName.length > MAX_LEDGER_CUSTOMER_NAME_LENGTH ||
    op.customerName.trim().length === 0
  ) {
    return false;
  }
  if (typeof op.amountCents !== "string" || !CANONICAL_LEDGER_CENTS_RE.test(op.amountCents)) {
    return false;
  }
  return true;
}

export type LedgerDiscardReason =
  | "MALFORMED"
  | "OUT_OF_ORDER"
  | "DUPLICATE_LINE_INDEX"
  | "INVALID_MONEY"
  | "INVALID_CUSTOMER"
  | "INVALID_UNCERTAIN_FIELDS"
  | "INVALID_UNREADABLE_FIELDS"
  | "DISALLOWED_PROPERTIES";

export type LedgerDiscardDiagnostic = Readonly<{
  inputIndex: number;
  lineIndex?: number;
  reason: LedgerDiscardReason;
}>;

export type LedgerPageDate = Readonly<{
  date: SafeDateCandidate;
  confidence: number;
}>;

export type LedgerExtractionResult = Readonly<{
  operations: readonly LedgerOperationV4[];
  discards: readonly LedgerDiscardDiagnostic[];
  unreadTextRemaining: boolean;
  pageIncomplete: boolean;
  pageDate?: LedgerPageDate;
}>;

export type ExtractLedgerPageResponseV3 = Readonly<{
  contractVersion: 3;
  requestId: string;
  status: InterpretAiStatus;
  operations: readonly LedgerOperationV3[] | null;
  discards: readonly LedgerDiscardDiagnostic[];
  unreadTextRemaining: boolean;
  pageIncomplete: boolean;
}>;

export type ExtractLedgerPageResponseV4 = Readonly<{
  contractVersion: 4;
  requestId: string;
  status: InterpretAiStatus;
  operations: readonly LedgerOperationV4[] | null;
  discards: readonly LedgerDiscardDiagnostic[];
  unreadTextRemaining: boolean;
  pageIncomplete: boolean;
  pageDate?: LedgerPageDate;
}>;

export type ExtractLedgerPageResponse = ExtractLedgerPageResponseV3 | ExtractLedgerPageResponseV4;

export type ExtractLedgerPageValidation =
  | Readonly<{ ok: true; value: ExtractLedgerPageRequest }>
  | Readonly<{ ok: false; reason: "invalid-request" }>;

export const MAX_LEDGER_IMAGE_BASE64_LENGTH = 8_000_000;

export const BASE64_ALPHABET_RE = /^[A-Za-z0-9+/]+$/;

export function isWellFormedBase64(str: string): boolean {
  if (str.length === 0 || str.length % 4 !== 0) {
    return false;
  }
  const prefix = str.slice(0, -4);
  if (prefix.length > 0 && !BASE64_ALPHABET_RE.test(prefix)) {
    return false;
  }
  const last4 = str.slice(-4);
  return (
    /^[A-Za-z0-9+/]{4}$/.test(last4) ||
    /^[A-Za-z0-9+/]{3}=$/.test(last4) ||
    /^[A-Za-z0-9+/]{2}==$/.test(last4)
  );
}

const EXTRACT_LEDGER_ACTION = "extract_ledger_page";

const EXTRACT_LEDGER_KEYS = [
  "contractVersion",
  "requestId",
  "action",
  "imageBase64",
  "imageMimeType",
] as const;

const ALLOWED_IMAGE_MIME_TYPES: readonly ExtractLedgerPageImageMimeType[] = [
  "image/jpeg",
  "image/png",
  "image/webp",
];

/**
 * Accepts contract v3 or v4 extract_ledger_page allow-list: a plain non-null object whose only keys
 * are contractVersion, requestId, action, imageBase64 and imageMimeType, with contractVersion 3 or 4,
 * action "extract_ledger_page", a 36-character UUID, non-empty well-formed base64 image data of at
 * most MAX_LEDGER_IMAGE_BASE64_LENGTH code units, and an allowed mime type.
 *
 * Hardened object checks: strictly requires prototype to be Object.prototype or null, with no
 * symbols or inherited keys, and wraps access safely so no input or throwing getter can escape.
 *
 * Every failure returns the fixed rejection { ok: false, reason: "invalid-request" }.
 * Never throws, never normalizes, never logs.
 */
export function validateExtractLedgerPageRequest(input: unknown): ExtractLedgerPageValidation {
  try {
    if (
      typeof input !== "object" ||
      input === null ||
      Array.isArray(input) ||
      Object.getOwnPropertySymbols(input).length > 0
    ) {
      return { ok: false, reason: "invalid-request" };
    }
    const proto = Object.getPrototypeOf(input);
    if (proto !== Object.prototype && proto !== null) {
      return { ok: false, reason: "invalid-request" };
    }
    const record = input as Record<string, unknown>;
    const ownNames = Object.getOwnPropertyNames(record);
    if (
      ownNames.length !== EXTRACT_LEDGER_KEYS.length ||
      !EXTRACT_LEDGER_KEYS.every((key) => Object.prototype.hasOwnProperty.call(record, key))
    ) {
      return { ok: false, reason: "invalid-request" };
    }
    const version = record["contractVersion"];
    if ((version !== 3 && version !== 4) || record["action"] !== EXTRACT_LEDGER_ACTION) {
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
    const imageMimeType = record["imageMimeType"];
    if (
      typeof imageMimeType !== "string" ||
      !(ALLOWED_IMAGE_MIME_TYPES as readonly string[]).includes(imageMimeType)
    ) {
      return { ok: false, reason: "invalid-request" };
    }
    const imageBase64 = record["imageBase64"];
    if (
      typeof imageBase64 !== "string" ||
      imageBase64.length === 0 ||
      imageBase64.length > MAX_LEDGER_IMAGE_BASE64_LENGTH ||
      !isWellFormedBase64(imageBase64)
    ) {
      return { ok: false, reason: "invalid-request" };
    }
    return {
      ok: true,
      value: {
        contractVersion: version as 3 | 4,
        requestId,
        action: EXTRACT_LEDGER_ACTION,
        imageBase64,
        imageMimeType: imageMimeType as ExtractLedgerPageImageMimeType,
      },
    };
  } catch {
    return { ok: false, reason: "invalid-request" };
  }
}

export { parseLedgerExtraction } from "./ai/ledgerExtraction.js";
