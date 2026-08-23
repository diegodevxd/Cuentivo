import {
  validateInterpretAiRequest,
  type InterpretAiResponse,
  type SafeInterpretation,
} from "./contract";
import type { TextInterpretationProvider } from "./ai/geminiTextProvider.js";
import type { AiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import type { AuthorizeAiInput, AuthorizeAiResult } from "./ai/controlPlane";

export type CallableLikeRequest = Readonly<{ auth?: Readonly<{ uid: string }>; data: unknown }>;

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
}>;

const UNAVAILABLE = (requestId: string): InterpretAiResponse => ({
  contractVersion: 1,
  requestId,
  status: "AI_UNAVAILABLE",
  interpretation: null,
});

/**
 * Interpretation orchestration boundary. Sequence is fixed:
 * authenticated uid -> strict v1 contract (bounded text) -> warm-instance edge guard (single
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
 * provider failure collapses to the fixed AI_UNAVAILABLE response with no interpretation and no
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
    const nowEpochMillis = deps.now();

    const guardDecision = deps.guard.beforeRepository(uid, requestId, nowEpochMillis);
    if (guardDecision.kind === "RATE_LIMITED") {
      return UNAVAILABLE(requestId);
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

    try {
      const interpretation = await deps.provider.interpret({ text });
      if (interpretation === null) {
        return UNAVAILABLE(requestId);
      }
      return {
        contractVersion: 1,
        requestId,
        status: "AI_INTERPRETED",
        interpretation,
      };
    } catch {
      return UNAVAILABLE(requestId);
    }
  };
}
