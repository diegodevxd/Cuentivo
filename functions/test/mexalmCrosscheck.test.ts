import { describe, expect, test, vi } from "vitest";
import { createExtractLedgerPageHandler } from "../src/extractLedgerPage.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { LedgerExtractionProvider } from "../src/ai/geminiLedgerProvider.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { LedgerExtractionResult, LedgerOperationV5 } from "../src/contract.js";
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

/**
 * La petición de la app NUEVA: la misma de siempre más la capacidad de leer el bloque.
 *
 * Este archivo prueba la verificación cruzada, así que sus casos "con la bandera encendida" tienen
 * que mandar lo que manda la app que puede leer el bloque. Sin la capacidad el servidor no manda
 * nada —a propósito, porque la app ≤ v48 rechaza la página entera ante una llave raíz que no
 * conoce—, y una prueba que esperara el bloque sin declararla estaría pidiendo el defecto.
 *
 * `VALID_DATA`, sin la capacidad, es la petición de la app publicada hoy, y se usa en los casos que
 * justamente verifican que esa app sigue recibiendo los mismos bytes.
 */
const NEW_APP_DATA = { ...VALID_DATA, acceptsMexalmCrosscheck: true } as const;

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

/**
 * Una página de UNA clienta, como la trae una libreta de fiado de verdad: el nombre arriba y los
 * renglones abajo, SIN repetir el nombre en cada renglón. Es el caso real que rompía la
 * verificación: el Ledger no tenía de dónde sacar a quién pertenece cada movimiento.
 */
const CHELO_PAGE: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Chelo",
      amountCents: "25000",
      description: "Protector tapete",
      uncertainFields: [],
      unreadableFields: [],
      confidence: { customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 },
      sources: { customer: "EXPLICIT", date: "PAGE_DATE" },
      rawText: "protector tapete 250",
      items: [{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }],
      warnings: [],
    },
    {
      lineIndex: 1,
      proposedKind: "PAYMENT",
      customerName: "Chelo",
      amountCents: "10000",
      uncertainFields: [],
      unreadableFields: [],
      rawText: "abono 100",
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
};

/** El Ledger de acuerdo con esa página: mismo tipo, misma clienta, mismo monto. */
const LEDGER_CHELO_AGREED = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: "Chelo", amountCents: "25000" },
    { lineIndex: 1, proposedKind: "PAYMENT", customerName: "Chelo", amountCents: "10000" },
  ],
};

/** El Ledger que NO pudo leer la clienta: `null`, no un nombre inventado. */
const LEDGER_CHELO_NO_CUSTOMER = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: null, amountCents: "25000" },
    { lineIndex: 1, proposedKind: "PAYMENT", customerName: null, amountCents: "10000" },
  ],
};

/** El Ledger que leyó el abono con otro monto. */
const LEDGER_CHELO_DIFFERENT_AMOUNT = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: "Chelo", amountCents: "25000" },
    { lineIndex: 1, proposedKind: "PAYMENT", customerName: "Chelo", amountCents: "9000" },
  ],
};

/** Dos clientas en la misma página, cada una con sus renglones. */
const TWO_CUSTOMERS_PAGE: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Chelo",
      amountCents: "25000",
      uncertainFields: [],
      unreadableFields: [],
      rawText: "protector tapete 250",
    },
    {
      lineIndex: 1,
      proposedKind: "SALE",
      customerName: "Chelo",
      amountCents: "10000",
      uncertainFields: [],
      unreadableFields: [],
      rawText: "jabon 100",
    },
    {
      lineIndex: 2,
      proposedKind: "PAYMENT",
      customerName: "Beto",
      amountCents: "5000",
      uncertainFields: [],
      unreadableFields: [],
      rawText: "abono 50",
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
};

/** El Ledger de acuerdo con las dos clientas. */
const LEDGER_TWO_CUSTOMERS_AGREED = {
  status: "OK",
  operations: [
    { lineIndex: 0, proposedKind: "SALE", customerName: "Chelo", amountCents: "25000" },
    { lineIndex: 1, proposedKind: "SALE", customerName: "Chelo", amountCents: "10000" },
    { lineIndex: 2, proposedKind: "PAYMENT", customerName: "Beto", amountCents: "5000" },
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

      // La bandera apagada se representa como la representa el composition root: `crosscheck` en
      // null. Aquí NO se usa un verificador de mentira, y es a propósito: con la bandera apagada el
      // servidor no tiene verificador que consultar, así que fingir uno probaría otra cosa —que la
      // capacidad de la petición lo frena—, y esa propiedad tiene su propia prueba en
      // `extractLedgerPage.test.ts`, con el verificador cableado y la petición vieja.
      const withFlagOff = await createExtractLedgerPageHandler(deps({ crosscheck: null }))({
        auth: AUTH_USER,
        data: VALID_DATA,
      });

      expect(JSON.stringify(withFlagOff)).toBe(JSON.stringify(withoutFeature));
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(failures[0]?.category).toBe("TIMEOUT");
    });

    test("una respuesta inútil se descarta sin cambiar la respuesta", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(jsonResponse({ status: "OK", operations: "no-es-un-arreglo" }), failures);

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(JSON.stringify(withCrosscheck)).toBe(JSON.stringify(withoutCrosscheck));
      expect(failures[0]?.category).toBe("INVALID_RESPONSE");
    });

    test("un status sin veredicto no se compara: no hay veredicto que inventar", async () => {
      const failures: MexalmLedgerFailure[] = [];
      const crosscheck = crosscheckerOver(jsonResponse({ status: "INVALID_OUTPUT", operations: [] }), failures);

      const withCrosscheck = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;
      const withoutCrosscheck = (await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
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
        data: NEW_APP_DATA,
      })) as any;

      const serialized = JSON.stringify(result.mexalmCrosscheck);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_ONE);
      expect(serialized).not.toContain(PAGE_TEXT_LINE_TWO);
      expect(serialized).not.toContain("rawText");
    });

    test("el cuerpo que viaja al Ledger lleva el texto de la página, con su encabezado", async () => {
      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => LEDGER_AGREED };
      };
      const crosscheck = crosscheckerOver(captureFetch);

      await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      });

      const body = JSON.parse(sentBody);
      // El texto de la página: el encabezado de cada clienta y luego sus renglones. El nombre
      // va tal cual lo escribió Gemini.
      expect(body.text).toBe(
        `Rosa\n${PAGE_TEXT_LINE_ONE}\nToño\n${PAGE_TEXT_LINE_TWO}`,
      );
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

    test("un `null` del Ledger es «sin opinión»: no es un desacuerdo de cliente", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Chelo",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        [{ lineIndex: 0, proposedKind: "SALE", customerName: null, amountCents: "25000" }],
      );

      expect(disagreements).toEqual([]);
    });

    test("un nombre en blanco del Ledger tampoco es una contradicción", () => {
      const disagreements = compareLedgerOperations(
        [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Chelo",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
          },
        ],
        [{ lineIndex: 0, proposedKind: "SALE", customerName: "   ", amountCents: "25000" }],
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

  describe("el encabezado de la clienta", () => {
    test("una página de una clienta lleva su nombre arriba y el Ledger está de acuerdo", async () => {
      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => LEDGER_CHELO_AGREED };
      };

      const result = (await createExtractLedgerPageHandler(
        deps({
          provider: providerReturning(CHELO_PAGE),
          crosscheck: crosscheckerOver(captureFetch),
        }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // El encabezado va arriba y los renglones NO repiten el nombre: así es como la hoja lo trae.
      expect(JSON.parse(sentBody).text).toBe("Chelo\nprotector tapete 250\nabono 100");
      // Y con el encabezado, el Ledger sabe a quién pertenece cada renglón y está de acuerdo.
      expect(result.mexalmCrosscheck.agreed).toBe(true);
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
    });

    test("un `null` del Ledger es «sin opinión», no un desacuerdo de cliente", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_CHELO_NO_CUSTOMER));

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_PAGE), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // El Ledger no pudo leer la clienta: eso NO contradice a la lectura de la foto.
      const fields = result.mexalmCrosscheck.disagreements.map((entry: any) => entry.field);
      expect(fields).not.toContain("CUSTOMER");
      expect(result.mexalmCrosscheck.agreed).toBe(true);
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
    });

    test("un monto distinto sigue contando como desacuerdo, con el encabezado puesto", async () => {
      const crosscheck = crosscheckerOver(jsonResponse(LEDGER_CHELO_DIFFERENT_AMOUNT));

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_PAGE), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(result.mexalmCrosscheck.agreed).toBe(false);
      expect(result.mexalmCrosscheck.disagreements).toHaveLength(1);
      const disagreement = result.mexalmCrosscheck.disagreements[0];
      expect(disagreement.field).toBe("AMOUNT");
      expect(disagreement.lineIndex).toBe(1);
      expect(disagreement.gemini.amountCents).toBe("10000");
      expect(disagreement.ledger.amountCents).toBe("9000");
      // El renglón sigue siendo el de Gemini: la marca no reescribe la operación.
      expect(result.operations[1].amountCents).toBe("10000");
    });

    test("dos clientas en la misma página: cada una con su encabezado", async () => {
      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => LEDGER_TWO_CUSTOMERS_AGREED };
      };

      const result = (await createExtractLedgerPageHandler(
        deps({
          provider: providerReturning(TWO_CUSTOMERS_PAGE),
          crosscheck: crosscheckerOver(captureFetch),
        }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(JSON.parse(sentBody).text).toBe(
        "Chelo\nprotector tapete 250\njabon 100\nBeto\nabono 50",
      );
      expect(result.mexalmCrosscheck.agreed).toBe(true);
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
    });

    test("los renglones de una clienta quedan juntos aunque en la hoja vinieran intercalados", async () => {
      const page: LedgerExtractionResult = {
        operations: [
          {
            lineIndex: 0,
            proposedKind: "SALE",
            customerName: "Chelo",
            amountCents: "25000",
            uncertainFields: [],
            unreadableFields: [],
            rawText: "protector tapete 250",
          },
          {
            lineIndex: 1,
            proposedKind: "SALE",
            customerName: "Beto",
            amountCents: "5000",
            uncertainFields: [],
            unreadableFields: [],
            rawText: "jabon 100",
          },
          {
            lineIndex: 2,
            proposedKind: "SALE",
            customerName: "Chelo",
            amountCents: "15000",
            uncertainFields: [],
            unreadableFields: [],
            rawText: "cepillo 150",
          },
        ],
        discards: [],
        unreadTextRemaining: false,
        pageIncomplete: false,
      };
      // El Ledger leyó el tercer renglón de la hoja con otro monto.
      const ledger = {
        status: "OK",
        operations: [
          { lineIndex: 0, proposedKind: "SALE", customerName: "Chelo", amountCents: "25000" },
          { lineIndex: 1, proposedKind: "SALE", customerName: "Chelo", amountCents: "14000" },
          { lineIndex: 2, proposedKind: "SALE", customerName: "Beto", amountCents: "5000" },
        ],
      };

      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => ledger };
      };

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(page), crosscheck: crosscheckerOver(captureFetch) }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // Los renglones de Chelo quedan juntos, con un solo encabezado.
      expect(JSON.parse(sentBody).text).toBe(
        "Chelo\nprotector tapete 250\ncepillo 150\nBeto\njabon 100",
      );
      // Y el desacuerdo se marca en el RENGLÓN de la hoja, no en la posición de la página: la app
      // marca renglones, no posiciones.
      expect(result.mexalmCrosscheck.agreed).toBe(false);
      expect(result.mexalmCrosscheck.disagreements).toHaveLength(1);
      expect(result.mexalmCrosscheck.disagreements[0].field).toBe("AMOUNT");
      expect(result.mexalmCrosscheck.disagreements[0].lineIndex).toBe(2);
    });

    test("la libreta de una clienta con ventas y abonos: el Ledger está de acuerdo", async () => {
      // El caso real que salió en la libreta de "Chelo": 8 ventas y 4 abonos, y el bloque decía
      // "11 renglones distintos" porque la página no llevaba el nombre de la clienta.
      const operations: LedgerOperationV5[] = Array.from({ length: 12 }, (_, index) => ({
        lineIndex: index,
        proposedKind: index < 8 ? "SALE" : "PAYMENT",
        customerName: "Chelo",
        amountCents: String(10000 + index),
        uncertainFields: [],
        unreadableFields: [],
        rawText: `renglon ${index}`,
      }));
      const ledgerOperations = operations.map((operation) => ({
        lineIndex: operation.lineIndex,
        proposedKind: operation.proposedKind,
        customerName: "Chelo",
        amountCents: operation.amountCents,
      }));

      const crosscheck = crosscheckerOver(
        jsonResponse({ status: "OK", operations: ledgerOperations }),
      );

      const result = (await createExtractLedgerPageHandler(
        deps({
          provider: providerReturning({
            operations,
            discards: [],
            unreadTextRemaining: false,
            pageIncomplete: false,
          }),
          crosscheck,
        }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(result.mexalmCrosscheck.agreed).toBe(true);
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
    });

    test("un renglón sin clienta no inventa un encabezado", () => {
      const text = buildCrosscheckPageText([
        {
          lineIndex: 0,
          uncertainFields: [],
          unreadableFields: [],
          rawText: "protector tapete 250",
        },
      ]);

      expect(text).toBe("protector tapete 250");
    });

    test("el encabezado lleva el nombre TAL CUAL lo escribió la lectura", () => {
      const text = buildCrosscheckPageText([
        {
          lineIndex: 0,
          proposedKind: "SALE",
          customerName: "María de los Ángeles",
          amountCents: "25000",
          uncertainFields: [],
          unreadableFields: [],
          rawText: "protector tapete 250",
        },
      ]);

      expect(text).toBe("María de los Ángeles\nprotector tapete 250");
    });
  });

  /**
   * El caso que salió en el teléfono: la libreta de una clienta, doce renglones, y el resumen
   * diciendo "el Ledger no lo vio" en CADA renglón. El Ledger no había contestado "no vi ése": no
   * había podido certificar la página y lo había dicho (`BLOCKED`).
   */
  describe("cuando el Ledger no lee la página", () => {
    /** La libreta de una clienta: doce renglones, como la del teléfono. */
    const CHELO_TWELVE_ROWS: LedgerExtractionResult = {
      operations: Array.from({ length: 12 }, (_, index) => ({
        lineIndex: index,
        proposedKind: index < 8 ? "SALE" : "PAYMENT",
        customerName: "Chelo",
        amountCents: String(10000 + index),
        uncertainFields: [],
        unreadableFields: [],
        rawText: `renglon ${index}`,
      })),
      discards: [],
      unreadTextRemaining: false,
      pageIncomplete: false,
    };

    test("BLOCKED sin operaciones NO deja un desacuerdo en cada renglón", async () => {
      const crosscheck = crosscheckerOver(jsonResponse({ status: "BLOCKED", operations: [] }));

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_TWELVE_ROWS), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // El status dice lo que pasó...
      expect(result.mexalmCrosscheck.ledgerStatus).toBe("BLOCKED");
      // ...y NO hay doce renglones "no lo vio": la página entera no se pudo certificar, y eso no
      // es una diferencia renglón por renglón.
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
      // Y `agreed` en falso: no se puede decir que los dos lados dicen lo mismo.
      expect(result.mexalmCrosscheck.agreed).toBe(false);
    });

    test("REVIEW_REQUIRED sin operaciones tampoco", async () => {
      const crosscheck = crosscheckerOver(jsonResponse({ status: "REVIEW_REQUIRED", operations: [] }));

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_TWELVE_ROWS), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      expect(result.mexalmCrosscheck.ledgerStatus).toBe("REVIEW_REQUIRED");
      expect(result.mexalmCrosscheck.disagreements).toEqual([]);
      expect(result.mexalmCrosscheck.agreed).toBe(false);
    });

    test("con OK todo sigue igual: los renglones que el Ledger no vio SÍ se reportan", async () => {
      const crosscheck = crosscheckerOver(jsonResponse({ status: "OK", operations: [] }));

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_TWELVE_ROWS), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // Con "OK" el Ledger sí dio veredicto, y un veredicto que no ve un renglón ES un desacuerdo.
      expect(result.mexalmCrosscheck.ledgerStatus).toBe("OK");
      expect(result.mexalmCrosscheck.disagreements).toHaveLength(12);
      expect(result.mexalmCrosscheck.disagreements[0].field).toBe("MISSING_IN_LEDGER");
      expect(result.mexalmCrosscheck.agreed).toBe(false);
    });

    test("BLOCKED CON operaciones se compara normal: lo que el Ledger sí leyó, se compara", async () => {
      const crosscheck = crosscheckerOver(
        jsonResponse({
          status: "BLOCKED",
          operations: [
            { lineIndex: 0, proposedKind: "SALE", customerName: "Chelo", amountCents: "10000" },
          ],
        }),
      );

      const result = (await createExtractLedgerPageHandler(
        deps({ provider: providerReturning(CHELO_TWELVE_ROWS), crosscheck }),
      )({
        auth: AUTH_USER,
        data: NEW_APP_DATA,
      })) as any;

      // El Ledger devolvió una operación: hay algo que comparar, y se compara.
      expect(result.mexalmCrosscheck.ledgerStatus).toBe("BLOCKED");
      expect(result.mexalmCrosscheck.disagreements).toHaveLength(11);
      expect(result.mexalmCrosscheck.disagreements[0].field).toBe("MISSING_IN_LEDGER");
    });
  });
});

/**
 * Los renglones que el Ledger CONFIRMÓ.
 *
 * Es la pieza que hace posible el encargo del dueño: cuando el Ledger confirma un renglón, ese
 * renglón ya no cuenta como duda de lectura. Y para que la app pueda saberlo, el bloque tiene que
 * llevar la lista: "no hubo desacuerdos" NO es lo mismo que "el Ledger confirmó este renglón" —un
 * Ledger que no tuvo opinión de nada no discrepa de nada—.
 */
describe("los renglones que el Ledger confirmó", () => {
  /**
   * La libreta de doce renglones de una clienta: ocho ventas y cuatro abonos. Es el caso real
   * del dueño, y el que sale de la prueba de la libreta de verdad.
   */
  const TWELVE_ROWS: LedgerExtractionResult = {
    operations: Array.from({ length: 12 }, (_, index) => ({
      lineIndex: index,
      proposedKind: index < 8 ? "SALE" : "PAYMENT",
      customerName: "Chelo",
      amountCents: String(10000 + index),
      uncertainFields: [],
      unreadableFields: [],
      rawText: `renglon ${index}`,
    })),
    discards: [],
    unreadTextRemaining: false,
    pageIncomplete: false,
  };

  test("un renglón con las dos lecturas de acuerdo queda confirmado", async () => {
    const crosscheck = crosscheckerOver(jsonResponse(LEDGER_AGREED));

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    expect(result.mexalmCrosscheck.agreed).toBe(true);
    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([0, 1]);
  });

  test("un renglón que discrepa NO queda confirmado", async () => {
    const crosscheck = crosscheckerOver(jsonResponse(LEDGER_DIFFERENT_AMOUNT));

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    // El primer renglón coincide entero; el segundo discrepa en el monto.
    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([0]);
  });

  test("un Ledger sin opinión de nada no confirma ni un renglón, aunque no haya desacuerdos", async () => {
    const crosscheck = crosscheckerOver(jsonResponse(LEDGER_CHELO_NO_CUSTOMER));

    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(CHELO_PAGE), crosscheck }),
    )({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    // `agreed` en true: no hubo desacuerdos. Y la lista VACÍA: el Ledger no confirmó nada,
    // porque no tuvo opinión del cliente. Confundir las dos cosas es justo el error que la lista
    // viene a evitar.
    expect(result.mexalmCrosscheck.agreed).toBe(true);
    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([]);
  });

  test("el mismo cliente con otra grafía SÍ se confirma: la comparación normaliza", async () => {
    const crosscheck = crosscheckerOver(
      jsonResponse({
        status: "OK",
        operations: [
          { lineIndex: 0, proposedKind: "SALE", customerName: "ROSA", amountCents: "25000" },
          { lineIndex: 1, proposedKind: "SALE", customerName: "Toño", amountCents: "3800" },
        ],
      }),
    );

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([0, 1]);
  });

  test("un renglón que el propio Ledger marcó dudoso NO se confirma aunque coincida", async () => {
    // Forma real del Ledger (rev 00007) para "$10 (o $10.50?)": el status de la página es OK,
    // pero el renglón trae AMOUNT en uncertainFields.
    const crosscheck = crosscheckerOver(
      jsonResponse({
        status: "OK",
        needsReview: false,
        operations: [
          {
            lineIndex: 0,
            uncertainFields: ["AMOUNT"],
            unreadableFields: [],
            proposedKind: "SALE",
            customerName: "Rosa",
            amountCents: "25000",
          },
          {
            lineIndex: 1,
            uncertainFields: [],
            unreadableFields: [],
            proposedKind: "SALE",
            customerName: "Toño",
            amountCents: "3800",
          },
        ],
      }),
    );

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([1]);
  });

  test("un renglón con un campo ilegible o con dudas mal formadas NO se confirma", async () => {
    const crosscheck = crosscheckerOver(
      jsonResponse({
        status: "OK",
        operations: [
          {
            lineIndex: 0,
            uncertainFields: [],
            unreadableFields: ["DATE"],
            proposedKind: "SALE",
            customerName: "Rosa",
            amountCents: "25000",
          },
          {
            lineIndex: 1,
            uncertainFields: "AMOUNT",
            proposedKind: "SALE",
            customerName: "Toño",
            amountCents: "3800",
          },
        ],
      }),
    );

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([]);
  });

  test("el Ledger que no leyó la página no confirma ningún renglón", async () => {
    const crosscheck = crosscheckerOver(jsonResponse({ status: "BLOCKED", operations: [] }));

    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(TWELVE_ROWS), crosscheck }),
    )({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    expect(result.mexalmCrosscheck.ledgerStatus).toBe("BLOCKED");
    expect(result.mexalmCrosscheck.confirmedLineIndexes).toEqual([]);
  });

  test("los doce renglones de la libreta que coinciden todos quedan confirmados", async () => {
    const twelveRows = Array.from({ length: 12 }, (_, index) => ({
      lineIndex: index,
      proposedKind: index < 8 ? "SALE" : "PAYMENT",
      customerName: "Chelo",
      amountCents: String(10000 + index),
    }));
    const crosscheck = crosscheckerOver(jsonResponse({ status: "OK", operations: twelveRows }));

    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(TWELVE_ROWS), crosscheck }),
    )({
      auth: AUTH_USER,
      data: NEW_APP_DATA,
    })) as any;

    // El caso del dueño: doce renglones, el Ledger de acuerdo, y los doce confirmados.
    expect(result.mexalmCrosscheck.agreed).toBe(true);
    expect(result.mexalmCrosscheck.confirmedLineIndexes).toHaveLength(12);
  });
});
