import { describe, expect, test, vi } from "vitest";
import { createExtractLedgerPageHandler } from "../src/extractLedgerPage.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { LedgerExtractionProvider } from "../src/ai/geminiLedgerProvider.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { LedgerExtractionResult } from "../src/contract.js";
import {
  createMexalmLedgerClient,
  type MexalmLedgerFetch,
  type MexalmLedgerFailure,
} from "../src/ai/mexalmLedgerClient.js";
import {
  buildCrosscheckPageText,
  compareLedgerOperations,
  createMexalmLedgerCrosschecker,
  normalizeCustomerNameForCrosscheck,
} from "../src/ai/mexalmCrosscheck.js";

const NOW = 1_000_000;

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const VALID_DATA = {
  contractVersion: 5,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "extract_ledger_page",
  imageBase64: "aGVsbG8=",
  imageMimeType: "image/jpeg",
} as const;

const AUTHORIZED: AuthorizeAiResult = {
  decision: "AI_AUTHORIZED",
  provider: "GEMINI",
  fallbackUsed: false,
};

/**
 * Dos renglones de una libreta, con el `rawText` que el modelo escribe de cada renglón. El
 * `rawText` es el texto de la página: es lo que se manda al Ledger y lo que NO puede aparecer en
 * ningún log.
 */
const PAGE_TEXT_LINE_ONE = "Rosa protector tapete 250";
const PAGE_TEXT_LINE_TWO = "Toño 2 cocas 38";

const EXTRACTION_WITH_TEXT: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Rosa",
      amountCents: "25000",
      description: "Protector tapete",
      uncertainFields: [],
      unreadableFields: [],
      confidence: { customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 },
      sources: { customer: "EXPLICIT", date: "PAGE_DATE" },
      rawText: PAGE_TEXT_LINE_ONE,
      items: [{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }],
      warnings: [],
    },
    {
      lineIndex: 1,
      proposedKind: "SALE",
      customerName: "Toño",
      amountCents: "3800",
      uncertainFields: [],
      unreadableFields: [],
      rawText: PAGE_TEXT_LINE_TWO,
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
};

/** El Ledger de acuerdo: mismo tipo, mismo cliente, mismo monto. */
const LEDGER_AGREED = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" },
    { lineIndex: 1, proposedKind: "SALE", customerName: "Toño", amountCents: "3800" },
  ],
};

/** El Ledger que leyó el segundo renglón con otro monto. */
const LEDGER_DIFFERENT_AMOUNT = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" },
    { lineIndex: 1, proposedKind: "SALE", customerName: "Toño", amountCents: "3900" },
  ],
};

function openGuard() {
  return createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1_000, maxTrackedUids: 1_000 });
}

function providerReturning(result: LedgerExtractionResult | null): LedgerExtractionProvider {
  return { extract: vi.fn(async () => result) };
}

function deps(overrides: Partial<Parameters<typeof createExtractLedgerPageHandler>[0]> = {}) {
  return {
    authorize: async () => AUTHORIZED,
    claimProviderInvocation: async () => "CLAIMED" as const,
    provider: providerReturning(EXTRACTION_WITH_TEXT),
    guard: openGuard(),
    now: () => NOW,
    ...overrides,
  };
}

function jsonResponse(payload: unknown, status = 200): MexalmLedgerFetch {
  return async () => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => payload,
  });
}

function failingFetch(error: unknown): MexalmLedgerFetch {
  return async () => {
    throw error;
  };
}

/**
 * Cliente + verificador reales con un `fetch` falso. La prueba NO reimplementa la comparación:
 * ejercita el mismo código que corre en producción, con el Ledger simulado en la red.
 */
function crosscheckerOver(
  fetchImpl: MexalmLedgerFetch,
  failures: MexalmLedgerFailure[] = [],
  options: { timeoutMillis?: number } = {},
) {
  const client = createMexalmLedgerClient({
    baseUrl: "ledger.invalid",
    token: "token-de-prueba",
    fetchImpl,
    onFailure: (failure) => {
      failures.push(failure);
    },
    ...(options.timeoutMillis !== undefined ? { timeoutMillis: options.timeoutMillis } : {}),
  });
  return createMexalmLedgerCrosschecker({ client, now: () => NOW });
}

describe("verificación cruzada con el Ledger de MexaLM", () => {
  describe("con la bandera apagada", () => {
    test("la salida es byte a byte la de hoy", async () => {
      const withoutFeature = await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      const crosscheck = vi.fn();
      const withFlagOff = await createExtractLedgerPageHandler(
        deps({ crosscheck: crosscheck as never }),
      )({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      expect(JSON.stringify(withFlagOff)).toBe(JSON.stringify(withoutFeature));
      expect(crosscheck).not.toHaveBeenCalled();
      expect(withFlagOff).not.toHaveProperty("mexalmCrosscheck");
    });

    test("el snapshot de la respuesta apagada no cambia", async () => {
      const result = await createExtractLedgerPageHandler(deps({ crosscheck: null }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      expect(JSON.stringify(result)).toMatchInlineSnapshot(`"{"contractVersion":5,"requestId":"123e4567-e89b-12d3-a456-426614174000","status":"AI_PROPOSAL","operations":[{"lineIndex":0,"proposedKind":"SALE","customerName":"Rosa","amountCents":"25000","description":"Protector tapete","uncertainFields":[],"unreadableFields":[],"confidence":{"customer":0.95,"concept":0.9,"amount":0.98,"date":0.9,"kind":0.95},"sources":{"customer":"EXPLICIT","date":"PAGE_DATE"},"rawText":"Rosa protector tapete 250","items":[{"description":"Protector tapete","quantity":1,"subtotalCents":"25000"}],"warnings":[]},{"lineIndex":1,"proposedKind":"SALE","customerName":"Toño","amountCents":"3800","uncertainFields":[],"unreadableFields":[],"rawText":"Toño 2 cocas 38"}],"discards":[],"unreadTextRemaining":false,"pageIncomplete":false}"`);
    });
  });

  describe("con la bandera encendida", () => {
    test("el Ledger de acuerdo da agreed=true y ninguna diferencia", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_AGREED));

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(result.mexalmCrosscheck).toBeDefined();
      expect(result.mexalmCrosscheck.agreed).toBe(true);
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
      expect(result.mexalmCrosscheck.ledgerStatus).toBe("OK");
      expect(typeof result.mexalmCrosscheck.latencyMs).toBe("number");
    });

    test("las operaciones de Gemini NO se tocan: el bloque sólo se agrega", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_DIFFERENT_AMOUNT));

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(withCrosscheck.operations).toEqual(withoutCrosscheck.operations);
      expect(withCrosscheck.status).toBe(withoutCrosscheck.status);
      expect(withCrosscheck.discards).toEqual(withoutCrosscheck.discards);
      expect(withCrosscheck.pageIncomplete).toBe(withoutCrosscheck.pageIncomplete);
      // La única diferencia entre las dos respuestas es la llave nueva.
      expect(Object.keys(withCrosscheck).filter((key) => key !== "mexalmCrosscheck")).toEqual(
        Object.keys(withoutCrosscheck),
      );
    });

    test("un monto distinto aparece como desacuerdo, sólo dentro del bloque nuevo", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_DIFFERENT_AMOUNT));

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(result.mexalmCrosscheck.agreed).toBe(false);
      expect(result.mexalmCrosscheck.disagreements).toHaveLength(1);
      const disagreement = result.mexalmCrosscheck.disagreements[0];
      expect(disagreement.field).toBe("AMOUNT");
      expect(disagreement.lineIndex).toBe(1);
      expect(disagreement.gemini.amountCents).toBe("3800");
      expect(disagreement.ledger.amountCents).toBe("3900");
      // El renglón sigue siendo el de Gemini: la marca no reescribe la operación.
      expect(result.operations[1].amountCents).toBe("3800");
      expect(result.operations[1].uncertainFields).toEqual([]);
    });

    test("el cliente que no está en la lista no se marca: la comparación es por renglón", async () => {
      const crosscheck = crosscheckerOver(
        jsonResponse({
          status: "OK",
          operations: [
            { lineIndex: 0, proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" },
          ],
        }),
      );

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      const fields = result.mexalmCrosscheck.disagreements.map((entry: any) => entry.field);
      expect(fields).toContain("MISSING_IN_LEDGER");
      expect(result.mexalmCrosscheck.agreed).toBe(false);
    });
  });

  describe("cuando el Ledger falla", () => {
    test("un timeout deja la respuesta igual a la de hoy y registra la falla sin texto", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(failingFetch(new Error("boom")), failures);

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(withCrosscheck).not.toHaveProperty("mexalmCrosscheck");
      expect(failures).toHaveLength(1);
      expect(failures[0]?.category).toBe("NETWORK_ERROR");
    });

    test("el plazo del cliente se cumple: una llamada que no contesta se corta", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const hangingFetch: MexalmLedgerFetch = async () =>
        new Promise(() => {
          // No resuelve nunca: el plazo del cliente es lo que tiene que cortarlo.
        });
      const crosscheck = crosscheckerOver(hangingFetch, failures, { timeoutMillis: 20 });

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(failures[0]?.category).toBe("TIMEOUT");
    });

    test("una respuesta inútil se descarta sin cambiar la respuesta", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(jsonResponse({ status: "OK", operations: "no-es-un-arreglo" }), failures);

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(failures[0]?.category).toBe("INVALID_RESPONSE");
    });

    test("un status sin veredicto no se compara: no hay veredicto que inventar", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(jsonResponse({ status: "INVALID_OUTPUT", operations: [] }), failures);

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(withCrosscheck).not.toHaveProperty("mexalmCrosscheck");
      expect(failures[0]?.category).toBe("REJECTED");
    });

    test("un status que este servicio no conoce NO apaga la verificación", async () => {
      const crosscheck = crosscheckerOver(
        jsonResponse({ status: "success", operations: LEDGER_AGREED.operations }),
      );

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(result.mexalmCrosscheck.ledgerStatus).toBe("success");
      expect(result.mexalmCrosscheck.agreed).toBe(true);
    });

    test("una página sin texto no llama al Ledger", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const fetchImpl = vi.fn(jsonResponse(LEDGER_AGREED));
      const crosscheck = crosscheckerOver(fetchImpl, failures);

      const result = (await createExtractLedgerPageHandler(
        deps({
          provider: providerReturning({
            ...EXTRACTION_WITH_TEXT,
            operations: EXTRACTION_WITH_TEXT.operations.map((operation) => ({
              ...operation,
              rawText: undefined,
            })),
          }),
          crosscheck,
        }),
      )({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      expect(fetchImpl).not.toHaveBeenCalled();
      expect(result).not.toHaveProperty("mexalmCrosscheck");
    });
  });

  describe("el texto de la página no se registra", () => {
    test("la telemetría de la falla no lleva el texto de la página", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(failingFetch(new Error(PAGE_TEXT_LINE_ONE)), failures);

      await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      const serialized = JSON.stringify(failures);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_ONE);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_TWO);
      expect(serialized).not.toContain("Rosa");
      expect(serialized).not.toContain("Toño");
      expect(serialized).not.toContain("text");
    });

    test("el bloque de desacuerdos no lleva el texto de la página", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_DIFFERENT_AMOUNT));

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      })) as any;

      const serialized = JSON.stringify(result.mexalmCrosscheck);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_ONE);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_TWO);
      expect(serialized).not.toContain("rawText");
    });

    test("el cuerpo que viaja al Ledger lleva el texto, y sólo el texto de los renglones", async () => {
      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => LEDGER_AGREED };
      };
      const crosscheck = crosscheckerOver(captureFetch);

      await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      const body = JSON.parse(sentBody);
      expect(body.text).toBe(`${PAGE_TEXT_LINE_ONE}\n${PAGE_TEXT_LINE_TWO}`);
      expect(body.knownCustomers).toBeNull();
    });
  });

  describe("la comparación, en aislamiento", () => {
    test("el nombre se compara normalizado: acentos, mayúsculas y espacios no cuentan", () => {
      expect(normalizeCustomerNameForCrosscheck("  María de los Ángeles ")).toBe(
        "maria de los angeles",
      );
      expect(normalizeCustomerNameForCrosscheck("MARÍA")).toBe("maria");
      expect(normalizeCustomerNameForCrosscheck("Toño")).toBe("tono");
    });

    test("el texto de la página es un renglón por línea, en orden", () => {
      const text = buildCrosscheckPageText([
        { lineIndex: 1, uncertainFields: [], unreadableFields: [], rawText: "segundo" },
        { lineIndex: 0, uncertainFields: [], unreadableFields: [], rawText: "primero" },
      ]);
      expect(text).toBe("primero\nsegundo");
    });

    test("un salto de línea dentro de un renglón no rompe la numeración de la página", () => {
      const text = buildCrosscheckPageText([
        { lineIndex: 0, uncertainFields: [], unreadableFields: [], rawText: "Rosa\n250" },
      ]);
      expect(text).toBe("Rosa 250");
    });

    test("sin rawText no hay texto de página", () => {
      expect(
        buildCrosscheckPageText([
          { lineIndex: 0, uncertainFields: [], unreadableFields: [], customerName: "Rosa" },
        ]),
      ).toBeNull();
    });

    test("tipo, cliente y monto son los tres campos que se comparan", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "María",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        [
          { lineIndex: 0, proposedKind: "PAYMENT", customerName: "Rosa", amountCents: "25000" },
        ],
      );

      const fields = disagreements.map((entry) => entry.field).sort();
      expect(fields).toEqual(["CUSTOMER", "TYPE"]);
    });

    test("el mismo cliente con otra grafía NO es desacuerdo", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "María",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        [
          { lineIndex: 0, proposedKind: "SALE", customerName: "maria", amountCents: "25000" },
        ],
      );

      expect(disagreements).toEqual([]);
    });

    test("un campo que a un lado le falta no es contradicción", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            amountCents: "25000",
            uncertainFields: ["CUSTOMER"],
            unreadableFields: [],
          },
        ],
        [{ lineIndex: 0, proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" }],
      );

      expect(disagreements).toEqual([]);
    });

    test("una operación del Ledger sin renglón se reporta, no se pierde en silencio", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Rosa",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        [
          { lineIndex: 0, proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" },
          { proposedKind: "SALE", customerName: "Toño", amountCents: "3800" },
        ],
      );

      expect(disagreements).toHaveLength(1);
      expect(disagreements[0]?.field).toBe("MISSING_IN_GEMINI");
      expect(disagreements[0]?.lineIndex).toBe(-1);
    });
  });
});
