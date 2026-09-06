import { describe, expect, test } from "vitest";
import {
  MAX_LEDGER_OPERATIONS,
  parseLedgerExtraction,
} from "../src/ai/ledgerExtraction.js";
import { MAX_DISPLAY_NAME_LENGTH } from "../src/ai/interpretation.js";
import type { LedgerOperation } from "../src/contract.js";

const VALID_OPERATION_PRIOR_DEBT: LedgerOperation = {
  lineIndex: 0,
  proposedKind: "PRIOR_DEBT",
  customerName: "Doña Carmen",
  amountCents: "50000",
  description: "Saldo libreta vieja",
  date: { year: 2026, month: 8, day: 15 },
  uncertainFields: ["DATE"],
  region: { x: 0.1, y: 0.2, width: 0.8, height: 0.05 },
};

const VALID_OPERATION_SALE: LedgerOperation = {
  lineIndex: 1,
  proposedKind: "SALE",
  customerName: "Don Pepe",
  amountCents: "35000",
  uncertainFields: [],
};

const VALID_OPERATION_PAYMENT: LedgerOperation = {
  lineIndex: 2,
  proposedKind: "PAYMENT",
  customerName: "Rosa López",
  amountCents: "10000",
  description: "Abono efectivo",
  uncertainFields: ["AMOUNT", "CUSTOMER"],
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
});
