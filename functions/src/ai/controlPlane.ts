export type AiProviderId = "GEMINI";

export type AiCapability = "TEXT";

export type AiAuthorizationDecision =
  | "AI_DISABLED"
  | "AI_AUTHENTICATION_REQUIRED"
  | "AI_ENTITLEMENT_REQUIRED"
  | "AI_RATE_LIMITED"
  | "AI_QUOTA_EXHAUSTED"
  | "AI_AUTHORIZED";

export type AiControlConfig = Readonly<{
  enabled: boolean;
  geminiEnabled: boolean;
  monthlyActionLimit: number;
  perMinuteLimit: number;
}>;

export type AiEntitlement = Readonly<{ enabled: boolean; expiresAtEpochMillis: number }>;

export type AiUsage = Readonly<{ usedActions: number }>;

export type AiRateWindow = Readonly<{ usedActions: number }>;

export type AuthorizeAiInput = Readonly<{
  uid: string;
  requestId: string;
  capability: AiCapability;
  nowEpochMillis: number;
  isAnonymous?: boolean;
}>;

export type AuthorizeAiResult = Readonly<{
  decision: AiAuthorizationDecision;
  provider: AiProviderId | null;
  fallbackUsed: false;
}>;

const DENIED = (
  decision: AiAuthorizationDecision,
): AuthorizeAiResult => ({ decision, provider: null, fallbackUsed: false });

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isValidConfig(config: AiControlConfig | null): config is AiControlConfig {
  return (
    config !== null &&
    typeof config === "object" &&
    config.enabled === true &&
    typeof config.geminiEnabled === "boolean" &&
    isPositiveSafeInteger(config.monthlyActionLimit) &&
    isPositiveSafeInteger(config.perMinuteLimit)
  );
}

function isValidEntitlement(entitlement: AiEntitlement | null): entitlement is AiEntitlement {
  return (
    entitlement !== null &&
    typeof entitlement === "object" &&
    entitlement.enabled === true &&
    isPositiveSafeInteger(entitlement.expiresAtEpochMillis)
  );
}

function usedActionsOf(doc: AiUsage | AiRateWindow | null): number | null {
  if (doc === null || typeof doc !== "object") {
    return null;
  }
  const used = (doc as { usedActions?: unknown }).usedActions;
  if (typeof used !== "number" || !Number.isSafeInteger(used) || used < 0) {
    return null;
  }
  return used;
}

function pickProvider(config: AiControlConfig): AiProviderId | null {
  if (config.geminiEnabled) {
    return "GEMINI";
  }
  return null;
}

/** True only when the control configuration is present, enabled, well-formed and has a provider. */
export function isControlAllowed(config: AiControlConfig | null): boolean {
  return isValidConfig(config) && pickProvider(config) !== null;
}

/** True only when the entitlement is enabled, well-formed and strictly unexpired at `nowEpochMillis`. */
export function isEntitlementValid(entitlement: AiEntitlement | null, nowEpochMillis: number): boolean {
  return isValidEntitlement(entitlement) && nowEpochMillis < entitlement.expiresAtEpochMillis;
}

/**
 * Pure provider-agnostic allow-list policy. Never throws; every denial returns no provider.
 * Configuration must be present, enabled, carry geminiEnabled and positive safe-integer limits.
 * The entitlement must be enabled and strictly unexpired (now < expiresAt). Quota and rate denials
 * happen at the exact limit. The only provider is GEMINI; fallback is never used.
 */
export function decideAuthorization(
  input: AuthorizeAiInput,
  config: AiControlConfig | null,
  entitlement: AiEntitlement | null,
  usage: AiUsage | null,
  rate: AiRateWindow | null,
): AuthorizeAiResult {
  if (input.isAnonymous === true) {
    return DENIED("AI_AUTHENTICATION_REQUIRED");
  }
  if (!isValidConfig(config)) {
    return DENIED("AI_DISABLED");
  }
  const provider = pickProvider(config);
  if (provider === null) {
    return DENIED("AI_DISABLED");
  }
  if (!isValidEntitlement(entitlement)) {
    return DENIED("AI_ENTITLEMENT_REQUIRED");
  }
  if (input.nowEpochMillis >= entitlement.expiresAtEpochMillis) {
    return DENIED("AI_ENTITLEMENT_REQUIRED");
  }
  const usedMonthly = usedActionsOf(usage);
  const usedPerMinute = usedActionsOf(rate);
  if (usedMonthly === null || usedPerMinute === null) {
    return DENIED("AI_DISABLED");
  }
  if (usedMonthly >= config.monthlyActionLimit) {
    return DENIED("AI_QUOTA_EXHAUSTED");
  }
  if (usedPerMinute >= config.perMinuteLimit) {
    return DENIED("AI_RATE_LIMITED");
  }
  return { decision: "AI_AUTHORIZED", provider, fallbackUsed: false };
}
