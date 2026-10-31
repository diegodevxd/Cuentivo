import { isAnonymousUser, type CallableAuth } from "./contract.js";
import { isEntitlementValid, type AiEntitlement } from "./ai/controlPlane.js";

/**
 * Despertar el Ledger de MexaLM cuando alguien con CuentivoAI abre la app.
 *
 * La callable no registra nada: lo único que hace es pedirle al Ledger que empiece a despertarse.
 * El "¿tiene CuentivoAI?" lo responde el mismo documento de suscripción que ya escribe
 * `verifyAiSubscription` (`aiEntitlements/{uid}`) y que lee el control de acceso de la app, con
 * la misma regla de siempre: habilitado y sin expirar.
 *
 * Por qué una callable y no piggyback sobre `getAiAvailability`: la de disponibilidad dice si el
 * servicio de IA está ENCENDIDO para todo el mundo, y la app la llama con un TTL de 5 minutos y
 * sólo cuando el plan es de IA. El despertar es otra cosa —tiene su propio ritmo, su propio
 * límite y su propio motivo para no Awake— y mezclarlo con la disponibilidad haría que un
 * "AI disponible" significara dos cosas distintas. Con una callable aparte, cada una dice una
 * cosa, y el apagado de la bandera la deja inerte sin tocar la otra.
 */
export type WarmMexalmLedgerStatus = "WARMED" | "SKIPPED";

/**
 * Por qué NO se pidió el despertar.
 *
 * `DISABLED`: la verificación cruzada está apagada, y sin ella el Ledger no se usa.
 * `NO_LEDGER`: no hay dirección del Ledger configurada.
 * `NO_SUBSCRIPTION`: la cuenta no tiene CuentivoAI activo.
 * `RATE_LIMITED`: ya se despertó hace menos de la ventana.
 */
export type WarmMexalmLedgerSkipReason =
  | "DISABLED"
  | "NO_LEDGER"
  | "NO_SUBSCRIPTION"
  | "RATE_LIMITED";

export type WarmMexalmLedgerResponse = Readonly<{
  status: WarmMexalmLedgerStatus;
  /** Por qué no se pidió el despertar, o `null` si sí se pidió. */
  reason: WarmMexalmLedgerSkipReason | null;
}>;

/**
 * Ventana de un despertar por usuario.
 *
 * En memoria basta, y a propósito: el límite existe para que abrir y cerrar la app no dispare
 * veinte health checks, no para proteger un recurso scarce. Un reinicio de la instancia pierde
 * la cuenta, y eso sólo significa un despertar de más: el Ledger es el que se apaga solo.
 */
export const MEXALM_LEDGER_WARM_WINDOW_MILLIS = 5 * 60 * 1000;

/**
 * Tope de cuentas recordadas. La memoria de una instancia de Functions no es infinita y un
 * `Map` que crece con cada cuenta que abre la app es una fuga: se podan las entradas vencidas y,
 * si todavía sobran, se echa la más vieja.
 */
const MAX_TRACKED_ACCOUNTS = 500;

export class MexalmLedgerWarmLimiter {
  private readonly lastWakeByUid = new Map<string, number>();

  constructor(
    private readonly windowMillis: number = MEXALM_LEDGER_WARM_WINDOW_MILLIS,
    private readonly maxTrackedAccounts: number = MAX_TRACKED_ACCOUNTS,
  ) {}

  /**
   * ¿Esta cuenta puede despertar al Ledger ahora?
   *
   * Devuelve `true` sólo la primera vez dentro de la ventana. La marca se pone al INTENTAR, no
   * al acertar: un Ledger caído que se reintenta en bucle cada cinco segundos es peor que uno
   * que no despierte.
   */
  tryAcquire(uid: string, nowEpochMillis: number): boolean {
    this.prune(nowEpochMillis);
    const last = this.lastWakeByUid.get(uid);
    if (last !== undefined && nowEpochMillis - last < this.windowMillis) {
      return false;
    }
    this.lastWakeByUid.set(uid, nowEpochMillis);
    while (this.lastWakeByUid.size > this.maxTrackedAccounts) {
      const oldest = this.lastWakeByUid.keys().next();
      if (oldest.done) break;
      this.lastWakeByUid.delete(oldest.value);
    }
    return true;
  }

  private prune(nowEpochMillis: number): void {
    for (const [uid, last] of this.lastWakeByUid) {
      if (nowEpochMillis - last >= this.windowMillis) {
        this.lastWakeByUid.delete(uid);
      }
    }
  }
}

export function createWarmMexalmLedgerHandler(deps: {
  /** La bandera de la verificación cruzada. Apagada, esta callable no hace nada. */
  isEnabled: () => boolean;
  /** Dónde vive el Ledger. Vacía, no hay a quién despertar. */
  ledgerUrl: () => string;
  /** El documento de suscripción de la cuenta. `null` cuando no existe. */
  readEntitlement: (uid: string) => Promise<AiEntitlement | null>;
  /** Dispara el despertar. No espera y nunca lanza. */
  warm: () => void;
  now: () => number;
  /** El límite de un despertar por cuenta cada 5 minutos. */
  limiter?: MexalmLedgerWarmLimiter;
}) {
  const limiter = deps.limiter ?? new MexalmLedgerWarmLimiter();
  return async (request: {
    auth?: CallableAuth;
    data?: unknown;
  }): Promise<WarmMexalmLedgerResponse | { code: "unauthenticated" }> => {
    const uid = request.auth?.uid;
    if (!uid || isAnonymousUser(request.auth)) {
      return { code: "unauthenticated" };
    }

    // La bandera primero: apagada, la callable es un no-op y ni siquiera lee la suscripción.
    if (!deps.isEnabled()) {
      return { status: "SKIPPED", reason: "DISABLED" };
    }
    if (deps.ledgerUrl().length === 0) {
      return { status: "SKIPPED", reason: "NO_LEDGER" };
    }

    // La suscripción: la misma regla de siempre —habilitada y sin expirar—. La escribe
    // `verifyAiSubscription` cuando Play confirma la compra, y la apaga el consumidor de RTDN
    // cuando se cancela o expira.
    const entitlement = await deps.readEntitlement(uid);
    if (!isEntitlementValid(entitlement, deps.now())) {
      return { status: "SKIPPED", reason: "NO_SUBSCRIPTION" };
    }

    if (!limiter.tryAcquire(uid, deps.now())) {
      return { status: "SKIPPED", reason: "RATE_LIMITED" };
    }

    // El despertar se dispara y se olvida: la respuesta no espera al Ledger, y un Ledger que no
    // contesta no cambia nada de lo que la app está haciendo.
    deps.warm();
    return { status: "WARMED", reason: null };
  };
}
