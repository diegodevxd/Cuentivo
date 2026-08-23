import type {
  BusinessQueryType,
  ClarificationReason,
  SafeAiProposal,
  SafeDateCandidate,
  SafePeriodCandidate,
  SafeSaleLine,
  UnresolvedSlot,
} from "../contract";

/**
 * Maximum display-name length (customer/product) in UTF-16 code units after trim.
 *
 * Initial-proposal bound: kept small enough that the maximum allowed shape fits inside the
 * provider's fixed 512-token output cap (8 lines x 80-char names). A future multimodal capture
 * may split a notebook image into several drafts instead of raising this bound.
 */
export const MAX_DISPLAY_NAME_LENGTH = 80;

/**
 * Maximum number of sale lines in a single initial create_sale proposal. Same rationale as
 * MAX_DISPLAY_NAME_LENGTH; multimodal sections become multiple proposals in a later increment.
 */
export const MAX_SALE_LINES = 8;

/** Canonical non-negative decimal centavo string: no signs, decimals, exponents or leading zeros. */
const CANONICAL_CENTS_RE = /^(0|[1-9][0-9]{0,17})$/;

const REASONS: readonly ClarificationReason[] = [
  "AMBIGUOUS_CUSTOMER",
  "AMBIGUOUS_PRODUCT",
  "MISSING_CUSTOMER",
  "MISSING_PRODUCT",
  "MISSING_AMOUNT",
  "UNKNOWN_INPUT",
];

const SLOTS: readonly UnresolvedSlot[] = [
  "CUSTOMER",
  "PRODUCT",
  "AMOUNT",
  "QUANTITY",
  "PERIOD",
];

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.getOwnPropertySymbols(value).length === 0
  );
}

function hasExactKeys(record: Record<string, unknown>, keys: readonly string[]): boolean {
  const ownKeys = Object.keys(record);
  return (
    ownKeys.length === keys.length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(record, key))
  );
}

function hasOnlyKeys(record: Record<string, unknown>, allowed: readonly string[]): boolean {
  const ownKeys = Object.keys(record);
  return ownKeys.every((key) => allowed.includes(key));
}

function isNonblankBounded(value: unknown, maxLength: number): value is string {
  // Raw length applies to the value as received (padding included), matching the wire/schema
  // maxLength bound; a provider must not bypass the limit with whitespace that trims away.
  // The value is never normalized: it is required to be a nonblank string of 1..maxLength raw
  // UTF-16 code units.
  return (
    typeof value === "string" &&
    value.length >= 1 &&
    value.length <= maxLength &&
    value.trim().length > 0
  );
}

function isCanonicalCents(value: unknown): value is string {
  return typeof value === "string" && CANONICAL_CENTS_RE.test(value);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 1;
}

function isValidDateParts(year: number, month: number, day: number): boolean {
  if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1) {
    return false;
  }
  const daysInMonth = (() => {
    switch (month) {
      case 1:
      case 3:
      case 5:
      case 7:
      case 8:
      case 10:
      case 12:
        return 31;
      case 4:
      case 6:
      case 9:
      case 11:
        return 30;
      case 2:
        return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
      default:
        return 0;
    }
  })();
  return day <= daysInMonth;
}

function parseStructuredDate(value: unknown): SafeDateCandidate | null {
  if (!isPlainObject(value) || !hasExactKeys(value, ["year", "month", "day"])) {
    return null;
  }
  const year = value["year"];
  const month = value["month"];
  const day = value["day"];
  if (
    typeof year !== "number" ||
    !Number.isSafeInteger(year) ||
    typeof month !== "number" ||
    !Number.isSafeInteger(month) ||
    typeof day !== "number" ||
    !Number.isSafeInteger(day)
  ) {
    return null;
  }
  if (!isValidDateParts(year, month, day)) {
    return null;
  }
  return { year, month, day };
}

function compareDate(
  y1: number,
  m1: number,
  d1: number,
  y2: number,
  m2: number,
  d2: number,
): number {
  if (y1 !== y2) {
    return y1 < y2 ? -1 : 1;
  }
  if (m1 !== m2) {
    return m1 < m2 ? -1 : 1;
  }
  return d1 === d2 ? 0 : d1 < d2 ? -1 : 1;
}

function parseStructuredPeriod(value: unknown): SafePeriodCandidate | null {
  if (!isPlainObject(value)) {
    return null;
  }
  const keys = ["startYear", "startMonth", "startDay", "endYear", "endMonth", "endDay"];
  if (!hasExactKeys(value, keys)) {
    return null;
  }
  const startYear = value["startYear"];
  const startMonth = value["startMonth"];
  const startDay = value["startDay"];
  const endYear = value["endYear"];
  const endMonth = value["endMonth"];
  const endDay = value["endDay"];
  if (
    [startYear, startMonth, startDay, endYear, endMonth, endDay].some(
      (n) => typeof n !== "number" || !Number.isSafeInteger(n),
    )
  ) {
    return null;
  }
  const sy = startYear as number;
  const sm = startMonth as number;
  const sd = startDay as number;
  const ey = endYear as number;
  const em = endMonth as number;
  const ed = endDay as number;
  if (!isValidDateParts(sy, sm, sd) || !isValidDateParts(ey, em, ed)) {
    return null;
  }
  if (compareDate(sy, sm, sd, ey, em, ed) > 0) {
    return null;
  }
  return { startYear: sy, startMonth: sm, startDay: sd, endYear: ey, endMonth: em, endDay: ed };
}

function parseUnresolvedSlots(value: unknown): UnresolvedSlot[] | null {
  if (!Array.isArray(value) || value.length === 0) {
    return null;
  }
  const seen = new Set<UnresolvedSlot>();
  const result: UnresolvedSlot[] = [];
  for (const item of value) {
    if (typeof item !== "string" || !(SLOTS as readonly string[]).includes(item)) {
      return null;
    }
    const slot = item as UnresolvedSlot;
    if (seen.has(slot)) {
      return null; // duplicates are fail-closed
    }
    seen.add(slot);
    result.push(slot);
  }
  return result;
}

function parseSaleLine(value: unknown): SafeSaleLine | null {
  if (!isPlainObject(value) || !hasExactKeys(value, ["productName", "quantity", "unitPriceCents"])) {
    return null;
  }
  const productName = value["productName"];
  const quantity = value["quantity"];
  const unitPriceCents = value["unitPriceCents"];
  if (!isNonblankBounded(productName, MAX_DISPLAY_NAME_LENGTH)) {
    return null;
  }
  if (!isPositiveInteger(quantity)) {
    return null;
  }
  if (!isCanonicalCents(unitPriceCents)) {
    return null;
  }
  return { productName, quantity, unitPriceCents };
}

function parseClarification(record: Record<string, unknown>): SafeAiProposal | null {
  if (!hasExactKeys(record, ["kind", "reason", "unresolvedSlots"])) {
    return null;
  }
  const reason = record["reason"];
  if (typeof reason !== "string" || !(REASONS as readonly string[]).includes(reason)) {
    return null;
  }
  const slots = parseUnresolvedSlots(record["unresolvedSlots"]);
  if (slots === null) {
    return null;
  }
  return { kind: "requires_clarification", reason: reason as ClarificationReason, unresolvedSlots: slots };
}

function parseCreateSale(record: Record<string, unknown>): SafeAiProposal | null {
  const allowed = ["kind", "customerName", "lines", "declaredInitialPaymentCents", "promisedPaymentDate"];
  if (!hasOnlyKeys(record, allowed)) {
    return null;
  }
  const customerName = record["customerName"];
  if (!isNonblankBounded(customerName, MAX_DISPLAY_NAME_LENGTH)) {
    return null;
  }
  const lines = record["lines"];
  if (!Array.isArray(lines) || lines.length === 0 || lines.length > MAX_SALE_LINES) {
    return null;
  }
  const parsedLines: SafeSaleLine[] = [];
  for (const line of lines) {
    const parsed = parseSaleLine(line);
    if (parsed === null) {
      return null;
    }
    parsedLines.push(parsed);
  }
  const declaredInitialPaymentCents = record["declaredInitialPaymentCents"];
  if (!isCanonicalCents(declaredInitialPaymentCents)) {
    return null;
  }
  const promise = record["promisedPaymentDate"];
  if (promise !== undefined) {
    const parsedPromise = parseStructuredDate(promise);
    if (parsedPromise === null) {
      return null;
    }
    return {
      kind: "create_sale",
      customerName,
      lines: parsedLines,
      declaredInitialPaymentCents,
      promisedPaymentDate: parsedPromise,
    };
  }
  return {
    kind: "create_sale",
    customerName,
    lines: parsedLines,
    declaredInitialPaymentCents,
  };
}

function parseRecordPayment(record: Record<string, unknown>): SafeAiProposal | null {
  if (!hasExactKeys(record, ["kind", "customerName", "declaredAmountCents"])) {
    return null;
  }
  const customerName = record["customerName"];
  const declaredAmountCents = record["declaredAmountCents"];
  if (!isNonblankBounded(customerName, MAX_DISPLAY_NAME_LENGTH)) {
    return null;
  }
  if (!isCanonicalCents(declaredAmountCents)) {
    return null;
  }
  return { kind: "record_payment", customerName, declaredAmountCents };
}

function parseBusinessQuery(record: Record<string, unknown>): SafeAiProposal | null {
  const allowed = ["kind", "queryType", "customerName", "period"];
  if (!hasOnlyKeys(record, allowed)) {
    return null;
  }
  const queryType = record["queryType"];
  const QUERY_TYPES: readonly BusinessQueryType[] = [
    "CUSTOMER_BALANCE",
    "CUSTOMERS_WITH_DEBT",
    "SALES_FOR_PERIOD",
  ];
  if (typeof queryType !== "string" || !(QUERY_TYPES as readonly string[]).includes(queryType)) {
    return null;
  }
  const typedQueryType = queryType as BusinessQueryType;
  const customerName = record["customerName"];
  const period = record["period"];

  switch (typedQueryType) {
    case "CUSTOMER_BALANCE":
      if (customerName === undefined || period !== undefined) {
        return null;
      }
      if (!isNonblankBounded(customerName, MAX_DISPLAY_NAME_LENGTH)) {
        return null;
      }
      return { kind: "business_query", queryType: typedQueryType, customerName };

    case "CUSTOMERS_WITH_DEBT":
      if (customerName !== undefined || period !== undefined) {
        return null;
      }
      return { kind: "business_query", queryType: typedQueryType };

    case "SALES_FOR_PERIOD": {
      if (customerName !== undefined || period === undefined) {
        return null;
      }
      const parsedPeriod = parseStructuredPeriod(period);
      if (parsedPeriod === null) {
        return null;
      }
      return { kind: "business_query", queryType: typedQueryType, period: parsedPeriod };
    }
  }
}

/**
 * Provider-neutral, exact-object parser for typed AI proposals.
 *
 * Accepts ONLY plain objects with exact keys per kind, bounded nonblank display names, canonical
 * non-negative decimal centavo strings (never JSON numbers), positive integer quantities, valid
 * calendar dates with no overflow, non-empty duplicate-free unresolved slots, and exact
 * business-query shapes. Anything else — extra keys, missing keys, malformed money, invalid
 * dates, nested objects, unknown enums — returns null. Never throws, never logs, never stores.
 */
export function parseSafeAiProposal(value: unknown): SafeAiProposal | null {
  if (!isPlainObject(value)) {
    return null;
  }
  if (!value) {
    return null;
  }
  const kind = value["kind"];
  if (typeof kind !== "string") {
    return null;
  }
  switch (kind) {
    case "requires_clarification":
      return parseClarification(value);
    case "create_sale":
      return parseCreateSale(value);
    case "record_payment":
      return parseRecordPayment(value);
    case "business_query":
      return parseBusinessQuery(value);
    default:
      return null;
  }
}
