import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";
import * as compositionRoot from "../src/index.js";

const SOURCE = readFileSync(resolve(process.cwd(), "src/index.ts"), "utf8");

/**
 * El composition root se importa ARRIBA, en la carga del archivo, y no dentro de la prueba.
 *
 * Estaba dentro de la prueba y eso lo volvía la única prueba de la suite cuyo plazo de 5 s incluía
 * cargar `firebase-functions` + `firebase-admin` + los siete handlers. Con la caché de transformación
 * fría —la primera corrida de una máquina limpia, o un CI— esa carga pasó de los 5 s y la prueba
 * falló por tiempo, no por lo que afirma: una de 574, una sola vez, imposible de reproducir después
 * porque la caché ya estaba tibia. Era la prueba inestable.
 *
 * Cargar el módulo fuera del cuerpo no debilita nada: se sigue importando el composition root real y
 * las aserciones son las mismas. Si el módulo no carga, el archivo falla al recolectarse —ruidoso—
 * en vez de esconderse detrás de un "timeout" que parece un problema de la máquina.
 */
describe("regional callable gateway", () => {
  test("exports interpretAiRequest, authorizeAiAction, getAiAvailability, verifyAiSubscription and handlePlayRtdnNotification", () => {
    expect(compositionRoot.interpretAiRequest).toBeDefined();
    expect(compositionRoot.authorizeAiAction).toBeDefined();
    expect(compositionRoot.getAiAvailability).toBeDefined();
    expect(compositionRoot.verifyAiSubscription).toBeDefined();
    expect(compositionRoot.handlePlayRtdnNotification).toBeDefined();
    expect(compositionRoot.warmMexalmLedger).toBeDefined();
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

  /**
   * La verificación cruzada contra el Ledger de MexaLM vive detrás de una bandera APAGADA por
   * defecto. Estos guardas son la forma de que nadie la encienda por accidente ni la quite sin
   * querer: la bandera es un parámetro con default `false`, la URL es un parámetro y el token es
   * un secreto declarado y ligado SÓLO a la callable de la libreta.
   */
  test("declares the crosscheck flag off by default and the ledger url as a parameter", () => {
    expect(SOURCE).toContain('defineBoolean("MEXALM_LEDGER_CROSSCHECK", { default: false })');
    expect(SOURCE).toContain('defineString("MEXALM_LEDGER_URL", { default: "" })');
    expect(SOURCE).toContain('defineSecret("MEXALM_LEDGER_TOKEN")');
  });

  test("binds the ledger secret only to extractLedgerPage", () => {
    const interpretIndex = SOURCE.indexOf("export const interpretAiRequest");
    const extractIndex = SOURCE.indexOf("export const extractLedgerPage");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    expect(interpretIndex).toBeGreaterThanOrEqual(0);
    expect(extractIndex).toBeGreaterThan(interpretIndex);
    expect(authorizeIndex).toBeGreaterThan(extractIndex);

    const interpretBlock = SOURCE.slice(interpretIndex, extractIndex);
    const extractBlock = SOURCE.slice(extractIndex, authorizeIndex);

    expect(interpretBlock).not.toContain("mexalmLedgerToken");
    expect(extractBlock).toContain("mexalmLedgerToken");
    expect(extractBlock).toContain("secrets: [geminiApiKey, mexalmLedgerToken]");
    expect(extractBlock).not.toContain("playServiceAccountKey");
  });

  test("reads the ledger secret value only inside the extractLedgerPage handler", () => {
    const extractIndex = SOURCE.indexOf("export const extractLedgerPage");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    const valueIndex = SOURCE.indexOf("mexalmLedgerToken.value()");
    expect(valueIndex).toBeGreaterThan(extractIndex);
    expect(valueIndex).toBeLessThan(authorizeIndex);
  });

  test("the crosscheck is wired only where the flag is on and never logs page text", () => {
    const extractIndex = SOURCE.indexOf("export const extractLedgerPage");
    const authorizeIndex = SOURCE.indexOf("export const authorizeAiAction");
    const extractBlock = SOURCE.slice(extractIndex, authorizeIndex);
    expect(extractBlock).toContain("mexalmLedgerCrosscheck.value()");
    expect(extractBlock).toContain("createMexalmLedgerCrosschecker");
    expect(extractBlock).toContain("crosscheck,");
    expect(SOURCE).toContain('logger.warn("MEXALM_LEDGER_FAILURE"');
    // El texto de la página son nombres de clientas y montos: al log no llega.
    expect(SOURCE).not.toMatch(/logger\.(warn|error|info|log)\([^)]*(rawText|ocrText|pageText|ledgerText)/);
  });

  test("the composition root keeps no literal provider url for the ledger", () => {
    expect(SOURCE).not.toContain("mexalm-ledger");
    expect(SOURCE).not.toContain("huggingface.co");
  });

  /**
   * El volcado de diagnóstico del Ledger. Es una herramienta del EMULADOR: en producción el
   * parámetro se ignora, y eso no se deja a la memoria de nadie sino a una decisión que vive en un
   * solo lugar (`isMexalmLedgerDebugDumpEnabled`).
   */
  test("declares the debug dump off by default and bound to the emulator", () => {
    expect(SOURCE).toContain('defineBoolean("MEXALM_LEDGER_DEBUG_DUMP", { default: false })');
    // El escritor recibe el emulador y el parámetro: la decisión es suya, no de un `if` de aquí.
    expect(SOURCE).toContain("emulator: process.env.FUNCTIONS_EMULATOR");
    expect(SOURCE).toContain("parameterEnabled: mexalmLedgerDebugDump.value()");
  });

  test("the debug dump never reaches the log", () => {
    // El volcado es un archivo local del emulador. Al log no llega: el texto de la libreta y la
    // respuesta del Ledger nunca se registran.
    expect(SOURCE).not.toMatch(
      /logger\.(warn|error|info|log)\([^)]*(rawText|ledgerText|pageText|debugDump)/,
    );
  });

  /**
   * El despertar del Ledger de MexaLM.
   *
   * Es una callable más, con la misma frontera de las demás: autenticación, App Check y la
   * bandera de la verificación cruzada. Y con la misma regla del secreto: el token del Ledger
   * sigue ligado a UNA sola callable, la de la libreta, porque un health check no lo necesita.
   */
  test("the warm callable is gated by the crosscheck flag and never binds the ledger secret", () => {
    const warmIndex = SOURCE.indexOf("export const warmMexalmLedger");
    const verifyIndex = SOURCE.indexOf("export const verifyAiSubscription");
    expect(warmIndex).toBeGreaterThan(0);
    expect(warmIndex).toBeLessThan(verifyIndex);

    const warmBlock = SOURCE.slice(warmIndex, verifyIndex);
    expect(warmBlock).toContain("mexalmLedgerCrosscheck.value()");
    expect(warmBlock).toContain("createWarmMexalmLedgerHandler");
    // El secreto del Ledger NO se liga aquí: el health check no lo necesita.
    expect(warmBlock).not.toContain("mexalmLedgerToken");
    expect(warmBlock).not.toContain("secrets:");
  });

  test("the warm callable never logs the account", () => {
    const warmIndex = SOURCE.indexOf("export const warmMexalmLedger");
    const verifyIndex = SOURCE.indexOf("export const verifyAiSubscription");
    const warmBlock = SOURCE.slice(warmIndex, verifyIndex);
    expect(warmBlock).toContain('logger.info("MEXALM_LEDGER_WARM"');
    // Ni el uid, ni la dirección del Ledger, ni lo que el health check contestó.
    expect(warmBlock).not.toMatch(/logger\.(warn|error|info|log)\([^)]*(auth\.uid|request\.data|ledgerUrl)/);
  });
});
