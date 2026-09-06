import {
  validateExtractLedgerPageRequest,
  isAnonymousUser,
  type CallableAuth,
  type ExtractLedgerPageResponse,
} from "./contract";
import { computeReferenceDate } from "./ai/geminiTextProvider.js";
import type { LedgerExtractionProvider } from "./ai/geminiLedgerProvider.js";
import type { AiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import type { AuthorizeAiInput, AuthorizeAiResult } from "./ai/controlPlane";
import type { CallableFailure, CallableLikeRequest } from "./interpretAiRequest";

export type ExtractLedgerPageCallableDeps = Readonly<{
  authorize: (input: AuthorizeAiInput) => Promise<AuthorizeAiResult>;
  claimProviderInvocation: (input: Readonly<{
    uid: string;
    requestId: string;
    nowEpochMillis: number;
  }>) => Promise<"CLAIMED" | "ALREADY_CLAIMED">;
  provider: LedgerExtractionProvider | null;
  guard: AiEdgeRequestGuard;
  now: () => number;
  onRejected?: (reason: "ANONYMOUS_SESSION") => void;
  onProviderInvocation?: () => void;
  onProviderLatency?: (durationMillis: number) => void;
}>;

const UNAVAILABLE = (requestId: string): ExtractLedgerPageResponse => ({
  contractVersion: 3,
  requestId,
  status: "AI_UNAVAILABLE",
  operations: null,
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
});

/**
 * Reading a photographed notebook page, with the same boundary the text path already uses:
 * authenticated uid -> strict v3 contract -> edge guard -> control plane authorization ->
 * durable at-most-once claim -> provider invoked exactly once.
 *
 * This path costs more than the text one — an image is orders of magnitude more input than a
 * sentence — which is exactly why it reuses the same claim and quota rather than opening a second
 * door into the provider.
 *
 * Every denial, missing configuration, storage failure, replayed request id or provider failure
 * collapses to a fixed AI_UNAVAILABLE with no operations. The image is never logged, stored or
 * echoed back.
 */
export function createExtractLedgerPageHandler(
  deps: ExtractLedgerPageCallableDeps,
): (request: CallableLikeRequest) => Promise<ExtractLedgerPageResponse | CallableFailure> {
  return async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      return { code: "unauthenticated" };
    }
    const validation = validateExtractLedgerPageRequest(request.data);
    if (!validation.ok) {
      return { code: "invalid-argument" };
    }
    const { requestId, imageBase64, imageMimeType } = validation.value;

    if (isAnonymousUser(request.auth as CallableAuth | undefined)) {
      deps.onRejected?.("ANONYMOUS_SESSION");
      return UNAVAILABLE(requestId);
    }

    const nowEpochMillis = deps.now();
    const referenceDate = computeReferenceDate(nowEpochMillis);

    const guardDecision = deps.guard.beforeRepository(uid, requestId, nowEpochMillis);
    if (guardDecision.kind === "RATE_LIMITED") {
      return {
        contractVersion: 3,
        requestId,
        status: "AI_RATE_LIMITED",
        operations: null,
        discards: [],
        unreadTextRemaining: false,
        pageIncomplete: false,
      };
    }

    let authorization: AuthorizeAiResult;
    if (guardDecision.kind === "REPLAY") {
      authorization = guardDecision.result;
    } else {
      try {
        authorization = await deps.authorize({
          uid,
          requestId,
          capability: "TEXT",
          nowEpochMillis,
        });
      } catch {
        return UNAVAILABLE(requestId);
      }
      if (authorization.decision !== "AI_AUTHORIZED") {
        if (
          authorization.decision === "AI_QUOTA_EXHAUSTED" ||
          authorization.decision === "AI_RATE_LIMITED" ||
          authorization.decision === "AI_DISABLED" ||
          authorization.decision === "AI_ENTITLEMENT_REQUIRED"
        ) {
          return {
            contractVersion: 3,
            requestId,
            status: authorization.decision,
            operations: null,
            discards: [],
            unreadTextRemaining: false,
            pageIncomplete: false,
          };
        }
        return UNAVAILABLE(requestId);
      }
      deps.guard.remember(uid, requestId, authorization, nowEpochMillis);
    }

    if (deps.provider === null) {
      return UNAVAILABLE(requestId);
    }

    let claim: "CLAIMED" | "ALREADY_CLAIMED";
    try {
      claim = await deps.claimProviderInvocation({ uid, requestId, nowEpochMillis });
    } catch {
      return UNAVAILABLE(requestId);
    }
    if (claim !== "CLAIMED") {
      return UNAVAILABLE(requestId);
    }

    deps.onProviderInvocation?.();
    const providerStartTime = deps.now();
    try {
      const extraction = await deps.provider.extract({
        imageBase64,
        imageMimeType,
        referenceDate,
      });
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      if (extraction === null) {
        return UNAVAILABLE(requestId);
      }
      return {
        contractVersion: 3,
        requestId,
        status: "AI_PROPOSAL",
        operations: extraction.operations,
        discards: extraction.discards,
        unreadTextRemaining: extraction.unreadTextRemaining,
        pageIncomplete: extraction.pageIncomplete,
      };
    } catch {
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      return UNAVAILABLE(requestId);
    }
  };
}
