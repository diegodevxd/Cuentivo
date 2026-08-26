import {
  validateAuthorizeAiActionRequest,
  isAnonymousUser,
  type CallableAuth,
  type AuthorizeAiActionResponse,
} from "./contract";
import type { AiControlPlaneRepository } from "./ai/firestoreControlPlane";
import type { AiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import type { AuthorizeAiResult } from "./ai/controlPlane";

export type AuthorizeCallableRequest = Readonly<{
  auth?: CallableAuth;
  data: unknown;
}>;

export type AuthorizeAiFailure = Readonly<{ code: "unauthenticated" | "invalid-argument" }>;

/**
 * Pure callable boundary for authorizeAiAction. Sequence: authenticated uid -> strict v1
 * allow-list validation -> warm-instance edge guard -> durable repository only for ALLOW_NEW ->
 * remember only after AI_AUTHORIZED. REPLAY returns the stored durable result; RATE_LIMITED
 * returns the fixed denial response without repository access. A repository failure collapses to
 * AI_DISABLED. No logging, no provider knowledge, no text or business data.
 */
export function createAuthorizeAiHandler(
  repository: AiControlPlaneRepository,
  now: () => number,
  guard: AiEdgeRequestGuard,
  onRejected?: (reason: "ANONYMOUS_SESSION") => void,
): (request: AuthorizeCallableRequest) => Promise<AuthorizeAiActionResponse | AuthorizeAiFailure> {
  return async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      return { code: "unauthenticated" };
    }
    const validation = validateAuthorizeAiActionRequest(request.data);
    if (!validation.ok) {
      return { code: "invalid-argument" };
    }
    const { requestId } = validation.value;

    // Anonymous sessions are rejected before any edge guard, repository read, or quota/ledger access.
    if (isAnonymousUser(request.auth)) {
      onRejected?.("ANONYMOUS_SESSION");
      return {
        contractVersion: 1,
        requestId,
        decision: "AI_AUTHENTICATION_REQUIRED",
        provider: null,
        fallbackUsed: false,
      };
    }

    const nowEpochMillis = now();

    const guardDecision = guard.beforeRepository(uid, requestId, nowEpochMillis);
    if (guardDecision.kind === "RATE_LIMITED") {
      return {
        contractVersion: 1,
        requestId,
        decision: "AI_RATE_LIMITED",
        provider: null,
        fallbackUsed: false,
      };
    }
    if (guardDecision.kind === "REPLAY") {
      const replayed = guardDecision.result;
      return {
        contractVersion: 1,
        requestId,
        decision: replayed.decision,
        provider: replayed.provider,
        fallbackUsed: false,
      };
    }

    let result: AuthorizeAiResult;
    try {
      result = await repository.authorize({
        uid,
        requestId,
        capability: "TEXT",
        nowEpochMillis,
      });
    } catch {
      result = { decision: "AI_DISABLED", provider: null, fallbackUsed: false };
    }

    if (result.decision === "AI_AUTHORIZED") {
      guard.remember(uid, requestId, result, nowEpochMillis);
    }

    return {
      contractVersion: 1,
      requestId,
      decision: result.decision,
      provider: result.provider,
      fallbackUsed: result.fallbackUsed,
    };
  };
}
