import {
  type PlaySubscriptionApi,
  type PlaySubscriptionV2Response,
  PlayApiConfigurationError,
  translatePlaySubscription,
} from "./ai/playSubscriptionVerifier";
import type { SubscriptionEntitlementRepository } from "./ai/subscriptionEntitlementRepository";

export type PlayRtdnPayload = Readonly<{
  version?: string;
  packageName?: string;
  eventTimeMillis?: string | number;
  subscriptionNotification?: Readonly<{
    version?: string;
    notificationType?: number;
    purchaseToken?: string;
    subscriptionId?: string;
  }>;
  voidedPurchaseNotification?: Readonly<{
    purchaseToken?: string;
    orderId?: string;
    productType?: number;
    refundType?: number;
  }>;
  testNotification?: Readonly<{
    version?: string;
  }>;
}>;

export type PlayRtdnHandlerResult =
  | { status: "TEST_NOTIFICATION" }
  | { status: "IGNORED"; reason: string }
  | { status: "TOKEN_NOT_BOUND" }
  | {
      status: "PROCESSED";
      uid: string;
      enabled: boolean;
      expiresAtEpochMillis: number;
    };

export const SUPPORTED_AI_PRODUCT_ID = "cuentivo_ai_mensual";

export function isPermanentConfigError(error: unknown): boolean {
  if (error instanceof PlayApiConfigurationError) {
    return true;
  }
  if (typeof error === "object" && error !== null && (error as { isPermanentConfigError?: boolean }).isPermanentConfigError === true) {
    return true;
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    if (
      msg.includes("service account not configured") ||
      msg.includes("not valid json") ||
      msg.includes("invalid_grant") ||
      msg.includes("unauthorized_client")
    ) {
      return true;
    }
  }
  return false;
}

/**
 * Extracts and decodes the Play RTDN payload from various message shapes (base64 string,
 * raw string, buffer, or parsed object).
 */
export function parsePlayRtdnPayload(raw: unknown): PlayRtdnPayload | null {
  if (!raw) return null;

  let target: unknown = raw;
  if (typeof raw === "object" && raw !== null && "data" in raw) {
    target = (raw as { data: unknown }).data;
  }

  if (typeof target === "object" && target !== null && !Buffer.isBuffer(target)) {
    return target as PlayRtdnPayload;
  }

  let text: string;
  if (typeof target === "string") {
    text = target.trim();
  } else if (Buffer.isBuffer(target)) {
    text = target.toString("utf8").trim();
  } else {
    return null;
  }

  // Try direct JSON parse first
  try {
    return JSON.parse(text) as PlayRtdnPayload;
  } catch {
    // If not direct JSON, attempt base64 decode then JSON parse
    try {
      const decoded = Buffer.from(text, "base64").toString("utf8");
      return JSON.parse(decoded) as PlayRtdnPayload;
    } catch {
      return null;
    }
  }
}

export function createPlayRtdnHandler(deps: {
  playApi: PlaySubscriptionApi;
  repository: SubscriptionEntitlementRepository;
  packageName: string;
  now: () => number;
  onIgnored?: (reason: string) => void;
  onProcessed?: (details: { uid: string; enabled: boolean; expiresAtEpochMillis: number }) => void;
}): (rawMessage: unknown) => Promise<PlayRtdnHandlerResult> {
  return async (rawMessage: unknown): Promise<PlayRtdnHandlerResult> => {
    const payload = parsePlayRtdnPayload(rawMessage);
    if (!payload) {
      deps.onIgnored?.("INVALID_PAYLOAD");
      return { status: "IGNORED", reason: "INVALID_PAYLOAD" };
    }

    if (payload.testNotification) {
      deps.onIgnored?.("TEST_NOTIFICATION");
      return { status: "TEST_NOTIFICATION" };
    }

    if (payload.packageName && payload.packageName !== deps.packageName) {
      deps.onIgnored?.("PACKAGE_NAME_MISMATCH");
      return { status: "IGNORED", reason: "PACKAGE_NAME_MISMATCH" };
    }

    const purchaseToken =
      payload.subscriptionNotification?.purchaseToken?.trim() ||
      payload.voidedPurchaseNotification?.purchaseToken?.trim();

    if (!purchaseToken) {
      deps.onIgnored?.("NO_PURCHASE_TOKEN");
      return { status: "IGNORED", reason: "NO_PURCHASE_TOKEN" };
    }

    const binding = await deps.repository.findTokenBinding(purchaseToken);
    if (!binding || !binding.uid) {
      deps.onIgnored?.("TOKEN_NOT_BOUND");
      return { status: "TOKEN_NOT_BOUND" };
    }

    // Fail-closed towards NOT TOUCHING: if Play Developer API throws a transient error
    // (timeout, network, 429, 5xx, or ambiguous API error), we rethrow so Pub/Sub retries.
    // Permanent configuration errors (missing secret, invalid JSON) terminate cleanly without retry.
    let playResponse: PlaySubscriptionV2Response | null;
    try {
      playResponse = await deps.playApi.getSubscription(deps.packageName, purchaseToken);
    } catch (error) {
      if (isPermanentConfigError(error)) {
        deps.onIgnored?.("PERMANENT_CONFIG_ERROR");
        return { status: "IGNORED", reason: "PERMANENT_CONFIG_ERROR" };
      }
      throw error;
    }

    const productId = binding.productId || SUPPORTED_AI_PRODUCT_ID;
    const verification = translatePlaySubscription(playResponse, productId);

    let enabled = false;
    let expiresAtEpochMillis = 0;

    if (verification.status === "VERIFIED") {
      enabled = true;
      expiresAtEpochMillis = verification.expiresAtEpochMillis;
    } else if (verification.status === "INACTIVE") {
      enabled = false;
      expiresAtEpochMillis = verification.expiresAtEpochMillis ?? 0;
    } else {
      // INVALID (e.g. 404 or product mismatch on voided purchase)
      enabled = false;
      expiresAtEpochMillis = 0;
    }

    // `now` timestamps the observation of Play, not the start of a call of unbounded duration.
    // The entitlement write is ordered by this value, so taking it earlier would let a slow
    // notification outrank a verification that actually observed Play later.
    const observedAtEpochMillis = deps.now();

    const update = await deps.repository.updateEntitlement({
      uid: binding.uid,
      productId,
      purchaseToken,
      enabled,
      expiresAtEpochMillis,
      nowEpochMillis: observedAtEpochMillis,
    });

    // The stored entitlement is owned by a newer token or a newer observation: this notification
    // describes a subscription the user already replaced, and applying it would revoke a
    // legitimate one. Dropping it is the correct outcome, not a failure to retry.
    if (update === "IGNORED_SUPERSEDED") {
      deps.onIgnored?.("SUPERSEDED_BY_NEWER_STATE");
      return { status: "IGNORED", reason: "SUPERSEDED_BY_NEWER_STATE" };
    }

    // The entitlement predates source-token tracking, so this notification cannot prove it
    // describes the subscription currently granting access. Revoking on that basis could cut off
    // a paying user; the next verification adopts a source and restores normal handling.
    if (update === "IGNORED_LEGACY_SOURCE") {
      deps.onIgnored?.("LEGACY_SOURCE_UNRESOLVED");
      return { status: "IGNORED", reason: "LEGACY_SOURCE_UNRESOLVED" };
    }

    deps.onProcessed?.({
      uid: binding.uid,
      enabled,
      expiresAtEpochMillis,
    });

    return {
      status: "PROCESSED",
      uid: binding.uid,
      enabled,
      expiresAtEpochMillis,
    };
  };
}
