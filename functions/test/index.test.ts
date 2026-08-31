import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

const SOURCE = readFileSync(resolve(process.cwd(), "src/index.ts"), "utf8");

describe("regional callable gateway", () => {
  test("exports interpretAiRequest, authorizeAiAction, getAiAvailability, verifyAiSubscription and handlePlayRtdnNotification", async () => {
    const module = await import("../src/index.js");
    expect(module.interpretAiRequest).toBeDefined();
    expect(module.authorizeAiAction).toBeDefined();
    expect(module.getAiAvailability).toBeDefined();
    expect(module.verifyAiSubscription).toBeDefined();
    expect(module.handlePlayRtdnNotification).toBeDefined();
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

  test("declares the gemini and play service account secret params with exact literals", () => {
    expect(SOURCE).toContain('defineSecret("CUENTIVOAI_GEMINI_API_KEY")');
    expect(SOURCE).toContain('defineSecret("CUENTIVOAI_PLAY_SERVICE_ACCOUNT_KEY")');
  });

  test("uses a Gemini-compatible provider deadline below the callable deadline", () => {
    expect(SOURCE).toContain("timeoutMillis: 12000");
    expect(SOURCE).not.toContain("timeoutMillis: 8000");
  });

  test("binds secrets strictly to their dedicated callables and background functions", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    const verifyIndex = SOURCE.indexOf("export const verifyAiSubscription");
    const rtdnIndex = SOURCE.indexOf("export const handlePlayRtdnNotification");
    expect(interpretIndex).toBeGreaterThanOrEqual(0);
    expect(authorizeIndex).toBeGreaterThan(interpretIndex);
    expect(verifyIndex).toBeGreaterThan(authorizeIndex);
    expect(rtdnIndex).toBeGreaterThan(verifyIndex);

    const interpretBlock = SOURCE.slice(interpretIndex, authorizeIndex);
    expect(interpretBlock).toContain("secrets: [geminiApiKey]");
    expect(interpretBlock).not.toContain("playServiceAccountKey");

    const authorizeBlock = SOURCE.slice(authorizeIndex, verifyIndex);
    expect(authorizeBlock).not.toContain("secrets:");

    const verifyBlock = SOURCE.slice(verifyIndex, rtdnIndex);
    expect(verifyBlock).toContain("secrets: [playServiceAccountKey]");
    expect(verifyBlock).not.toContain("geminiApiKey");

    const rtdnBlock = SOURCE.slice(rtdnIndex);
    expect(rtdnBlock).toContain("secrets: [playServiceAccountKey]");
    expect(rtdnBlock).not.toContain("geminiApiKey");
    expect(rtdnBlock).toContain('topic: "play-rtdn-cuentivo"');
    expect(rtdnBlock).toContain("retry: true");
  });

  test("reads the secret value only inside the respective handlers", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const verifyIndex = SOURCE.indexOf("export const verifyAiSubscription");
    const geminiValueIndex = SOURCE.indexOf("geminiApiKey.value()");
    const playValueIndex = SOURCE.indexOf("playServiceAccountKey.value()");

    expect(geminiValueIndex).toBeGreaterThan(interpretIndex);
    expect(geminiValueIndex).toBeLessThan(verifyIndex);
    expect(playValueIndex).toBeGreaterThan(verifyIndex);
  });

  test("composition root has no deepseek or direct provider urls", () => {
    for (const forbidden of ["DEEPSEEK", "deepseek", "generativelanguage", "https://"]) {
      expect(SOURCE).not.toContain(forbidden);
    }
  });

  test("injects the shared aiEdgeGuard into the interpret handler", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    const interpretBlock = SOURCE.slice(interpretIndex, authorizeIndex);
    expect(interpretBlock).toContain("guard: aiEdgeGuard");
  });

  test("initializes default firebase admin app at module level and passes it to getFirestore", () => {
    expect(SOURCE).toMatch(/const\s+adminApp\s*=\s*initializeApp\(\);/);
    expect(SOURCE).toContain("getFirestore(adminApp)");
    expect(SOURCE).not.toContain("getApps()");
    expect(SOURCE).not.toContain("getApp()");
  });

  test("wires safe provider failure telemetry to logger.warn without logging sensitive details", () => {
    expect(SOURCE).toContain('logger.warn("AI_PROVIDER_FAILURE"');
    expect(SOURCE).toContain("category: failure.category");
    expect(SOURCE).toContain("status: failure.status");
    expect(SOURCE).not.toMatch(/logger\.(warn|error|info|log)\([^)]*(request\.data|apiKey|auth\.uid|error\.message|stack)/);
  });

  test("logs only the fixed callable outcome without request or proposal data", () => {
    expect(SOURCE).toContain('logger.info("AI_INTERPRET_RESULT"');
    expect(SOURCE).toContain("status: result.status");
    expect(SOURCE).not.toMatch(/AI_INTERPRET_RESULT[^;]*(requestId|proposal|request\.data|auth\.uid)/s);
  });

  test("logs provider invocation and latency strictly without PII", () => {
    expect(SOURCE).toContain('logger.info("AI_PROVIDER_INVOCATION")');
    expect(SOURCE).toContain('logger.info("AI_PROVIDER_LATENCY"');
    expect(SOURCE).toContain("durationMillis");
    expect(SOURCE).not.toMatch(/AI_PROVIDER_(INVOCATION|LATENCY)[^;]*(requestId|proposal|request\.data|auth\.uid|text|prompt)/s);
  });
});
