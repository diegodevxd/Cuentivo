import { describe, expect, test, vi } from "vitest";
import {
  createGeminiTextProvider,
  SAFE_PROPOSAL_JSON_SCHEMA,
  type GeminiClientFactory,
  type GeminiClientSeam,
} from "../src/ai/geminiTextProvider.js";

const VALID_PROPOSAL = {
  kind: "record_payment",
  customerName: "María",
  declaredAmountCents: "15000",
} as const;

function createMockClient(
  handler: (params: any) => Promise<{ text?: string | null }>,
): GeminiClientSeam {
  return {
    models: {
      generateContent: vi.fn().mockImplementation(handler),
    },
  };
}

describe("createGeminiTextProvider", () => {
  test("configures HTTP timeout and strictly disables SDK retries (attempts: 1)", async () => {
    let capturedFactoryOptions: any = null;
    const mockClient = createMockClient(async () => ({
      text: JSON.stringify(VALID_PROPOSAL),
    }));

    const mockFactory: GeminiClientFactory = (options) => {
      capturedFactoryOptions = options;
      return mockClient;
    };

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockFactory,
    );

    await provider.interpret({ text: "vendo zapatos" });

    expect(capturedFactoryOptions).toBeDefined();
    expect(capturedFactoryOptions.apiKey).toBe("test-fake-key");
    expect(capturedFactoryOptions.httpOptions).toEqual({
      timeout: 5000,
      retryOptions: {
        attempts: 1,
      },
    });
  });

  test("requests model gemini-2.5-flash-lite with temperature 0, cap 512 tokens, application/json and the proposal schema", async () => {
    let capturedParams: any = null;
    const mockClient = createMockClient(async (params) => {
      capturedParams = params;
      return { text: JSON.stringify(VALID_PROPOSAL) };
    });

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "vendo zapatos" });

    expect(mockClient.models.generateContent).toHaveBeenCalledTimes(1);
    expect(capturedParams).toBeDefined();
    expect(capturedParams.model).toBe("gemini-2.5-flash-lite");
    expect(capturedParams.contents).toBe("vendo zapatos");
    expect(capturedParams.config).toMatchObject({
      responseMimeType: "application/json",
      responseJsonSchema: SAFE_PROPOSAL_JSON_SCHEMA,
      temperature: 0,
      maxOutputTokens: 512,
    });
    expect(capturedParams.config.responseSchema).toBeUndefined();
    expect(SAFE_PROPOSAL_JSON_SCHEMA.anyOf).toBeDefined();
    expect(result).toEqual(VALID_PROPOSAL);
  });

  test("proposal schema is independent exact kind-schemas covering all four kinds", () => {
    const branches = SAFE_PROPOSAL_JSON_SCHEMA.anyOf as readonly any[];
    expect(branches.length).toBe(6);

    const kinds = branches
      .map((b) => (b.properties?.kind?.enum as readonly string[] | undefined)?.[0])
      .filter((k): k is string => typeof k === "string");
    expect([...new Set(kinds)].sort()).toEqual(
      ["business_query", "create_sale", "record_payment", "requires_clarification"].sort(),
    );

    const branchByKindAndQuery = (kind: string, query?: string): any =>
      branches.find(
        (b) =>
          (b.properties?.kind?.enum ?? []).includes(kind) &&
          (query === undefined ||
            (b.properties?.queryType?.enum as readonly string[] | undefined)?.[0] === query),
      );

    const clarify = branchByKindAndQuery("requires_clarification");
    expect(clarify).toBeDefined();
    expect(clarify.type).toBe("object");
    expect(clarify.additionalProperties).toBe(false);
    expect(Object.keys(clarify.properties ?? {}).sort()).toEqual(
      ["kind", "reason", "unresolvedSlots"].sort(),
    );
    expect(clarify.required).toEqual(["kind", "reason", "unresolvedSlots"]);

    const sale = branchByKindAndQuery("create_sale");
    expect(sale).toBeDefined();
    expect(sale.additionalProperties).toBe(false);
    expect(Object.keys(sale.properties ?? {}).sort()).toEqual(
      ["kind", "customerName", "lines", "declaredInitialPaymentCents", "promisedPaymentDate"].sort(),
    );
    expect(sale.required).toEqual(["kind", "customerName", "lines", "declaredInitialPaymentCents"]);
    // Hallazgo 4: initial-proposal bounds representable within the 512-token output cap.
    expect(sale.properties.lines.maxItems).toBe(8);
    expect(sale.properties.customerName.maxLength).toBe(80);
    expect(sale.properties.lines.items.properties.productName.maxLength).toBe(80);

    const payment = branchByKindAndQuery("record_payment");
    expect(payment).toBeDefined();
    expect(payment.additionalProperties).toBe(false);
    expect(Object.keys(payment.properties ?? {}).sort()).toEqual(
      ["kind", "customerName", "declaredAmountCents"].sort(),
    );
    expect(payment.required).toEqual(["kind", "customerName", "declaredAmountCents"]);
    expect(payment.properties.customerName.maxLength).toBe(80);

    // Hallazgo 3: business_query splits into three exact per-queryType variants.
    const balance = branchByKindAndQuery("business_query", "CUSTOMER_BALANCE");
    expect(balance).toBeDefined();
    expect(balance.additionalProperties).toBe(false);
    expect(Object.keys(balance.properties ?? {}).sort()).toEqual(
      ["kind", "queryType", "customerName"].sort(),
    );
    expect(balance.required).toEqual(["kind", "queryType", "customerName"]);
    expect(Object.keys(balance.properties ?? {})).not.toContain("period");

    const debt = branchByKindAndQuery("business_query", "CUSTOMERS_WITH_DEBT");
    expect(debt).toBeDefined();
    expect(debt.additionalProperties).toBe(false);
    expect(Object.keys(debt.properties ?? {}).sort()).toEqual(["kind", "queryType"].sort());
    expect(debt.required).toEqual(["kind", "queryType"]);

    const sales = branchByKindAndQuery("business_query", "SALES_FOR_PERIOD");
    expect(sales).toBeDefined();
    expect(sales.additionalProperties).toBe(false);
    expect(Object.keys(sales.properties ?? {}).sort()).toEqual(
      ["kind", "queryType", "period"].sort(),
    );
    expect(sales.required).toEqual(["kind", "queryType", "period"]);
    expect(Object.keys(sales.properties ?? {})).not.toContain("customerName");
  });

  test.each([
    [
      {
        kind: "requires_clarification",
        reason: "AMBIGUOUS_CUSTOMER",
        unresolvedSlots: ["CUSTOMER", "AMOUNT"],
      },
    ],
    [
      {
        kind: "create_sale",
        customerName: "María",
        lines: [{ productName: "Perfume A", quantity: 2, unitPriceCents: "30000" }],
        declaredInitialPaymentCents: "20000",
        promisedPaymentDate: { year: 2026, month: 8, day: 30 },
      },
    ],
    [{ kind: "record_payment", customerName: "María", declaredAmountCents: "0" }],
    [
      {
        kind: "business_query",
        queryType: "SALES_FOR_PERIOD",
        period: { startYear: 2026, startMonth: 8, startDay: 1, endYear: 2026, endMonth: 8, endDay: 31 },
      },
    ],
  ] as const)("parses valid allowed proposal %o", async (allowedProposal) => {
    const mockClient = createMockClient(async () => ({
      text: JSON.stringify(allowedProposal),
    }));

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toEqual(allowedProposal);
  });

  test("returns null when model returns missing / null / empty text", async () => {
    for (const emptyText of [null, undefined, "", "   "]) {
      const mockClient = createMockClient(async () => ({
        text: emptyText as any,
      }));
      const provider = createGeminiTextProvider(
        { apiKey: "test-fake-key", timeoutMillis: 5000 },
        mockClient,
      );

      const result = await provider.interpret({ text: "input" });
      expect(result).toBeNull();
    }
  });

  test("returns null when model returns malformed JSON", async () => {
    const mockClient = createMockClient(async () => ({
      text: "{ invalid json ...",
    }));
    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toBeNull();
  });

  test.each([
    ["extra property", { kind: "record_payment", customerName: "A", declaredAmountCents: "0", amountCents: 500 }],
    ["customer id property", { kind: "record_payment", customerName: "A", declaredAmountCents: "0", customerId: "c1" }],
    ["malformed money", { kind: "record_payment", customerName: "A", declaredAmountCents: "01" }],
    ["negative money", { kind: "record_payment", customerName: "A", declaredAmountCents: "-1" }],
    ["unknown kind", { kind: "sale_draft", customerName: "A" }],
    ["duplicated slots", { kind: "requires_clarification", reason: "AMBIGUOUS_PRODUCT", unresolvedSlots: ["PRODUCT", "PRODUCT"] }],
    ["empty slots", { kind: "requires_clarification", reason: "MISSING_CUSTOMER", unresolvedSlots: [] }],
    ["inverted period", { kind: "business_query", queryType: "SALES_FOR_PERIOD", period: { startYear: 2026, startMonth: 8, startDay: 31, endYear: 2026, endMonth: 8, endDay: 1 } }],
    ["query invalid shape", { kind: "business_query", queryType: "CUSTOMER_BALANCE" }],
    ["array root", [{ kind: "record_payment", customerName: "A", declaredAmountCents: "0" }]],
    ["nested object", { kind: "record_payment", customerName: "A", declaredAmountCents: "0", payload: { x: 1 } }],
  ] as const)("returns null when model returns schema-invalid JSON (%s)", async (_name, invalidPayload) => {
    const mockClient = createMockClient(async () => ({
      text: JSON.stringify(invalidPayload),
    }));
    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toBeNull();
  });

  test("returns null and never throws when client throws an error", async () => {
    const mockClient = createMockClient(async () => {
      throw new Error("SDK network failure");
    });
    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toBeNull();
    expect(mockClient.models.generateContent).toHaveBeenCalledTimes(1);
  });

  test("returns null when invocation times out and never retries", async () => {
    const mockClient = createMockClient(
      () =>
        new Promise<{ text: string }>((resolve) => {
          setTimeout(() => {
            resolve({ text: JSON.stringify(VALID_PROPOSAL) });
          }, 200);
        }),
    );

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 50 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toBeNull();
    expect(mockClient.models.generateContent).toHaveBeenCalledTimes(1);
  });

  test("never retries on failure (strictly at most once invocation)", async () => {
    const generateFn = vi.fn().mockRejectedValue(new Error("Internal error"));
    const mockClient: GeminiClientSeam = {
      models: {
        generateContent: generateFn,
      },
    };

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    await provider.interpret({ text: "input" });
    expect(generateFn).toHaveBeenCalledTimes(1);
  });
});
