import type { AuthorizeAiResult } from "./controlPlane";

export type EdgeGuardDecision =
  | Readonly<{ kind: "ALLOW_NEW" }>
  | Readonly<{ kind: "REPLAY"; result: AuthorizeAiResult }>
  | Readonly<{ kind: "RATE_LIMITED" }>;

export interface AiEdgeRequestGuard {
  beforeRepository(uid: string, requestId: string, nowEpochMillis: number): EdgeGuardDecision;
  remember(uid: string, requestId: string, result: AuthorizeAiResult, nowEpochMillis: number): void;
}

interface UidWindow {
  minute: number;
  newRequestIds: Set<string>;
  replayed: Map<string, AuthorizeAiResult>;
}

/**
 * Bounded warm-instance edge guard. It is a cost mitigation, NOT a durable security boundary:
 * state lives in the process memory of one warm callable instance, so cold starts reset it.
 *
 * Memory is strictly bounded: at most `maxTrackedUids` windows, each tracking at most
 * `maxNewRequestsPerMinute` new request ids and only authorized replays for ids already tracked.
 * Everything is synchronous, so it is safe on a single-threaded event loop with concurrency 1.
 *
 * Semantics:
 * - A new request id within the current minute admits (ALLOW_NEW) until the cap; beyond the cap
 *   it returns RATE_LIMITED without any repository access.
 * - A repeat of an id that was already authorized in the current minute replays the stored result.
 * - A repeat of an id that was admitted but not authorized is admitted again without consuming a
 *   new slot, so the durable ledger keeps idempotency authority.
 * - A full UID map denies unknown UIDs without inserting anything (fails closed for AI).
 * - A minute rollover evicts the uid's window and admits again.
 */
export function createAiEdgeRequestGuard(options: Readonly<{
  maxNewRequestsPerMinute: number;
  maxTrackedUids: number;
}>): AiEdgeRequestGuard {
  const { maxNewRequestsPerMinute, maxTrackedUids } = options;
  const windows = new Map<string, UidWindow>();

  function minuteOf(nowEpochMillis: number): number {
    return Math.floor(nowEpochMillis / 60_000);
  }

  function windowFor(uid: string, minute: number): UidWindow | undefined {
    const entry = windows.get(uid);
    if (entry === undefined || entry.minute !== minute) {
      return undefined;
    }
    return entry;
  }

  return {
    beforeRepository(uid, requestId, nowEpochMillis) {
      const minute = minuteOf(nowEpochMillis);
      const entry = windowFor(uid, minute);
      if (entry !== undefined) {
        const replayed = entry.replayed.get(requestId);
        if (replayed !== undefined) {
          return { kind: "REPLAY", result: replayed };
        }
        if (entry.newRequestIds.has(requestId)) {
          // Admitted but not authorized: retry reaches the durable ledger without a new slot.
          return { kind: "ALLOW_NEW" };
        }
        if (entry.newRequestIds.size >= maxNewRequestsPerMinute) {
          return { kind: "RATE_LIMITED" };
        }
        entry.newRequestIds.add(requestId);
        return { kind: "ALLOW_NEW" };
      }

      // New or expired window. First evict every stale window (any uid whose tracked minute is
      // not the current one) so expired entries cannot let sybil UIDs pin the map until cold
      // start. Bounded sweep: O(maxTrackedUids), no Firestore access.
      for (const [trackedUid, tracked] of windows) {
        if (tracked.minute !== minute) {
          windows.delete(trackedUid);
        }
      }
      if (windows.size >= maxTrackedUids) {
        // Memory bound reached with only current-minute windows: fail closed without insertion.
        return { kind: "RATE_LIMITED" };
      }
      windows.set(uid, { minute, newRequestIds: new Set([requestId]), replayed: new Map() });
      return { kind: "ALLOW_NEW" };
    },

    remember(uid, requestId, result, nowEpochMillis) {
      if (result.decision !== "AI_AUTHORIZED") {
        return;
      }
      const entry = windowFor(uid, minuteOf(nowEpochMillis));
      if (entry === undefined || !entry.newRequestIds.has(requestId)) {
        // Only ids admitted by this guard in the current window may be replayed; anything else
        // would grow the map outside the admitted set.
        return;
      }
      entry.replayed.set(requestId, result);
    },
  };
}
