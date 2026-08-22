import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

const SOURCE = readFileSync(resolve(process.cwd(), "src/index.ts"), "utf8");

describe("regional callable gateway", () => {
  test("exports interpretAiRequest", async () => {
    const module = await import("../src/index.js");
    expect(module.interpretAiRequest).toBeDefined();
  });

  test("declares exactly the five approved runtime options", () => {
    for (const required of [
      'region: "us-south1"',
      "enforceAppCheck: true",
      "maxInstances: 1",
      "timeoutSeconds: 15",
      'memory: "256MiB"',
    ]) {
      expect(SOURCE, `source must contain ${required}`).toContain(required);
    }
  });

  test("declares the gemini secret param with the exact literal", () => {
    expect(SOURCE).toContain('defineSecret("CUENTIVOAI_GEMINI_API_KEY")');
  });

  test("binds the secret only to interpretAiRequest", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    expect(interpretIndex).toBeGreaterThanOrEqual(0);
    expect(authorizeIndex).toBeGreaterThan(interpretIndex);

    const interpretBlock = SOURCE.slice(interpretIndex, authorizeIndex);
    expect(interpretBlock).toContain("secrets: [geminiApiKey]");

    const authorizeBlock = SOURCE.slice(authorizeIndex);
    expect(authorizeBlock).not.toContain("secrets:");
  });

  test("reads the secret value only inside the interpret handler", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const valueIndex = SOURCE.indexOf(".value()");
    expect(valueIndex).toBeGreaterThan(interpretIndex);
  });

  test("composition root has no deepseek or direct provider urls", () => {
    for (const forbidden of ["DEEPSEEK", "deepseek", "generativelanguage", "https://"]) {
      expect(SOURCE).not.toContain(forbidden);
    }
  });
});
