import { describe, expect, test, vi } from "vitest";
import { createGetAiAvailabilityHandler } from "../src/getAiAvailability.js";
import type { CallableAuth } from "../src/contract.js";

const AUTH_USER: CallableAuth = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
};

const ANONYMOUS_USER: CallableAuth = {
  uid: "anon-user",
  token: { firebase: { sign_in_provider: "anonymous" } },
};

describe("createGetAiAvailabilityHandler", () => {
  test("returns available true when control plane allows it", async () => {
    const getAvailability = vi.fn().mockResolvedValue({ available: true });
    const handler = createGetAiAvailabilityHandler({ getAvailability });

    const result = await handler({ auth: AUTH_USER });

    expect(result).toEqual({ available: true });
    expect(getAvailability).toHaveBeenCalledTimes(1);
  });

  test("returns available false when control plane is disabled", async () => {
    const getAvailability = vi.fn().mockResolvedValue({ available: false });
    const handler = createGetAiAvailabilityHandler({ getAvailability });

    const result = await handler({ auth: AUTH_USER });

    expect(result).toEqual({ available: false });
    expect(getAvailability).toHaveBeenCalledTimes(1);
  });

  test("rejects unauthenticated caller without invoking control plane", async () => {
    const getAvailability = vi.fn();
    const onRejected = vi.fn();
    const handler = createGetAiAvailabilityHandler({ getAvailability, onRejected });

    const result = await handler({ auth: undefined });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(getAvailability).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("UNAUTHENTICATED");
  });

  test("rejects anonymous caller without invoking control plane", async () => {
    const getAvailability = vi.fn();
    const onRejected = vi.fn();
    const handler = createGetAiAvailabilityHandler({ getAvailability, onRejected });

    const result = await handler({ auth: ANONYMOUS_USER });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(getAvailability).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("UNAUTHENTICATED");
  });
});
