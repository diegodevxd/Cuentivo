import {
  validateAuthorizeAiActionRequest,
  type AuthorizeAiActionResponse,
} from "./contract";
import type { AiControlPlaneRepository } from "./ai/firestoreControlPlane";
import type { AuthorizeAiResult } from "./ai/controlPlane";

export type AuthorizeCallableRequest = Readonly<{
  auth?: Readonly<{ uid: string }>;
  data: unknown;
}>;

export type AuthorizeAiFailure = Readonly<{ code: "unauthenticated" | "invalid-argument" }>;

/**
 * Pure callable boundary for authorizeAiAction: requires a Firebase identity, validates the v1
 * allow-list, delegates to the repository with the authenticated uid and the injected server
 * clock, and mirrors the decision exactly. A repository failure collapses to AI_DISABLED.
 * No logging, no provider knowledge, no text or business data.
 */
export function createAuthorizeAiHandler(
  repository: AiControlPlaneRepository,
  now: () => number,
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

    let result: AuthorizeAiResult;
    try {
      result = await repository.authorize({
        uid,
        requestId,
        capability: "TEXT",
        nowEpochMillis: now(),
      });
    } catch {
      result = { decision: "AI_DISABLED", provider: null, fallbackUsed: false };
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
