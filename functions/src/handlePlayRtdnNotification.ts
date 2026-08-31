import type { PlaySubscriptionApi, PlaySubscriptionV2Response } from "./ai/playSubscriptionVerifier";
import { translatePlaySubscription } from "./ai/playSubscriptionVerifier";
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

    // Fail-closed towards NOT TOUCHING: if Play Developer API throws (timeout, network, 5xx),
    // we rethrow so Pub/Sub retries without modifying the user's entitlement document.
    let playResponse: PlaySubscriptionV2Response | null;
    try {
      playResponse = await deps.playApi.getSubscription(deps.packageName, purchaseToken);
    } catch (error) {
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

    await deps.repository.updateEntitlement({
      uid: binding.uid,
      productId,
      enabled,
      expiresAtEpochMillis,
      nowEpochMillis: deps.now(),
    });

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
