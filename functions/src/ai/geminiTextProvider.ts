import { GoogleGenAI } from "@google/genai";
import type { SafeAiProposal } from "../contract.js";
import { parseSafeAiProposal } from "./interpretation.js";

export const GEMINI_MODEL = "gemini-2.5-flash-lite";

/**
 * Exact JSON Schema for the typed proposal wire format: independent object schemas per kind (and
 * per business-query type), each with its own properties/required and additionalProperties:false,
 * so cross-kind fields (e.g. create_sale + reason) and cross-query selectors (e.g. balance +
 * period) are rejected by Structured Output itself. Money is canonical decimal STRINGS.
 *
 * Initial-proposal bounds: max 8 sale lines, 80-char display names, chosen so the largest
 * allowed shape stays inside the fixed 512-token output cap. Future multimodal capture will
 * split a notebook image into several drafts instead of raising these bounds.
 */
export const SAFE_PROPOSAL_JSON_SCHEMA = {
  anyOf: [
    {
      type: "object",
      properties: {
        kind: { enum: ["requires_clarification"] },
        reason: {
          type: "string",
          enum: [
            "AMBIGUOUS_CUSTOMER",
            "AMBIGUOUS_PRODUCT",
            "MISSING_CUSTOMER",
            "MISSING_PRODUCT",
            "MISSING_AMOUNT",
            "UNKNOWN_INPUT",
          ],
        },
        unresolvedSlots: {
          type: "array",
          items: {
            type: "string",
            enum: ["CUSTOMER", "PRODUCT", "AMOUNT", "QUANTITY", "PERIOD"],
          },
          minItems: 1,
          uniqueItems: true,
        },
      },
      required: ["kind", "reason", "unresolvedSlots"],
      additionalProperties: false,
    },
    {
      type: "object",
      properties: {
        kind: { enum: ["create_sale"] },
        customerName: { type: "string", minLength: 1, maxLength: 80 },
        lines: {
          type: "array",
          minItems: 1,
          maxItems: 8,
          items: {
            type: "object",
            properties: {
              productName: { type: "string", minLength: 1, maxLength: 80 },
              quantity: { type: "integer", minimum: 1 },
              unitPriceCents: { type: "string", pattern: "^(0|[1-9][0-9]{0,17})$" },
            },
            required: ["productName", "quantity", "unitPriceCents"],
            additionalProperties: false,
          },
        },
        declaredInitialPaymentCents: { type: "string", pattern: "^(0|[1-9][0-9]{0,17})$" },
        promisedPaymentDate: {
          type: "object",
          properties: {
            year: { type: "integer", minimum: 1, maximum: 9999 },
            month: { type: "integer", minimum: 1, maximum: 12 },
            day: { type: "integer", minimum: 1, maximum: 31 },
          },
          required: ["year", "month", "day"],
          additionalProperties: false,
        },
      },
      required: ["kind", "customerName", "lines", "declaredInitialPaymentCents"],
      additionalProperties: false,
    },
    {
      type: "object",
      properties: {
        kind: { enum: ["record_payment"] },
        customerName: { type: "string", minLength: 1, maxLength: 80 },
        declaredAmountCents: { type: "string", pattern: "^(0|[1-9][0-9]{0,17})$" },
      },
      required: ["kind", "customerName", "declaredAmountCents"],
      additionalProperties: false,
    },
    {
      type: "object",
      properties: {
        kind: { enum: ["business_query"] },
        queryType: { enum: ["CUSTOMER_BALANCE"] },
        customerName: { type: "string", minLength: 1, maxLength: 80 },
      },
      required: ["kind", "queryType", "customerName"],
      additionalProperties: false,
    },
    {
      type: "object",
      properties: {
        kind: { enum: ["business_query"] },
        queryType: { enum: ["CUSTOMERS_WITH_DEBT"] },
      },
      required: ["kind", "queryType"],
      additionalProperties: false,
    },
    {
      type: "object",
      properties: {
        kind: { enum: ["business_query"] },
        queryType: { enum: ["SALES_FOR_PERIOD"] },
        period: {
          type: "object",
          properties: {
            startYear: { type: "integer", minimum: 1, maximum: 9999 },
            startMonth: { type: "integer", minimum: 1, maximum: 12 },
            startDay: { type: "integer", minimum: 1, maximum: 31 },
            endYear: { type: "integer", minimum: 1, maximum: 9999 },
            endMonth: { type: "integer", minimum: 1, maximum: 12 },
            endDay: { type: "integer", minimum: 1, maximum: 31 },
          },
          required: ["startYear", "startMonth", "startDay", "endYear", "endMonth", "endDay"],
          additionalProperties: false,
        },
      },
      required: ["kind", "queryType", "period"],
      additionalProperties: false,
    },
  ],
} as const;

export interface GeminiClientSeam {
  models: {
    generateContent(params: {
      model: string;
      contents: string;
      config?: {
        responseMimeType?: string;
        responseJsonSchema?: unknown;
        temperature?: number;
        maxOutputTokens?: number;
        abortSignal?: AbortSignal;
      };
    }): Promise<{ text?: string | null }>;
  };
}

export type GeminiClientFactory = (options: {
  apiKey: string;
  httpOptions: {
    timeout: number;
    retryOptions: {
      attempts: number;
    };
  };
}) => GeminiClientSeam;

export interface TextInterpretationProvider {
  interpret(input: Readonly<{ text: string }>): Promise<SafeAiProposal | null>;
}

class GeminiTextProvider implements TextInterpretationProvider {
  private readonly client: GeminiClientSeam;
  private readonly timeoutMillis: number;

  constructor(client: GeminiClientSeam, timeoutMillis: number) {
    this.client = client;
    this.timeoutMillis = timeoutMillis;
  }

  async interpret(input: Readonly<{ text: string }>): Promise<SafeAiProposal | null> {
    let timerId: ReturnType<typeof setTimeout> | undefined;
    try {
      const signal = AbortSignal.timeout(this.timeoutMillis);

      const timeoutPromise = new Promise<never>((_, reject) => {
        timerId = setTimeout(() => reject(new Error("provider-timeout")), this.timeoutMillis);
      });

      const generatePromise = this.client.models.generateContent({
        model: GEMINI_MODEL,
        contents: input.text,
        config: {
          responseMimeType: "application/json",
          responseJsonSchema: SAFE_PROPOSAL_JSON_SCHEMA,
          temperature: 0,
          // Hard output cap consistent with the largest allowed shape (8 lines x 80 chars).
          // Still bounded and never retried: a truncated output fails closed to null.
          maxOutputTokens: 512,
          abortSignal: signal,
        },
      });

      const response = await Promise.race([generatePromise, timeoutPromise]);
      const rawText = response?.text;
      if (typeof rawText !== "string" || rawText.trim().length === 0) {
        return null;
      }

      let parsedJson: unknown;
      try {
        parsedJson = JSON.parse(rawText);
      } catch {
        return null;
      }

      return parseSafeAiProposal(parsedJson);
    } catch {
      // Fail closed to null on any failure (timeout, network, SDK rejection, malformed payload).
      // Never throw, never retry, and never log exception or text contents.
      return null;
    } finally {
      if (timerId !== undefined) {
        clearTimeout(timerId);
      }
    }
  }
}

export function createGeminiTextProvider(
  options: Readonly<{
    apiKey: string;
    timeoutMillis: number;
  }>,
  clientOrFactory?: GeminiClientSeam | GeminiClientFactory,
): TextInterpretationProvider {
  const clientFactory: GeminiClientFactory =
    typeof clientOrFactory === "function"
      ? clientOrFactory
      : (clientOptions) =>
          clientOrFactory ??
          new GoogleGenAI({
            apiKey: clientOptions.apiKey,
            httpOptions: clientOptions.httpOptions,
          });

  const client = clientFactory({
    apiKey: options.apiKey,
    httpOptions: {
      timeout: options.timeoutMillis,
      retryOptions: {
        attempts: 1,
      },
    },
  });

  return new GeminiTextProvider(client, options.timeoutMillis);
}
