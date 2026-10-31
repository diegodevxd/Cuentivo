/**
 * Despertar el Ledger de MexaLM.
 *
 * El Ledger es un servicio de Cloud Run con `min-instances=0`: se apaga solo cuando nadie lo usa
 * y tarda entre 30 y 60 s en volver. Una página tarda unos 16 s en leerse, así que la primera
 * lectura después de un ratoidle se come medio minuto de espera que no es de la app.
 *
 * Este módulo dispara un `GET <baseUrl>/health` para que la instancia empiece a despertar mientras
 * la persona todavía está preparando la foto. Es **sólo un despertar**: no lee la página, no
 * manda texto, y no registra nada.
 *
 * Tres reglas que este archivo cumple y que no son negociables:
 *
 * 1. **No manda el token.** El secreto `MEXALM_LEDGER_TOKEN` está ligado a una sola callable
 *    (`extractLedgerPage`), y un health check no lo necesita. Si algún día lo necesita, es una
 *    decisión que se toma con el secreto delante, no de paso.
 * 2. **Nunca lanza.** Un Ledger caído es un Ledger que no despertó, y eso no puede tumbar la
 *    callable que lo pidió.
 * 3. **El plazo es corto: 2 s.** El despertar no es el trabajo de nadie: se dispara y se olvida.
 */

/**
 * Envoltura mínima de `fetch`, declarada propia para que las pruebas puedan inyectar un doble
 * sin depender de undici ni de la red.
 */
export type MexalmLedgerWarmFetch = (
  url: string,
  init: Readonly<{
    method: string;
    headers: Record<string, string>;
    signal: AbortSignal;
  }>,
) => Promise<Readonly<{ ok: boolean; status: number }>>;

/**
 * Plazo del health check. Dos segundos: lo que tarda en "disparar y olvidar", y lo que hace
 * imposible que el Ledger se cuelgue en la petición.
 */
export const MEXALM_LEDGER_WARM_TIMEOUT_MILLIS = 2_000;

const HEALTH_PATH = "/health";

export interface MexalmLedgerWarmer {
  /**
   * Dispara el despertar y devuelve. NO espera al Ledger: la idea es que la instancia empiece a
   * levantarse mientras la app sigue haciendo lo suyo.
   *
   * La promesa queda viva por su cuenta y su error se traga: el despertar es un favor, no una
   * promesa que alguien espere.
   */
  warm(): void;
}

class MexalmLedgerHttpWarmer implements MexalmLedgerWarmer {
  private readonly baseUrl: string;
  private readonly timeoutMillis: number;
  private readonly fetchImpl: MexalmLedgerWarmFetch;

  constructor(options: Readonly<{
    baseUrl: string;
    timeoutMillis: number;
    fetchImpl: MexalmLedgerWarmFetch;
  }>) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.timeoutMillis = options.timeoutMillis;
    this.fetchImpl = options.fetchImpl;
  }

  warm(): void {
    if (this.baseUrl.length === 0) {
      // Sin dirección no hay a quién despertar: ni una llamada, ni un error.
      return;
    }
    // La promesa se suelta a propósito. `void` no es descuido: es la regla —"dispara y
    // responde"— y el `catch` de abajo existe para que un Ledger caído no aparezca como una
    // promesa rechazada sin manejar en la consola del servidor.
    void this.fetchImpl(`${this.baseUrl}${HEALTH_PATH}`, {
      method: "GET",
      headers: {},
      signal: AbortSignal.timeout(this.timeoutMillis),
    }).catch(() => {
      // El Ledger no despertó. No es un error de la app y no se registra nada: lo único que
      // se sabría es que un servicio de terceros no estaba.
    });
  }
}

/**
 * Construye el despertador.
 *
 * `baseUrl` vacío deja el despertador en "no configurado": no hace ninguna llamada. El plazo
 * tiene un tope corto por defecto; subirlo haría que la petición viva más que el interest de
 * la app, que es justo lo contrario de lo que se quiere.
 */
export function createMexalmLedgerWarmer(options: Readonly<{
  baseUrl: string;
  timeoutMillis?: number;
  fetchImpl?: MexalmLedgerWarmFetch;
}>): MexalmLedgerWarmer {
  const fetchImpl: MexalmLedgerWarmFetch =
    options.fetchImpl ??
    ((url, init) => {
      const globalFetch = globalThis.fetch;
      return globalFetch(url, {
        method: init.method,
        headers: init.headers,
        signal: init.signal,
      });
    });

  return new MexalmLedgerHttpWarmer({
    baseUrl: options.baseUrl,
    timeoutMillis: options.timeoutMillis ?? MEXALM_LEDGER_WARM_TIMEOUT_MILLIS,
    fetchImpl,
  });
}
