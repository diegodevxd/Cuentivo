import type {
  LedgerDiscardDiagnostic,
  LedgerExtractionResult,
  LedgerItem,
  LedgerOperation,
  LedgerOperationV4,
  LedgerPageDate,
  LedgerRegion,
  LedgerSource,
  LedgerSources,
  LedgerConfidence,
  LedgerWarning,
  ProposedLedgerKind,
  SafeDateCandidate,
  UncertainLedgerField,
  UnreadableLedgerField,
} from "../contract.js";
import { MAX_DISPLAY_NAME_LENGTH } from "./interpretation.js";

export const MAX_LEDGER_OPERATIONS = 25;

/**
 * Máximo de items (productos/conceptos) que puede traer un renglón.
 *
 * No existía, y eso dejaba el peor caso de la respuesta sin cota: el presupuesto de salida del
 * proveedor no se puede justificar sobre un campo que puede crecer sin límite. Un renglón de una
 * libreta detalla los productos de ESA línea —el ejemplo de la instrucción trae uno—, así que cinco
 * cubre de sobra lo legítimo. Pasarse descarta el renglón (MALFORMED), como cualquier otro límite
 * violado: es el lado seguro, porque el renglón se ve y se puede corregir a mano.
 */
export const MAX_LEDGER_ITEMS_PER_OPERATION = 5;

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

export const ALLOWED_WARNINGS: readonly LedgerWarning[] = [
  "ITEMS_TOTAL_MISMATCH",
  "INHERITED_CUSTOMER",
  "LOW_CONFIDENCE_CUSTOMER",
  "LOW_CONFIDENCE_CONCEPT",
  "LOW_CONFIDENCE_AMOUNT",
  "AMBIGUOUS_KIND",
  "AMBIGUOUS_SEPARATOR",
  "MULTIPLE_MOVEMENTS_IN_ROW",
  "NOT_A_MOVEMENT",
];

const ALLOWED_SOURCES: readonly LedgerSource[] = [
  "EXPLICIT",
  "INHERITED_PREVIOUS_ROW",
  "PAGE_DATE",
  "INFERRED",
];

/** Canonical non-negative decimal centavo string: no signs, decimals, exponents or leading zeros (up to 18 digits). */
const CANONICAL_CENTS_RE = /^(0|[1-9][0-9]{0,17})$/;

const ROOT_KEYS = ["operations", "pageIncomplete", "pageDate"] as const;

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

function parseConfidence(value: unknown): LedgerConfidence | null | false {
  if (value === undefined) return null;
  if (!isPlainObject(value)) return false;
  const allowed = ["customer", "concept", "amount", "date", "kind"];
  if (!hasOnlyKeys(value, allowed)) return false;

  const res: Record<string, number> = {};
  for (const k of allowed) {
    if (k in value && value[k] !== undefined) {
      const v = value[k];
      if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > 1) {
        return false;
      }
      res[k] = v;
    }
  }
  return Object.freeze(res as LedgerConfidence);
}

function parseSources(value: unknown): LedgerSources | null | false {
  if (value === undefined) return null;
  if (!isPlainObject(value)) return false;
  const allowed = ["customer", "date"];
  if (!hasOnlyKeys(value, allowed)) return false;

  const res: Record<string, LedgerSource> = {};
  for (const k of allowed) {
    if (k in value && value[k] !== undefined) {
      const v = value[k];
      if (typeof v !== "string" || !(ALLOWED_SOURCES as readonly string[]).includes(v)) {
        return false;
      }
      res[k] = v as LedgerSource;
    }
  }
  return Object.freeze(res as LedgerSources);
}

function parseRawText(value: unknown): string | null | false {
  if (value === undefined) return null;
  if (typeof value !== "string" || value.length === 0 || value.length > 200 || value.trim().length === 0) {
    return false;
  }
  return value;
}

function parseItems(
  value: unknown,
): { ok: true; items: readonly LedgerItem[] | null } | { ok: false; reason: "INVALID_MONEY" | "MALFORMED" } {
  if (value === undefined) return { ok: true, items: null };
  if (!Array.isArray(value)) return { ok: false, reason: "MALFORMED" };
  if (value.length > MAX_LEDGER_ITEMS_PER_OPERATION) return { ok: false, reason: "MALFORMED" };

  const parsedItems: LedgerItem[] = [];
  for (const item of value) {
    if (!isPlainObject(item)) return { ok: false, reason: "MALFORMED" };
    if (!hasOnlyKeys(item, ["description", "quantity", "subtotalCents"])) {
      return { ok: false, reason: "MALFORMED" };
    }
    const rawDesc = item["description"];
    if (!isNonblankBounded(rawDesc, MAX_DISPLAY_NAME_LENGTH)) {
      return { ok: false, reason: "MALFORMED" };
    }
    let quantity: number | undefined;
    if ("quantity" in item && item["quantity"] !== undefined) {
      const rawQ = item["quantity"];
      if (typeof rawQ !== "number" || !Number.isSafeInteger(rawQ) || rawQ <= 0) {
        return { ok: false, reason: "MALFORMED" };
      }
      quantity = rawQ;
    }
    let subtotalCents: string | undefined;
    if ("subtotalCents" in item && item["subtotalCents"] !== undefined) {
      const rawSub = item["subtotalCents"];
      if (typeof rawSub !== "string" || !CANONICAL_CENTS_RE.test(rawSub)) {
        return { ok: false, reason: "INVALID_MONEY" };
      }
      subtotalCents = rawSub;
    }
    parsedItems.push({
      description: rawDesc,
      ...(quantity !== undefined ? { quantity } : {}),
      ...(subtotalCents !== undefined ? { subtotalCents } : {}),
    });
  }
  return { ok: true, items: Object.freeze(parsedItems) };
}

function parseWarnings(value: unknown): readonly LedgerWarning[] | null | false {
  if (value === undefined) return null;
  const parsed = parseFieldList(value, ALLOWED_WARNINGS);
  if (parsed === null) return false;
  return parsed;
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
  "confidence",
  "sources",
  "rawText",
  "items",
  "warnings",
] as const;

type SingleOpParseResult =
  | { ok: true; op: LedgerOperationV4 }
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
        // Spec: if region is malformed, discard the region, NOT the operation. The region is the
        // crop shown beside the row; losing it costs a convenience, losing the row costs money.
        if (parsedRegion) {
          region = parsedRegion;
        }
      } catch {
        // Discard only the region if its getters throw
      }
    }

    // Parse v4 fields
    const confidence = parseConfidence(value["confidence"]);
    if (confidence === false) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    const sources = parseSources(value["sources"]);
    if (sources === false) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    const rawText = parseRawText(value["rawText"]);
    if (rawText === false) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    const itemsResult = parseItems(value["items"]);
    if (!itemsResult.ok) {
      return { ok: false, lineIndex: candidateLineIndex, reason: itemsResult.reason };
    }
    const items = itemsResult.items;

    const parsedWarnings = parseWarnings(value["warnings"]);
    if (parsedWarnings === false) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    let finalUncertainFields = [...parsedUncertainFields];
    let finalUnreadableFields = [...parsedUnreadableFields];
    let finalWarnings = parsedWarnings ? [...parsedWarnings] : [];

    // A warning that only travels as text is a warning a client can drop, and one client already
    // did: Android accepted the key and never stored it, so a row the model flagged as ambiguous
    // arrived looking clean and stayed registerable. So every warning that means "a person has to
    // look at this" is also written into uncertainFields here, where both v3 and v4 clients read it
    // and where the registrability rule already lives. The text is advice; the list is the brake.
    const WARNING_BLOCKS: ReadonlyArray<readonly [LedgerWarning, UncertainLedgerField]> = [
      ["AMBIGUOUS_SEPARATOR", "AMOUNT"],
      ["MULTIPLE_MOVEMENTS_IN_ROW", "AMOUNT"],
      ["LOW_CONFIDENCE_AMOUNT", "AMOUNT"],
      ["AMBIGUOUS_KIND", "KIND"],
      ["INHERITED_CUSTOMER", "CUSTOMER"],
      ["LOW_CONFIDENCE_CUSTOMER", "CUSTOMER"],
    ];
    for (const [warning, field] of WARNING_BLOCKS) {
      if (
        finalWarnings.includes(warning) &&
        !finalUnreadableFields.includes(field as UnreadableLedgerField) &&
        !finalUncertainFields.includes(field)
      ) {
        finalUncertainFields.push(field);
      }
    }

    // NOT_A_MOVEMENT is the model saying this line is a note, a reminder or a heading. It is not a
    // doubtful movement to be reviewed: it is not a movement, and offering it for review invites
    // somebody to confirm a debt that was never written.
    if (finalWarnings.includes("NOT_A_MOVEMENT")) {
      return { ok: false, lineIndex: candidateLineIndex, reason: "MALFORMED" };
    }

    // Items that do not add up to the amount are NOT reconciled by arithmetic. Picking a winner
    // here would be inventing a number nobody wrote: either the model misread a line or it misread
    // the total, and only the person holding the notebook knows which. So it is declared and the
    // amount is sent back for review.
    if (items && items.length > 0 && amountCents !== undefined) {
      const itemsWithSubtotal = items.filter((i) => i.subtotalCents !== undefined);
      if (itemsWithSubtotal.length === items.length) {
        let sum = 0n;
        for (const i of itemsWithSubtotal) {
          sum += BigInt(i.subtotalCents!);
        }
        if (sum.toString() !== amountCents) {
          if (!finalWarnings.includes("ITEMS_TOTAL_MISMATCH")) {
            finalWarnings.push("ITEMS_TOTAL_MISMATCH");
          }
          // The amount goes back for review -- but only if the model had not already said it could
          // not read it. Turning "I could not read this" into "I read it with doubt" would be the
          // parser quietly making a weaker claim than the model made, and the two lists are the
          // record of what the model actually said. A field may never sit in both, so when AMOUNT is
          // already unreadable the warning alone carries the mismatch and the lists stay untouched.
          if (
            !finalUnreadableFields.includes("AMOUNT") &&
            !finalUncertainFields.includes("AMOUNT")
          ) {
            finalUncertainFields.push("AMOUNT");
          }
        }
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
        uncertainFields: Object.freeze(finalUncertainFields as UncertainLedgerField[]),
        unreadableFields: Object.freeze(finalUnreadableFields as UnreadableLedgerField[]),
        ...(region !== undefined ? { region } : {}),
        ...(confidence !== null ? { confidence } : {}),
        ...(sources !== null ? { sources } : {}),
        ...(rawText !== null ? { rawText } : {}),
        ...(items !== null ? { items } : {}),
        ...(finalWarnings.length > 0 ? { warnings: Object.freeze(finalWarnings) } : {}),
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
 * - Root must be a plain object with keys "operations" and "pageIncomplete", optional "pageDate".
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
    if (!hasOnlyKeys(value, ROOT_KEYS)) {
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

    let pageDate: LedgerPageDate | undefined;
    if ("pageDate" in value && value["pageDate"] !== undefined) {
      const rawPageDate = value["pageDate"];
      if (isPlainObject(rawPageDate)) {
        if (hasOnlyKeys(rawPageDate, ["date", "confidence"])) {
          const parsedDate = parseStructuredDate(rawPageDate["date"]);
          const conf = rawPageDate["confidence"];
          if (parsedDate && typeof conf === "number" && Number.isFinite(conf) && conf >= 0 && conf <= 1) {
            pageDate = { date: parsedDate, confidence: conf };
          }
        }
      }
    }

    const parsedOperations: LedgerOperationV4[] = [];
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
      // lineIndex must be strictly ascending, which guarantees uniqueness and ordering at once.
      // The review screen edits and deletes rows by that identity, so two rows sharing one would
      // let a correction land on the wrong movement.
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
    // A page that stopped exactly at the cap is a page that was probably cut off, not one that
    // happened to have exactly 25 rows.
    const reachedCapacity = rawOperations.length === MAX_LEDGER_OPERATIONS;
    const unreadTextRemaining = rawPageIncomplete || reachedCapacity;
    const effectivePageIncomplete = unreadTextRemaining || discards.length > 0;

    return {
      operations: Object.freeze(parsedOperations),
      discards: Object.freeze(discards),
      unreadTextRemaining,
      pageIncomplete: effectivePageIncomplete,
      ...(pageDate !== undefined ? { pageDate } : {}),
    };
  } catch {
    return null;
  }
}
