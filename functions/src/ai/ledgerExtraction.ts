import type {
  LedgerDiscardDiagnostic,
  LedgerExtractionResult,
  LedgerOperation,
  LedgerRegion,
  ProposedLedgerKind,
  SafeDateCandidate,
  UncertainLedgerField,
  UnreadableLedgerField,
} from "../contract.js";
import { MAX_DISPLAY_NAME_LENGTH } from "./interpretation.js";

export const MAX_LEDGER_OPERATIONS = 25;

const PROPOSED_KINDS: readonly ProposedLedgerKind[] = [
  "PRIOR_DEBT",
  "SALE",
  "PAYMENT",
];

const UNCERTAIN_FIELDS: readonly UncertainLedgerField[] = [
  "AMOUNT",
  "CUSTOMER",
  "DATE",
  "KIND",
];

const UNREADABLE_FIELDS: readonly UnreadableLedgerField[] = [
  "AMOUNT",
  "CUSTOMER",
  "DATE",
  "KIND",
];

/** Canonical non-negative decimal centavo string: no signs, decimals, exponents or leading zeros (up to 18 digits). */
const CANONICAL_CENTS_RE = /^(0|[1-9][0-9]{0,17})$/;

const ROOT_KEYS = ["operations", "pageIncomplete"] as const;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (
    typeof value !== "object" ||
    value === null ||
    Array.isArray(value) ||
    Object.getOwnPropertySymbols(value).length > 0
  ) {
    return false;
  }
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function hasOnlyKeys(record: Record<string, unknown>, allowed: readonly string[]): boolean {
  const ownNames = Object.getOwnPropertyNames(record);
  return ownNames.every((key) => allowed.includes(key));
}

function isNonblankBounded(value: unknown, maxLength: number): value is string {
  return (
    typeof value === "string" &&
    value.length >= 1 &&
    value.length <= maxLength &&
    value.trim().length > 0
  );
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
  if (!isPlainObject(value)) {
    return null;
  }
  const ownNames = Object.getOwnPropertyNames(value);
  if (
    ownNames.length !== 3 ||
    !["year", "month", "day"].every((k) => Object.prototype.hasOwnProperty.call(value, k))
  ) {
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

function parseRegion(value: unknown): LedgerRegion | null {
  if (!isPlainObject(value)) {
    return null;
  }
  const ownNames = Object.getOwnPropertyNames(value);
  if (
    ownNames.length !== 4 ||
    !["x", "y", "width", "height"].every((k) => Object.prototype.hasOwnProperty.call(value, k))
  ) {
    return null;
  }
  const x = value["x"];
  const y = value["y"];
  const width = value["width"];
  const height = value["height"];

  if (
    typeof x !== "number" ||
    !Number.isFinite(x) ||
    typeof y !== "number" ||
    !Number.isFinite(y) ||
    typeof width !== "number" ||
    !Number.isFinite(width) ||
    typeof height !== "number" ||
    !Number.isFinite(height)
  ) {
    return null;
  }

  if (x < 0 || x > 1 || y < 0 || y > 1) {
    return null;
  }
  if (width <= 0 || width > 1 || height <= 0 || height > 1) {
    return null;
  }
  if (x + width > 1.0001 || y + height > 1.0001) {
    return null;
  }

  return { x, y, width, height };
}

function parseFieldList<T extends string>(
  value: unknown,
  allowedValues: readonly T[],
): readonly T[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const seen = new Set<T>();
  const result: T[] = [];
  for (const item of value) {
    if (typeof item !== "string" || !(allowedValues as readonly string[]).includes(item)) {
      return null;
    }
    const typed = item as T;
    if (seen.has(typed)) {
      return null; // No duplicates allowed
    }
    seen.add(typed);
    result.push(typed);
  }
  return Object.freeze(result);
}

const OPERATION_ALLOWED_KEYS = [
  "lineIndex",
  "proposedKind",
  "customerName",
  "amountCents",
  "description",
  "date",
  "uncertainFields",
  "unreadableFields",
  "region",
] as const;

type SingleOpParseResult =
  | { ok: true; op: LedgerOperation }
  | { ok: false; lineIndex?: number; reason: LedgerDiscardDiagnostic["reason"] };

function parseSingleOperation(value: unknown): SingleOpParseResult {
  try {
    if (!isPlainObject(value)) {
      return { ok: false, reason: "MALFORMED" };
    }

    const rawLineIndex = value["lineIndex"];
    const candidateLineIndex =
      typeof rawLineIndex === "number" && Number.isSafeInteger(rawLineIndex) && rawLineIndex >= 0
        ? rawLineIndex
        : undefined;

    if (!hasOnlyKeys(value, OPERATION_ALLOWED_KEYS)) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "DISALLOWED_PROPERTIES" };
    }

    if (candidateLineIndex === undefined) {
      return { ok: false, reason: "MALFORMED" };
    }

    // Parse uncertainFields and unreadableFields
    const uncertainFieldsRaw = value["uncertainFields"];
    const parsedUncertainFields = parseFieldList(uncertainFieldsRaw, UNCERTAIN_FIELDS);
    if (!parsedUncertainFields) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_UNCERTAIN_FIELDS" };
    }

    const unreadableFieldsRaw = value["unreadableFields"];
    const parsedUnreadableFields = parseFieldList(unreadableFieldsRaw, UNREADABLE_FIELDS);
    if (!parsedUnreadableFields) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_UNREADABLE_FIELDS" };
    }

    // Invariant: a field cannot be in uncertainFields AND unreadableFields simultaneously
    for (const unreadable of parsedUnreadableFields) {
      if (parsedUncertainFields.includes(unreadable as UncertainLedgerField)) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_UNREADABLE_FIELDS" };
      }
    }

    const isKindUnreadable = parsedUnreadableFields.includes("KIND");
    let proposedKind: ProposedLedgerKind | undefined;
    if ("proposedKind" in value && value["proposedKind"] !== undefined) {
      const rawKind = value["proposedKind"];
      if (
        typeof rawKind !== "string" ||
        !(PROPOSED_KINDS as readonly string[]).includes(rawKind)
      ) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
      }
      proposedKind = rawKind as ProposedLedgerKind;
    } else if (!isKindUnreadable) {
      // proposedKind may only be omitted if declared in unreadableFields
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    const isCustomerUnreadable = parsedUnreadableFields.includes("CUSTOMER");
    let customerName: string | undefined;
    if ("customerName" in value && value["customerName"] !== undefined) {
      const rawCustomer = value["customerName"];
      if (!isNonblankBounded(rawCustomer, MAX_DISPLAY_NAME_LENGTH)) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_CUSTOMER" };
      }
      customerName = rawCustomer;
    } else if (!isCustomerUnreadable) {
      // customerName may only be omitted if declared in unreadableFields
      return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_CUSTOMER" };
    }

    const isAmountUnreadable = parsedUnreadableFields.includes("AMOUNT");
    let amountCents: string | undefined;
    if ("amountCents" in value && value["amountCents"] !== undefined) {
      const rawAmount = value["amountCents"];
      // Hard rule: amountCents MUST NEVER be a JSON number.
      if (typeof rawAmount !== "string" || !CANONICAL_CENTS_RE.test(rawAmount)) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_MONEY" };
      }
      amountCents = rawAmount;
    } else if (!isAmountUnreadable) {
      // amountCents may only be omitted if declared in unreadableFields
      return { ok: false, lineIndex: candidateLineIndex, reason: "INVALID_MONEY" };
    }

    let description: string | undefined;
    if ("description" in value && value["description"] !== undefined) {
      const rawDesc = value["description"];
      if (!isNonblankBounded(rawDesc, MAX_DISPLAY_NAME_LENGTH)) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
      }
      description = rawDesc;
    }

    let date: SafeDateCandidate | undefined;
    if ("date" in value && value["date"] !== undefined) {
      const parsedDate = parseStructuredDate(value["date"]);
      if (!parsedDate) {
        return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
      }
      date = parsedDate;
    }

    let region: LedgerRegion | undefined;
    if ("region" in value && value["region"] !== undefined) {
      try {
        const parsedRegion = parseRegion(value["region"]);
        // Spec: if region is malformed, discard the region, NOT the operation.
        if (parsedRegion) {
          region = parsedRegion;
        }
      } catch {
        // Discard only the region if its getters throw
      }
    }

    return {
      ok: true,
      op: {
        lineIndex: candidateLineIndex,
        ...(proposedKind !== undefined ? { proposedKind } : {}),
        ...(customerName !== undefined ? { customerName } : {}),
        ...(amountCents !== undefined ? { amountCents } : {}),
        ...(description !== undefined ? { description } : {}),
        ...(date !== undefined ? { date } : {}),
        uncertainFields: parsedUncertainFields,
        unreadableFields: parsedUnreadableFields,
        ...(region !== undefined ? { region } : {}),
      },
    };
  } catch {
    return { ok: false, reason: "MALFORMED" };
  }
}

/**
 * Parses raw JSON returned by the model for an extract_ledger_page action.
 *
 * Requirements:
 * - Root must be a plain object with keys "operations" and "pageIncomplete".
 * - operations must be an array of length <= MAX_LEDGER_OPERATIONS (25). If longer, rejected (returns null).
 * - lineIndex must be unique and in strictly ascending order.
 * - Malformed operations within the array are discarded with safe diagnostics (inputIndex, lineIndex, reason).
 * - Distinguishes unreadTextRemaining (from model declaration or reaching MAX_LEDGER_OPERATIONS 25)
 *   from discards (rows dropped during parsing).
 * - pageIncomplete is true if unreadTextRemaining is true OR any operations were discarded.
 * - Entire document rejected (null) if root is malformed, operations is not an array, or length > 25.
 * - Never throws on any input.
 */
export function parseLedgerExtraction(value: unknown): LedgerExtractionResult | null {
  try {
    if (!isPlainObject(value)) {
      return null;
    }
    const ownNames = Object.getOwnPropertyNames(value);
    if (
      ownNames.length !== ROOT_KEYS.length ||
      !ROOT_KEYS.every((k) => Object.prototype.hasOwnProperty.call(value, k))
    ) {
      return null;
    }

    const rawPageIncomplete = value["pageIncomplete"];
    if (typeof rawPageIncomplete !== "boolean") {
      return null;
    }

    const rawOperations = value["operations"];
    if (!Array.isArray(rawOperations)) {
      return null;
    }

    if (rawOperations.length > MAX_LEDGER_OPERATIONS) {
      return null;
    }

    const parsedOperations: LedgerOperation[] = [];
    const discards: LedgerDiscardDiagnostic[] = [];
    let lastLineIndex = -1;

    for (let inputIndex = 0; inputIndex < rawOperations.length; inputIndex++) {
      const rawOp = rawOperations[inputIndex];
      const parsed = parseSingleOperation(rawOp);
      if (!parsed.ok) {
        discards.push({
          inputIndex,
          ...(parsed.lineIndex !== undefined ? { lineIndex: parsed.lineIndex } : {}),
          reason: parsed.reason,
        });
        continue;
      }

      const op = parsed.op;
      // lineIndex must be strictly ascending (which guarantees uniqueness and ordering)
      if (op.lineIndex === lastLineIndex) {
        discards.push({
          inputIndex,
          lineIndex: op.lineIndex,
          reason: "DUPLICATE_LINE_INDEX",
        });
        continue;
      }
      if (op.lineIndex < lastLineIndex) {
        discards.push({
          inputIndex,
          lineIndex: op.lineIndex,
          reason: "OUT_OF_ORDER",
        });
        continue;
      }

      lastLineIndex = op.lineIndex;
      parsedOperations.push(op);
    }

    // Spec rule: reaching MAX_LEDGER_OPERATIONS (25) almost always means more text was left unread.
    const reachedCapacity = rawOperations.length === MAX_LEDGER_OPERATIONS;
    const unreadTextRemaining = rawPageIncomplete || reachedCapacity;
    const effectivePageIncomplete = unreadTextRemaining || discards.length > 0;

    return {
      operations: Object.freeze(parsedOperations),
      discards: Object.freeze(discards),
      unreadTextRemaining,
      pageIncomplete: effectivePageIncomplete,
    };
  } catch {
    return null;
  }
}
