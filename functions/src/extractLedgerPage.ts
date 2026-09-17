import {
  validateExtractLedgerPageRequest,
  isAnonymousUser,
  type CallableAuth,
  type ExtractLedgerPageResponse,
  type InterpretAiStatus,
  type LedgerExtractionResult,
  type LedgerOperationV3,
  type LedgerOperationV4,
  type LedgerOperationV5,
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

/**
 * Builds the response in the version the caller asked for, and that is the whole point.
 *
 * The provider always produces the rich shape. Handing it back untouched to a v3 caller would ship
 * keys that client never heard of, and its parser compares the key set exactly: it would collapse
 * every page to "unavailable", silently, in production, on a build already in closed testing. So v3
 * is built field by field from an explicit allow-list rather than spread from the rich object —
 * spreading is what makes this class of bug invisible in review.
 *
 * Every branch goes through here, not only the successful one. A rate limit or an outage assembled
 * by hand somewhere else is the same leak with fewer eyes on it.
 */
function buildExtractLedgerResponse(
  contractVersion: 3 | 4 | 5,
  requestId: string,
  status: InterpretAiStatus,
  extraction: LedgerExtractionResult | null,
): ExtractLedgerPageResponse {
  if (contractVersion === 3) {
    const opsV3: LedgerOperationV3[] | null = extraction?.operations
      ? extraction.operations.map((op) => ({
          lineIndex: op.lineIndex,
          ...(op.proposedKind !== undefined ? { proposedKind: op.proposedKind } : {}),
          ...(op.customerName !== undefined ? { customerName: op.customerName } : {}),
          ...(op.amountCents !== undefined ? { amountCents: op.amountCents } : {}),
          ...(op.description !== undefined ? { description: op.description } : {}),
          ...(op.date !== undefined ? { date: op.date } : {}),
          uncertainFields: op.uncertainFields,
          unreadableFields: op.unreadableFields,
          ...(op.region !== undefined ? { region: op.region } : {}),
        }))
      : null;

    return {
      contractVersion: 3,
      requestId,
      status,
      operations: opsV3,
      discards: extraction ? extraction.discards : [],
      unreadTextRemaining: extraction ? extraction.unreadTextRemaining : false,
      pageIncomplete: extraction ? extraction.pageIncomplete : false,
    };
  } else if (contractVersion === 4) {
    // Se construye campo por campo, igual que v3, y NO se pasa `extraction.operations` en crudo.
    // El objeto rico lleva `marginNote` desde que existe v5, y un V5 ES asignable a un V4, así que
    // pasarlo en crudo compilaba y aun así filtraba una llave que el teléfono v37/v40 no conoce —y
    // su parser compara el juego de llaves exacto, así que habría rechazado la PÁGINA ENTERA—.
    // Es la misma trampa que el comentario de arriba describe: el spread es lo que vuelve invisible
    // este defecto en revisión.
    const opsV4: LedgerOperationV4[] | null = extraction?.operations
      ? extraction.operations.map((op) => ({
          lineIndex: op.lineIndex,
          ...(op.proposedKind !== undefined ? { proposedKind: op.proposedKind } : {}),
          ...(op.customerName !== undefined ? { customerName: op.customerName } : {}),
          ...(op.amountCents !== undefined ? { amountCents: op.amountCents } : {}),
          ...(op.description !== undefined ? { description: op.description } : {}),
          ...(op.date !== undefined ? { date: op.date } : {}),
          uncertainFields: op.uncertainFields,
          unreadableFields: op.unreadableFields,
          ...(op.region !== undefined ? { region: op.region } : {}),
          ...(op.confidence !== undefined ? { confidence: op.confidence } : {}),
          ...(op.sources !== undefined ? { sources: op.sources } : {}),
          ...(op.rawText !== undefined ? { rawText: op.rawText } : {}),
          ...(op.items !== undefined ? { items: op.items } : {}),
          ...(op.warnings !== undefined ? { warnings: op.warnings } : {}),
        }))
      : null;

    return {
      contractVersion: 4,
      requestId,
      status,
      operations: opsV4,
      discards: extraction ? extraction.discards : [],
      unreadTextRemaining: extraction ? extraction.unreadTextRemaining : false,
      pageIncomplete: extraction ? extraction.pageIncomplete : false,
      ...(extraction?.pageDate ? { pageDate: extraction.pageDate } : {}),
    };
  } else {
    const opsV5: LedgerOperationV5[] | null = extraction?.operations
      ? extraction.operations.map((op) => ({
          lineIndex: op.lineIndex,
          ...(op.proposedKind !== undefined ? { proposedKind: op.proposedKind } : {}),
          ...(op.customerName !== undefined ? { customerName: op.customerName } : {}),
          ...(op.amountCents !== undefined ? { amountCents: op.amountCents } : {}),
          ...(op.description !== undefined ? { description: op.description } : {}),
          ...(op.date !== undefined ? { date: op.date } : {}),
          uncertainFields: op.uncertainFields,
          unreadableFields: op.unreadableFields,
          ...(op.region !== undefined ? { region: op.region } : {}),
          ...(op.confidence !== undefined ? { confidence: op.confidence } : {}),
          ...(op.sources !== undefined ? { sources: op.sources } : {}),
          ...(op.rawText !== undefined ? { rawText: op.rawText } : {}),
          ...(op.items !== undefined ? { items: op.items } : {}),
          ...(op.warnings !== undefined ? { warnings: op.warnings } : {}),
          ...(op.marginNote !== undefined ? { marginNote: op.marginNote } : {}),
        }))
      : null;
    return {
      contractVersion: 5,
      requestId,
      status,
      operations: opsV5,
      discards: extraction ? extraction.discards : [],
      unreadTextRemaining: extraction ? extraction.unreadTextRemaining : false,
      pageIncomplete: extraction ? extraction.pageIncomplete : false,
      ...(extraction?.pageDate ? { pageDate: extraction.pageDate } : {}),
      ...(extraction?.pageType ? { pageType: extraction.pageType } : {}),
      ...(extraction?.pageTotals ? { pageTotals: extraction.pageTotals } : {}),
    };
  }
}

/**
 * Reading a photographed notebook page, with the same boundary the text path already uses:
 * authenticated uid -> strict v3/v4 contract -> edge guard -> control plane authorization ->
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
    const { contractVersion, requestId, imageBase64, imageMimeType } = validation.value;

    if (isAnonymousUser(request.auth as CallableAuth | undefined)) {
      deps.onRejected?.("ANONYMOUS_SESSION");
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }

    const nowEpochMillis = deps.now();
    const referenceDate = computeReferenceDate(nowEpochMillis);

    const guardDecision = deps.guard.beforeRepository(uid, requestId, nowEpochMillis);
    if (guardDecision.kind === "RATE_LIMITED") {
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_RATE_LIMITED", null);
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
        return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
      }
      if (authorization.decision !== "AI_AUTHORIZED") {
        if (
          authorization.decision === "AI_QUOTA_EXHAUSTED" ||
          authorization.decision === "AI_RATE_LIMITED" ||
          authorization.decision === "AI_DISABLED" ||
          authorization.decision === "AI_ENTITLEMENT_REQUIRED"
        ) {
          return buildExtractLedgerResponse(contractVersion, requestId, authorization.decision, null);
        }
        return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
      }
      deps.guard.remember(uid, requestId, authorization, nowEpochMillis);
    }

    if (deps.provider === null) {
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }

    let claim: "CLAIMED" | "ALREADY_CLAIMED";
    try {
      claim = await deps.claimProviderInvocation({ uid, requestId, nowEpochMillis });
    } catch {
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }
    if (claim !== "CLAIMED") {
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }

    deps.onProviderInvocation?.();
    const providerStartTime = deps.now();
    try {
      const extraction = await deps.provider.extract({
        imageBase64,
        imageMimeType,
        referenceDate,
        contractVersion,
      });
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      if (extraction === null) {
        return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
      }
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_PROPOSAL", extraction);
    } catch {
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }
  };
}
