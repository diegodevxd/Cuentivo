import { describe, expect, test } from "vitest";
import {
  MAX_LEDGER_ITEMS_PER_OPERATION,
  MAX_LEDGER_OPERATIONS,
  parseLedgerExtraction,
} from "../src/ai/ledgerExtraction.js";
import { MAX_DISPLAY_NAME_LENGTH } from "../src/ai/interpretation.js";
import { MAX_LEDGER_PAGE_TOTALS } from "../src/contract.js";
import type { LedgerOperation } from "../src/contract.js";

const VALID_OPERATION_PRIOR_DEBT: LedgerOperation = {
  lineIndex: 0,
  proposedKind: "PRIOR_DEBT",
  customerName: "Doña Carmen",
  amountCents: "50000",
  description: "Saldo libreta vieja",
  date: { year: 2026, month: 8, day: 15 },
  uncertainFields: ["DATE"],
  unreadableFields: [],
  region: { x: 0.1, y: 0.2, width: 0.8, height: 0.05 },
};

const VALID_OPERATION_SALE: LedgerOperation = {
  lineIndex: 1,
  proposedKind: "SALE",
  customerName: "Don Pepe",
  amountCents: "35000",
  uncertainFields: [],
  unreadableFields: [],
};

const VALID_OPERATION_PAYMENT: LedgerOperation = {
  lineIndex: 2,
  proposedKind: "PAYMENT",
  customerName: "Rosa López",
  amountCents: "10000",
  description: "Abono efectivo",
  uncertainFields: ["AMOUNT", "CUSTOMER"],
  unreadableFields: [],
};

describe("parseLedgerExtraction", () => {
  test("accepts empty operations list with pageIncomplete false", () => {
    const result = parseLedgerExtraction({
      operations: [],
      pageIncomplete: false,
    });
    expect(result).toEqual({
      operations: [],
      discards: [],
      unreadTextRemaining: false,
      pageIncomplete: false,
    });
  });

  test("accepts operations representing each of the three proposedKinds", () => {
    const result = parseLedgerExtraction({
      operations: [
        VALID_OPERATION_PRIOR_DEBT,
        VALID_OPERATION_SALE,
        VALID_OPERATION_PAYMENT,
      ],
      pageIncomplete: false,
    });
    expect(result).toEqual({
      operations: [
        VALID_OPERATION_PRIOR_DEBT,
        VALID_OPERATION_SALE,
        VALID_OPERATION_PAYMENT,
      ],
      discards: [],
      unreadTextRemaining: false,
      pageIncomplete: false,
    });
  });

  test("preserves pageIncomplete true if declared by model", () => {
    const result = parseLedgerExtraction({
      operations: [VALID_OPERATION_SALE],
      pageIncomplete: true,
    });
    expect(result).toEqual({
      operations: [VALID_OPERATION_SALE],
      discards: [],
      unreadTextRemaining: true,
      pageIncomplete: true,
    });
  });

  test.each([
    ["null", null],
    ["undefined", undefined],
    ["string", "hello"],
    ["number", 123],
    ["array", []],
    ["missing operations", { pageIncomplete: false }],
    ["missing pageIncomplete", { operations: [] }],
    ["extra root key", { operations: [], pageIncomplete: false, extra: true }],
    ["non-boolean pageIncomplete", { operations: [], pageIncomplete: "false" }],
    ["non-array operations", { operations: {}, pageIncomplete: false }],
  ])("rejects invalid document root: %s", (_name, input) => {
    expect(parseLedgerExtraction(input)).toBeNull();
  });

  test("rejects document when operations exceed MAX_LEDGER_OPERATIONS (25)", () => {
    const ops: LedgerOperation[] = Array.from({ length: 26 }, (_, i) => ({
      lineIndex: i,
      proposedKind: "SALE",
      customerName: `Cliente ${i}`,
      amountCents: "1000",
      uncertainFields: [],
      unreadableFields: [],
    }));
    expect(parseLedgerExtraction({ operations: ops, pageIncomplete: false })).toBeNull();
  });

  test("reaching exactly MAX_LEDGER_OPERATIONS (25) marks unreadTextRemaining and pageIncomplete as true", () => {
    const ops: LedgerOperation[] = Array.from({ length: MAX_LEDGER_OPERATIONS }, (_, i) => ({
      lineIndex: i,
      proposedKind: "SALE",
      customerName: `Cliente ${i}`,
      amountCents: "1000",
      uncertainFields: [],
      unreadableFields: [],
    }));
    const result = parseLedgerExtraction({ operations: ops, pageIncomplete: false });
    expect(result).not.toBeNull();
    expect(result?.operations).toHaveLength(25);
    expect(result?.discards).toEqual([]);
    expect(result?.unreadTextRemaining).toBe(true);
    expect(result?.pageIncomplete).toBe(true);
  });

  describe("hard rule: money is never a JSON number and canonical bounds", () => {
    test.each([
      ["integer number", 1234],
      ["zero as number", 0],
      ["decimal float", 1234.5],
      ["scientific float", 1e4],
    ])("discards operation when amountCents is %s with INVALID_MONEY diagnostic", (_desc, badAmount) => {
      const result = parseLedgerExtraction({
        operations: [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Don Pepe",
            amountCents: badAmount,
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        pageIncomplete: false,
      });
      expect(result).toEqual({
        operations: [],
        discards: [{ inputIndex: 0, lineIndex: 0, reason: "INVALID_MONEY" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });

    test.each([
      ["negative string", "-500"],
      ["decimal string with dot", "500.50"],
      ["leading zeros", "00500"],
      ["comma formatted", "1,000"],
      ["currency symbol", "$500"],
      ["empty string", ""],
      ["whitespace padded", " 500 "],
      ["alpha string", "cien"],
    ])("discards operation when amountCents is non-canonical string %s", (_desc, badAmount) => {
      const result = parseLedgerExtraction({
        operations: [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Don Pepe",
            amountCents: badAmount,
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        pageIncomplete: false,
      });
      expect(result).toEqual({
        operations: [],
        discards: [{ inputIndex: 0, lineIndex: 0, reason: "INVALID_MONEY" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });

    test("accepts 18-digit canonical money string", () => {
      const money18 = "9".repeat(18); // 18 digits
      const op = { ...VALID_OPERATION_SALE, amountCents: money18 };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.amountCents).toBe(money18);
    });

    test("discards 19-digit money string exceeding CANONICAL_CENTS_RE bound", () => {
      const money19 = "1" + "0".repeat(18); // 19 digits
      const op = { ...VALID_OPERATION_SALE, amountCents: money19 };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 1, reason: "INVALID_MONEY" }]);
    });
  });

  describe("string bounds for customerName and description", () => {
    test("accepts customerName at exact MAX_DISPLAY_NAME_LENGTH (80)", () => {
      const maxName = "A".repeat(MAX_DISPLAY_NAME_LENGTH);
      const op = { ...VALID_OPERATION_SALE, customerName: maxName };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.customerName).toBe(maxName);
    });

    test("discards customerName at MAX_DISPLAY_NAME_LENGTH + 1 (81)", () => {
      const overName = "A".repeat(MAX_DISPLAY_NAME_LENGTH + 1);
      const op = { ...VALID_OPERATION_SALE, customerName: overName };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 1, reason: "INVALID_CUSTOMER" }]);
    });

    test("accepts description at exact MAX_DISPLAY_NAME_LENGTH (80)", () => {
      const maxDesc = "D".repeat(MAX_DISPLAY_NAME_LENGTH);
      const op = { ...VALID_OPERATION_SALE, description: maxDesc };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.description).toBe(maxDesc);
    });

    test("discards description at MAX_DISPLAY_NAME_LENGTH + 1 (81)", () => {
      const overDesc = "D".repeat(MAX_DISPLAY_NAME_LENGTH + 1);
      const op = { ...VALID_OPERATION_SALE, description: overDesc };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 1, reason: "MALFORMED" }]);
    });
  });

  describe("date validation and leap years", () => {
    test("accepts Feb 29 on leap years (2024, 2000)", () => {
      const leap2024 = { ...VALID_OPERATION_SALE, lineIndex: 0, date: { year: 2024, month: 2, day: 29 } };
      const leap2000 = { ...VALID_OPERATION_SALE, lineIndex: 1, date: { year: 2000, month: 2, day: 29 } };
      const res = parseLedgerExtraction({ operations: [leap2024, leap2000], pageIncomplete: false });
      expect(res?.operations).toHaveLength(2);
      expect(res?.operations[0]?.date).toEqual({ year: 2024, month: 2, day: 29 });
      expect(res?.operations[1]?.date).toEqual({ year: 2000, month: 2, day: 29 });
    });

    test("discards Feb 29 on non-leap years (2023, 1900)", () => {
      const nonLeap2023 = { ...VALID_OPERATION_SALE, lineIndex: 0, date: { year: 2023, month: 2, day: 29 } };
      const nonLeap1900 = { ...VALID_OPERATION_SALE, lineIndex: 1, date: { year: 1900, month: 2, day: 29 } };
      const res = parseLedgerExtraction({ operations: [nonLeap2023, nonLeap1900], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([
        { inputIndex: 0, lineIndex: 0, reason: "MALFORMED" },
        { inputIndex: 1, lineIndex: 1, reason: "MALFORMED" },
      ]);
    });

    test("discards out-of-bounds days like April 31 or month 13", () => {
      const apr31 = { ...VALID_OPERATION_SALE, lineIndex: 0, date: { year: 2026, month: 4, day: 31 } };
      const m13 = { ...VALID_OPERATION_SALE, lineIndex: 1, date: { year: 2026, month: 13, day: 1 } };
      const res = parseLedgerExtraction({ operations: [apr31, m13], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toHaveLength(2);
    });
  });

  describe("lineIndex ordering and uniqueness diagnostics", () => {
    test("discards duplicate lineIndex with DUPLICATE_LINE_INDEX diagnostic", () => {
      const result = parseLedgerExtraction({
        operations: [
          { ...VALID_OPERATION_SALE, lineIndex: 0 },
          { ...VALID_OPERATION_PAYMENT, lineIndex: 0 },
        ],
        pageIncomplete: false,
      });
      expect(result).toEqual({
        operations: [{ ...VALID_OPERATION_SALE, lineIndex: 0 }],
        discards: [{ inputIndex: 1, lineIndex: 0, reason: "DUPLICATE_LINE_INDEX" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });

    test("discards out-of-order lineIndex with OUT_OF_ORDER diagnostic", () => {
      const result = parseLedgerExtraction({
        operations: [
          { ...VALID_OPERATION_SALE, lineIndex: 5 },
          { ...VALID_OPERATION_PAYMENT, lineIndex: 2 },
        ],
        pageIncomplete: false,
      });
      expect(result).toEqual({
        operations: [{ ...VALID_OPERATION_SALE, lineIndex: 5 }],
        discards: [{ inputIndex: 1, lineIndex: 2, reason: "OUT_OF_ORDER" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });

    test("discards negative or non-integer lineIndex with MALFORMED diagnostic", () => {
      const result = parseLedgerExtraction({
        operations: [
          { ...VALID_OPERATION_SALE, lineIndex: -1 },
          { ...VALID_OPERATION_PAYMENT, lineIndex: 1.5 },
        ],
        pageIncomplete: false,
      });
      expect(result).toEqual({
        operations: [],
        discards: [
          { inputIndex: 0, reason: "MALFORMED" },
          { inputIndex: 1, reason: "MALFORMED" },
        ],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });
  });

  describe("uncertainFields validation diagnostics", () => {
    test("accepts empty array", () => {
      const op = { ...VALID_OPERATION_SALE, uncertainFields: [] };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations[0]?.uncertainFields).toEqual([]);
    });

    test("accepts all 4 valid fields without duplicates", () => {
      const op = {
        ...VALID_OPERATION_SALE,
        uncertainFields: ["AMOUNT", "CUSTOMER", "DATE", "KIND"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations[0]?.uncertainFields).toEqual(["AMOUNT", "CUSTOMER", "DATE", "KIND"]);
    });

    test("discards operation with duplicate uncertainFields with INVALID_UNCERTAIN_FIELDS diagnostic", () => {
      const op = {
        ...VALID_OPERATION_SALE,
        uncertainFields: ["AMOUNT", "AMOUNT"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res).toEqual({
        operations: [],
        discards: [{ inputIndex: 0, lineIndex: 1, reason: "INVALID_UNCERTAIN_FIELDS" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });

    test("discards operation with unknown uncertainField with INVALID_UNCERTAIN_FIELDS diagnostic", () => {
      const op = {
        ...VALID_OPERATION_SALE,
        uncertainFields: ["AMOUNT", "PRODUCT"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res).toEqual({
        operations: [],
        discards: [{ inputIndex: 0, lineIndex: 1, reason: "INVALID_UNCERTAIN_FIELDS" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });
  });

  describe("unreadableFields validation and mandatory omission rules", () => {
    test("accepts empty unreadableFields when mandatory fields are present", () => {
      const op = { ...VALID_OPERATION_SALE, unreadableFields: [] };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations[0]?.unreadableFields).toEqual([]);
      expect(res?.operations[0]?.customerName).toBe("Don Pepe");
      expect(res?.operations[0]?.amountCents).toBe("35000");
      expect(res?.operations[0]?.proposedKind).toBe("SALE");
    });

    test("allows omitting amountCents only when declared in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Don Pepe",
        uncertainFields: [],
        unreadableFields: ["AMOUNT"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.amountCents).toBeUndefined();
      expect(res?.operations[0]?.unreadableFields).toEqual(["AMOUNT"]);
    });

    test("allows omitting customerName only when declared in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        amountCents: "2000",
        uncertainFields: [],
        unreadableFields: ["CUSTOMER"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.customerName).toBeUndefined();
      expect(res?.operations[0]?.unreadableFields).toEqual(["CUSTOMER"]);
    });

    test("allows omitting proposedKind only when declared in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        customerName: "Don Pepe",
        amountCents: "2000",
        uncertainFields: [],
        unreadableFields: ["KIND"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.proposedKind).toBeUndefined();
      expect(res?.operations[0]?.unreadableFields).toEqual(["KIND"]);
    });

    test("allows omitting all three mandatory fields when all are declared in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        uncertainFields: [],
        unreadableFields: ["CUSTOMER", "AMOUNT", "KIND"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.customerName).toBeUndefined();
      expect(res?.operations[0]?.amountCents).toBeUndefined();
      expect(res?.operations[0]?.proposedKind).toBeUndefined();
    });

    test("discards with INVALID_MONEY when amountCents is missing and NOT in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Don Pepe",
        uncertainFields: [],
        unreadableFields: [],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_MONEY" }]);
    });

    test("discards with INVALID_CUSTOMER when customerName is missing and NOT in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        amountCents: "1000",
        uncertainFields: [],
        unreadableFields: [],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_CUSTOMER" }]);
    });

    test("discards with MALFORMED when proposedKind is missing and NOT in unreadableFields", () => {
      const op = {
        lineIndex: 0,
        customerName: "Don Pepe",
        amountCents: "1000",
        uncertainFields: [],
        unreadableFields: [],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "MALFORMED" }]);
    });

    test("discards with INVALID_UNREADABLE_FIELDS when a field is in BOTH uncertainFields and unreadableFields", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Don Pepe",
        uncertainFields: ["AMOUNT"],
        unreadableFields: ["AMOUNT"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_UNREADABLE_FIELDS" }]);
    });

    test("discards with INVALID_UNREADABLE_FIELDS when unreadableFields has duplicates", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Don Pepe",
        uncertainFields: [],
        unreadableFields: ["AMOUNT", "AMOUNT"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_UNREADABLE_FIELDS" }]);
    });

    test("discards with INVALID_UNREADABLE_FIELDS when unreadableFields has unknown value", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Don Pepe",
        amountCents: "1000",
        uncertainFields: [],
        unreadableFields: ["UNKNOWN_FIELD"],
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_UNREADABLE_FIELDS" }]);
    });
  });

  describe("region discard vs operation validity", () => {
    test.each([
      ["out of range x", { x: -0.1, y: 0.1, width: 0.5, height: 0.1 }],
      ["out of range y", { x: 0.1, y: 1.2, width: 0.5, height: 0.1 }],
      ["zero width", { x: 0.1, y: 0.1, width: 0, height: 0.1 }],
      ["negative height", { x: 0.1, y: 0.1, width: 0.5, height: -0.1 }],
      ["overflow x+width > 1", { x: 0.8, y: 0.1, width: 0.5, height: 0.1 }],
      ["extra region key", { x: 0.1, y: 0.1, width: 0.5, height: 0.1, label: "crop" }],
      ["non-number coordinates", { x: "0.1", y: 0.1, width: 0.5, height: 0.1 }],
    ])("discards malformed region (%s) while preserving the operation", (_desc, badRegion) => {
      const op = {
        ...VALID_OPERATION_SALE,
        region: badRegion,
      };
      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res).not.toBeNull();
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0]?.region).toBeUndefined();
      expect(res?.operations[0]?.customerName).toBe(VALID_OPERATION_SALE.customerName);
      expect(res?.discards).toEqual([]);
      expect(res?.pageIncomplete).toBe(false);
    });
  });

  describe("prototype hardening and non-throwing behavior", () => {
    test("rejects non-plain prototypes at root", () => {
      class DocumentModel {
        operations = [];
        pageIncomplete = false;
      }
      expect(parseLedgerExtraction(new DocumentModel())).toBeNull();
    });

    test("discards operation with non-plain prototype as MALFORMED", () => {
      class OpModel {
        lineIndex = 0;
        proposedKind = "SALE" as const;
        customerName = "Pepe";
        amountCents = "100";
        uncertainFields = [];
        unreadableFields = [];
      }
      const res = parseLedgerExtraction({ operations: [new OpModel()], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, reason: "MALFORMED" }]);
    });

    test("never throws when root or operation contains throwing getters", () => {
      const explosiveRoot = {
        get operations(): unknown {
          throw new Error("Explosive operations getter");
        },
        pageIncomplete: false,
      };
      expect(parseLedgerExtraction(explosiveRoot)).toBeNull();

      const explosiveOp = {
        get lineIndex(): number {
          throw new Error("Explosive lineIndex getter");
        },
        proposedKind: "SALE",
        customerName: "Pepe",
        amountCents: "100",
        uncertainFields: [],
        unreadableFields: [],
      };
      const res = parseLedgerExtraction({ operations: [explosiveOp], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, reason: "MALFORMED" }]);

      const explosiveRegionOp = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Pepe",
        amountCents: "100",
        uncertainFields: [],
        unreadableFields: [],
        region: {
          get x(): number {
            throw new Error("Explosive region getter");
          },
          y: 0.1,
          width: 0.5,
          height: 0.1,
        },
      };
      const resRegion = parseLedgerExtraction({ operations: [explosiveRegionOp], pageIncomplete: false });
      expect(resRegion?.operations).toHaveLength(1);
      expect(resRegion?.operations[0]?.region).toBeUndefined();
      expect(resRegion?.discards).toEqual([]);
    });

    test("discards operation with own symbols as MALFORMED", () => {
      const symbolOp = { ...VALID_OPERATION_SALE, [Symbol("hack")]: true };
      const res = parseLedgerExtraction({ operations: [symbolOp], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, reason: "MALFORMED" }]);
    });
  });

  describe("forbidden fields and injection prevention", () => {
    test.each([
      ["SQL attempt", { ...VALID_OPERATION_SALE, sql: "SELECT * FROM sales" }],
      ["id assignment", { ...VALID_OPERATION_SALE, id: "sale_123" }],
      ["tool call", { ...VALID_OPERATION_SALE, toolCall: "recordPayment" }],
      ["computed balance", { ...VALID_OPERATION_SALE, balanceCents: "50000" }],
    ])("discards operation with unwhitelisted key (%s) with DISALLOWED_PROPERTIES", (_desc, injectedOp) => {
      const res = parseLedgerExtraction({ operations: [injectedOp], pageIncomplete: false });
      expect(res).toEqual({
        operations: [],
        discards: [{ inputIndex: 0, lineIndex: 1, reason: "DISALLOWED_PROPERTIES" }],
        unreadTextRemaining: false,
        pageIncomplete: true,
      });
    });
  });

  describe("contract v4 parsing (confidence, sources, items, warnings, pageDate)", () => {
    test("parses full v4 operation with valid confidence, sources, items, rawText, warnings and root pageDate", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "3800",
        uncertainFields: [],
        unreadableFields: [],
        confidence: { customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 },
        sources: { customer: "EXPLICIT", date: "PAGE_DATE" },
        rawText: "Rosa 2 cocas 38",
        items: [{ description: "coca", quantity: 2, subtotalCents: "3800" }],
        warnings: [],
      };

      const res = parseLedgerExtraction({
        pageDate: { date: { year: 2026, month: 6, day: 15 }, confidence: 0.9 },
        operations: [op],
        pageIncomplete: false,
      });

      expect(res).not.toBeNull();
      expect(res?.pageDate).toEqual({ date: { year: 2026, month: 6, day: 15 }, confidence: 0.9 });
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0].confidence?.customer).toBe(0.95);
      expect(res?.operations[0].sources?.customer).toBe("EXPLICIT");
      expect(res?.operations[0].rawText).toBe("Rosa 2 cocas 38");
      expect(res?.operations[0].items).toEqual([{ description: "coca", quantity: 2, subtotalCents: "3800" }]);
    });

    test.each([
      ["out of range 1.5", 1.5],
      ["negative -0.1", -0.1],
      ["string '0.9'", "0.9"],
      ["NaN", NaN],
      ["Infinity", Infinity],
    ])("discards operation when confidence value is invalid (%s)", (_desc, badConf) => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "3800",
        uncertainFields: [],
        unreadableFields: [],
        confidence: { customer: badConf },
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "MALFORMED" }]);
    });

    test("discards operation when item subtotalCents is a JSON number", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "3800",
        uncertainFields: [],
        unreadableFields: [],
        items: [{ description: "coca", quantity: 2, subtotalCents: 3800 }],
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(0);
      expect(res?.discards).toEqual([{ inputIndex: 0, lineIndex: 0, reason: "INVALID_MONEY" }]);
    });

    test("item total mismatch adds ITEMS_TOTAL_MISMATCH warning and AMOUNT to uncertainFields without recalculating total", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "5000", // 50 pesos
        uncertainFields: [],
        unreadableFields: [],
        items: [{ description: "coca", quantity: 1, subtotalCents: "2000" }], // 20 pesos
        warnings: [],
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      expect(res?.operations).toHaveLength(1);
      const parsedOp = res?.operations[0];
      expect(parsedOp?.amountCents).toBe("5000"); // Preserves original amountCents, does NOT recalculate!
      expect(parsedOp?.uncertainFields).toContain("AMOUNT");
      expect(parsedOp?.warnings).toContain("ITEMS_TOTAL_MISMATCH");
    });

    test("a warning that means somebody must look also blocks the row, not just describes it", () => {
      // A warning that only travels as text is a warning a client can drop, and one already did.
      // The list is the brake; the text is only advice about why.
      const ambiguous = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Pedro",
        amountCents: "3400",
        uncertainFields: [],
        unreadableFields: [],
        warnings: ["AMBIGUOUS_SEPARATOR"],
      };

      const op = parseLedgerExtraction({ operations: [ambiguous], pageIncomplete: false })?.operations[0];

      expect(op?.uncertainFields).toContain("AMOUNT");
      expect(op?.warnings).toContain("AMBIGUOUS_SEPARATOR");
    });

    test("an inherited customer is never left looking certain", () => {
      const inherited = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "3400",
        uncertainFields: [],
        unreadableFields: [],
        sources: { customer: "INHERITED_PREVIOUS_ROW" },
        warnings: ["INHERITED_CUSTOMER"],
      };

      const op = parseLedgerExtraction({ operations: [inherited], pageIncomplete: false })?.operations[0];

      expect(op?.uncertainFields).toContain("CUSTOMER");
    });

    test("a row the model itself calls a note is discarded, not offered for review", () => {
      // Offering it invites somebody to confirm a debt that was never written.
      const note = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "recordatorio",
        amountCents: "100",
        uncertainFields: [],
        unreadableFields: [],
        warnings: ["NOT_A_MOVEMENT"],
      };

      const res = parseLedgerExtraction({ operations: [note], pageIncomplete: false });

      expect(res?.operations).toHaveLength(0);
    });

    test("an operation without rawText still parses: the contract keeps it optional", () => {
      // El bloque 2 ahora EXIGE rawText en la instrucción, pero el parser no puede volverse estricto
      // por eso: si el modelo lo omite alguna vez, rechazar la respuesta entera dejaría sin libreta a
      // los teléfonos que ya tienen la app instalada (v37 publicado y v40 en uso). Android objeta por
      // su lado cuando falta, así que el renglón dudoso sigue siendo visible; lo que no puede pasar es
      // que el servidor se caiga entero.
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "25000",
        uncertainFields: [],
        unreadableFields: [],
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });

      expect(res).not.toBeNull();
      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0].rawText).toBeUndefined();
    });

    test("an operation carrying a key the contract does not know is discarded, not accepted", () => {
      // El bloque 2 no añade campos a la respuesta, y esta es la prueba de que el contrato sigue
      // cerrado: una llave de más descarta el renglón. Si algún día se añadiera un campo nuevo, esto
      // se pone rojo y avisa de que hay teléfonos que van a rechazar la respuesta completa.
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "25000",
        uncertainFields: [],
        unreadableFields: [],
        campoQueElContratoNoConoce: "algo",
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });

      expect(res?.operations).toHaveLength(0);
    });

    test("a pageTotals list longer than the cap keeps the first ten and marks the page unread", () => {
      // Decisión de Diego: con más de MAX_LEDGER_PAGE_TOTALS NO se ignora la lista. Se conservan las
      // primeras y la página queda marcada como "quedó algo sin comprobar", para que Android pida la
      // confirmación de página —que ya existe— en vez de perder esas comprobaciones en silencio.
      const eleven = Array.from({ length: MAX_LEDGER_PAGE_TOTALS + 1 }, (_, i) => ({
        label: `saldo ${i}`,
        amountCents: "100",
      }));

      const res = parseLedgerExtraction({
        operations: [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Rosa",
            amountCents: "100",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        pageIncomplete: false,
        pageTotals: eleven,
      });

      expect(res).not.toBeNull();
      expect(res?.operations).toHaveLength(1);
      expect(res?.pageTotals).toHaveLength(MAX_LEDGER_PAGE_TOTALS);
      expect(res?.pageTotals?.[0]?.label).toBe("saldo 0");
      expect(res?.pageTotals?.[MAX_LEDGER_PAGE_TOTALS - 1]?.label).toBe(`saldo ${MAX_LEDGER_PAGE_TOTALS - 1}`);
      expect(res?.unreadTextRemaining).toBe(true);
      expect(res?.pageIncomplete).toBe(true);
    });

    test("a pageTotals list at exactly the cap is kept whole and does not mark the page", () => {
      // El borde bueno: el límite no puede estar tirando listas legítimas ni marcando páginas que
      // estaban completas.
      const ten = Array.from({ length: MAX_LEDGER_PAGE_TOTALS }, (_, i) => ({
        label: `saldo ${i}`,
        amountCents: "100",
      }));

      const res = parseLedgerExtraction({
        operations: [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Rosa",
            amountCents: "100",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        pageIncomplete: false,
        pageTotals: ten,
      });

      expect(res?.pageTotals).toHaveLength(MAX_LEDGER_PAGE_TOTALS);
      expect(res?.unreadTextRemaining).toBe(false);
      expect(res?.pageIncomplete).toBe(false);
    });

    test("an operation with more items than the cap is discarded, so the output stays bounded", () => {
      // `items` no tenía tope, y un campo sin tope deja el peor caso de la respuesta sin cota: el
      // presupuesto de salida del proveedor no se puede justificar sobre algo que crece sin límite.
      const tooManyItems = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "25000",
        uncertainFields: [],
        unreadableFields: [],
        items: Array.from({ length: MAX_LEDGER_ITEMS_PER_OPERATION + 1 }, (_, j) => ({
          description: `producto ${j}`,
          quantity: 1,
          subtotalCents: "100",
        })),
      };

      const res = parseLedgerExtraction({ operations: [tooManyItems], pageIncomplete: false });

      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0].items).toHaveLength(5);
      expect(res?.operations[0].uncertainFields).toContain("AMOUNT");
    });

    test("an operation with exactly the cap of items still parses", () => {
      // El borde por el lado bueno: el límite no puede estar apagando renglones legítimos.
      const atCap = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "25000",
        uncertainFields: [],
        unreadableFields: [],
        items: Array.from({ length: MAX_LEDGER_ITEMS_PER_OPERATION }, (_, j) => ({
          description: `producto ${j}`,
          quantity: 1,
          subtotalCents: "100",
        })),
      };

      const res = parseLedgerExtraction({ operations: [atCap], pageIncomplete: false });

      expect(res?.operations).toHaveLength(1);
      expect(res?.operations[0].items).toHaveLength(MAX_LEDGER_ITEMS_PER_OPERATION);
    });

    test("a warning about a field the model could not read does not soften it to a doubt", () => {
      const unreadable = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        uncertainFields: [],
        unreadableFields: ["AMOUNT"],
        warnings: ["LOW_CONFIDENCE_AMOUNT"],
      };

      const op = parseLedgerExtraction({ operations: [unreadable], pageIncomplete: false })?.operations[0];

      expect(op?.unreadableFields).toContain("AMOUNT");
      expect(op?.uncertainFields).not.toContain("AMOUNT");
    });

    test("a note that is not a movement is not turned into one", () => {
      // A shopping reminder written in the margin has no customer and no money. The model is told
      // to leave it out entirely, and if it ever sends one anyway the parser must not let a row
      // with no customer and no amount through as if it were debt somebody owes.
      const note = {
        lineIndex: 0,
        description: "comprar bolsas el jueves",
        uncertainFields: [],
        unreadableFields: [],
      };

      const res = parseLedgerExtraction({ operations: [note], pageIncomplete: false });

      expect(res?.operations).toHaveLength(0);
      expect(res?.discards.length).toBeGreaterThan(0);
    });

    test("a row with more than one price is not resolved by picking one", () => {
      // "Pedro 2 sabritas 34 + coca 18" has two prices and one total. Choosing 34, or 18, or
      // quietly adding them, are all the model inventing a number nobody wrote. The row arrives
      // with its items and a total, and when they disagree the amount goes back for review instead
      // of being reconciled by arithmetic.
      const twoPrices = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Pedro",
        amountCents: "3400",
        uncertainFields: [],
        unreadableFields: [],
        items: [
          { description: "sabritas", quantity: 2, subtotalCents: "3400" },
          { description: "coca", subtotalCents: "1800" },
        ],
        warnings: [],
      };

      const res = parseLedgerExtraction({ operations: [twoPrices], pageIncomplete: false });

      const op = res?.operations[0];
      expect(op?.amountCents).toBe("3400");
      expect(op?.uncertainFields).toContain("AMOUNT");
      expect(op?.warnings).toContain("ITEMS_TOTAL_MISMATCH");
      // Both prices survive: the person decides which reading is right, with the paper in hand.
      expect(op?.items).toHaveLength(2);
    });

    test("a mismatch never downgrades an amount the model said it could not read", () => {
      // The two lists are the record of what the model actually claimed. Moving AMOUNT from
      // unreadable to uncertain would be the parser making a weaker claim than the model made, and
      // hiding that it could not read the number at all. The warning carries the mismatch instead.
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "5000",
        uncertainFields: [],
        unreadableFields: ["AMOUNT"],
        items: [{ description: "coca", quantity: 1, subtotalCents: "2000" }],
        warnings: [],
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      const parsedOp = res?.operations[0];
      expect(parsedOp?.unreadableFields).toContain("AMOUNT");
      expect(parsedOp?.uncertainFields).not.toContain("AMOUNT");
      expect(parsedOp?.warnings).toContain("ITEMS_TOTAL_MISMATCH");
    });

    test("the mismatch warning is not repeated when the model already declared it", () => {
      const op = {
        lineIndex: 0,
        proposedKind: "SALE",
        customerName: "Rosa",
        amountCents: "5000",
        uncertainFields: ["AMOUNT"],
        unreadableFields: [],
        items: [{ description: "coca", subtotalCents: "2000" }],
        warnings: ["ITEMS_TOTAL_MISMATCH"],
      };

      const res = parseLedgerExtraction({ operations: [op], pageIncomplete: false });
      const parsedOp = res?.operations[0];
      expect(parsedOp?.warnings?.filter((w) => w === "ITEMS_TOTAL_MISMATCH")).toHaveLength(1);
      expect(parsedOp?.uncertainFields?.filter((f) => f === "AMOUNT")).toHaveLength(1);
    });

    test("v5 page totals reject invalid entries without rejecting the page", () => {
      const result = parseLedgerExtraction({
        operations: [VALID_OPERATION_SALE],
        pageIncomplete: false,
        pageType: "ACCOUNTS",
        pageTotals: [
          { label: "Saldo", amountCents: "1000", afterLineIndex: 1 },
          { label: "Falso", amountCents: 1000, afterLineIndex: 1 },
          { label: "Fuera", amountCents: "2000", afterLineIndex: 99 },
        ],
      });
      expect(result?.pageType).toBe("ACCOUNTS");
      expect(result?.pageTotals).toEqual([{ label: "Saldo", amountCents: "1000", afterLineIndex: 1 }]);
    });
  });
});
