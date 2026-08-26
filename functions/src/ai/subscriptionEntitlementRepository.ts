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
  | { status: "STORAGE_ERROR" };

export interface SubscriptionEntitlementRepository {
  recordSubscription(input: RecordSubscriptionInput): Promise<RecordSubscriptionResult>;
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

  const existingTokenDoc = await tx.get(tokenDocPath);
  if (existingTokenDoc !== null) {
    const boundUid = existingTokenDoc["uid"];
    if (typeof boundUid === "string" && boundUid !== input.uid) {
      return { status: "TOKEN_BOUND_TO_DIFFERENT_UID" };
    }
  } else {
    tx.set(tokenDocPath, {
      uid: input.uid,
      boundAtEpochMillis: input.nowEpochMillis,
      productId: input.productId,
      tokenHash,
    });
  }

  tx.set(
    entitlementDocPath,
    {
      enabled: input.enabled,
      expiresAtEpochMillis: input.expiresAtEpochMillis,
      lastVerifiedAtEpochMillis: input.nowEpochMillis,
      productId: input.productId,
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
  };
}
