import type * as FirebaseFirestore from "firebase-admin/firestore";
import { hashPurchaseToken } from "./playSubscriptionVerifier";

export type RecordSubscriptionInput = Readonly<{
  uid: string;
  purchaseToken: string;
  productId: string;
  enabled: boolean;
  expiresAtEpochMillis: number;
  nowEpochMillis: number;
}>;

export type RecordSubscriptionResult =
  | { status: "RECORDED"; enabled: boolean; expiresAtEpochMillis: number }
  | { status: "TOKEN_BOUND_TO_DIFFERENT_UID" }
  | { status: "STALE_IGNORED" }
  | { status: "STORAGE_ERROR" };

/**
 * Stored entitlement state relevant to deciding whether an incoming write may land.
 * Fields are optional because documents written before `sourceTokenHash` existed lack it.
 */
export type StoredEntitlementState = Readonly<{
  enabled?: boolean;
  lastVerifiedAtEpochMillis?: number;
  sourceTokenHash?: string;
}>;

export type IncomingEntitlementWrite = Readonly<{
  sourceTokenHash: string;
  enabled: boolean;
  nowEpochMillis: number;
  origin: "VERIFICATION" | "RTDN";
}>;

export type EntitlementWriteDecision =
  | "APPLY"
  | "IGNORE_STALE"
  | "IGNORE_FOREIGN_TOKEN"
  | "IGNORE_LEGACY_UNRESOLVED";

/**
 * Decides whether an entitlement write may land, given what is already stored.
 *
 * Both `verifyAiSubscription` and the Play RTDN consumer write `aiEntitlements/{uid}`, and they
 * race: a slow verification started before a cancellation can finish after it. Writing blindly
 * loses whichever update lost the race — in both directions. It can revoke a subscription the
 * user paid for, and it can re-enable one the user cancelled.
 *
 * Three rules, in order:
 *
 * 1. **No stored state** — apply. **State predating `sourceTokenHash`** — only a verification may
 *    adopt it. A verification has just asked Play about a token it knows belongs to this user, so
 *    it can establish the source safely. An RTDN cannot: a late notification about a historical
 *    token would become the first `sourceTokenHash` and revoke an entitlement that is still valid,
 *    which is the very failure this guard exists to prevent. Legacy revocation needs an explicit
 *    reconciliation, not an inference from one isolated notification.
 * 2. **A different token than the stored source** — only a verification may adopt it, and only
 *    if it observed Play more recently. An RTDN never may: a late notification about a token the
 *    user already replaced would otherwise revoke a new, legitimate subscription.
 * 3. **The same token** — monotonic by observation time. On an exact tie, revocation wins over
 *    grant, so equal timestamps never resolve towards handing out access.
 *
 * `nowEpochMillis` must be captured *after* Play answers, so it timestamps the observation
 * rather than the start of a call whose duration is unbounded.
 */
export function entitlementWriteDecision(
  stored: StoredEntitlementState | null,
  incoming: IncomingEntitlementWrite,
): EntitlementWriteDecision {
  if (stored === null) {
    return "APPLY";
  }

  const storedAt =
    typeof stored.lastVerifiedAtEpochMillis === "number" ? stored.lastVerifiedAtEpochMillis : 0;

  // Ordering by observation time applies to every branch: being a verification does not make a
  // stale observation fresh. On an exact tie, never resolve towards granting access.
  const byObservationTime = (): EntitlementWriteDecision => {
    if (incoming.nowEpochMillis > storedAt) {
      return "APPLY";
    }
    if (incoming.nowEpochMillis < storedAt) {
      return "IGNORE_STALE";
    }
    return stored.enabled === false && incoming.enabled ? "IGNORE_STALE" : "APPLY";
  };

  if (typeof stored.sourceTokenHash !== "string" || stored.sourceTokenHash === "") {
    return incoming.origin === "RTDN" ? "IGNORE_LEGACY_UNRESOLVED" : byObservationTime();
  }

  if (stored.sourceTokenHash !== incoming.sourceTokenHash) {
    if (incoming.origin === "RTDN") {
      return "IGNORE_FOREIGN_TOKEN";
    }
    // An inactive result for some *other* token proves nothing about the one currently granting
    // access: it may be an expired or refunded historical purchase. The app verifies every owned
    // purchase on launch, so this is the common case, not an edge one. Only an active token may
    // take over, and a tie is enough — refusing it over a clock coincidence would cut off a
    // legitimate resubscription because a different subscription ended.
    if (!incoming.enabled) {
      return "IGNORE_FOREIGN_TOKEN";
    }
    return incoming.nowEpochMillis >= storedAt ? "APPLY" : "IGNORE_STALE";
  }

  return byObservationTime();
}

export type TokenBinding = Readonly<{
  uid: string;
  productId?: string;
  boundAtEpochMillis?: number;
}>;

export type UpdateEntitlementInput = Readonly<{
  uid: string;
  productId: string;
  purchaseToken: string;
  enabled: boolean;
  expiresAtEpochMillis: number;
  nowEpochMillis: number;
}>;

export type UpdateEntitlementResult = "APPLIED" | "IGNORED_SUPERSEDED" | "IGNORED_LEGACY_SOURCE";

export interface SubscriptionEntitlementRepository {
  recordSubscription(input: RecordSubscriptionInput): Promise<RecordSubscriptionResult>;
  findTokenBinding(purchaseToken: string): Promise<TokenBinding | null>;
  updateEntitlement(input: UpdateEntitlementInput): Promise<UpdateEntitlementResult>;
}

export type SubscriptionTransactionBridge = Readonly<{
  get: (path: string) => Promise<Record<string, unknown> | null>;
  set: (path: string, data: Record<string, unknown>, merge?: boolean) => unknown;
}>;

export function tokenBindingPath(purchaseTokenHash: string): string {
  return `aiPurchaseTokens/${purchaseTokenHash}`;
}

export function entitlementPath(uid: string): string {
  return `aiEntitlements/${uid}`;
}

/**
 * Executes token binding and entitlement update within an atomic transaction.
 *
 * Guaranteed Invariants:
 * 1. Single-user token binding: If the token was previously bound to a different UID, the
 *    transaction rejects with TOKEN_BOUND_TO_DIFFERENT_UID and performs no mutation.
 * 2. Idempotency: Re-verifying a token by the same user is safe and writes the absolute
 *    expiration time from Play without extending duration twice.
 */
export async function recordSubscriptionInTransaction(
  tx: SubscriptionTransactionBridge,
  input: RecordSubscriptionInput,
): Promise<RecordSubscriptionResult> {
  const tokenHash = hashPurchaseToken(input.purchaseToken);
  const tokenDocPath = tokenBindingPath(tokenHash);
  const entitlementDocPath = entitlementPath(input.uid);

  // Firestore requires every read in a transaction to precede every write, so both documents
  // are read up front. Splitting them around the token write fails at runtime, and only the
  // emulator gate catches it: the in-memory bridge used by unit tests does not enforce ordering.
  const existingTokenDoc = await tx.get(tokenDocPath);
  const storedEntitlement = (await tx.get(entitlementDocPath)) as StoredEntitlementState | null;

  if (existingTokenDoc !== null) {
    const boundUid = existingTokenDoc["uid"];
    if (typeof boundUid === "string" && boundUid !== input.uid) {
      return { status: "TOKEN_BOUND_TO_DIFFERENT_UID" };
    }
  }

  // The binding is recorded even when the entitlement write is dropped below: that this token
  // belongs to this user is a fact independent of which observation is most recent, and it is
  // what lets the RTDN consumer resolve a uid later. Losing it would leave the purchase
  // unrevocable.
  if (existingTokenDoc === null) {
    tx.set(tokenDocPath, {
      uid: input.uid,
      boundAtEpochMillis: input.nowEpochMillis,
      productId: input.productId,
      tokenHash,
    });
  }

  // A verification that raced a newer one — or a cancellation — must not overwrite it.
  const decision = entitlementWriteDecision(storedEntitlement, {
    sourceTokenHash: tokenHash,
    enabled: input.enabled,
    nowEpochMillis: input.nowEpochMillis,
    origin: "VERIFICATION",
  });
  if (decision !== "APPLY") {
    return { status: "STALE_IGNORED" };
  }

  tx.set(
    entitlementDocPath,
    {
      enabled: input.enabled,
      expiresAtEpochMillis: input.expiresAtEpochMillis,
      lastVerifiedAtEpochMillis: input.nowEpochMillis,
      productId: input.productId,
      sourceTokenHash: tokenHash,
    },
    true,
  );

  return {
    status: "RECORDED",
    enabled: input.enabled,
    expiresAtEpochMillis: input.expiresAtEpochMillis,
  };
}

export function createFirestoreSubscriptionEntitlementRepository(
  firestore: FirebaseFirestore.Firestore,
): SubscriptionEntitlementRepository {
  return {
    recordSubscription(input: RecordSubscriptionInput): Promise<RecordSubscriptionResult> {
      return firestore
        .runTransaction(async (tx) => {
          const bridge: SubscriptionTransactionBridge = {
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
          return recordSubscriptionInTransaction(bridge, input);
        })
        .catch(() => ({ status: "STORAGE_ERROR" }));
    },

    async findTokenBinding(purchaseToken: string): Promise<TokenBinding | null> {
      const tokenHash = hashPurchaseToken(purchaseToken);
      const snapshot = await firestore.doc(tokenBindingPath(tokenHash)).get();
      if (!snapshot.exists) {
        return null;
      }
      const data = snapshot.data();
      const uid = typeof data?.uid === "string" ? data.uid : null;
      if (!uid) {
        return null;
      }
      return {
        uid,
        productId: typeof data?.productId === "string" ? data.productId : undefined,
        boundAtEpochMillis: typeof data?.boundAtEpochMillis === "number" ? data.boundAtEpochMillis : undefined,
      };
    },

    async updateEntitlement(input: UpdateEntitlementInput): Promise<UpdateEntitlementResult> {
      const tokenHash = hashPurchaseToken(input.purchaseToken);
      const docRef = firestore.doc(entitlementPath(input.uid));
      return firestore.runTransaction(async (tx) => {
        const snapshot = await tx.get(docRef);
        const stored = snapshot.exists ? (snapshot.data() as StoredEntitlementState) : null;
        const decision = entitlementWriteDecision(stored, {
          sourceTokenHash: tokenHash,
          enabled: input.enabled,
          nowEpochMillis: input.nowEpochMillis,
          origin: "RTDN",
        });
        if (decision === "IGNORE_LEGACY_UNRESOLVED") {
          return "IGNORED_LEGACY_SOURCE" as const;
        }
        if (decision !== "APPLY") {
          return "IGNORED_SUPERSEDED" as const;
        }
        tx.set(
          docRef,
          {
            enabled: input.enabled,
            expiresAtEpochMillis: input.expiresAtEpochMillis,
            lastVerifiedAtEpochMillis: input.nowEpochMillis,
            productId: input.productId,
            sourceTokenHash: tokenHash,
          },
          { merge: true },
        );
        return "APPLIED" as const;
      });
    },
  };
}
