import { GoogleGenAI } from "@google/genai";
import type { SafeInterpretation } from "../contract.js";
import { parseSafeInterpretation } from "./interpretation.js";

export const GEMINI_MODEL = "gemini-2.5-flash-lite";

export const SAFE_INTERPRETATION_JSON_SCHEMA = {
  type: "object",
  properties: {
    kind: {
      type: "string",
      enum: ["requires_clarification", "unsupported"],
    },
    code: {
      type: "string",
      enum: ["NEEDS_CONTEXT", "AMBIGUOUS", "UNSUPPORTED", "UNSAFE"],
    },
  },
  required: ["kind", "code"],
} as const;

export interface GeminiClientSeam {
  models: {
    generateContent(params: {
      model: string;
      contents: string;
      config?: {
        responseMimeType?: string;
        responseSchema?: unknown;
        temperature?: number;
        maxOutputTokens?: number;
        abortSignal?: AbortSignal;
      };
    }): Promise<{ text?: string | null }>;
  };
}

export interface TextInterpretationProvider {
  interpret(input: Readonly<{ text: string }>): Promise<SafeInterpretation | null>;
}

class GeminiTextProvider implements TextInterpretationProvider {
  private readonly client: GeminiClientSeam;
  private readonly timeoutMillis: number;

  constructor(
    options: Readonly<{ apiKey: string; timeoutMillis: number }>,
    injectedClient?: GeminiClientSeam,
  ) {
    this.timeoutMillis = options.timeoutMillis;
    this.client =
      injectedClient ??
      new GoogleGenAI({
        apiKey: options.apiKey,
        httpOptions: { timeout: options.timeoutMillis },
      });
  }

  async interpret(input: Readonly<{ text: string }>): Promise<SafeInterpretation | null> {
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
          responseSchema: SAFE_INTERPRETATION_JSON_SCHEMA,
          temperature: 0,
          maxOutputTokens: 64,
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

      return parseSafeInterpretation(parsedJson);
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
  injectedClient?: GeminiClientSeam,
): TextInterpretationProvider {
  return new GeminiTextProvider(options, injectedClient);
}
