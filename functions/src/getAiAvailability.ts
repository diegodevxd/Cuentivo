import { isAnonymousUser, type CallableAuth } from "./contract.js";

export type GetAiAvailabilityResponse = Readonly<{
  available: boolean;
}>;

export function createGetAiAvailabilityHandler(options: {
  getAvailability: () => Promise<{ available: boolean }>;
  onRejected?: (reason: string) => void;
}) {
  return async (request: {
    auth?: CallableAuth;
    data?: unknown;
  }): Promise<GetAiAvailabilityResponse | { code: "unauthenticated" }> => {
    if (!request.auth || isAnonymousUser(request.auth)) {
      options.onRejected?.("UNAUTHENTICATED");
      return { code: "unauthenticated" };
    }
    return await options.getAvailability();
  };
}
