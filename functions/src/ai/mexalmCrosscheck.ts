/**
 * Verificación cruzada de la página: Gemini (foto) contra el Ledger de MexaLM (texto).
 *
 * Qué se compara y qué NO:
 *
 * - **Se compara**: tipo de movimiento, cliente (normalizado) y monto en centavos, renglón por
 *   renglón. Si los dos lados dicen lo mismo, la página quedó de acuerdo. Si no, el renglón se
 *   marca para revisión DENTRO del bloque nuevo, y sólo ahí.
 * - **No se compara**: la operación que Gemini devuelve. El bloque es aditivo: el cliente decide
 *   qué hacer con él, y la app sigue registrando exactamente lo que registró hoy.
 *
 * De dónde sale el texto que se manda al Ledger:
 *
 * El Ledger es un lector de TEXTO, y en este repo no existe una transcripción de la página como
 * tal: lo que hay es el `rawText` de cada renglón, que el modelo escribe como "el texto de ESE
 * renglón tal como está escrito en la hoja" (obligatorio desde el bloque v4 del contrato). La
 * página que se manda al Ledger se arma con esos renglones, uno por línea y en orden de
 * `lineIndex`.
 *
 * Lo que esto NO es, y conviene decirlo claro: **esto no es una segunda lectura de la foto.**
 * El texto lo transcribió Gemini. El Ledger vuelve a interpretar ese mismo texto y compara. Lo
 * que se caza es "Gemini interpretó mal lo que escribió" (cliente equivocado, monto mal partido,
 * tipo equivocado); lo que NO se caza es "Gemini leyó mal el papel". Para cazar eso haría falta
 * OCR independiente, que no existe aquí.
 *
 * Y por eso el texto nunca sale de aquí: es la transcripción de la libreta de una clienta.
 */

import type {
  LedgerOperationV5,
  MexalmCrosscheckBlock,
  MexalmCrosscheckDisagreement,
  MexalmCrosscheckOperationSummary,
} from "../contract.js";
import {
  MEXALM_LEDGER_MAX_TEXT_LENGTH,
  type MexalmLedgerClient,
  type MexalmLedgerOperation,
} from "./mexalmLedgerClient.js";

/**
 * Los tipos del bloque viven en `contract.ts` porque son parte de la forma de la respuesta; aquí
 * sólo está la lógica.
 */
export type {
  MexalmCrosscheckBlock,
  MexalmCrosscheckDisagreement,
  MexalmCrosscheckOperationSummary,
};

/**
 * Normalización de nombre para comparar. Es la misma regla que usa la app para
 * `customers.normalizedName`: NFD, sin diacríticos, minúsculas, espacios colapsados.
 *
 * OJO: normalizar es sólo para COMPARAR. El nombre que viaja en el desacuerdo es el que cada
 * lado escribió, sin tocar: el normalizador es una ayuda de búsqueda en la app, nunca identidad,
 * y aquí tampoco decide quién es quién.
 */
export function normalizeCustomerNameForCrosscheck(name: string): string {
  return name
    .trim()
    .normalize("NFD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/\s+/g, " ");
}

function summarize(operation: {
  proposedKind?: string;
  customerName?: string;
  amountCents?: string;
}): MexalmCrosscheckOperationSummary {
  return {
    ...(operation.proposedKind !== undefined ? { proposedKind: operation.proposedKind } : {}),
    ...(operation.customerName !== undefined ? { customerName: operation.customerName } : {}),
    ...(operation.amountCents !== undefined ? { amountCents: operation.amountCents } : {}),
  };
}

/**
 * Compara dos montos en centavos. Ambos lados usan el formato canónico (`^(0|[1-9][0-9]{0,17})$`,
 * sin ceros a la izquierda), así que la igualdad de cadenas ES la igualdad de números. Se
 * comparan como cadenas y no como números de JavaScript a propósito: un `Number` de 64 bits
 * perdería centavos exactos en montos grandes, y aquí el dinero no se toca.
 */
function sameAmountCents(left: string | undefined, right: string | undefined): boolean {
  if (left === undefined || right === undefined) {
    return false;
  }
  return left === right;
}

/**
 * Arma el texto de la página con los `rawText` de los renglones: una línea por renglón, en orden
 * de `lineIndex`.
 *
 * Los saltos de línea que traiga un `rawText` se cambian por un espacio ANTES de unir: si un
 * renglón trajera un salto dentro, la página enviada tendría más líneas que los renglones y la
 * numeración del Ledger dejaría de corresponderse con la de Gemini. Una línea por renglón es lo
 * que hace que el emparejamiento sea por `lineIndex`.
 *
 * Devuelve `null` cuando no hay ningún `rawText`: sin texto no hay nada que mandar al Ledger, y
 * sin Ledger no hay veredicto.
 */
export function buildCrosscheckPageText(operations: readonly LedgerOperationV5[]): string | null {
  const ordered = [...operations].sort((left, right) => left.lineIndex - right.lineIndex);
  const lines: string[] = [];
  for (const operation of ordered) {
    const rawText = operation.rawText;
    if (typeof rawText !== "string") {
      continue;
    }
    const line = rawText.replace(/\r\n|\r|\n/g, " ").trim();
    if (line.length === 0) {
      continue;
    }
    lines.push(line);
  }
  if (lines.length === 0) {
    return null;
  }
  const text = lines.join("\n");
  if (text.length > MEXALM_LEDGER_MAX_TEXT_LENGTH) {
    return null;
  }
  return text;
}

type GeminiOperation = LedgerOperationV5;

/**
 * Renglón de un desacuerdo cuando el Ledger no indicó de cuál habla. El contrato C1 exige
 * `lineIndex` en toda operación, así que este valor sólo aparece si el Ledger se salió del
 * contrato, y sirve para que ese desacuerdo no se pierda en silencio.
 */
export const UNNUMBERED_LINE_INDEX = -1;

function byLineIndex(left: { lineIndex: number }, right: { lineIndex: number }): number {
  return left.lineIndex - right.lineIndex;
}

type OperationPair = Readonly<{
  gemini?: GeminiOperation;
  ledger?: MexalmLedgerOperation;
  lineIndex: number;
}>;

/**
 * Empareja los renglones de los dos lados.
 *
 * Por `lineIndex`, que es la identidad natural del renglón: las dos listas numeran los renglones
 * de la misma página. Si el Ledger numera distinto (otra base, o cuenta sólo los renglones con
 * movimiento), no hay ni un `lineIndex` en común y se empareja POR POSICIÓN, que para dos listas
 * del mismo orden es lo mismo. El síntoma de un emparejamiento roto es que TODOS los renglones
 * discrepan, y eso se ve.
 */
function pairOperations(
  geminiOperations: readonly GeminiOperation[],
  ledgerOperations: readonly MexalmLedgerOperation[],
): OperationPair[] {
  const gemini = [...geminiOperations].sort(byLineIndex);
  const ledger = [...ledgerOperations].sort(
    (left, right) => (left.lineIndex ?? -1) - (right.lineIndex ?? -1),
  );

  const ledgerByLineIndex = new Map<number, MexalmLedgerOperation>();
  for (const operation of ledger) {
    if (operation.lineIndex !== undefined && !ledgerByLineIndex.has(operation.lineIndex)) {
      ledgerByLineIndex.set(operation.lineIndex, operation);
    }
  }
  const geminiLineIndexes = new Set(gemini.map((operation) => operation.lineIndex));
  const sharesAnyLineIndex = ledger.some(
    (operation) => operation.lineIndex !== undefined && geminiLineIndexes.has(operation.lineIndex),
  );

  const pairs: OperationPair[] = [];
  if (sharesAnyLineIndex) {
    const usedLedgerLineIndexes = new Set<number>();
    for (const geminiOperation of gemini) {
      const ledgerOperation = ledgerByLineIndex.get(geminiOperation.lineIndex);
      if (ledgerOperation !== undefined) {
        usedLedgerLineIndexes.add(geminiOperation.lineIndex);
      }
      pairs.push({
        gemini: geminiOperation,
        ...(ledgerOperation !== undefined ? { ledger: ledgerOperation } : {}),
        lineIndex: geminiOperation.lineIndex,
      });
    }
    for (const ledgerOperation of ledger) {
      if (usedLedgerLineIndexes.has(ledgerOperation.lineIndex ?? -1)) {
        continue;
      }
      if (ledgerOperation.lineIndex === undefined) {
        // El Ledger mandó una operación SIN renglón, lo cual el contrato C1 no permite. No se puede
        // emparejarla con nada, pero tampoco se puede hacer como que no existe: se reporta con
        // lineIndex -1, que significa "el Ledger no indicó de qué renglón habla".
        pairs.push({ ledger: ledgerOperation, lineIndex: UNNUMBERED_LINE_INDEX });
        continue;
      }
      pairs.push({ ledger: ledgerOperation, lineIndex: ledgerOperation.lineIndex });
    }
    return pairs;
  }

  // Sin un solo lineIndex en común: se empareja por posición.
  const count = Math.max(gemini.length, ledger.length);
  for (let index = 0; index < count; index += 1) {
    const geminiOperation = gemini[index];
    const ledgerOperation = ledger[index];
    const lineIndex =
      geminiOperation?.lineIndex ??
      ledgerOperation?.lineIndex ??
      (ledgerOperation !== undefined ? UNNUMBERED_LINE_INDEX : index);
    pairs.push({
      ...(geminiOperation !== undefined ? { gemini: geminiOperation } : {}),
      ...(ledgerOperation !== undefined ? { ledger: ledgerOperation } : {}),
      lineIndex,
    });
  }
  return pairs;
}

/**
 * Compara renglón por renglón y devuelve SOLO las diferencias.
 *
 * Un desacuerdo se reporta cuando los dos lados tienen un valor y difieren. Que a un lado le falte
 * un campo no es una contradicción: es que uno de los dos no lo pudo leer, y eso ya lo dice el
 * propio renglón con `unreadableFields`. Lo que sí es contradicción es que un lado diga "aquí hay
 * un movimiento" y el otro diga "aquí no hay nada".
 */
export function compareLedgerOperations(
  geminiOperations: readonly GeminiOperation[],
  ledgerOperations: readonly MexalmLedgerOperation[],
): MexalmCrosscheckDisagreement[] {
  const disagreements: MexalmCrosscheckDisagreement[] = [];
  for (const pair of pairOperations(geminiOperations, ledgerOperations)) {
    const gemini = pair.gemini;
    const ledger = pair.ledger;
    if (gemini === undefined && ledger === undefined) {
      continue;
    }
    if (gemini === undefined) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "MISSING_IN_GEMINI",
        ...(ledger !== undefined ? { ledger: summarize(ledger) } : {}),
      });
      continue;
    }
    if (ledger === undefined) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "MISSING_IN_LEDGER",
        gemini: summarize(gemini),
      });
      continue;
    }

    if (
      gemini.proposedKind !== undefined &&
      ledger.proposedKind !== undefined &&
      gemini.proposedKind !== ledger.proposedKind
    ) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "TYPE",
        gemini: summarize(gemini),
        ledger: summarize(ledger),
      });
    }

    if (
      gemini.customerName !== undefined &&
      ledger.customerName !== undefined &&
      normalizeCustomerNameForCrosscheck(gemini.customerName) !==
        normalizeCustomerNameForCrosscheck(ledger.customerName)
    ) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "CUSTOMER",
        gemini: summarize(gemini),
        ledger: summarize(ledger),
      });
    }

    if (
      gemini.amountCents !== undefined &&
      ledger.amountCents !== undefined &&
      !sameAmountCents(gemini.amountCents, ledger.amountCents)
    ) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "AMOUNT",
        gemini: summarize(gemini),
        ledger: summarize(ledger),
      });
    }
  }
  return disagreements;
}

export interface MexalmLedgerCrosschecker {
  /**
   * Compara la página de Gemini contra el Ledger. Devuelve `null` cuando NO hubo veredicto
   * (sin texto, Ledger caído, plazo agotado, respuesta inútil): en ese caso la respuesta de la
   * app queda exactamente como está y el fallo queda sólo en el log del servidor.
   */
  crosscheck(input: Readonly<{
    operations: readonly LedgerOperationV5[];
    knownCustomers?: readonly string[] | null;
  }>): Promise<MexalmCrosscheckBlock | null>;
}

export function createMexalmLedgerCrosschecker(options: Readonly<{
  client: MexalmLedgerClient;
  now?: () => number;
}>): MexalmLedgerCrosschecker {
  const now = options.now ?? (() => Date.now());
  return {
    async crosscheck(input): Promise<MexalmCrosscheckBlock | null> {
      const text = buildCrosscheckPageText(input.operations);
      if (text === null) {
        return null;
      }
      const startedAt = now();
      const result = await options.client.extract({
        text,
        knownCustomers: input.knownCustomers ?? null,
      });
      const latencyMs = Math.max(0, now() - startedAt);
      if (result === null) {
        // El cliente ya reportó la causa por su cuenta. Acá no hay nada que añadir a la respuesta.
        return null;
      }
      const disagreements = compareLedgerOperations(input.operations, result.operations);
      return {
        agreed: disagreements.length === 0,
        disagreements,
        ledgerStatus: result.status,
        latencyMs,
      };
    },
  };
}
