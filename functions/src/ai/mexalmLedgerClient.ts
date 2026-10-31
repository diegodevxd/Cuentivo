/**
 * Cliente HTTP del Ledger de MexaLM (contrato C1) para la verificación cruzada de
 * `extractLedgerPage`.
 *
 * El Ledger recibe TEXTO y devuelve operaciones C1. Este cliente habla con
 * `POST <baseUrl>/v1/extract` y NO sabe nada de dinero, de Room ni de la app: sólo traduce una
 * página a una respuesta y devuelve `null` cuando no pudo.
 *
 * Tres reglas que este archivo cumple y que no son negociables:
 *
 * 1. **El texto de la página nunca se registra.** Ni en el error, ni en la telemetría, ni en la
 *    excepción: la telemetría lleva una categoría y, como mucho, un código HTTP. El texto de una
 *    libreta son nombres de clientas y montos; en un log es una fuga de PII.
 * 2. **Nunca lanza.** Cualquier fallo —timeout, red, HTTP, JSON inválido— se vuelve `null` con una
 *    categoría. El llamador no tiene que try/catch y la respuesta de la app nunca depende de que
 *    el Ledger esté sano.
 * 3. **El plazo es corto y ajeno al de Gemini.** 8 s: si el Ledger no contesta, la lectura de la
 *    libreta no se queda esperando. La app lee igual que hoy.
 */

export type MexalmLedgerFailureCategory =
  | "NOT_CONFIGURED"
  | "TIMEOUT"
  | "NETWORK_ERROR"
  | "HTTP_ERROR"
  | "INVALID_RESPONSE"
  | "REJECTED"
  | "TEXT_TOO_LONG";

export type MexalmLedgerFailure = Readonly<{
  category: MexalmLedgerFailureCategory;
  /** Código HTTP, sólo cuando la respuesta llegó y no fue 2xx. */
  status?: number;
  /** `status` que el propio Ledger报告会, cuando lo reportó y no fue "OK". */
  ledgerStatus?: string;
}>;

export type MexalmLedgerFailureListener = (failure: MexalmLedgerFailure) => void;

/**
 * Una operación tal como la devuelve el Ledger. Se leen sólo tres campos más `lineIndex`:
 * `proposedKind`, `customerName` y `amountCents`. Todo lo demás del contrato C1 se ignora a
 * propósito — la verificación cruzada compara tipo, cliente y monto, nada más.
 */
export type MexalmLedgerOperation = Readonly<{
  lineIndex?: number;
  proposedKind?: string;
  /**
   * El nombre tal cual lo mandó el Ledger, o `null` cuando no pudo leer una clienta en esa línea.
   *
   * El `null` se conserva: es lo que dijo el otro lado, y quitarlo en la frontera sería decidir
   * aquí lo que significa. Lo que significa "sin opinión" lo decide la comparación.
   */
  customerName?: string | null;
  amountCents?: string;
  /**
   * Los campos que el propio Ledger marcó como dudosos o ilegibles en ESE renglón, tal cual. Un
   * renglón con alguno no se puede dar por confirmado: el Ledger mismo dice que no está seguro.
   * Si el Ledger manda algo que no es una lista, se guarda `["MALFORMED"]` (falla cerrado).
   */
  uncertainFields?: readonly string[];
  unreadableFields?: readonly string[];
}>;

export type MexalmLedgerExtractResult = Readonly<{
  /** `status` del Ledger, verbatim. "OK" es el único que significa "aquí hay operaciones". */
  status: string;
  operations: readonly MexalmLedgerOperation[];
  /**
   * La respuesta COMPLETA del Ledger, tal cual llegó.
   *
   * **Nunca se registra.** Sólo la usa el volcado de diagnóstico del emulador, que es un archivo
   * local ignorado por Git: un log es un destino público y este texto es la transcripción de la
   * libreta de una clienta.
   */
  raw: unknown;
}>;

/**
 * Envoltura mínima de `fetch`. Se declara propia para que las pruebas puedan inyectar un doble
 * sin depender de undici ni de la red.
 */
export type MexalmLedgerFetch = (
  url: string,
  init: Readonly<{
    method: string;
    headers: Record<string, string>;
    body: string;
    signal: AbortSignal;
  }>,
) => Promise<Readonly<{ ok: boolean; status: number; json: () => Promise<unknown> }>>;

export interface MexalmLedgerClient {
  /**
   * Manda una página al Ledger. Devuelve `null` si no hubo respuesta utilizable; nunca lanza.
   * `text` vacío significa "no hay nada que comparar": no se hace ninguna llamada.
   */
  extract(input: Readonly<{
    text: string;
    knownCustomers: readonly string[] | null;
  }>): Promise<MexalmLedgerExtractResult | null>;
}

/**
 * Plazo del Ledger. Cubre los ~10-20 s que tarda una página completa en CPU; la lectura de la libreta es lo que importa y no puede
 * quedarse esperando a un servicio de terceros.
 */
export const MEXALM_LEDGER_TIMEOUT_MILLIS = 25_000;

/**
 * Tope de texto que acepta el servicio (`text` de máximo 8 000 caracteres). Si la página supera
 * esto no se manda recortada: una página recortada comparada como si fuera completa daría
 * desacuerdos inventados. Se devuelve `null` y la respuesta de la app queda como está.
 */
export const MEXALM_LEDGER_MAX_TEXT_LENGTH = 8_000;

const EXTRACT_PATH = "/v1/extract";

/**
 * `status` que el Ledger usa cuando NO produjo un veredicto (C1: "INVALID_OUTPUT" y "REFUSED").
 *
 * Con esos status no se compara nada. Cualquier otro status —incluido uno que este servicio no
 * conhece todavía— se toma como veredicto y se compara: un valor desconocido no puede apagar la
 * verificación, y el `status` viaja verbatim en el bloque para que quien lo lea vea lo que dijo.
 */
const NO_VERDICT_STATUSES = new Set(["INVALID_OUTPUT", "REFUSED"]);

/**
 * `status` que el Ledger usa cuando NO LEYÓ la página (C1: "BLOCKED" y "REVIEW_REQUIRED").
 *
 * Son distintos de los de arriba: el Ledger **respondió** y dijo que no pudo certificar la
 * página, en vez de devolver algo inútil. Por eso la respuesta viaja con `ledgerStatus` y la app
 * puede explicarle a la persona qué pasó, en vez de no decir nada.
 */
export const LEDGER_STATUS_WITHOUT_READ = new Set(["BLOCKED", "REVIEW_REQUIRED"]);

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Lee los tres campos que la comparación usa. Lo que no está o no es del tipo esperado se
 * devuelve como `undefined`, que es "no lo sé": la comparación sólo reporta desacuerdo cuando
 * LOS DOS lados tienen un valor y difieren.
 *
 * Un `customerName: null` se conserva tal cual: el Ledger no pudo leer una clienta en esa línea y
 * eso no es una contradicción con lo que dice la lectura de la foto. Lo decide la comparación, no
 * la frontera.
 */
function readOperation(value: unknown): MexalmLedgerOperation | null {
  if (!isPlainObject(value)) {
    return null;
  }
  const rawLineIndex = value["lineIndex"];
  const rawKind = value["proposedKind"];
  const rawCustomer = value["customerName"];
  const rawAmount = value["amountCents"];
  return {
    ...(typeof rawLineIndex === "number" && Number.isSafeInteger(rawLineIndex) && rawLineIndex >= 0
      ? { lineIndex: rawLineIndex }
      : {}),
    ...(typeof rawKind === "string" ? { proposedKind: rawKind } : {}),
    ...(typeof rawCustomer === "string"
      ? { customerName: rawCustomer }
      : rawCustomer === null
        ? { customerName: null }
        : {}),
    ...(typeof rawAmount === "string" ? { amountCents: rawAmount } : {}),
    ...readFieldList(value, "uncertainFields"),
    ...readFieldList(value, "unreadableFields"),
  };
}

function readFieldList(
  value: Record<string, unknown>,
  key: "uncertainFields" | "unreadableFields",
): Partial<Record<typeof key, readonly string[]>> {
  if (!(key in value)) {
    return {};
  }
  const raw = value[key];
  if (!Array.isArray(raw)) {
    return { [key]: ["MALFORMED"] };
  }
  return { [key]: raw.map((field) => (typeof field === "string" ? field : "MALFORMED")) };
}

function isAbortLike(error: unknown): boolean {
  if (typeof error !== "object" || error === null) {
    return false;
  }
  const name = (error as { name?: unknown }).name;
  return name === "AbortError" || name === "TimeoutError";
}

class MexalmLedgerHttpClient implements MexalmLedgerClient {
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly timeoutMillis: number;
  private readonly fetchImpl: MexalmLedgerFetch;
  private readonly onFailure?: MexalmLedgerFailureListener;

  constructor(options: Readonly<{
    baseUrl: string;
    token: string;
    timeoutMillis: number;
    fetchImpl: MexalmLedgerFetch;
    onFailure?: MexalmLedgerFailureListener;
  }>) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.token = options.token;
    this.timeoutMillis = options.timeoutMillis;
    this.fetchImpl = options.fetchImpl;
    this.onFailure = options.onFailure;
  }

  private emitFailure(failure: MexalmLedgerFailure): void {
    try {
      this.onFailure?.(failure);
    } catch {
      // La telemetría nunca puede propagar una excepción: si el logger falla, el Ledger falla.
    }
  }

  async extract(input: Readonly<{
    text: string;
    knownCustomers: readonly string[] | null;
  }>): Promise<MexalmLedgerExtractResult | null> {
    if (this.baseUrl.length === 0 || this.token.length === 0) {
      this.emitFailure({ category: "NOT_CONFIGURED" });
      return null;
    }
    const text = input.text;
    if (typeof text !== "string" || text.trim().length === 0) {
      // Sin texto no hay llamada: una página sin renglones no se puede comparar y un gasto
      // ocioso tampoco.
      return null;
    }
    if (text.length > MEXALM_LEDGER_MAX_TEXT_LENGTH) {
      this.emitFailure({ category: "TEXT_TOO_LONG" });
      return null;
    }

    let timedOut = false;
    let timerId: ReturnType<typeof setTimeout> | undefined;
    try {
      const timeoutPromise = new Promise<never>((_, reject) => {
        timerId = setTimeout(() => {
          timedOut = true;
          reject(new Error("mexalm-ledger-timeout"));
        }, this.timeoutMillis);
      });

      const response = await Promise.race([
        this.fetchImpl(`${this.baseUrl}${EXTRACT_PATH}`, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            authorization: `Bearer ${this.token}`,
          },
          body: JSON.stringify({
            text,
            knownCustomers: input.knownCustomers ?? null,
          }),
          signal: AbortSignal.timeout(this.timeoutMillis),
        }),
        timeoutPromise,
      ]);

      if (!response.ok) {
        this.emitFailure({ category: "HTTP_ERROR", status: response.status });
        return null;
      }

      let payload: unknown;
      try {
        payload = await response.json();
      } catch {
        this.emitFailure({ category: "INVALID_RESPONSE", status: response.status });
        return null;
      }
      if (!isPlainObject(payload)) {
        this.emitFailure({ category: "INVALID_RESPONSE", status: response.status });
        return null;
      }

      const rawStatus = payload["status"];
      const status = typeof rawStatus === "string" ? rawStatus : "";
      const rawOperations = payload["operations"];
      if (!Array.isArray(rawOperations)) {
        this.emitFailure({ category: "INVALID_RESPONSE", status: response.status });
        return null;
      }

      const operations: MexalmLedgerOperation[] = [];
      for (const rawOperation of rawOperations) {
        const operation = readOperation(rawOperation);
        if (operation !== null) {
          operations.push(operation);
        }
      }

      if (NO_VERDICT_STATUSES.has(status)) {
        // El Ledger respondió pero no con un veredicto utilizable ("INVALID_OUTPUT", "REFUSED"):
        // no produjo operaciones que comparar. No se compara nada, porque comparar contra una
        // página que el Ledger no pudo leer daría desacuerdos que no son de la lectura de Gemini.
        this.emitFailure({ category: "REJECTED", status: response.status, ledgerStatus: status });
        return null;
      }

      // `status` viaja verbatim en el bloque de la respuesta, incluso si el Ledger no mandó
      // ninguno (queda ""). No se inventa un "OK" que el Ledger no dijo.
      return { status, operations: Object.freeze(operations), raw: payload };
    } catch (error) {
      if (timedOut || isAbortLike(error)) {
        this.emitFailure({ category: "TIMEOUT" });
      } else {
        this.emitFailure({ category: "NETWORK_ERROR" });
      }
      return null;
    } finally {
      if (timerId !== undefined) {
        clearTimeout(timerId);
      }
    }
  }
}

/**
 * Construye el cliente del Ledger.
 *
 * `baseUrl` y `token` vacíos dejan el cliente en "no configurado": devuelve `null` y avisa, en
 * vez de llamar a una URL inválida. `timeoutMillis` tiene un tope corto por defecto; no debe
 * subirse sin motivo, porque la lectura de la libreta espera a este llamada.
 */
export function createMexalmLedgerClient(options: Readonly<{
  baseUrl: string;
  token: string;
  timeoutMillis?: number;
  fetchImpl?: MexalmLedgerFetch;
  onFailure?: MexalmLedgerFailureListener;
}>): MexalmLedgerClient {
  const fetchImpl: MexalmLedgerFetch =
    options.fetchImpl ??
    ((url, init) => {
      const globalFetch = globalThis.fetch;
      return globalFetch(url, {
        method: init.method,
        headers: init.headers,
        body: init.body,
        signal: init.signal,
      });
    });

  return new MexalmLedgerHttpClient({
    baseUrl: options.baseUrl,
    token: options.token,
    timeoutMillis: options.timeoutMillis ?? MEXALM_LEDGER_TIMEOUT_MILLIS,
    fetchImpl,
    ...(options.onFailure ? { onFailure: options.onFailure } : {}),
  });
}
