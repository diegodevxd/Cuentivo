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
 * página que se manda al Ledger se arma con esos renglones **agrupados por clienta y con el
 * nombre de cada una como encabezado**; el porqué está en `planCrosscheckPage`.
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
  LEDGER_STATUS_WITHOUT_READ,
  MEXALM_LEDGER_MAX_TEXT_LENGTH,
  type MexalmLedgerClient,
  type MexalmLedgerOperation,
} from "./mexalmLedgerClient.js";
import type {
  MexalmLedgerDebugDumpEntry,
  MexalmLedgerDebugDumpWriter,
} from "./mexalmLedgerDebugDump.js";

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
  proposedKind?: string | null;
  customerName?: string | null;
  amountCents?: string | null;
}): MexalmCrosscheckOperationSummary {
  // Lo que un lado no sabe, no viaja en el resumen: un `null` del Ledger no es un nombre vacío
  // que la app pueda leer como "la clienta se llama nada".
  const kind = opinion(operation.proposedKind);
  const customer = opinion(operation.customerName);
  const amount = opinion(operation.amountCents);
  return {
    ...(kind !== undefined ? { proposedKind: kind } : {}),
    ...(customer !== undefined ? { customerName: customer } : {}),
    ...(amount !== undefined ? { amountCents: amount } : {}),
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
 * El valor de un campo cuando ese lado SÍ tiene opinión.
 *
 * "Sin opinión" son tres cosas y las tres son lo mismo para la comparación: que el campo no
 * venga, que venga `null` y que venga vacío. El Ledger responde `customerName: null` cuando no
 * puede leer un cliente de la página, y eso NO es una contradicción con lo que dice Gemini: es
 * que uno de los dos no lo pudo leer, y eso ya lo dice el propio renglón con `unreadableFields`.
 *
 * Una contradicción exige que LOS DOS lados tengan un valor y que sean distintos. El valor que
 * sale es el que cada lado escribió, sin tocar: normalizar es para comparar, nunca para
 * reescribir lo que dijo el otro lado.
 */
function opinion(value: string | undefined | null): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  return value.trim().length === 0 ? undefined : value;
}

/**
 * Una línea, nunca dos.
 *
 * Los saltos de línea que traiga un texto se cambian por un espacio ANTES de unir: si un renglón
 * trajera un salto dentro, la página enviada tendría más líneas que los renglones y la
 * numeración del Ledger dejaría de corresponderse con la de Gemini.
 */
function singleLine(text: string): string {
  return text.replace(/\r\n|\r|\n/g, " ").trim();
}

/**
 * El texto de UN renglón: su `rawText`, en una línea.
 *
 * Devuelve `null` cuando el renglón no tiene texto: un renglón sin `rawText` no se puede mandar
 * al Ledger.
 */
function rowLine(operation: LedgerOperationV5): string | null {
  const rawText = operation.rawText;
  if (typeof rawText !== "string") {
    return null;
  }
  const line = singleLine(rawText);
  return line.length === 0 ? null : line;
}

/**
 * Una clienta de la página: su encabezado y sus renglones.
 *
 * El encabezado es el nombre TAL CUAL lo escribió Gemini. Un renglón sin clienta no lleva
 * encabezado: no se inventa a quién pertenece.
 */
type CustomerGroup = {
  /** El nombre tal cual, o `undefined` si el grupo no lleva encabezado. */
  customerName?: string;
  operations: LedgerOperationV5[];
};

/**
 * Agrupa los renglones por clienta, en orden de `lineIndex`.
 *
 * Los renglones de una misma clienta quedan juntos aunque en la hoja vinieran intercalados: el
 * encabezado de una clienta vale para los renglones que la siguen, y si sus renglones están
 * repartidos, el encabezado tendría que repetirse y el Ledger atribuiría al último nombre leído
 * los renglones que no son suyos.
 *
 * La clave del grupo es el nombre NORMALIZADO: "María" y "MARIA" son la misma clienta y no pueden
 * ser dos grupos con dos encabezados. El encabezado lleva el nombre tal cual: normalizar es para
 * comparar, nunca para reescribir lo que dijo el otro lado.
 */
function groupByCustomer(ordered: readonly LedgerOperationV5[]): CustomerGroup[] {
  const groups: CustomerGroup[] = [];
  const byCustomer = new Map<string, CustomerGroup>();
  for (const operation of ordered) {
    const name = operation.customerName;
    if (typeof name !== "string" || name.trim().length === 0) {
      // Sin clienta no hay encabezado: este renglón es su propio grupo.
      groups.push({ operations: [operation] });
      continue;
    }
    const key = normalizeCustomerNameForCrosscheck(name);
    const existing = byCustomer.get(key);
    if (existing !== undefined) {
      existing.operations.push(operation);
      continue;
    }
    const group: CustomerGroup = { customerName: name, operations: [operation] };
    byCustomer.set(key, group);
    groups.push(group);
  }
  return groups;
}

/**
 * El plan de la página: qué se manda al Ledger y en qué orden queda cada renglón.
 *
 * `text` es el texto que se manda, o `null` cuando no hay nada que mandar (ningún renglón con
 * texto, o un texto más largo de lo que el Ledger acepta). `operations` son las operaciones en el
 * ORDEN en que aparecen en la página.
 */
export type CrosscheckPagePlan = Readonly<{
  text: string | null;
  operations: readonly LedgerOperationV5[];
}>;

/**
 * Arma la página que se manda al Ledger.
 *
 * **El encabezado de la clienta.** En una libreta de fiado el nombre se escribe una vez, arriba de
 * los renglones de esa clienta, y los renglones no lo repiten. El `rawText` de cada renglón es el
 * texto de ESE renglón y nada más: sin el nombre. Una página mandada renglón por renglón no tiene
 * de dónde sacar a quién pertenece cada movimiento, y el Ledger responde `customerName: null` —"no
 * lo sé"— donde la lectura de la foto sí sabe la clienta. Esa es la diferencia entre un
 * desacuerdo y una opinión que no existe.
 *
 * Por eso la página lleva, antes de los renglones de cada clienta, una línea con su nombre tal
 * cual. El Ledger reconoce esa línea como encabezado y le atribuye los renglones que siguen: con
 * "Chelo" arriba, sus renglones salen con `customerName: "Chelo"`.
 *
 * **El orden.** El Ledger numera sus operaciones por su POSICIÓN en la página —`lineIndex` es el
 * ordinal de la operación, no el número de la línea física: una línea con dos movimientos da dos
 * operaciones y la segunda no repite el número de la primera—, así que el plan lleva las
 * operaciones en el orden de la página y el emparejamiento se hace por esa posición.
 *
 * El encabezado NO es un renglón: el Ledger no saca operaciones de él, así que no desplaza la
 * numeración.
 */
export function planCrosscheckPage(operations: readonly LedgerOperationV5[]): CrosscheckPagePlan {
  const ordered = [...operations].sort((left, right) => left.lineIndex - right.lineIndex);
  const groups = groupByCustomer(ordered);

  const lines: string[] = [];
  const planned: LedgerOperationV5[] = [];
  for (const group of groups) {
    const rows: string[] = [];
    for (const operation of group.operations) {
      const line = rowLine(operation);
      if (line !== null) {
        rows.push(line);
      }
      planned.push(operation);
    }
    if (rows.length === 0) {
      // Un grupo sin renglones con texto no es una clienta de la página: mandar su nombre solo
      // sería una línea que no es un movimiento.
      continue;
    }
    if (group.customerName !== undefined) {
      lines.push(singleLine(group.customerName));
    }
    lines.push(...rows);
  }

  if (lines.length === 0) {
    // Sin texto no hay nada que mandar al Ledger, y sin Ledger no hay veredicto.
    return { text: null, operations: planned };
  }
  const text = lines.join("\n");
  if (text.length > MEXALM_LEDGER_MAX_TEXT_LENGTH) {
    return { text: null, operations: planned };
  }
  return { text, operations: planned };
}

/**
 * El texto de la página que se manda al Ledger.
 *
 * Ver `planCrosscheckPage`, que es donde vive el porqué del encabezado de la clienta.
 */
export function buildCrosscheckPageText(operations: readonly LedgerOperationV5[]): string | null {
  return planCrosscheckPage(operations).text;
}

type GeminiOperation = LedgerOperationV5;

/**
 * Renglón de un desacuerdo cuando el Ledger no indicó de cuál habla. El contrato C1 exige
 * `lineIndex` en toda operación, así que este valor sólo aparece si el Ledger se salió del
 * contrato, y sirve para que ese desacuerdo no se pierda en silencio.
 */
export const UNNUMBERED_LINE_INDEX = -1;

/**
 * Un renglón emparejado: lo que dijo cada lado de ESA línea.
 *
 * La columna `lineIndex` es la del renglón de Gemini —la que la app sabe marcar— salvo cuando el
 * renglón sólo existe del lado del Ledger, y entonces es la de él.
 */
export type OperationPair = Readonly<{
  gemini?: GeminiOperation;
  ledger?: MexalmLedgerOperation;
  lineIndex: number;
}>;

/**
 * Empareja los renglones de los dos lados.
 *
 * El Ledger numera sus operaciones por su POSICIÓN en la página —su `lineIndex` es el ordinal de
 * la operación, no el número de la línea física— así que su número se lee como una posición
 * dentro del plan de la página (ver `planCrosscheckPage`), y el `lineIndex` que se reporta en un
 * desacuerdo es el del renglón de Gemini: el que la app sabe marcar.
 *
 * Si el Ledger numera distinto (otra base, o cuenta sólo los renglones con movimiento), no hay ni
 * una posición en común y se empareja POR ORDEN, que para dos listas del mismo orden es lo mismo.
 * El síntoma de un emparejamiento roto es que TODOS los renglones discrepan, y eso se ve.
 *
 * Es pública porque el volcado de diagnóstico tiene que mostrar EL emparejamiento —el mismo que
 * usa la comparación— y no una copia que se parece.
 */
export function pairLedgerOperations(
  geminiOperations: readonly GeminiOperation[],
  ledgerOperations: readonly MexalmLedgerOperation[],
): OperationPair[] {
  const planned = planCrosscheckPage(geminiOperations).operations;
  const ledger = [...ledgerOperations].sort(
    (left, right) => (left.lineIndex ?? -1) - (right.lineIndex ?? -1),
  );

  const sharesAnyPosition = ledger.some(
    (operation) =>
      operation.lineIndex !== undefined &&
      operation.lineIndex >= 0 &&
      operation.lineIndex < planned.length,
  );

  const pairs: OperationPair[] = [];
  if (sharesAnyPosition) {
    const usedPositions = new Set<number>();
    for (const ledgerOperation of ledger) {
      const position = ledgerOperation.lineIndex;
      if (position === undefined) {
        // El Ledger mandó una operación SIN renglón, lo cual el contrato C1 no permite. No se puede
        // emparejarla con nada, pero tampoco se puede hacer como que no existe: se reporta con
        // lineIndex -1, que significa "el Ledger no indicó de qué renglón habla".
        pairs.push({ ledger: ledgerOperation, lineIndex: UNNUMBERED_LINE_INDEX });
        continue;
      }
      const geminiOperation =
        position >= 0 && position < planned.length ? planned[position] : undefined;
      if (geminiOperation !== undefined && !usedPositions.has(position)) {
        usedPositions.add(position);
        pairs.push({
          gemini: geminiOperation,
          ledger: ledgerOperation,
          lineIndex: geminiOperation.lineIndex,
        });
        continue;
      }
      // Una segunda operación con la misma posición, o una posición que no existe en la página:
      // no se empareja con nada, y no se pierde en silencio.
      pairs.push({ ledger: ledgerOperation, lineIndex: position });
    }
    // Los renglones que el Ledger no vio: la página los tenía y él no los devolvió.
    for (let position = 0; position < planned.length; position += 1) {
      if (usedPositions.has(position)) {
        continue;
      }
      const geminiOperation = planned[position];
      if (geminiOperation === undefined) {
        continue;
      }
      pairs.push({ gemini: geminiOperation, lineIndex: geminiOperation.lineIndex });
    }
    return pairs;
  }

  // Sin una posición en común: se empareja por orden.
  const count = Math.max(planned.length, ledger.length);
  for (let index = 0; index < count; index += 1) {
    const geminiOperation = planned[index];
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
  return comparePairs(pairLedgerOperations(geminiOperations, ledgerOperations));
}

/**
 * Los renglones que el Ledger CONFIRMÓ.
 *
 * Un renglón está confirmado cuando **los dos lados tienen un valor** en el tipo, el cliente y el
 * monto, y **los tres coinciden**: el tipo igual, el cliente igual después de normalizar, y el
 * monto igual en centavos.
 *
 * La diferencia con "no hubo desacuerdos" es la que importa, y es la que hace que este encargo
 * sea posible: un desacuerdo sólo existe cuando los dos lados tienen un valor, así que un Ledger
 * que no tuvo opinión de nada no discrepa de nada — `agreed: true` y, al mismo tiempo, esta lista
 * vacía. Con esta lista el cliente sabe qué renglón quedó confirmado de verdad.
 *
 * Por eso el renglón confirmado exige que el Ledger **también** afirmara el monto, el cliente y el
 * tipo: "no lo sé" no es un acuerdo.
 */
export function confirmAgreedPairs(pairs: readonly OperationPair[]): number[] {
  const confirmed: number[] = [];
  for (const pair of pairs) {
    const gemini = pair.gemini;
    const ledger = pair.ledger;
    if (gemini === undefined || ledger === undefined) {
      continue;
    }
    // Si el propio Ledger marcó una duda en el renglón, que coincida con Gemini no confirma nada.
    if ((ledger.uncertainFields?.length ?? 0) > 0 || (ledger.unreadableFields?.length ?? 0) > 0) {
      continue;
    }
    const geminiKind = opinion(gemini.proposedKind);
    const ledgerKind = opinion(ledger.proposedKind);
    if (geminiKind === undefined || ledgerKind === undefined || geminiKind !== ledgerKind) {
      continue;
    }
    const geminiCustomer = opinion(gemini.customerName);
    const ledgerCustomer = opinion(ledger.customerName);
    if (geminiCustomer === undefined || ledgerCustomer === undefined) {
      continue;
    }
    if (
      normalizeCustomerNameForCrosscheck(geminiCustomer) !==
      normalizeCustomerNameForCrosscheck(ledgerCustomer)
    ) {
      continue;
    }
    const geminiAmount = opinion(gemini.amountCents);
    const ledgerAmount = opinion(ledger.amountCents);
    if (geminiAmount === undefined || ledgerAmount === undefined) {
      continue;
    }
    if (!sameAmountCents(geminiAmount, ledgerAmount)) {
      continue;
    }
    confirmed.push(pair.lineIndex);
  }
  return confirmed.sort((left, right) => left - right);
}

/**
 * Compara los renglones YA emparejados. Es el corazón de la comparación, y está aparte para que
 * el volcado de diagnóstico y la comparación usen exactamente el mismo emparejamiento.
 */
function comparePairs(pairs: readonly OperationPair[]): MexalmCrosscheckDisagreement[] {
  const disagreements: MexalmCrosscheckDisagreement[] = [];
  for (const pair of pairs) {
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

    const geminiKind = opinion(gemini.proposedKind);
    const ledgerKind = opinion(ledger.proposedKind);
    if (geminiKind !== undefined && ledgerKind !== undefined && geminiKind !== ledgerKind) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "TYPE",
        gemini: summarize(gemini),
        ledger: summarize(ledger),
      });
    }

    const geminiCustomer = opinion(gemini.customerName);
    const ledgerCustomer = opinion(ledger.customerName);
    if (
      geminiCustomer !== undefined &&
      ledgerCustomer !== undefined &&
      normalizeCustomerNameForCrosscheck(geminiCustomer) !==
        normalizeCustomerNameForCrosscheck(ledgerCustomer)
    ) {
      disagreements.push({
        lineIndex: pair.lineIndex,
        field: "CUSTOMER",
        gemini: summarize(gemini),
        ledger: summarize(ledger),
      });
    }

    const geminiAmount = opinion(gemini.amountCents);
    const ledgerAmount = opinion(ledger.amountCents);
    if (
      geminiAmount !== undefined &&
      ledgerAmount !== undefined &&
      !sameAmountCents(geminiAmount, ledgerAmount)
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

/**
 * ¿El Ledger NO leyó la página?
 *
 * Es el caso que se ve desde el teléfono como "cada renglón dice que el Ledger no lo vio": el
 * Ledger respondió `BLOCKED` (o `REVIEW_REQUIRED`) y sin operaciones. No es que le faltara un
 * renglón: es que **la página entera** no se pudo certificar.
 *
 * Por eso, en ese caso NO se reportan diferencias renglón por renglón. Un `MISSING_IN_LEDGER` en
 * cada renglón no dice "el Ledger no leyó esta página": dice "el Ledger no leyó N renglones", que
 * es una lectura equivocada de lo que pasó — y hace que la persona revise una libreta que en
 * realidad no tiene nada que ver con lo que el Ledger dijo.
 */
export function ledgerDidNotReadPage(status: string, operationCount: number): boolean {
  return operationCount === 0 && LEDGER_STATUS_WITHOUT_READ.has(status);
}

/**
 * El emparejamiento, escrito para el volcado de diagnóstico.
 *
 * Usa el MISMO `summarize` que la comparación: el archivo tiene que mostrar lo que se comparó.
 */
export function describeCrosscheckPairing(
  pairs: readonly OperationPair[],
): MexalmLedgerDebugDumpEntry[] {
  return pairs.map((pair) => ({
    lineIndex: pair.lineIndex,
    ...(pair.gemini !== undefined ? { gemini: summarize(pair.gemini) } : {}),
    ...(pair.ledger !== undefined ? { ledger: summarize(pair.ledger) } : {}),
  }));
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
  /**
   * El volcado de diagnóstico. Es OPCIONAL y sólo existe en el emulador: aquí se pasa el
   * escritor, que apagado no escribe nada. `null` es lo mismo que no pasarlo.
   */
  debugDump?: MexalmLedgerDebugDumpWriter | null;
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
      const pairs = pairLedgerOperations(input.operations, result.operations);
      const disagreements = comparePairs(pairs);
      // El Ledger no leyó la página: no hay veredicto renglón por renglón, y por eso no se
      // reportan diferencias por renglón. El `ledgerStatus` del bloque dice lo que pasó, y la app
      // lo explica una sola vez.
      const didNotRead = ledgerDidNotReadPage(result.status, result.operations.length);
      // El volcado va ANTES de la respuesta: aunque el bloque se arme raro, el diagnóstico está.
      options.debugDump?.write({
        text,
        response: result.raw,
        matching: describeCrosscheckPairing(pairs),
      });
      return {
        agreed: !didNotRead && disagreements.length === 0,
        disagreements: didNotRead ? [] : disagreements,
        ledgerStatus: result.status,
        latencyMs,
        // Los renglones que el Ledger confirmó de verdad. Cuando no leyó la página, no confirmó
        // ninguno: no hay veredicto que haya confirmado nada.
        confirmedLineIndexes: didNotRead ? [] : confirmAgreedPairs(pairs),
      };
    },
  };
}
