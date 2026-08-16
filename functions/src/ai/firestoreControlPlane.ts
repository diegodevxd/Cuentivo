import type * as FirebaseFirestore from "firebase-admin/firestore";
import {
  decideAuthorization,
  type AiAuthorizationDecision,
  type AiProviderId,
  type AuthorizeAiInput,
  type AuthorizeAiResult,
} from "./controlPlane";

export interface AiControlPlaneRepository {
  authorize(input: AuthorizeAiInput): Promise<AuthorizeAiResult>;
}

/** Minimal transaction surface so the transactional logic is testable without a Firestore emulator. */
export type AiTransaction = Readonly<{
  get: (path: string) => Promise<Record<string, unknown> | null>;
  set: (path: string, data: Record<string, unknown>, merge?: boolean) => unknown;
}>;

const CONFIG_PATH = "aiControl/global";

const DECISIONS: readonly AiAuthorizationDecision[] = [
  "AI_DISABLED",
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
  return value === null || value === "DEEPSEEK" || value === "GEMINI";
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
 * One authorization per Firestore transaction. Reads the ledger first: only an existing entry
 * that exactly belongs to uid/requestId AND records an authorized decision is returned without
 * increments. Any other stored ledger (malformed, foreign or denied) fails closed untouched.
 * Every denial — disabled config, missing or expired entitlement, quota/rate exhausted, malformed
 * documents, ownership mismatch or storage error — returns WITHOUT creating or modifying ledger,
 * usage or rate documents, so attackers cannot generate documents or cost while AI is off. Only
 * AI_AUTHORIZED writes the ledger and increments usage and rate by exactly one in the same
 * transaction.
 */
export async function authorizeInTransaction(
  tx: AiTransaction,
  input: AuthorizeAiInput,
): Promise<AuthorizeAiResult> {
  try {
    const existingLedger = await tx.get(ledgerPath(input.uid, input.requestId));
    if (existingLedger !== null) {
      const stored = storedResultOf(existingLedger, input.uid, input.requestId);
      if (stored !== null && stored.decision === "AI_AUTHORIZED") {
        return stored;
      }
      return DENIED("AI_DISABLED");
    }

    const config = await tx.get(CONFIG_PATH);
    const entitlement = await tx.get(entitlementPath(input.uid));
    const usage = await tx.get(usagePath(input.uid, input.nowEpochMillis));
    const rate = await tx.get(rateWindowPath(input.uid, input.nowEpochMillis));

    const usageDoc = usage === null ? { usedActions: 0 } : usage;
    const rateDoc = rate === null ? { usedActions: 0 } : rate;
    const usedMonthly = usedActionsOf(usageDoc);
    const usedPerMinute = usedActionsOf(rateDoc);
    if (usedMonthly === null || usedPerMinute === null) {
      return DENIED("AI_DISABLED");
    }

    const configProviders = (config?.["providers"] ?? {}) as Record<string, unknown>;
    const result = decideAuthorization(
      input,
      {
        enabled: typeof config?.["enabled"] === "boolean" ? config["enabled"] : false,
        providers: {
          DEEPSEEK: configProviders["DEEPSEEK"] === true,
          GEMINI: configProviders["GEMINI"] === true,
        },
        monthlyActionLimit:
          typeof config?.["monthlyActionLimit"] === "number"
            ? (config["monthlyActionLimit"] as number)
            : 0,
        perMinuteLimit:
          typeof config?.["perMinuteLimit"] === "number" ? (config["perMinuteLimit"] as number) : 0,
      },
      entitlement === null
        ? null
        : {
            enabled: entitlement["enabled"] === true,
            expiresAtEpochMillis:
              typeof entitlement["expiresAtEpochMillis"] === "number"
                ? (entitlement["expiresAtEpochMillis"] as number)
                : 0,
          },
      { usedActions: usedMonthly },
      { usedActions: usedPerMinute },
    );

    if (result.decision !== "AI_AUTHORIZED") {
      // Denials never write: no ledger, no usage, no rate documents.
      return result;
    }

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
  };
}
