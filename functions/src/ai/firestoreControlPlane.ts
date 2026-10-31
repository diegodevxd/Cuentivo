import type * as FirebaseFirestore from "firebase-admin/firestore";
import {
  decideAuthorization,
  isControlAllowed,
  isEntitlementValid,
  type AiAuthorizationDecision,
  type AiControlConfig,
  type AiEntitlement,
  type AiProviderId,
  type AuthorizeAiInput,
  type AuthorizeAiResult,
} from "./controlPlane.js";

export interface AiControlPlaneRepository {
  authorize(input: AuthorizeAiInput): Promise<AuthorizeAiResult>;
  claimProviderInvocation(input: Readonly<{
    uid: string;
    requestId: string;
    nowEpochMillis: number;
  }>): Promise<"CLAIMED" | "ALREADY_CLAIMED">;
  getAvailability(): Promise<{ available: boolean }>;
  /**
   * La suscripción de la cuenta, tal como la dejó `verifyAiSubscription`.
   *
   * Es el MISMO documento que lee la autorización (`aiEntitlements/{uid}`), leído por su cuenta y
   * no dentro de una transacción: aquí no se autoriza nada, sólo se pregunta "¿esta cuenta tiene
   * CuentivoAI?". Un documento ausente o malformado se lee como "no lo tiene", que es lo que
   * `isEntitlementValid` decide con `null`.
   */
  readEntitlement(uid: string): Promise<AiEntitlement | null>;
}

/** Minimal transaction surface so the transactional logic is testable without a Firestore emulator. */
export type AiTransaction = Readonly<{
  get: (path: string) => Promise<Record<string, unknown> | null>;
  set: (path: string, data: Record<string, unknown>, merge?: boolean) => unknown;
}>;

const CONFIG_PATH = "aiControl/global";

const DECISIONS: readonly AiAuthorizationDecision[] = [
  "AI_DISABLED",
  "AI_AUTHENTICATION_REQUIRED",
  "AI_ENTITLEMENT_REQUIRED",
  "AI_RATE_LIMITED",
  "AI_QUOTA_EXHAUSTED",
  "AI_AUTHORIZED",
];

function entitlementPath(uid: string): string {
  return `aiEntitlements/${uid}`;
}

function monthKey(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

function minuteKey(epochMillis: number): string {
  const d = new Date(epochMillis);
  return (
    monthKey(epochMillis) +
    `${String(d.getUTCDate()).padStart(2, "0")}${String(d.getUTCHours()).padStart(2, "0")}${String(
      d.getUTCMinutes(),
    ).padStart(2, "0")}`
  );
}

function usagePath(uid: string, epochMillis: number): string {
  return `aiUsage/${uid}_${monthKey(epochMillis)}`;
}

function rateWindowPath(uid: string, epochMillis: number): string {
  return `aiRateWindows/${uid}_${minuteKey(epochMillis)}`;
}

function ledgerPath(uid: string, requestId: string): string {
  return `aiRequestLedger/${uid}_${requestId}`;
}

const DENIED = (
  decision: AiAuthorizationDecision,
): AuthorizeAiResult => ({ decision, provider: null, fallbackUsed: false });

function isStoredDecision(value: unknown): value is AiAuthorizationDecision {
  return typeof value === "string" && (DECISIONS as readonly string[]).includes(value);
}

function isStoredProvider(value: unknown): value is AiProviderId | null {
  // Exactly GEMINI (or null) only: a persisted ledger with any other provider must fail closed
  // instead of being reinterpreted.
  return value === null || value === "GEMINI";
}

/** Returns the stored result when the ledger entry exactly belongs to uid/requestId, else null. */
function storedResultOf(
  ledger: Record<string, unknown>,
  uid: string,
  requestId: string,
): AuthorizeAiResult | null {
  if (
    ledger["uid"] !== uid ||
    ledger["requestId"] !== requestId ||
    !isStoredDecision(ledger["decision"]) ||
    !isStoredProvider(ledger["provider"]) ||
    ledger["fallbackUsed"] !== false
  ) {
    return null;
  }
  return {
    decision: ledger["decision"],
    provider: ledger["provider"],
    fallbackUsed: false,
  };
}

function usedActionsOf(doc: Record<string, unknown> | null): number | null {
  if (doc === null || typeof doc !== "object") {
    return null;
  }
  const used = doc["usedActions"];
  if (typeof used !== "number" || !Number.isSafeInteger(used) || used < 0) {
    return null;
  }
  return used;
}

/**
 * One authorization per Firestore transaction with a strict read short-circuit:
 *
 * 1. `aiControl/global` is read first. Absent, malformed, disabled or provider-less config returns
 *    AI_DISABLED immediately — no further reads, no writes.
 * 2. Only when the config allows AI, the entitlement is read. Absent, malformed or expired
 *    entitlement returns AI_ENTITLEMENT_REQUIRED without touching ledger, usage or rate.
 * 3. Only with a valid entitlement the ledger is read. An existing authorized entry owned by
 *    uid/requestId returns the original result without reading counters or writing. Any other
 *    stored ledger fails closed to AI_DISABLED with zero writes.
 * 4. Only a new eligible authorization reads month usage and the minute window. Every denial
 *    writes nothing. Only AI_AUTHORIZED writes the ledger, the monthly usage and the minute
 *    window, exactly once, inside the same transaction.
 *
 * Durable guarantee: Firestore limits provider admissions only. It does not rate-limit ingress
 * reads; the warm-instance edge guard in the callable boundary is the cost mitigation, and a
 * deployed API Gateway/Cloud Armor design remains the future global ingress limit.
 */
export async function authorizeInTransaction(
  tx: AiTransaction,
  input: AuthorizeAiInput,
): Promise<AuthorizeAiResult> {
  if (input.isAnonymous === true) {
    return DENIED("AI_AUTHENTICATION_REQUIRED");
  }
  try {
    // 1. Control configuration first: nothing else is read unless AI can run.
    const config = await tx.get(CONFIG_PATH);
    const typedConfig = {
      enabled: typeof config?.["enabled"] === "boolean" ? config["enabled"] : false,
      geminiEnabled: config?.["geminiEnabled"] === true,
      monthlyActionLimit:
        typeof config?.["monthlyActionLimit"] === "number"
          ? (config["monthlyActionLimit"] as number)
          : 0,
      perMinuteLimit:
        typeof config?.["perMinuteLimit"] === "number" ? (config["perMinuteLimit"] as number) : 0,
    };
    if (!isControlAllowed(typedConfig)) {
      return DENIED("AI_DISABLED");
    }

    // 2. Entitlement second: without a valid entitlement nothing else is read or written.
    const entitlement = await tx.get(entitlementPath(input.uid));
    const typedEntitlement =
      entitlement === null
        ? null
        : {
            enabled: entitlement["enabled"] === true,
            expiresAtEpochMillis:
              typeof entitlement["expiresAtEpochMillis"] === "number"
                ? (entitlement["expiresAtEpochMillis"] as number)
                : 0,
          };
    if (!isEntitlementValid(typedEntitlement, input.nowEpochMillis)) {
      return DENIED("AI_ENTITLEMENT_REQUIRED");
    }

    // 3. Ledger third: an existing authorized entry short-circuits without reading counters.
    const existingLedger = await tx.get(ledgerPath(input.uid, input.requestId));
    if (existingLedger !== null) {
      const stored = storedResultOf(existingLedger, input.uid, input.requestId);
      if (stored !== null && stored.decision === "AI_AUTHORIZED") {
        return stored;
      }
      return DENIED("AI_DISABLED");
    }

    // 4. Counters last, only for a new eligible authorization.
    const usage = await tx.get(usagePath(input.uid, input.nowEpochMillis));
    const rate = await tx.get(rateWindowPath(input.uid, input.nowEpochMillis));
    const usageDoc = usage === null ? { usedActions: 0 } : usage;
    const rateDoc = rate === null ? { usedActions: 0 } : rate;
    const usedMonthly = usedActionsOf(usageDoc);
    const usedPerMinute = usedActionsOf(rateDoc);
    if (usedMonthly === null || usedPerMinute === null) {
      return DENIED("AI_DISABLED");
    }

    const result = decideAuthorization(
      input,
      typedConfig,
      typedEntitlement,
      { usedActions: usedMonthly },
      { usedActions: usedPerMinute },
    );

    if (result.decision !== "AI_AUTHORIZED") {
      // Every denial writes nothing: no ledger, no usage, no rate documents.
      return result;
    }

    // Only AI_AUTHORIZED writes, exactly once, inside the same transaction.
    await tx.set(ledgerPath(input.uid, input.requestId), {
      uid: input.uid,
      requestId: input.requestId,
      decision: result.decision,
      provider: result.provider,
      fallbackUsed: false,
      createdAtEpochMillis: input.nowEpochMillis,
    });
    await tx.set(usagePath(input.uid, input.nowEpochMillis), { usedActions: usedMonthly + 1 });
    await tx.set(rateWindowPath(input.uid, input.nowEpochMillis), {
      usedActions: usedPerMinute + 1,
    });

    return result;
  } catch {
    return DENIED("AI_DISABLED");
  }
}

/**
 * Durable at-most-once provider invocation claim over the existing authorized ledger row
 * `aiRequestLedger/{uid}_{requestId}`. The ledger must already exist, belong exactly to
 * uid/requestId, and record AI_AUTHORIZED with the GEMINI provider and no fallback. The first
 * valid claim adds ONLY an invocation state and timestamp to that row and returns CLAIMED; any
 * repetition, malformed/foreign/denied ledger or storage error fails closed to ALREADY_CLAIMED
 * without writing. Raw text, provider output, tokens and keys are never part of this row.
 */
export async function claimProviderInvocationInTransaction(
  tx: AiTransaction,
  input: Readonly<{ uid: string; requestId: string; nowEpochMillis: number }>,
): Promise<"CLAIMED" | "ALREADY_CLAIMED"> {
  try {
    const ledger = await tx.get(ledgerPath(input.uid, input.requestId));
    if (ledger === null) {
      return "ALREADY_CLAIMED";
    }
    const stored = storedResultOf(ledger, input.uid, input.requestId);
    if (stored === null || stored.decision !== "AI_AUTHORIZED" || stored.provider !== "GEMINI") {
      return "ALREADY_CLAIMED";
    }
    const invocationState = ledger["providerInvocation"];
    if (invocationState !== undefined && invocationState !== null) {
      // Any recorded state (CLAIMED or unexpected) means the provider slot was already consumed.
      return "ALREADY_CLAIMED";
    }
    await tx.set(
      ledgerPath(input.uid, input.requestId),
      {
        ...ledger,
        providerInvocation: "CLAIMED",
        providerClaimedAtEpochMillis: input.nowEpochMillis,
      },
    );
    return "CLAIMED";
  } catch {
    // Storage error: fail closed so the provider is never invoked twice.
    return "ALREADY_CLAIMED";
  }
}

/** Firestore composition root: one Admin SDK transaction per authorization, fail-closed on error. */
export function createFirestoreControlPlaneRepository(
  firestore: FirebaseFirestore.Firestore,
): AiControlPlaneRepository {
  return {
    authorize(input: AuthorizeAiInput): Promise<AuthorizeAiResult> {
      return firestore
        .runTransaction(async (tx) => {
          const bridge: AiTransaction = {
            get: (path) =>
              tx.get(firestore.doc(path)).then((snapshot) =>
                snapshot.exists ? (snapshot.data() as Record<string, unknown>) : null,
              ),
            set: (path, data, merge) => {
              const typed = data as FirebaseFirestore.WithFieldValue<FirebaseFirestore.DocumentData>;
              return merge
                ? tx.set(firestore.doc(path), typed, { merge: true })
                : tx.set(firestore.doc(path), typed);
            },
          };
          return authorizeInTransaction(bridge, input);
        })
        .catch(() => DENIED("AI_DISABLED"));
    },

    claimProviderInvocation(
      input: Readonly<{ uid: string; requestId: string; nowEpochMillis: number }>,
    ): Promise<"CLAIMED" | "ALREADY_CLAIMED"> {
      return firestore
        .runTransaction(async (tx) => {
          const bridge: AiTransaction = {
            get: (path) =>
              tx.get(firestore.doc(path)).then((snapshot) =>
                snapshot.exists ? (snapshot.data() as Record<string, unknown>) : null,
              ),
            set: (path, data, merge) => {
              const typed = data as FirebaseFirestore.WithFieldValue<FirebaseFirestore.DocumentData>;
              return merge
                ? tx.set(firestore.doc(path), typed, { merge: true })
                : tx.set(firestore.doc(path), typed);
            },
          };
          return claimProviderInvocationInTransaction(bridge, input);
        })
        .catch(() => "ALREADY_CLAIMED");
    },
    async getAvailability(): Promise<{ available: boolean }> {
      try {
        const snap = await firestore.doc(CONFIG_PATH).get();
        if (!snap.exists) {
          return { available: false };
        }
        const data = snap.data() as AiControlConfig | undefined;
        return { available: isControlAllowed(data ?? null) };
      } catch {
        return { available: false };
      }
    },
    async readEntitlement(uid: string): Promise<AiEntitlement | null> {
      if (uid.length === 0) {
        return null;
      }
      try {
        const snap = await firestore.doc(entitlementPath(uid)).get();
        if (!snap.exists) {
          return null;
        }
        const data = snap.data();
        if (data === undefined) {
          return null;
        }
        return {
          enabled: data["enabled"] === true,
          expiresAtEpochMillis:
            typeof data["expiresAtEpochMillis"] === "number" ? data["expiresAtEpochMillis"] : 0,
        };
      } catch {
        // Un Firestore que no contesta NO es una suscripción: se lee como "no la tiene" y el
        // despertar se salta. Fallar hacia "sí la tiene" sería despertarle el Ledger a cualquiera.
        return null;
      }
    },
  };
}
