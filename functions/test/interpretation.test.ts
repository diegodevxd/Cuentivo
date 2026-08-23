import { describe, expect, test } from "vitest";
import { parseSafeAiProposal } from "../src/ai/interpretation.js";

describe("parseSafeAiProposal", () => {
  test.each([
    [
      "requires_clarification valid",
      { kind: "requires_clarification", reason: "AMBIGUOUS_CUSTOMER", unresolvedSlots: ["CUSTOMER", "AMOUNT"] },
    ],
    [
      "create_sale valid with promised date",
      {
        kind: "create_sale",
        customerName: "María",
        lines: [
          { productName: "Perfume A", quantity: 2, unitPriceCents: "30000" },
          { productName: "Cremas", quantity: 1, unitPriceCents: "15000" },
        ],
        declaredInitialPaymentCents: "20000",
        promisedPaymentDate: { year: 2026, month: 8, day: 30 },
      },
    ],
    [
      "create_sale valid zero payment without promise",
      {
        kind: "create_sale",
        customerName: "María",
        lines: [{ productName: "Perfume A", quantity: 1, unitPriceCents: "0" }],
        declaredInitialPaymentCents: "0",
      },
    ],
    [
      "record_payment valid zero amount",
      { kind: "record_payment", customerName: "María", declaredAmountCents: "0" },
    ],
    [
      "business_query balance with customer",
      { kind: "business_query", queryType: "CUSTOMER_BALANCE", customerName: "María" },
    ],
    [
      "business_query debt without selectors",
      { kind: "business_query", queryType: "CUSTOMERS_WITH_DEBT" },
    ],
    [
      "business_query sales with period",
      {
        kind: "business_query",
        queryType: "SALES_FOR_PERIOD",
        period: { startYear: 2026, startMonth: 8, startDay: 1, endYear: 2026, endMonth: 8, endDay: 31 },
      },
    ],
  ] as const)("accepts %s", (_name, value) => {
    expect(parseSafeAiProposal(value)).toEqual(value);
  });

  test("accepts maximum canonical money (18 digits) and zero", () => {
    const proposal = {
      kind: "record_payment",
      customerName: "A",
      declaredAmountCents: "999999999999999999",
    };
    expect(parseSafeAiProposal(proposal)).toEqual(proposal);
    expect(
      parseSafeAiProposal({ kind: "record_payment", customerName: "A", declaredAmountCents: "0" }),
    ).toEqual({ kind: "record_payment", customerName: "A", declaredAmountCents: "0" });
  });

  test("accepts up to 8 sale lines and rejects the ninth", () => {
    const lines = Array.from({ length: 8 }, (_, i) => ({
      productName: `P${i}`,
      quantity: 1,
      unitPriceCents: "1",
    }));
    const ok = { kind: "create_sale", customerName: "A", lines, declaredInitialPaymentCents: "0" };
    expect(parseSafeAiProposal(ok)?.kind).toBe("create_sale");

    const ninth = [...lines, { productName: "P8", quantity: 1, unitPriceCents: "1" }];
    expect(parseSafeAiProposal({ ...ok, lines: ninth })).toBeNull();
  });

  test("accepts an 80-char display name and rejects an 81-char name", () => {
    const name80 = "X".repeat(80);
    const ok = { kind: "record_payment", customerName: name80, declaredAmountCents: "0" };
    expect(parseSafeAiProposal(ok)).toEqual(ok);

    const name81 = "X".repeat(81);
    expect(
      parseSafeAiProposal({ kind: "record_payment", customerName: name81, declaredAmountCents: "0" }),
    ).toBeNull();
  });

  test("rejects raw display names padded with spaces beyond the raw limit", () => {
    // Raw length counts the padding: a provider must not bypass the 80 limit with whitespace
    // that trims away. The value is never normalized, only rejected.
    const padded = " ".repeat(1000) + "A";
    expect(
      parseSafeAiProposal({ kind: "record_payment", customerName: padded, declaredAmountCents: "0" }),
    ).toBeNull();
    expect(
      parseSafeAiProposal({
        kind: "create_sale",
        customerName: "A",
        lines: [{ productName: padded, quantity: 1, unitPriceCents: "1" }],
        declaredInitialPaymentCents: "0",
      }),
    ).toBeNull();
  });

  test.each([
    ["global one", "01"],
    ["negative one", "-1"],
    ["decimal", "1.5"],
    ["spaces", " 100"],
    ["exponent", "1e3"],
    ["empty", ""],
    ["nineteen digits", "1000000000000000000"],
  ] as const)("rejects malformed money string %s", (_name, money) => {
    expect(
      parseSafeAiProposal({ kind: "record_payment", customerName: "A", declaredAmountCents: money }),
    ).toBeNull();
  });

  test.each([
    ["extra key on clarification", { kind: "requires_clarification", reason: "AMBIGUOUS_CUSTOMER", unresolvedSlots: ["CUSTOMER"], extra: true }],
    ["unknown reason", { kind: "requires_clarification", reason: "NOPE", unresolvedSlots: ["CUSTOMER"] }],
    ["mismatched reason enum", { kind: "requires_clarification", reason: "UNSUPPORTED", unresolvedSlots: ["CUSTOMER"] }],
    ["empty slots", { kind: "requires_clarification", reason: "MISSING_CUSTOMER", unresolvedSlots: [] }],
    ["duplicated slots", { kind: "requires_clarification", reason: "AMBIGUOUS_PRODUCT", unresolvedSlots: ["PRODUCT", "PRODUCT"] }],
    ["unknown slot", { kind: "requires_clarification", reason: "AMBIGUOUS_CUSTOMER", unresolvedSlots: ["CUSTOMER", "MONEY"] }],
    ["missing slots", { kind: "requires_clarification", reason: "AMBIGUOUS_CUSTOMER" }],
    ["foreign slot string", { kind: "requires_clarification", reason: "AMBIGUOUS_CUSTOMER", unresolvedSlots: ["CUSTOMER", 5] }],
    ["extra key on sale", { kind: "create_sale", customerName: "A", lines: [], declaredInitialPaymentCents: "0", id: "x" }],
    ["sale empty lines", { kind: "create_sale", customerName: "A", lines: [], declaredInitialPaymentCents: "0" }],
    ["sale too many lines", { kind: "create_sale", customerName: "A", lines: Array.from({ length: 21 }, (_) => ({ productName: "P", quantity: 1, unitPriceCents: "1" })), declaredInitialPaymentCents: "0" }],
    ["sale blank customer", { kind: "create_sale", customerName: "  ", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0" }],
    ["sale bad line extra key", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1", qty: 2 }], declaredInitialPaymentCents: "0" }],
    ["sale blank product", { kind: "create_sale", customerName: "A", lines: [{ productName: "", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0" }],
    ["sale zero quantity", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 0, unitPriceCents: "1" }], declaredInitialPaymentCents: "0" }],
    ["sale fractional quantity", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1.5, unitPriceCents: "1" }], declaredInitialPaymentCents: "0" }],
    ["sale negative price", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "-1" }], declaredInitialPaymentCents: "0" }],
    ["sale bad payment", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "01" }],
    ["sale invalid promise month 13", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0", promisedPaymentDate: { year: 2026, month: 13, day: 1 } }],
    ["sale invalid promise leap", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0", promisedPaymentDate: { year: 2023, month: 2, day: 29 } }],
    ["sale promise year zero", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0", promisedPaymentDate: { year: 0, month: 1, day: 1 } }],
    ["sale promise extra key", { kind: "create_sale", customerName: "A", lines: [{ productName: "P", quantity: 1, unitPriceCents: "1" }], declaredInitialPaymentCents: "0", promisedPaymentDate: { year: 2026, month: 8, day: 30, extra: 1 } }],
    ["payment extra key", { kind: "record_payment", customerName: "A", declaredAmountCents: "0", balanceCents: "100" }],
    ["payment blank customer", { kind: "record_payment", customerName: "", declaredAmountCents: "0" }],
    ["payment negative amount", { kind: "record_payment", customerName: "A", declaredAmountCents: "-1" }],
    ["query extra key", { kind: "business_query", queryType: "CUSTOMERS_WITH_DEBT", sql: "SELECT" }],
    ["query unknown type", { kind: "business_query", queryType: "DELETE_ALL" }],
    ["query balance missing customer", { kind: "business_query", queryType: "CUSTOMER_BALANCE" }],
    ["query balance with period", { kind: "business_query", queryType: "CUSTOMER_BALANCE", customerName: "A", period: { startYear: 2026, startMonth: 8, startDay: 1, endYear: 2026, endMonth: 8, endDay: 31 } }],
    ["query debt with selector", { kind: "business_query", queryType: "CUSTOMERS_WITH_DEBT", customerName: "A" }],
    ["query sales without period", { kind: "business_query", queryType: "SALES_FOR_PERIOD" }],
    ["query sales with customer", { kind: "business_query", queryType: "SALES_FOR_PERIOD", customerName: "A", period: { startYear: 2026, startMonth: 8, startDay: 1, endYear: 2026, endMonth: 8, endDay: 31 } }],
    ["query invalid period month", { kind: "business_query", queryType: "SALES_FOR_PERIOD", period: { startYear: 2026, startMonth: 13, startDay: 1, endYear: 2026, endMonth: 8, endDay: 31 } }],
    ["query inverted period", { kind: "business_query", queryType: "SALES_FOR_PERIOD", period: { startYear: 2026, startMonth: 8, startDay: 31, endYear: 2026, endMonth: 8, endDay: 1 } }],
    ["root array", [{ kind: "record_payment", customerName: "A", declaredAmountCents: "0" }]],
    ["root null", null],
    ["root string", "kind"],
    ["unknown kind", { kind: "sale_draft" }],
  ] as const)("rejects %s with null", (_name, value) => {
    expect(parseSafeAiProposal(value)).toBeNull();
  });
});
