import { describe, expect, test } from "vitest";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";

const MINUTE = 60_000;
const NOW = 1_800_000_000;

const AUTHORIZED: AuthorizeAiResult = {
  decision: "AI_AUTHORIZED",
  provider: "GEMINI",
  fallbackUsed: false,
};

const DENIED: AuthorizeAiResult = {
  decision: "AI_RATE_LIMITED",
  provider: null,
  fallbackUsed: false,
};

describe("createAiEdgeRequestGuard", () => {
  test("first new request id allows", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 10 });
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("cap exhaustion returns RATE_LIMITED", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1, maxTrackedUids: 10 });
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });
    expect(guard.beforeRepository("u1", "r2", NOW)).toEqual({ kind: "RATE_LIMITED" });
    expect(guard.beforeRepository("u1", "r3", NOW)).toEqual({ kind: "RATE_LIMITED" });
  });

  test("remembered authorized result replays exactly", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 10 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.remember("u1", "r1", AUTHORIZED, NOW);

    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({
      kind: "REPLAY",
      result: AUTHORIZED,
    });
  });

  test("minute rollover permits a new request", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1, maxTrackedUids: 10 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.remember("u1", "r1", AUTHORIZED, NOW);
    expect(guard.beforeRepository("u1", "r2", NOW)).toEqual({ kind: "RATE_LIMITED" });

    // A new minute evicts the exhausted window for this uid.
    expect(guard.beforeRepository("u1", "r2", NOW + MINUTE)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("full uid map fails closed without growing memory", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 1 });
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });

    // Unknown uid with a full map: denied without insertion, so the first uid still works.
    expect(guard.beforeRepository("u2", "r1", NOW)).toEqual({ kind: "RATE_LIMITED" });
    expect(guard.beforeRepository("u2", "r1", NOW)).toEqual({ kind: "RATE_LIMITED" });
    expect(guard.beforeRepository("u1", "r2", NOW)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("repeat of a non-authorized request id does not consume a new slot", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 2, maxTrackedUids: 10 });
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });
    // r1 was not remembered (denied downstream); a retry reaches the durable ledger again
    // without consuming a second slot.
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });
    expect(guard.beforeRepository("u1", "r2", NOW)).toEqual({ kind: "ALLOW_NEW" });
    expect(guard.beforeRepository("u1", "r3", NOW)).toEqual({ kind: "RATE_LIMITED" });
  });

  test("remembering a denied result never creates a replay", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 10 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.remember("u1", "r1", DENIED, NOW);

    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("replay of an authorized result expires with the minute", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 10 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.remember("u1", "r1", AUTHORIZED, NOW);

    expect(guard.beforeRepository("u1", "r1", NOW + MINUTE)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("stale uid windows are evicted so new uids are not blocked", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 1 });
    expect(guard.beforeRepository("u1", "r1", NOW)).toEqual({ kind: "ALLOW_NEW" });

    // Next minute: u1's window is stale; the map must evict it and admit the new uid.
    expect(guard.beforeRepository("u2", "r1", NOW + MINUTE)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("eviction removes only stale windows and keeps current-minute uids", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 1 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.beforeRepository("u2", "r1", NOW + MINUTE);

    // u1 returns in the current minute: u2's window is current, so u1 fails closed.
    expect(guard.beforeRepository("u1", "r2", NOW + MINUTE)).toEqual({ kind: "RATE_LIMITED" });
    // u2 keeps working in its own window.
    expect(guard.beforeRepository("u2", "r2", NOW + MINUTE)).toEqual({ kind: "ALLOW_NEW" });
  });

  test("remember with a non-admitted request id never creates a replay", () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 5, maxTrackedUids: 10 });
    guard.beforeRepository("u1", "r1", NOW);
    guard.remember("u1", "never-admitted", AUTHORIZED, NOW);

    expect(guard.beforeRepository("u1", "never-admitted", NOW)).toEqual({ kind: "ALLOW_NEW" });
    expect(guard.beforeRepository("u1", "never-admitted", NOW)).toEqual({ kind: "ALLOW_NEW" });
  });
});
