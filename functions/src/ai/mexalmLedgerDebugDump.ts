/**
 * Volcado de diagnóstico de la verificación cruzada. **SOLO en el emulador.**
 *
 * Para qué existe: cuando el Ledger de MexaLM NO lee la página (por ejemplo `BLOCKED`, o sea que
 * no pudo certificar alguna línea), el bloque de la respuesta dice `ledgerStatus: "BLOCKED"` y ya
 * no hay manera de saber **por qué** ni **qué le mandamos**. Con este volcado la respuesta sale
 * completa: el texto exacto que se mandó, la respuesta completa del Ledger y el emparejamiento
 * renglón por renglón.
 *
 * Tres reglas que este archivo cumple y que no son negociables:
 *
 * 1. **En producción NO existe.** El volcado sólo se escribe si el proceso corre en el emulador
 *    (`FUNCTIONS_EMULATOR === "true"`) **Y** el parámetro `MEXALM_LEDGER_DEBUG_DUMP` está en
 *    `true`. Fuera del emulador el parámetro se ignora: no hay forma de encenderlo en producción,
 *    ni por accidente. La decisión vive en UN solo lugar, `isMexalmLedgerDebugDumpEnabled`, que es
 *    el que usa el código de producción y el que prueba la prueba.
 * 2. **El texto de la página sale del servidor, pero sólo a un archivo local del emulador.** Es la
 *    transcripción de la libreta de una clienta: por eso el archivo vive en un directorio ignorado
 *    por Git y nunca se manda a ningún log, a Analytics ni a Crashlytics. La regla del repo "nada
 *    de PII en logs" sigue valiendo: un archivo local del emulador no es un log.
 * 3. **El volcado nunca puede tumbar la callable.** Si el disco falla, la escritura falla y se
 *    sigue: la lectura de la libreta ya se pagó y no se pierde por un archivo de diagnóstico.
 */

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import type { MexalmCrosscheckOperationSummary } from "../contract.js";

/**
 * El directorio del volcado, DENTRO del paquete de Functions: `functions/.mexalm-debug/`.
 *
 * Está en `.gitignore` porque los archivos llevan el texto de la libreta de una clienta.
 */
export const MEXALM_LEDGER_DEBUG_DUMP_DIRECTORY_NAME = ".mexalm-debug";

/**
 * El parámetro que enciende el volcado. Apagado por defecto; y apagado de más, porque fuera del
 * emulador no hace nada.
 */
export const MEXALM_LEDGER_DEBUG_DUMP_PARAMETER = "MEXALM_LEDGER_DEBUG_DUMP";

/**
 * Un renglón del emparejamiento: lo que dijo cada lado de ESA línea.
 *
 * Es el mismo `summarize` que usa la comparación, no una copia: el volcado tiene que mostrar lo
 * que se comparó, no lo que parece que se comparó.
 */
export type MexalmLedgerDebugDumpEntry = Readonly<{
  lineIndex: number;
  gemini?: MexalmCrosscheckOperationSummary;
  ledger?: MexalmCrosscheckOperationSummary;
}>;

/**
 * Lo que se escribe en el archivo: las tres cosas que hacen falta para entender qué pasó.
 */
export type MexalmLedgerDebugDumpPayload = Readonly<{
  /** El texto EXACTO que se mandó al Ledger. */
  text: string;
  /** La respuesta COMPLETA del Ledger, tal cual llegó. */
  response: unknown;
  /** El emparejamiento renglón por renglón: lo que se comparó. */
  matching: readonly MexalmLedgerDebugDumpEntry[];
}>;

export interface MexalmLedgerDebugDumpWriter {
  /**
   * Escribe el volcado. Devuelve la ruta escrita, o `null` cuando no se escribió nada (el
   * volcado está apagado, o el disco falló).
   */
  write(payload: MexalmLedgerDebugDumpPayload): string | null;
}

/**
 * La decisión de si hay volcado. **Un solo lugar.**
 *
 * El emulador se identifica con la variable de entorno que pone el propio Firebase Emulator
 * Suite: `FUNCTIONS_EMULATOR=true`. Sin esa variable NO hay volcado, aunque el parámetro esté
 * encendido: en producción el parámetro se ignora y el volcado no existe.
 */
export function isMexalmLedgerDebugDumpEnabled(input: {
  /** `process.env.FUNCTIONS_EMULATOR`, tal cual. */
  emulator: string | undefined;
  /** El valor del parámetro `MEXALM_LEDGER_DEBUG_DUMP`. */
  parameterEnabled: boolean;
}): boolean {
  return input.emulator === "true" && input.parameterEnabled === true;
}

/**
 * El nombre del archivo: el momento de la llamada, con los dos puntos cambiados por guiones.
 *
 * Los dos puntos no valen en un nombre de archivo de Windows, y este volcado se escribe en la
 * máquina de quien desarrolla, que es Windows la mitad de las veces.
 */
export function mexalmLedgerDebugDumpFileName(timestampMillis: number): string {
  return `${new Date(timestampMillis).toISOString().replace(/[:]/g, "-")}.json`;
}

/**
 * El directorio donde cae el volcado: el paquete de Functions, y no el directorio desde el que
 * corra el proceso.
 *
 * El emulador puede arrancar el proceso de las Functions desde otro directorio (la raíz del
 * repo, por ejemplo), así que el directorio se busca **subiendo** hasta encontrar el
 * `package.json` del paquete de Functions. Si no se encuentra, se usa el directorio de trabajo:
 * mejor un volcado en el lugar unexpected que ningún volcado.
 */
export function resolveMexalmLedgerDebugDumpDirectory(options: {
  /** El directorio de trabajo del proceso. */
  cwd: string;
  /** Lee el `name` de un `package.json`. Por defecto lo lee del disco. */
  readPackageName?: (packageJsonPath: string) => string | null;
}): string {
  const readPackageName =
    options.readPackageName ??
    ((packageJsonPath: string): string | null => {
      try {
        const raw = readFileSync(packageJsonPath, "utf8");
        const parsed = JSON.parse(raw) as { name?: unknown };
        return typeof parsed.name === "string" ? parsed.name : null;
      } catch {
        return null;
      }
    });

  let current = resolve(options.cwd);
  // Se sube un número acotado de niveles: más allá de esto no hay un paquete de Functions.
  for (let depth = 0; depth < 8; depth += 1) {
    const name = readPackageName(join(current, "package.json"));
    if (name === "functions") {
      return join(current, MEXALM_LEDGER_DEBUG_DUMP_DIRECTORY_NAME);
    }
    const parent = dirname(current);
    if (parent === current) {
      break;
    }
    current = parent;
  }
  return join(resolve(options.cwd), MEXALM_LEDGER_DEBUG_DUMP_DIRECTORY_NAME);
}

/**
 * Construye el escritor del volcado.
 *
 * El escritor se puede construir SIEMPRE: apagado simply no escribe. Así el código de producción
 * no tiene que ramificar, y lo que decide si hay archivo es esta función y no el llamador.
 */
export function createMexalmLedgerDebugDumpWriter(options: {
  /** `process.env.FUNCTIONS_EMULATOR`, tal cual. */
  emulator: string | undefined;
  /** El valor del parámetro `MEXALM_LEDGER_DEBUG_DUMP`. */
  parameterEnabled: boolean;
  /** El directorio del volcado. Por defecto se busca el paquete de Functions. */
  directory?: string;
  /** El directorio de trabajo, para buscar el paquete de Functions. */
  cwd?: string;
  /** El reloj. Por defecto, el del sistema. */
  now?: () => number;
  /** Escribe el archivo. Por defecto, en disco. */
  writeFile?: (path: string, contents: string) => void;
  /** Crea el directorio. Por defecto, en disco. */
  makeDirectory?: (path: string) => void;
}): MexalmLedgerDebugDumpWriter {
  const enabled = isMexalmLedgerDebugDumpEnabled({
    emulator: options.emulator,
    parameterEnabled: options.parameterEnabled,
  });
  const now = options.now ?? (() => Date.now());
  const writeFile =
    options.writeFile ?? ((path: string, contents: string) => writeFileSync(path, contents, "utf8"));
  const makeDirectory =
    options.makeDirectory ?? ((path: string) => mkdirSync(path, { recursive: true }));

  return {
    write(payload: MexalmLedgerDebugDumpPayload): string | null {
      if (!enabled) {
        // Fuera del emulador —o con el parámetro apagado— el volcado no existe. Ni el archivo, ni
        // el directorio, ni una línea de texto de la libreta en ningún lado.
        return null;
      }
      const directory =
        options.directory ??
        resolveMexalmLedgerDebugDumpDirectory({ cwd: options.cwd ?? process.cwd() });
      const path = join(directory, mexalmLedgerDebugDumpFileName(now()));
      try {
        makeDirectory(directory);
        writeFile(
          path,
          `${JSON.stringify(
            {
              dumpedAtEpochMillis: now(),
              ledgerStatus:
                typeof (payload.response as { status?: unknown } | null)?.status === "string"
                  ? (payload.response as { status: string }).status
                  : null,
              text: payload.text,
              response: payload.response,
              matching: payload.matching,
            },
            null,
            2,
          )}\n`,
        );
        return path;
      } catch {
        // El volcado es un adorno del diagnóstico: si el disco falla, la callable sigue.
        return null;
      }
    },
  };
}
