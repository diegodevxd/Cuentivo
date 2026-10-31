import { describe, expect, test, vi } from "vitest";
import { createExtractLedgerPageHandler } from "../src/extractLedgerPage.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { LedgerExtractionProvider } from "../src/ai/geminiLedgerProvider.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { LedgerExtractionResult, MexalmCrosscheckBlock } from "../src/contract.js";
import type { MexalmLedgerCrosschecker } from "../src/ai/mexalmCrosscheck.js";

const NOW = 1_000_000;

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const VALID_DATA = {
  contractVersion: 3,
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

const EXTRACTION: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Rosa",
      amountCents: "25000",
      uncertainFields: [],
      unreadableFields: [],
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
};

const EXTRACTION_V4: LedgerExtractionResult = {
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
      rawText: "Rosa protector tapete 250",
      items: [{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }],
      warnings: [],
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
  pageDate: { date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 },
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
    provider: providerReturning(EXTRACTION_V4),
    guard: openGuard(),
    now: () => NOW,
    ...overrides,
  };
}

const V3_ALLOWED_ROOT_KEYS = [
  "contractVersion",
  "requestId",
  "status",
  "operations",
  "discards",
  "unreadTextRemaining",
  "pageIncomplete",
] as const;

const V3_ALLOWED_OP_KEYS = [
  "lineIndex",
  "proposedKind",
  "customerName",
  "amountCents",
  "description",
  "date",
  "uncertainFields",
  "unreadableFields",
  "region",
] as const;

/**
 * El bloque tal como lo devuelve el verificador cuando el Ledger sí contestó.
 *
 * `confirmedLineIndexes` va incluido a propósito: es la llave OPCIONAL del bloque, y una prueba de
 * compatibilidad con la app vieja tiene que ejercitar el bloque CON su forma más ancha. Si probara
 * con la forma mínima, la llave de más quedaría sin cubrir.
 */
const CROSSCHECK_BLOCK: MexalmCrosscheckBlock = {
  agreed: true,
  disagreements: [],
  ledgerStatus: "OK",
  latencyMs: 12,
  confirmedLineIndexes: [0],
};

/**
 * Verificador de mentira que cuenta si lo llamaron. `crosscheck` es lo que cuesta dinero —una ida y
 * vuelta al Ledger por página—, así que "no se llamó" es una aserción sobre el gasto, no sobre el
 * estilo.
 */
function crosscheckerSpy(block: MexalmCrosscheckBlock | null = CROSSCHECK_BLOCK): MexalmLedgerCrosschecker & {
  crosscheck: ReturnType<typeof vi.fn>;
} {
  return { crosscheck: vi.fn(async () => block) } as MexalmLedgerCrosschecker & {
    crosscheck: ReturnType<typeof vi.fn>;
  };
}

/**
 * La regla EXACTA con la que la app publicada hoy (≤ v48) decide si acepta una respuesta. Está
 * copiada de `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt` en el
 * commit `76242e5`, que es el parser que corre en los teléfonos de los clientes.
 *
 * Se copia en vez de importarse porque es Kotlin y esto es TypeScript, y se copia TAL CUAL —con su
 * comparación de juego exacto para v3 y su lista cerrada para v4/v5— porque una versión "equivalente
 * pero más amable" de esta regla no probaría nada: probaría que el servidor le habla bien a un
 * parser que no existe.
 *
 * Devuelve `null` cuando la app rechaza la página entera (lo que en Kotlin es `Unavailable`): para
 * esta prueba basta saber si la página pasa o no, no qué contendría.
 */
function oldAppAcceptsResponse(root: Record<string, unknown>): boolean {
  const version = root["contractVersion"] as number;
  const baseKeys = [
    "contractVersion",
    "requestId",
    "status",
    "operations",
    "discards",
    "unreadTextRemaining",
    "pageIncomplete",
  ];
  const rootKeys = Object.keys(root);
  if (version === 3) {
    return rootKeys.length === baseKeys.length && baseKeys.every((k) => rootKeys.includes(k));
  }
  const allowedKeys = [
    ...baseKeys,
    "pageDate",
    ...(version === 5 ? ["pageType", "pageTotals"] : []),
  ];
  return rootKeys.every((k) => allowedKeys.includes(k)) && baseKeys.every((k) => rootKeys.includes(k));
}

describe("createExtractLedgerPageHandler", () => {
  test("v3 request returns exact v3 projection without a single v4 key even when provider returns rich v4 data", async () => {
    const result = (await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(result.status).toBe("AI_PROPOSAL");
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(result).not.toHaveProperty("pageDate");

    expect(result.operations).toHaveLength(1);
    const op = result.operations[0];
    const opKeys = Object.keys(op);
    for (const key of opKeys) {
      expect((V3_ALLOWED_OP_KEYS as readonly string[]).includes(key)).toBe(true);
    }
    expect(op).not.toHaveProperty("confidence");
    expect(op).not.toHaveProperty("sources");
    expect(op).not.toHaveProperty("rawText");
    expect(op).not.toHaveProperty("items");
    expect(op).not.toHaveProperty("warnings");
  });

  test("v4 request preserves rich v4 data including pageDate, confidence, sources, items, rawText, warnings", async () => {
    const v4Data = { ...VALID_DATA, contractVersion: 4 };
    const result = (await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: v4Data,
    })) as any;

    expect(result.contractVersion).toBe(4);
    expect(result.status).toBe("AI_PROPOSAL");
    expect(result.pageDate).toEqual({ date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 });
    expect(result.operations[0].confidence).toEqual({ customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 });
    expect(result.operations[0].sources).toEqual({ customer: "EXPLICIT", date: "PAGE_DATE" });
    expect(result.operations[0].rawText).toBe("Rosa protector tapete 250");
    expect(result.operations[0].items).toEqual([{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }]);
  });

  /**
   * Esta es la prueba que faltaba, y su ausencia dejó pasar un defecto real: la proyección de v4
   * pasaba `extraction.operations` EN CRUDO. Como un V5 es asignable a un V4, compilaba — pero el
   * objeto rico ya trae `marginNote`, así que la respuesta v4 habría llevado una llave que el v37 y
   * el v40 no conocen, y su parser compara el juego de llaves exacto: habría rechazado la PÁGINA
   * ENTERA. Se prueba con la extracción ENRIQUECIDA, que es justo lo que lo volvía invisible.
   */
  const V5_RICH_EXTRACTION: LedgerExtractionResult = {
    operations: [
      {
        ...EXTRACTION_V4.operations[0],
        marginNote: "Agotado",
      },
    ],
    discards: [],
    unreadTextRemaining: false,
    pageIncomplete: false,
    pageDate: { date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 },
    pageType: "COST_OR_PRICE_LIST",
    pageTotals: [{ label: "saldo", amountCents: "20000", afterLineIndex: 0 }],
  };

  test("a v4 request drops every v5-only key even when the provider returns the rich v5 shape", async () => {
    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(V5_RICH_EXTRACTION) }),
    )({
      auth: AUTH_USER,
      data: { ...VALID_DATA, contractVersion: 4 },
    })) as any;

    expect(result.contractVersion).toBe(4);
    // La raíz de v4 no lleva ninguna llave de v5...
    expect(result).not.toHaveProperty("pageType");
    expect(result).not.toHaveProperty("pageTotals");
    // ...y la operación tampoco, aunque el objeto rico la traiga.
    expect(result.operations[0]).not.toHaveProperty("marginNote");
    // Lo que sí es de v4 sigue llegando intacto.
    expect(result.operations[0].rawText).toBe("Rosa protector tapete 250");
    expect(result.operations[0].confidence).toEqual({ customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 });
    expect(result.pageDate).toEqual({ date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 });
  });

  test("a v3 request drops the v5 keys too, not only the v4 ones", async () => {
    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(V5_RICH_EXTRACTION) }),
    )({
      auth: AUTH_USER,
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(result.operations[0]).not.toHaveProperty("marginNote");
  });

  test("a v5 request is the only one that carries pageType, pageTotals and marginNote", async () => {
    const result = (await createExtractLedgerPageHandler(
      deps({ provider: providerReturning(V5_RICH_EXTRACTION) }),
    )({
      auth: AUTH_USER,
      data: { ...VALID_DATA, contractVersion: 5 },
    })) as any;

    expect(result.contractVersion).toBe(5);
    expect(result.pageType).toBe("COST_OR_PRICE_LIST");
    expect(result.pageTotals).toEqual([{ label: "saldo", amountCents: "20000", afterLineIndex: 0 }]);
    expect(result.operations[0].marginNote).toBe("Agotado");
  });

  test.each([
    ["AI_UNAVAILABLE", async () => ({} as any), deps({ provider: null })],
    ["AI_QUOTA_EXHAUSTED", async () => ({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false }) })],
    ["AI_RATE_LIMITED", async () => ({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false }) })],
    ["AI_DISABLED", async () => ({ decision: "AI_DISABLED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_DISABLED", provider: null, fallbackUsed: false }) })],
    ["AI_ENTITLEMENT_REQUIRED", async () => ({ decision: "AI_ENTITLEMENT_REQUIRED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_ENTITLEMENT_REQUIRED", provider: null, fallbackUsed: false }) })],
    ["PROVIDER_ERROR", async () => ({} as any), deps({ provider: { extract: async () => { throw new Error("boom"); } } })],
    ["REPLAY", async () => ({} as any), deps({ claimProviderInvocation: async () => "ALREADY_CLAIMED" as const })],
  ])("v3 projection holds for non-proposal branch: %s", async (_name, _authOverride, testDeps) => {
    const result = (await createExtractLedgerPageHandler(testDeps)({
      auth: AUTH_USER,
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(result).not.toHaveProperty("pageDate");
  });

  test("refuses an unauthenticated caller before touching anything", async () => {
    const provider = providerReturning(EXTRACTION_V4);
    const authorize = vi.fn(async () => AUTHORIZED);

    const result = await createExtractLedgerPageHandler(deps({ provider, authorize }))({
      data: VALID_DATA,
    });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(authorize).not.toHaveBeenCalled();
    expect(provider.extract).not.toHaveBeenCalled();
  });

  test("an anonymous session never reaches the provider and respects v3 key projection", async () => {
    const provider = providerReturning(EXTRACTION_V4);
    const onRejected = vi.fn();

    const result = (await createExtractLedgerPageHandler(deps({ provider, onRejected }))({
      auth: { uid: "anon", token: { firebase: { sign_in_provider: "anonymous" } } },
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(result.operations).toBeNull();
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(provider.extract).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("ANONYMOUS_SESSION");
  });

  test("a v2 or v5 contractVersion is rejected as invalid-argument", async () => {
    for (const badVersion of [2]) {
      const result = await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: {
          ...VALID_DATA,
          contractVersion: badVersion,
        },
      });
      expect(result).toEqual({ code: "invalid-argument" });
    }
  });

  test("an incomplete page keeps its warning instead of looking whole", async () => {
    const result = await createExtractLedgerPageHandler(
      deps({
        provider: providerReturning({ ...EXTRACTION_V4, pageIncomplete: true, unreadTextRemaining: true }),
      }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ pageIncomplete: true, unreadTextRemaining: true });
  });

  test("the image reaches the provider exactly as validated", async () => {
    const provider = providerReturning(EXTRACTION_V4);

    await createExtractLedgerPageHandler(deps({ provider }))({
      auth: AUTH_USER,
      data: VALID_DATA,
    });

    expect(provider.extract).toHaveBeenCalledTimes(1);
    expect(provider.extract).toHaveBeenCalledWith(
      expect.objectContaining({
        imageBase64: VALID_DATA.imageBase64,
        imageMimeType: VALID_DATA.imageMimeType,
      }),
    );
  });
});

/**
 * La verificación cruzada se manda SÓLO a la app que la pide.
 *
 * El defecto que estas pruebas cierran ya estaba en producción: la app que hoy tienen los clientes
 * (≤ v48) compara el juego de llaves de la raíz y, ante una llave que no conoce, rechaza la página
 * ENTERA. Con la bandera encendida y sin esta condición, encenderla habría dejado el escaneo mudo
 * en todos los teléfonos a la vez —y el síntoma habría sido "la IA no sirve", no "llegó una llave
 * de más"—.
 *
 * Son dos condiciones distintas y las dos se prueban por separado: la bandera dice si el servidor
 * verifica; la capacidad dice si ESTA app sabe leer el bloque.
 */
describe("verificación cruzada: la capacidad decide a quién se le cuenta", () => {
  const VERSIONS = [3, 4, 5] as const;

  test.each(VERSIONS)(
    "v%i con la bandera encendida y SIN la capacidad: ni bloque, ni llamada al Ledger, ni un byte distinto",
    async (version) => {
      const crosscheck = crosscheckerSpy();
      const data = { ...VALID_DATA, contractVersion: version };

      const withFlagOn = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data,
      })) as any;
      const today = (await createExtractLedgerPageHandler(deps())({ auth: AUTH_USER, data })) as any;

      // Lo que cuesta: el Ledger no se toca.
      expect(crosscheck.crosscheck).not.toHaveBeenCalled();
      // Lo que se ve: la llave no existe y la respuesta es la de hoy, byte a byte.
      expect(withFlagOn).not.toHaveProperty("mexalmCrosscheck");
      expect(JSON.stringify(withFlagOn)).toBe(JSON.stringify(today));
      expect(withFlagOn.status).toBe("AI_PROPOSAL");
    },
  );

  test.each(VERSIONS)(
    "v%i con la bandera encendida y con la capacidad: el bloque llega y el Ledger se consulta UNA vez",
    async (version) => {
      const crosscheck = crosscheckerSpy();

      const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
        auth: AUTH_USER,
        data: { ...VALID_DATA, contractVersion: version, acceptsMexalmCrosscheck: true },
      })) as any;

      expect(crosscheck.crosscheck).toHaveBeenCalledTimes(1);
      expect(result.mexalmCrosscheck).toEqual(CROSSCHECK_BLOCK);
      // El bloque es aditivo: la página sigue exactamente igual que sin él.
      expect(result.status).toBe("AI_PROPOSAL");
      expect(result.operations).toHaveLength(1);
    },
  );

  test("la capacidad en false explícito se trata igual que ausente, no como un consentimiento tácito", async () => {
    const crosscheck = crosscheckerSpy();

    const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
      auth: AUTH_USER,
      data: { ...VALID_DATA, contractVersion: 5, acceptsMexalmCrosscheck: false },
    })) as any;

    expect(crosscheck.crosscheck).not.toHaveBeenCalled();
    expect(result).not.toHaveProperty("mexalmCrosscheck");
  });

  test("una capacidad que no es booleana se RECHAZA antes de gastar, y el mensaje lo dice", async () => {
    const crosscheck = crosscheckerSpy();
    const provider = providerReturning(EXTRACTION_V4);

    for (const bad of ["true", 1, null, {}, []]) {
      const result = await createExtractLedgerPageHandler(deps({ crosscheck, provider }))({
        auth: AUTH_USER,
        data: { ...VALID_DATA, contractVersion: 5, acceptsMexalmCrosscheck: bad },
      });
      expect(result).toEqual({ code: "invalid-argument" });
    }

    // Rechazar no gasta: ni el Ledger ni el modelo se tocan.
    expect(crosscheck.crosscheck).not.toHaveBeenCalled();
    expect(provider.extract).not.toHaveBeenCalled();
  });

  /**
   * La prueba de "app vieja contra servidor nuevo".
   *
   * `oldAppAcceptsResponse` es la regla del parser publicado, copiada del Kotlin. Se le pasa la
   * respuesta REAL que produce el handler, no una inventada.
   *
   * Y se prueba en las dos direcciones a propósito: si la regla copiada aceptara también la
   * respuesta CON el bloque, esta prueba estaría en verde sin demostrar nada —diría "la app vieja
   * aguanta" cuando lo que pasa es que la regla copiada no se parece a la app vieja—.
   */
  describe("compatibilidad con la app publicada (≤ v48)", () => {
    test.each(VERSIONS)(
      "v%i bandera encendida y sin capacidad: la respuesta PASA la regla de la app vieja",
      async (version) => {
        const crosscheck = crosscheckerSpy();

        const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
          auth: AUTH_USER,
          data: { ...VALID_DATA, contractVersion: version },
        })) as any;

        expect(oldAppAcceptsResponse(result)).toBe(true);
      },
    );

    test.each(VERSIONS)(
      "v%i la MISMA respuesta con el bloque NO pasaría esa regla: la prueba discrimina",
      async (version) => {
        const crosscheck = crosscheckerSpy();

        const result = (await createExtractLedgerPageHandler(deps({ crosscheck }))({
          auth: AUTH_USER,
          data: { ...VALID_DATA, contractVersion: version, acceptsMexalmCrosscheck: true },
        })) as any;

        expect(result.mexalmCrosscheck).toBeDefined();
        expect(oldAppAcceptsResponse(result)).toBe(false);
      },
    );
  });
});
