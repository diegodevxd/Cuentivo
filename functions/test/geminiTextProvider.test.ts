import { describe, expect, test, vi } from "vitest";
import {
  createGeminiTextProvider,
  SAFE_INTERPRETATION_JSON_SCHEMA,
  type GeminiClientFactory,
  type GeminiClientSeam,
} from "../src/ai/geminiTextProvider.js";

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
      text: JSON.stringify({ kind: "requires_clarification", code: "NEEDS_CONTEXT" }),
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

  test("requests model gemini-2.5-flash-lite with temperature 0, max 64, application/json and responseJsonSchema with additionalProperties:false", async () => {
    let capturedParams: any = null;
    const mockClient = createMockClient(async (params) => {
      capturedParams = params;
      return { text: JSON.stringify({ kind: "requires_clarification", code: "NEEDS_CONTEXT" }) };
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
      responseJsonSchema: SAFE_INTERPRETATION_JSON_SCHEMA,
      temperature: 0,
      maxOutputTokens: 64,
    });
    expect(capturedParams.config.responseSchema).toBeUndefined();
    expect(SAFE_INTERPRETATION_JSON_SCHEMA.additionalProperties).toBe(false);
    expect(SAFE_INTERPRETATION_JSON_SCHEMA.required).toEqual(["kind", "code"]);
    expect(SAFE_INTERPRETATION_JSON_SCHEMA.anyOf).toBeDefined();
    expect(result).toEqual({ kind: "requires_clarification", code: "NEEDS_CONTEXT" });
  });

  test.each([
    [{ kind: "requires_clarification", code: "NEEDS_CONTEXT" }],
    [{ kind: "requires_clarification", code: "AMBIGUOUS" }],
    [{ kind: "unsupported", code: "UNSUPPORTED" }],
    [{ kind: "unsupported", code: "UNSAFE" }],
  ] as const)("parses valid allowed response %o", async (allowedPair) => {
    const mockClient = createMockClient(async () => ({
      text: JSON.stringify(allowedPair),
    }));

    const provider = createGeminiTextProvider(
      { apiKey: "test-fake-key", timeoutMillis: 5000 },
      mockClient,
    );

    const result = await provider.interpret({ text: "input" });
    expect(result).toEqual(allowedPair);
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
    ["extra property", { kind: "unsupported", code: "UNSAFE", amountCents: 500 }],
    ["customer id property", { kind: "unsupported", code: "UNSAFE", customerId: "c1" }],
    ["mismatched pair", { kind: "unsupported", code: "NEEDS_CONTEXT" }],
    ["unknown code", { kind: "unsupported", code: "UNKNOWN_CODE" }],
    ["unknown kind", { kind: "sale_draft", code: "DRAFT" }],
    ["array root", [{ kind: "unsupported", code: "UNSAFE" }]],
    ["nested object", { kind: "unsupported", code: "UNSAFE", payload: { x: 1 } }],
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
            resolve({
              text: JSON.stringify({ kind: "unsupported", code: "UNSAFE" }),
            });
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
