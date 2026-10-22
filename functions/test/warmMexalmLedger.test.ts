import { describe, expect, test, vi } from "vitest";
import {
  createWarmMexalmLedgerHandler,
  MexalmLedgerWarmLimiter,
  MEXALM_LEDGER_WARM_WINDOW_MILLIS,
  type WarmMexalmLedgerResponse,
} from "../src/warmMexalmLedger.js";
import {
  createMexalmLedgerWarmer,
  MEXALM_LEDGER_WARM_TIMEOUT_MILLIS,
  type MexalmLedgerWarmFetch,
} from "../src/ai/mexalmLedgerWarm.js";
import type { AiEntitlement } from "../src/ai/controlPlane.js";

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const ANONYMOUS_USER = {
  uid: "test-anonymous",
  token: { firebase: { sign_in_provider: "anonymous" } },
} as const;

const NOW = 1_700_000_000_000;

const ACTIVE_ENTITLEMENT: AiEntitlement = {
  enabled: true,
  expiresAtEpochMillis: NOW + 60_000,
};

function handlerWith(overrides: Partial<Parameters<typeof createWarmMexalmLedgerHandler>[0]> = {}) {
  const warm = vi.fn();
  const readEntitlement = vi.fn(async () => ACTIVE_ENTITLEMENT as AiEntitlement | null);
  const handler = createWarmMexalmLedgerHandler({
    isEnabled: () => true,
    ledgerUrl: () => "ledger.invalid",
    readEntitlement,
    warm,
    now: () => NOW,
    ...overrides,
  });
  return { handler, warm, readEntitlement };
}

describe("despertar el Ledger de MexaLM", () => {
  test("sin sesión no hace nada", async () => {
    const { handler, warm } = handlerWith();

    const result = await handler({ data: {} });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("una cuenta anónima no hace nada", async () => {
    const { handler, warm } = handlerWith();

    const result = await handler({ auth: ANONYMOUS_USER, data: {} });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("con la bandera apagada no llama al Ledger", async () => {
    const { handler, warm, readEntitlement } = handlerWith({ isEnabled: () => false });

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "SKIPPED", reason: "DISABLED" });
    expect(warm).not.toHaveBeenCalled();
    // Apagada, ni siquiera se lee la suscripción: no se gasta una lectura de Firestore.
    expect(readEntitlement).not.toHaveBeenCalled();
  });

  test("sin dirección del Ledger no llama a nadie", async () => {
    const { handler, warm } = handlerWith({ ledgerUrl: () => "" });

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "SKIPPED", reason: "NO_LEDGER" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("sin suscripción activa no llama al Ledger", async () => {
    const { handler, warm } = handlerWith({ readEntitlement: async () => null });

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "SKIPPED", reason: "NO_SUBSCRIPTION" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("una suscripción vencida no cuenta como suscripción", async () => {
    const { handler, warm } = handlerWith({
      readEntitlement: async () => ({ enabled: true, expiresAtEpochMillis: NOW - 1 }),
    });

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "SKIPPED", reason: "NO_SUBSCRIPTION" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("una suscripción deshabilitada no cuenta como suscripción", async () => {
    const { handler, warm } = handlerWith({
      readEntitlement: async () => ({ enabled: false, expiresAtEpochMillis: NOW + 60_000 }),
    });

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "SKIPPED", reason: "NO_SUBSCRIPTION" });
    expect(warm).not.toHaveBeenCalled();
  });

  test("con suscripción activa dispara el despertar y responde", async () => {
    const { handler, warm } = handlerWith();

    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "WARMED", reason: null });
    expect(warm).toHaveBeenCalledTimes(1);
  });

  test("el límite es de un despertar por cuenta cada 5 minutos", async () => {
    const { handler, warm } = handlerWith({ now: () => NOW });

    expect(await handler({ auth: AUTH_USER, data: {} })).toEqual({
      status: "WARMED",
      reason: null,
    });
    // Inmediatamente después: no se vuelve a despertar.
    expect(await handler({ auth: AUTH_USER, data: {} })).toEqual({
      status: "SKIPPED",
      reason: "RATE_LIMITED",
    });
    expect(warm).toHaveBeenCalledTimes(1);
  });

  test("pasados los 5 minutos se vuelve a despertar", async () => {
    let now = NOW;
    const { handler, warm } = handlerWith({
      now: () => now,
      // La suscripción sigue viva: el reloj de la prueba es el del despertar, no el de la cuenta.
      readEntitlement: async () => ({ enabled: true, expiresAtEpochMillis: NOW + 86_400_000 }),
    });

    await handler({ auth: AUTH_USER, data: {} });
    now = NOW + MEXALM_LEDGER_WARM_WINDOW_MILLIS;
    const result = await handler({ auth: AUTH_USER, data: {} });

    expect(result).toEqual({ status: "WARMED", reason: null });
    expect(warm).toHaveBeenCalledTimes(2);
  });

  test("el límite es por cuenta: otra cuenta sí puede despertar", async () => {
    const { handler, warm } = handlerWith();

    await handler({ auth: AUTH_USER, data: {} });
    const other = await handler({
      auth: { uid: "otro-usuario", token: AUTH_USER.token },
      data: {},
    });

    expect(other).toEqual({ status: "WARMED", reason: null });
    expect(warm).toHaveBeenCalledTimes(2);
  });

  test("un error del Ledger no rompe nada", async () => {
    // El despertador real nunca lanza: el health check que falla se traga. Esto es lo que la
    // app ve cuando el Ledger está caído.
    const failingFetch: MexalmLedgerWarmFetch = async () => {
      throw new Error("el Ledger no contesta");
    };
    const warmer = createMexalmLedgerWarmer({ baseUrl: "ledger.invalid", fetchImpl: failingFetch });

    expect(() => warmer.warm()).not.toThrow();
    // Y la callable sigue respondiendo lo mismo.
    const { handler } = handlerWith();
    const result = (await handler({ auth: AUTH_USER, data: {} })) as WarmMexalmLedgerResponse;
    expect(result.status).toBe("WARMED");
  });

  test("unLedger que contesta con un error HTTP tampoco rompe nada", async () => {
    const failingFetch: MexalmLedgerWarmFetch = async () => ({ ok: false, status: 503 });
    const warmer = createMexalmLedgerWarmer({ baseUrl: "ledger.invalid", fetchImpl: failingFetch });

    expect(() => warmer.warm()).not.toThrow();
  });
});

describe("el despertador del Ledger", () => {
  test("dispara un GET al health check y no espera", () => {
    const calls: Array<{ url: string; init: { method: string; headers: Record<string, string> } }> = [];
    const fetchImpl: MexalmLedgerWarmFetch = async (url, init) => {
      calls.push({ url, init });
      return { ok: true, status: 200 };
    };
    const warmer = createMexalmLedgerWarmer({ baseUrl: "ledger.invalid", fetchImpl });

    warmer.warm();

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("ledger.invalid/health");
    expect(calls[0].init.method).toBe("GET");
  });

  test("no manda el token: el secreto sigue ligado a una sola callable", () => {
    const calls: Array<{ init: { headers: Record<string, string> } }> = [];
    const fetchImpl: MexalmLedgerWarmFetch = async (_url, init) => {
      calls.push({ init });
      return { ok: true, status: 200 };
    };
    const warmer = createMexalmLedgerWarmer({ baseUrl: "ledger.invalid", fetchImpl });

    warmer.warm();

    expect(calls[0].init.headers).toEqual({});
  });

  test("sin dirección no hace ninguna llamada", () => {
    const fetchImpl = vi.fn(async () => ({ ok: true, status: 200 }));
    const warmer = createMexalmLedgerWarmer({ baseUrl: "", fetchImpl });

    warmer.warm();

    expect(fetchImpl).not.toHaveBeenCalled();
  });

  test("el plazo del health check es de 2 segundos", () => {
    expect(MEXALM_LEDGER_WARM_TIMEOUT_MILLIS).toBe(2_000);
  });
});

describe("el límite en memoria", () => {
  test("permite el primer despertar y lo vuelve a negar dentro de la ventana", () => {
    const limiter = new MexalmLedgerWarmLimiter();

    expect(limiter.tryAcquire("cuenta", NOW)).toBe(true);
    expect(limiter.tryAcquire("cuenta", NOW + 1_000)).toBe(false);
    expect(limiter.tryAcquire("cuenta", NOW + MEXALM_LEDGER_WARM_WINDOW_MILLIS)).toBe(true);
  });

  test("la marca se pone al intentar, no al acertar", () => {
    const limiter = new MexalmLedgerWarmLimiter();

    // El primero falla (el Ledger no despertó) y el segundo, dentro de la ventana, tampoco se
    // intenta: reintentar en bucle cada cinco segundos es peor que no despertar.
    expect(limiter.tryAcquire("cuenta", NOW)).toBe(true);
    expect(limiter.tryAcquire("cuenta", NOW + 1_000)).toBe(false);
  });

  test("no crece sin límite: las cuentas viejas se olvidan", () => {
    const limiter = new MexalmLedgerWarmLimiter(MEXALM_LEDGER_WARM_WINDOW_MILLIS, 3);

    for (let index = 0; index < 10; index += 1) {
      limiter.tryAcquire(`cuenta-${index}`, NOW + index);
    }

    // La cuenta más antigua quedó fuera del mapa, así que puede volver a intentarlo.
    expect(limiter.tryAcquire("cuenta-0", NOW + 10)).toBe(true);
  });
});
