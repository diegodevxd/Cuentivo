import { describe, expect, test, vi } from "vitest";
import {
  buildSystemInstruction,
  createGeminiTextProvider,
  SAFE_PROPOSAL_JSON_SCHEMA,
  type GeminiClientFactory,
  type GeminiClientSeam,
  type ReferenceDate,
} from "../src/ai/geminiTextProvider.js";

const VALID_PROPOSAL = {
  kind: "record_payment",
  customerName: "María",
  declaredAmountCents: "15000",
} as const;

const TEST_REF_DATE: ReferenceDate = {
  year: 2026,
  month: 8,
  day: 24,
  isoDate: "2026-08-24",
  weekday: "lunes",
};

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

    await provider.interpret({ text: "vendo zapatos", referenceDate: TEST_REF_DATE });

    expect(capturedFactoryOptions).toBeDefined();
    expect(capturedFactoryOptions.apiKey).toBe("test-fake-key");
    expect(capturedFactoryOptions.httpOptions).toEqual({
      timeout: 5000,
      retryOptions: {
        attempts: 1,
      },
    });
  });

  test("requests model gemini-3.5-flash-lite with temperature 0, cap 512 tokens, application/json, proposal schema and systemInstruction", async () => {
    let capturedParams: any = null;
    const mockClient = createMockClient(async (params) => {
      capturedParams = params;
      return { text: JSON.stringify(VALID_PROPOSAL) };
    });

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "vendo zapatos", referenceDate: TEST_REF_DATE });

    expect(mockClient.models.generateContent).toHaveBeenCalledTimes(1);
    expect(capturedParams).toBeDefined();
    expect(capturedParams.model).toBe("gemini-3.5-flash-lite");
    expect(capturedParams.contents).toBe("vendo zapatos");
    expect(capturedParams.config).toMatchObject({
      responseMimeType: "application/json",
      responseJsonSchema: SAFE_PROPOSAL_JSON_SCHEMA,
      temperature: 0,
      maxOutputTokens: 512,
    });
    expect(capturedParams.config.systemInstruction).toBeDefined();
    expect(capturedParams.config.systemInstruction).toContain("2026-08-24");
    expect(capturedParams.config.responseSchema).toBeUndefined();
    expect(SAFE_PROPOSAL_JSON_SCHEMA.anyOf).toBeDefined();
    expect(result).toEqual(VALID_PROPOSAL);
  });

  test("passes official systemInstruction containing reference date and domain rules in config", async () => {
    let capturedParams: any = null;
    const mockClient = createMockClient(async (params) => {
      capturedParams = params;
      return { text: JSON.stringify(VALID_PROPOSAL) };
    });

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const refDate = {
      year: 2026,
      month: 8,
      day: 24,
      isoDate: "2026-08-24",
      weekday: "lunes",
    };

    await provider.interpret({ text: "¿cuánto vendí esta semana?", referenceDate: refDate });

    expect(capturedParams).toBeDefined();
    expect(capturedParams.config.systemInstruction).toBeDefined();
    const instruction: string = capturedParams.config.systemInstruction;
    expect(instruction).toContain("2026-08-24");
    expect(instruction).toContain("America/Mexico_City");
    expect(instruction).toContain("SALES_FOR_PERIOD");
    expect(instruction).toContain("lunes a domingo");
    expect(instruction).toContain("MISSING_PRODUCT");
    expect(instruction).toContain("create_sale");
    expect(instruction).toContain("record_payment");
    expect(instruction).toContain("CUSTOMER_BALANCE");
    expect(instruction).toContain("CUSTOMERS_WITH_DEBT");
    expect(instruction).toContain("requires_clarification");
    expect(instruction).toContain("María me pagó 92 de un boleto");
    expect(instruction).toContain("declaredInitialPaymentCents");
  });

  test("buildSystemInstruction includes all domain rules, monetary units rule and credit vs cash sale discriminant rules", () => {
    const refDate = {
      year: 2026,
      month: 8,
      day: 24,
      isoDate: "2026-08-24",
      weekday: "lunes",
    };

    const instruction = buildSystemInstruction(refDate);

    // Reference date and timezone
    expect(instruction).toContain("2026-08-24 (lunes)");
    expect(instruction).toContain("America/Mexico_City");
    expect(instruction).toContain("No calculas cifras, no inventas datos y no ejecutas mutaciones financieras.");

    // Natural queries rules
    expect(instruction).toContain("queryType: \"SALES_FOR_PERIOD\"");
    expect(instruction).toContain("queryType: \"CUSTOMER_BALANCE\"");
    expect(instruction).toContain("queryType: \"CUSTOMERS_WITH_DEBT\"");
    expect(instruction).toContain("La semana va de lunes a domingo.");

    // Sales & payments base rules
    expect(instruction).toContain("create_sale");
    expect(instruction).toContain("record_payment");
    expect(instruction).toContain("le vendí 2 cremas a María a 100 pesos");

    // Monetary units rule
    expect(instruction).toContain("Regla estricta de unidades monetarias");
    expect(instruction).toContain("todos los montos que expresa el usuario están en PESOS, NUNCA en centavos");
    expect(instruction).toContain("esperan CENTAVOS: multiplica siempre por 100");
    expect(instruction).toContain('"en 100" -> "10000"');
    expect(instruction).toContain('"92 pesos" -> "9200"');
    expect(instruction).toContain('"$120" -> "12000"');
    expect(instruction).toContain('"10.50" -> "1050"');
    expect(instruction).toContain('"50 de abono" -> "5000"');
    expect(instruction).toContain("Aplica a unitPriceCents, declaredInitialPaymentCents y declaredAmountCents por igual");

    // Initial payment & credit sale rules (3 cases)
    expect(instruction).toContain('Regla de abono inicial en ventas (create_sale): declaredInitialPaymentCents es "0" salvo que la frase indique explícitamente que el cliente entregó dinero');
    expect(instruction).toContain("Vender no implica cobrar: Cuentivo es una app de venta a crédito y lo normal es que no se haya pagado");
    expect(instruction).toContain('"le vendí 2 cremas a María en 100" -> create_sale con declaredInitialPaymentCents = "0" (fiado)');
    expect(instruction).toContain('"María me pagó 92 de un boleto" -> create_sale con declaredInitialPaymentCents = "9200"');
    expect(instruction).toContain('"le vendí 2 cremas a María en 100 y me pagó 40" -> create_sale con unitPriceCents = "10000" y declaredInitialPaymentCents = "4000"');
    expect(instruction).toContain("Un abono (record_payment) NUNCA nombra un producto o servicio");
    expect(instruction).toContain("Carlos me abonó 92");

    // Clarification fallback
    expect(instruction).toContain("requires_clarification");
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

    const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

      const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

    const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

    const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

    const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

    const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
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

    await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
    expect(generateFn).toHaveBeenCalledTimes(1);
  });

  describe("safe failure telemetry", () => {
    test.each([
      [400, 400],
      [401, 401],
      [403, 403],
      [404, 404],
      [408, 408],
      [429, 429],
      [500, 500],
      [502, 502],
      [503, 503],
      [504, 504],
      [418, null],
      [501, null],
      [undefined, null],
    ])("reports PROVIDER_ERROR_OR_TIMEOUT with allowlisted status (%s -> %s)", async (status, expectedStatus) => {
      const failures: any[] = [];
      const error: any = new Error("API call failed with secret message https://secret-url");
      if (status !== undefined) {
        error.status = status;
      }
      const mockClient = createMockClient(async () => {
        throw error;
      });

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "sensitive user text", referenceDate: TEST_REF_DATE });
      expect(result).toBeNull();
      expect(failures).toEqual([
        {
          category: "PROVIDER_ERROR_OR_TIMEOUT",
          status: expectedStatus,
        },
      ]);
    });

    test("extracts status from response.status or statusCode only if allowlisted", async () => {
      const failures: any[] = [];
      const error: any = { response: { status: 429 }, message: "Too many requests" };
      const mockClient = createMockClient(async () => {
        throw error;
      });

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      await provider.interpret({ text: "vendo zapatos", referenceDate: TEST_REF_DATE });
      expect(failures).toEqual([
        {
          category: "PROVIDER_ERROR_OR_TIMEOUT",
          status: 429,
        },
      ]);
    });

    test("reports EMPTY_RESPONSE with null status when output text is empty", async () => {
      const failures: any[] = [];
      const mockClient = createMockClient(async () => ({ text: "   " }));

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
      expect(result).toBeNull();
      expect(failures).toEqual([
        {
          category: "EMPTY_RESPONSE",
          status: null,
        },
      ]);
    });

    test("reports INVALID_JSON with null status when output is not valid JSON", async () => {
      const failures: any[] = [];
      const mockClient = createMockClient(async () => ({ text: "not a json" }));

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
      expect(result).toBeNull();
      expect(failures).toEqual([
        {
          category: "INVALID_JSON",
          status: null,
        },
      ]);
    });

    test("reports INVALID_PROPOSAL with null status when JSON fails schema parsing", async () => {
      const failures: any[] = [];
      const mockClient = createMockClient(async () => ({
        text: JSON.stringify({ kind: "unknown_kind", customerName: "Ana" }),
      }));

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
      expect(result).toBeNull();
      expect(failures).toEqual([
        {
          category: "INVALID_PROPOSAL",
          status: null,
        },
      ]);
    });

    test("never passes raw error object, error message, input text, or output text to telemetry", async () => {
      const failures: any[] = [];
      const sensitiveError = new Error("API key AIzaSy... leaked in stack");
      (sensitiveError as any).status = 401;
      (sensitiveError as any).details = "private user data";

      const mockClient = createMockClient(async () => {
        throw sensitiveError;
      });

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      await provider.interpret({ text: "Secret Customer owes $500", referenceDate: TEST_REF_DATE });

      expect(failures.length).toBe(1);
      const failure = failures[0];
      expect(Object.keys(failure).sort()).toEqual(["category", "status"].sort());
      expect(failure.category).toBe("PROVIDER_ERROR_OR_TIMEOUT");
      expect(failure.status).toBe(401);

      const serialized = JSON.stringify(failure);
      expect(serialized).not.toContain("AIzaSy");
      expect(serialized).not.toContain("leaked");
      expect(serialized).not.toContain("private");
      expect(serialized).not.toContain("Secret Customer");
      expect(serialized).not.toContain("500");
    });

    test("does not invoke onFailure when proposal is valid", async () => {
      const failures: any[] = [];
      const mockClient = createMockClient(async () => ({
        text: JSON.stringify(VALID_PROPOSAL),
      }));

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: (f) => failures.push(f),
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "vendo zapatos", referenceDate: TEST_REF_DATE });
      expect(result).toEqual(VALID_PROPOSAL);
      expect(failures).toHaveLength(0);
    });

    test("still returns null safely if onFailure throws an error", async () => {
      const mockClient = createMockClient(async () => {
        throw new Error("SDK error");
      });

      const provider = createGeminiTextProvider(
        {
          apiKey: "test-fake-key",
          timeoutMillis: 5000,
          onFailure: () => {
            throw new Error("telemetry boom");
          },
        },
        mockClient,
      );

      const result = await provider.interpret({ text: "input", referenceDate: TEST_REF_DATE });
      expect(result).toBeNull();
    });
  });
});
