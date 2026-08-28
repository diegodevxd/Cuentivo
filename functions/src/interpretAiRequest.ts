import {
  validateInterpretAiRequest,
  isAnonymousUser,
  type CallableAuth,
  type InterpretAiResponse,
  type SafeAiProposal,
} from "./contract";
import { computeReferenceDate, type TextInterpretationProvider } from "./ai/geminiTextProvider.js";
import type { AiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import type { AuthorizeAiInput, AuthorizeAiResult } from "./ai/controlPlane";

export type CallableLikeRequest = Readonly<{ auth?: CallableAuth; data: unknown }>;

export type CallableFailure = Readonly<{ code: "unauthenticated" | "invalid-argument" }>;

export type InterpretAiCallableDeps = Readonly<{
  authorize: (input: AuthorizeAiInput) => Promise<AuthorizeAiResult>;
  claimProviderInvocation: (input: Readonly<{
    uid: string;
    requestId: string;
    nowEpochMillis: number;
  }>) => Promise<"CLAIMED" | "ALREADY_CLAIMED">;
  provider: TextInterpretationProvider | null;
  guard: AiEdgeRequestGuard;
  now: () => number;
  onRejected?: (reason: "ANONYMOUS_SESSION") => void;
  onProviderInvocation?: () => void;
  onProviderLatency?: (durationMillis: number) => void;
}>;

const UNAVAILABLE = (requestId: string): InterpretAiResponse => ({
  contractVersion: 2,
  requestId,
  status: "AI_UNAVAILABLE",
  proposal: null,
});

/**
 * Interpretation orchestration boundary. Sequence is fixed:
 * authenticated uid -> strict v2 proposal contract (bounded text) -> warm-instance edge guard (single
 * now()) -> control plane authorization (capability TEXT) -> durable at-most-once provider claim
 * -> provider invocation exactly once.
 *
 * The edge guard is a cost mitigation shared with the authorization callable: a rate denial
 * returns AI_UNAVAILABLE without any authorization, claim or provider access; an authorized
 * replay is reused for the authorization step but STILL passes through the durable claim, which
 * remains the sole authority preventing a second Gemini call. Successful authorization is
 * remembered only for an in-memory hot replay.
 *
 * Every denial, missing server configuration, storage failure, already-claimed request id or
 * provider failure collapses to the fixed AI_UNAVAILABLE response with a null proposal and no
 * financial mutation. Authorization internals, decisions or costs are never exposed to the client.
 */
export function createInterpretAiHandler(
  deps: InterpretAiCallableDeps,
): (request: CallableLikeRequest) => Promise<InterpretAiResponse | CallableFailure> {
  return async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      return { code: "unauthenticated" };
    }
    const validation = validateInterpretAiRequest(request.data);
    if (!validation.ok) {
      return { code: "invalid-argument" };
    }
    const { requestId, text } = validation.value;

    // Anonymous sessions are rejected before any edge guard, control plane, quota or Gemini invocation.
    if (isAnonymousUser(request.auth)) {
      deps.onRejected?.("ANONYMOUS_SESSION");
      return UNAVAILABLE(requestId);
    }

    const nowEpochMillis = deps.now();
    const referenceDate = computeReferenceDate(nowEpochMillis);

    const guardDecision = deps.guard.beforeRepository(uid, requestId, nowEpochMillis);
    if (guardDecision.kind === "RATE_LIMITED") {
      return {
        contractVersion: 2,
        requestId,
        status: "AI_RATE_LIMITED",
        proposal: null,
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
            contractVersion: 2,
            requestId,
            status: authorization.decision,
            proposal: null,
          };
        }
        return UNAVAILABLE(requestId);
      }
      // Remember only after a successful authorization, before the null-provider/claim path.
      deps.guard.remember(uid, requestId, authorization, nowEpochMillis);
    }

    // A missing server-side configuration recovers without consuming the durable claim.
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
      // Already claimed (or fail-closed): the provider must never be invoked again.
      return UNAVAILABLE(requestId);
    }

    deps.onProviderInvocation?.();
    const providerStartTime = deps.now();
    try {
      const proposal = await deps.provider.interpret({ text, referenceDate });
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      if (proposal === null) {
        return UNAVAILABLE(requestId);
      }
      return {
        contractVersion: 2,
        requestId,
        status: "AI_PROPOSAL",
        proposal,
      };
    } catch {
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      return UNAVAILABLE(requestId);
    }
  };
}
