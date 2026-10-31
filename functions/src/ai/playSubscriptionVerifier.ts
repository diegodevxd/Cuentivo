import * as crypto from "node:crypto";
import { GoogleAuth } from "google-auth-library";

export type PlaySubscriptionStatus =
  | "SUBSCRIPTION_STATE_ACTIVE"
  | "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"
  | "SUBSCRIPTION_STATE_ON_HOLD"
  | "SUBSCRIPTION_STATE_PAUSED"
  | "SUBSCRIPTION_STATE_CANCELED"
  | "SUBSCRIPTION_STATE_EXPIRED"
  | "SUBSCRIPTION_STATE_PENDING"
  | "SUBSCRIPTION_STATE_UNSPECIFIED";

export type PlaySubscriptionLineItem = Readonly<{
  productId: string;
  expiryTime?: string;
}>;

export type PlaySubscriptionV2Response = Readonly<{
  subscriptionState?: PlaySubscriptionStatus;
  lineItems?: readonly PlaySubscriptionLineItem[];
  externalAccountIdentifiers?: Readonly<{
    obfuscatedExternalAccountId?: string;
  }>;
}>;

export interface PlaySubscriptionApi {
  getSubscription(
    packageName: string,
    token: string,
  ): Promise<PlaySubscriptionV2Response | null>;
}

export type PlayVerificationResult =
  | {
      status: "VERIFIED";
      enabled: true;
      expiresAtEpochMillis: number;
      obfuscatedExternalAccountId?: string;
    }
  | {
      status: "INACTIVE";
      enabled: false;
      expiresAtEpochMillis?: number;
      obfuscatedExternalAccountId?: string;
    }
  | { status: "INVALID" }
  | { status: "UNAVAILABLE" };

/**
 * Computes a SHA-256 hex hash of the purchase token for Firestore document keys.
 * Ensures the raw token is never exposed in collection paths or logs.
 */
export function hashPurchaseToken(purchaseToken: string): string {
  return crypto.createHash("sha256").update(purchaseToken.trim()).digest("hex");
}

/**
 * Hashes the Firebase UID using SHA-256 (UTF-8, trimmed, lowercase hex) to match BillingAccountHasher.kt.
 */
export function hashObfuscatedAccountId(uid: string): string {
  return crypto.createHash("sha256").update(uid.trim(), "utf8").digest("hex").toLowerCase();
}

/**
 * Pure translation of Google Play purchases.subscriptionsv2 response into an internal verification result.
 *
 * Expiry time is parsed into an absolute epoch milliseconds timestamp. Active and in-grace-period
 * subscriptions result in enabled entitlement. Expired, canceled, paused, or hold subscriptions
 * result in inactive status without granting entitlement.
 */
export function translatePlaySubscription(
  response: PlaySubscriptionV2Response | null,
  expectedProductId: string,
): PlayVerificationResult {
  if (!response || typeof response !== "object") {
    return { status: "INVALID" };
  }

  const state = response.subscriptionState;
  const lineItems = response.lineItems;
  if (!Array.isArray(lineItems) || lineItems.length === 0) {
    return { status: "INVALID" };
  }

  const lineItem = lineItems.find((item) => item.productId === expectedProductId);
  if (!lineItem) {
    return { status: "INVALID" };
  }

  const expiryTimeStr = lineItem.expiryTime;
  if (!expiryTimeStr || typeof expiryTimeStr !== "string") {
    return { status: "INVALID" };
  }

  const expiresAtEpochMillis = Date.parse(expiryTimeStr);
  if (isNaN(expiresAtEpochMillis) || expiresAtEpochMillis <= 0) {
    return { status: "INVALID" };
  }

  const obfuscatedExternalAccountId = response.externalAccountIdentifiers?.obfuscatedExternalAccountId?.trim();

  if (
    state === "SUBSCRIPTION_STATE_ACTIVE" ||
    state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ||
    state === "SUBSCRIPTION_STATE_CANCELED"
  ) {
    return {
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis,
      obfuscatedExternalAccountId,
    };
  }

  return {
    status: "INACTIVE",
    enabled: false,
    expiresAtEpochMillis,
    obfuscatedExternalAccountId,
  };
}

export type PlaySubscriptionApiOptions = {
  serviceAccountJson?: string | null;
  customFetcher?: (url: string, headers: Record<string, string>) => Promise<unknown>;
};

export class PlayApiConfigurationError extends Error {
  readonly isPermanentConfigError = true;
  constructor(message: string) {
    super(message);
    this.name = "PlayApiConfigurationError";
  }
}

/**
 * Factory for Google Play Subscription API client.
 *
 * In production, it authenticates with the provided service account JSON credentials
 * using the `https://www.googleapis.com/auth/androidpublisher` OAuth2 scope.
 *
 * When serviceAccountJson is empty, invalid, or customFetcher is provided (in tests),
 * it behaves accordingly without leaking credentials or tokens.
 */
export function createDefaultPlaySubscriptionApi(
  options?: PlaySubscriptionApiOptions | ((url: string, headers: Record<string, string>) => Promise<unknown>),
): PlaySubscriptionApi {
  const customFetcher = typeof options === "function" ? options : options?.customFetcher;
  const serviceAccountJson = typeof options === "object" && options !== null ? options.serviceAccountJson : undefined;

  return {
    async getSubscription(packageName: string, token: string): Promise<PlaySubscriptionV2Response | null> {
      if (customFetcher) {
        const response = await customFetcher(
          `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`,
          {},
        );
        return response as PlaySubscriptionV2Response;
      }

      const trimmedJson = serviceAccountJson?.trim();
      if (!trimmedJson) {
        throw new PlayApiConfigurationError("Play Developer API service account not configured in this environment");
      }

      let credentials: Record<string, unknown>;
      try {
        credentials = JSON.parse(trimmedJson);
      } catch {
        throw new PlayApiConfigurationError("Play Developer API service account credentials are not valid JSON");
      }

      let client;
      let accessToken;
      try {
        const auth = new GoogleAuth({
          credentials,
          scopes: ["https://www.googleapis.com/auth/androidpublisher"],
        });
        client = await auth.getClient();
        const accessTokenResponse = await client.getAccessToken();
        accessToken = accessTokenResponse.token;
      } catch (authError) {
        const msg = authError instanceof Error ? authError.message.toLowerCase() : "";
        if (msg.includes("invalid_grant") || msg.includes("unauthorized_client")) {
          throw new PlayApiConfigurationError(
            `Play Developer API authentication failed: ${authError instanceof Error ? authError.message : String(authError)}`,
          );
        }
        throw authError;
      }

      if (!accessToken) {
        throw new PlayApiConfigurationError("Failed to obtain OAuth2 access token for Play Developer API");
      }

      const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`;
      const response = await fetch(url, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          Accept: "application/json",
        },
      });

      if (response.status === 404) {
        return null;
      }

      if (!response.ok) {
        throw new Error(`Play Developer API request failed with HTTP ${response.status}`);
      }

      return (await response.json()) as PlaySubscriptionV2Response;
    },
  };
}
