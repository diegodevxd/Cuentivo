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
  type MexalmCrosscheckBlock,
} from "./contract";
import { computeReferenceDate } from "./ai/geminiTextProvider.js";
import type { LedgerExtractionProvider } from "./ai/geminiLedgerProvider.js";
import type { MexalmLedgerCrosschecker } from "./ai/mexalmCrosscheck.js";
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
  /**
   * Verificación cruzada contra el Ledger de MexaLM. `null` (o ausente) significa APAGADA: no se
   * llama al Ledger y la respuesta no lleva el bloque, o sea que es byte a byte la de hoy.
   *
   * La bandera no se lee aquí: esto es el seam, la decisión de encenderla es del composition root
   * (`src/index.ts`), que es donde viven los parámetros de Functions.
   */
  crosscheck?: MexalmLedgerCrosschecker | null;
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
 *
 * `crosscheck` is the ONE thing here that is optional by design: it is added at the END and only
 * when it exists, so with the feature flag off this function produces exactly the bytes it produced
 * before. It never rewrites an operation: it is a comment next to the page, not an edit of it.
 */
function buildExtractLedgerResponse(
  contractVersion: 3 | 4 | 5,
  requestId: string,
  status: InterpretAiStatus,
  extraction: LedgerExtractionResult | null,
  crosscheck: MexalmCrosscheckBlock | null = null,
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
      ...(crosscheck ? { mexalmCrosscheck: crosscheck } : {}),
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
      ...(crosscheck ? { mexalmCrosscheck: crosscheck } : {}),
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
      ...(crosscheck ? { mexalmCrosscheck: crosscheck } : {}),
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
    /**
     * La capacidad de leer el bloque, tal como la declaró ESTA petición.
     *
     * `=== true` y no truthiness: lo que llegó aquí ya pasó el validador, pero la comparación
     * estricta deja dicho en el código que sólo un `true` booleano enciende nada. Ausente es
     * `false`, que es el caso de toda app publicada antes de este cambio.
     */
    const acceptsMexalmCrosscheck = validation.value.acceptsMexalmCrosscheck === true;

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
      const crosscheck = acceptsMexalmCrosscheck
        ? await runCrosscheck(deps, extraction)
        : null;
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_PROPOSAL", extraction, crosscheck);
    } catch {
      const durationMillis = Math.max(0, deps.now() - providerStartTime);
      deps.onProviderLatency?.(durationMillis);
      return buildExtractLedgerResponse(contractVersion, requestId, "AI_UNAVAILABLE", null);
    }
  };
}

/**
 * Corre la verificación cruzada, si está encendida.
 *
 * **Ojo: la bandera sola no basta.** Quien llama aquí sólo lo hace cuando la petición declaró
 * `acceptsMexalmCrosscheck === true`. Son dos condiciones y las dos importan, pero por razones
 * distintas: la bandera dice si el servidor verifica, y la capacidad dice si ESTA app sabe leer el
 * bloque. Una app publicada antes de este cambio compara el juego de llaves de la raíz y rechaza la
 * página entera ante una llave que no conoce, así que mandarle el bloque no es mandarle un adorno:
 * es dejarle el escaneo mudo. Encender la bandera sin esta segunda condición rompería a todos los
 * clientes viejos a la vez.
 *
 * Devuelve `null` cuando no hubo veredicto: la bandera apagada, la página sin texto que mandar,
 * o el Ledger que no pudo contestar. En esos casos la respuesta sale EXACTAMENTE como sale hoy,
 * sin una llave de más: la app no tiene por qué enterarse de que un servicio de terceros se cayó.
 * La causa queda registrada en el log del servidor, con una categoría y sin el texto de la página.
 *
 * Un error del verificador NUNCA se propaga: una excepción aquí dejaría sin página una lectura que
 * ya se pagó.
 */
async function runCrosscheck(
  deps: ExtractLedgerPageCallableDeps,
  extraction: LedgerExtractionResult,
): Promise<MexalmCrosscheckBlock | null> {
  const crosschecker = deps.crosscheck;
  if (crosschecker === null || crosschecker === undefined) {
    return null;
  }
  try {
    // `knownCustomers` va en null a propósito: el contrato de `extract_ledger_page` no lleva la
    // lista de clientes de la tienda —son cinco llaves exactas y el cliente Android no manda más—,
    // así que hoy no hay lista que mandar. El hueco queda en el seam: cuando el flujo la traiga,
    // se pasa aquí y no se toca nada más.
    return await crosschecker.crosscheck({
      operations: extraction.operations,
      knownCustomers: null,
    });
  } catch {
    return null;
  }
}
