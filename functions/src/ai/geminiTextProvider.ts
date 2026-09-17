import { GoogleGenAI } from "@google/genai";
import type { SafeAiProposal } from "../contract.js";
import { parseSafeAiProposal } from "./interpretation.js";
/**
 * Model for operational text and voice prompt interpretation.
 * Fast, lightweight, and low cost (gemini-3.5-flash-lite).
 * Deliberately kept distinct from GEMINI_LEDGER_MODEL ("gemini-3.5-flash") used for notebook OCR.
 */
export const GEMINI_TEXT_MODEL = "gemini-3.5-flash-lite";

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

export type ReferenceDate = Readonly<{
  year: number;
  month: number;
  day: number;
  isoDate: string;
  weekday: string;
}>;

const WEEKDAY_ES_BY_EN: Readonly<Record<string, string>> = {
  Monday: "lunes",
  Tuesday: "martes",
  Wednesday: "miércoles",
  Thursday: "jueves",
  Friday: "viernes",
  Saturday: "sábado",
  Sunday: "domingo",
};

export function computeReferenceDate(nowEpochMillis: number): ReferenceDate {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Mexico_City",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    weekday: "long",
  });
  const parts = formatter.formatToParts(new Date(nowEpochMillis));
  let year = 0;
  let month = 0;
  let day = 0;
  let weekdayEn = "";
  for (const part of parts) {
    if (part.type === "year") year = parseInt(part.value, 10);
    if (part.type === "month") month = parseInt(part.value, 10);
    if (part.type === "day") day = parseInt(part.value, 10);
    if (part.type === "weekday") weekdayEn = part.value;
  }
  const weekday = WEEKDAY_ES_BY_EN[weekdayEn] ?? weekdayEn.toLowerCase();
  const mm = String(month).padStart(2, "0");
  const dd = String(day).padStart(2, "0");
  return {
    year,
    month,
    day,
    isoDate: `${year}-${mm}-${dd}`,
    weekday,
  };
}

export function buildSystemInstruction(referenceDate: ReferenceDate): string {
  return [
    `Eres el asistente de Cuentivo, una app de gestión de ventas y cobros para pequeños negocios.`,
    `Tu función es clasificar la intención del usuario y proponer la estructura adecuada según el schema JSON.`,
    `No calculas cifras, no inventas datos y no ejecutas mutaciones financieras.`,
    `Fecha actual de referencia: ${referenceDate.isoDate} (${referenceDate.weekday}) en zona horaria America/Mexico_City.`,
    `Reglas de dominio:`,
    `- Preguntas de consulta como "¿cuánto vendí?", "¿cuánto cobré?", "ventas de esta semana", "resumen del mes" o ventas por periodo son consultas de tipo business_query con queryType: "SALES_FOR_PERIOD". NUNCA deben clasificarse como create_sale ni como requires_clarification con MISSING_PRODUCT.`,
    `- La semana va de lunes a domingo. "Esta semana" corresponde al periodo desde el lunes de la semana actual hasta el domingo de la semana actual (o hasta hoy si el usuario consulta lo que lleva vendido).`,
    `- "Hoy" corresponde a la fecha de referencia (${referenceDate.isoDate}).`,
    `- Para consultar saldo de un cliente ("¿cuánto me debe Juan?"), usa business_query con queryType: "CUSTOMER_BALANCE".`,
    `- Para consultar quién debe ("¿quién me debe?"), usa business_query con queryType: "CUSTOMERS_WITH_DEBT".`,
    `- Para registrar ventas ("le vendí 2 cremas a María a 100 pesos"), usa create_sale.`,
    `- Para registrar pagos o abonos ("María me abonó 50"), usa record_payment.`,
    `- Regla estricta de unidades monetarias: todos los montos que expresa el usuario están en PESOS, NUNCA en centavos. Los campos del schema terminan en Cents (unitPriceCents, declaredInitialPaymentCents, declaredAmountCents) y esperan CENTAVOS: multiplica siempre por 100. Ejemplos de conversión: "en 100" -> "10000", "92 pesos" -> "9200", "$120" -> "12000", "10.50" -> "1050", "50 de abono" -> "5000". Aplica a unitPriceCents, declaredInitialPaymentCents y declaredAmountCents por igual.`,
    `- Regla de abono inicial en ventas (create_sale): declaredInitialPaymentCents es "0" salvo que la frase indique explícitamente que el cliente entregó dinero o pagó en ese momento. Vender no implica cobrar: Cuentivo es una app de venta a crédito y lo normal es que no se haya pagado. Casos a distinguir: 1) Venta a crédito o fiado sin mención de pago: "le vendí 2 cremas a María en 100" -> create_sale con declaredInitialPaymentCents = "0" (fiado). 2) Venta de contado donde el verbo indica pago: "María me pagó 92 de un boleto" -> create_sale con declaredInitialPaymentCents = "9200" (al nombrar un producto o servicio es venta de contado y no un abono). 3) Venta con pago parcial: "le vendí 2 cremas a María en 100 y me pagó 40" -> create_sale con unitPriceCents = "10000" y declaredInitialPaymentCents = "4000".`,
    `- Un abono (record_payment) NUNCA nombra un producto o servicio: es exclusivamente dinero entregado contra un saldo existente (por ejemplo "Carlos me abonó 92" o "María me abonó 50").`,
    `- Si la entrada es incomprensible o ambigua, usa requires_clarification.`,
  ].join("\n");
}

export type GeminiPart =
  | Readonly<{ text: string }>
  | Readonly<{ inlineData: { mimeType: string; data: string } }>;

export interface GeminiClientSeam {
  models: {
    generateContent(params: {
      model: string;
      contents: string | readonly GeminiPart[];
      config?: {
        systemInstruction?: string;
        responseMimeType?: string;
        responseJsonSchema?: unknown;
        temperature?: number;
        maxOutputTokens?: number;
        /**
         * Razonamiento del modelo. `thinkingBudget: 0` lo DESACTIVA (así lo documenta el SDK
         * instalado: "@google/genai" 2.18.0, dist/genai.d.ts, interface ThinkingConfig).
         */
        thinkingConfig?: {
          thinkingBudget?: number;
          includeThoughts?: boolean;
        };
        abortSignal?: AbortSignal;
      };
    }): Promise<{ text?: string | null; finishReason?: string | null }>;
  };
}

export type ProviderFailureCategory =
  | "PROVIDER_ERROR_OR_TIMEOUT"
  | "EMPTY_RESPONSE"
  | "INVALID_JSON"
  | "INVALID_PROPOSAL"
  /**
   * El modelo cortó la generación por el tope de tokens: lo que llegó está incompleto. Categoría
   * propia a propósito — un JSON truncado se veía como INVALID_JSON o como un fallo genérico, y así
   * no se puede medir cuántas páginas se están cortando ni con qué tope.
   */
  | "TRUNCATED_BY_MAX_TOKENS";

export interface ProviderFailureTelemetry {
  readonly category: ProviderFailureCategory;
  readonly status: number | null;
}

export type ProviderFailureListener = (failure: ProviderFailureTelemetry) => void;

const ALLOWED_HTTP_STATUSES = new Set([400, 401, 403, 404, 408, 429, 500, 502, 503, 504]);

function extractAllowedHttpStatus(error: unknown): number | null {
  try {
    if (typeof error !== "object" || error === null) {
      return null;
    }
    const candidate =
      "status" in error && typeof (error as { status: unknown }).status === "number"
        ? (error as { status: number }).status
        : "statusCode" in error && typeof (error as { statusCode: unknown }).statusCode === "number"
          ? (error as { statusCode: number }).statusCode
          : "response" in error &&
              typeof (error as { response?: { status?: unknown } }).response?.status === "number"
            ? (error as { response: { status: number } }).response.status
            : null;

    if (candidate !== null && Number.isInteger(candidate) && ALLOWED_HTTP_STATUSES.has(candidate)) {
      return candidate;
    }
    return null;
  } catch {
    return null;
  }
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
  interpret(input: Readonly<{ text: string; referenceDate: ReferenceDate }>): Promise<SafeAiProposal | null>;
}

class GeminiTextProvider implements TextInterpretationProvider {
  private readonly client: GeminiClientSeam;
  private readonly timeoutMillis: number;
  private readonly onFailure?: ProviderFailureListener;

  constructor(
    client: GeminiClientSeam,
    timeoutMillis: number,
    onFailure?: ProviderFailureListener,
  ) {
    this.client = client;
    this.timeoutMillis = timeoutMillis;
    this.onFailure = onFailure;
  }

  private emitFailure(category: ProviderFailureCategory, status: number | null = null): void {
    try {
      this.onFailure?.({ category, status });
    } catch {
      // Fail-safe: telemetry listener must never propagate exceptions.
    }
  }

  async interpret(input: Readonly<{ text: string; referenceDate: ReferenceDate }>): Promise<SafeAiProposal | null> {
    let timerId: ReturnType<typeof setTimeout> | undefined;
    try {
      const signal = AbortSignal.timeout(this.timeoutMillis);

      const timeoutPromise = new Promise<never>((_, reject) => {
        timerId = setTimeout(() => reject(new Error("provider-timeout")), this.timeoutMillis);
      });

      const systemInstruction = buildSystemInstruction(input.referenceDate);

      const generatePromise = this.client.models.generateContent({
        model: GEMINI_TEXT_MODEL,
        contents: input.text,
        config: {
          systemInstruction,
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
        this.emitFailure("EMPTY_RESPONSE", null);
        return null;
      }

      let parsedJson: unknown;
      try {
        parsedJson = JSON.parse(rawText);
      } catch {
        this.emitFailure("INVALID_JSON", null);
        return null;
      }

      const proposal = parseSafeAiProposal(parsedJson);
      if (proposal === null) {
        this.emitFailure("INVALID_PROPOSAL", null);
        return null;
      }
      return proposal;
    } catch (error) {
      // Fail closed to null on any failure (timeout, network, SDK rejection, malformed payload).
      // Never throw, never retry, and never log exception or text contents.
      this.emitFailure("PROVIDER_ERROR_OR_TIMEOUT", extractAllowedHttpStatus(error));
      return null;
    } finally {
      if (timerId !== undefined) {
        clearTimeout(timerId);
      }
    }
  }
}

export function adaptGoogleGenAiClient(sdk: GoogleGenAI): GeminiClientSeam {
  return {
    models: {
      async generateContent(params) {
        const sdkContents =
          typeof params.contents === "string"
            ? params.contents
            : params.contents.map((part) => ({ ...part }));
        const response = await sdk.models.generateContent({
          model: params.model,
          contents: sdkContents,
          config: params.config,
        });
        return {
          text: response.text,
          // Se propaga a propósito: sin esto, una respuesta cortada por MAX_TOKENS es
          // indistinguible de un JSON mal escrito, y no se puede medir.
          finishReason: response.candidates?.[0]?.finishReason ?? null,
        };
      },
    },
  };
}

export function createGeminiTextProvider(
  options: Readonly<{
    apiKey: string;
    timeoutMillis: number;
    onFailure?: ProviderFailureListener;
  }>,
  clientOrFactory?: GeminiClientSeam | GeminiClientFactory,
): TextInterpretationProvider {
  const clientFactory: GeminiClientFactory =
    typeof clientOrFactory === "function"
      ? clientOrFactory
      : (clientOptions) =>
          clientOrFactory ??
          adaptGoogleGenAiClient(
            new GoogleGenAI({
              apiKey: clientOptions.apiKey,
              httpOptions: clientOptions.httpOptions,
            }),
          );

  const client = clientFactory({
    apiKey: options.apiKey,
    httpOptions: {
      timeout: options.timeoutMillis,
      retryOptions: {
        attempts: 1,
      },
    },
  });

  return new GeminiTextProvider(client, options.timeoutMillis, options.onFailure);
}
