import { mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test } from "vitest";
import { createMexalmLedgerClient, type MexalmLedgerFetch } from "../src/ai/mexalmLedgerClient.js";
import { createMexalmLedgerCrosschecker } from "../src/ai/mexalmCrosscheck.js";
import {
  createMexalmLedgerDebugDumpWriter,
  isMexalmLedgerDebugDumpEnabled,
  mexalmLedgerDebugDumpFileName,
  resolveMexalmLedgerDebugDumpDirectory,
} from "../src/ai/mexalmLedgerDebugDump.js";

/**
 * El volcado de diagnóstico del Ledger de MexaLM.
 *
 * Lo que estas pruebas protegen es una sola cosa, y es la importante: **el volcado es un artefacto
 * del emulador y en producción no existe.** No es "está apagado por defecto": es que fuera del
 * emulador el parámetro se ignora por completo.
 */

const PAGE_TEXT_LINE = "Rosa protector tapete 250";

const OPERATIONS = [
  {
    lineIndex: 0,
    proposedKind: "SALE" as const,
    customerName: "Rosa",
    amountCents: "25000",
    uncertainFields: [],
    unreadableFields: [],
    rawText: PAGE_TEXT_LINE,
  },
];

/** La respuesta del Ledger cuando no puede certificar la página. */
const LEDGER_BLOCKED_RESPONSE = {
  status: "BLOCKED",
  reasons: ["plan_uncertifiable_line"],
  operations: [],
};

const tempDirs: string[] = [];

function tempDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "cuentivo5-dump-"));
  tempDirs.push(dir);
  return dir;
}

afterEach(() => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop();
    if (dir !== undefined) {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

describe("el volcado de diagnóstico del Ledger", () => {
  describe("la decisión", () => {
    test("fuera del emulador el parámetro se ignora: no hay volcado", () => {
      expect(
        isMexalmLedgerDebugDumpEnabled({ emulator: undefined, parameterEnabled: true }),
      ).toBe(false);
      expect(
        isMexalmLedgerDebugDumpEnabled({ emulator: "false", parameterEnabled: true }),
      ).toBe(false);
    });

    test("dentro del emulador hace falta el parámetro", () => {
      expect(isMexalmLedgerDebugDumpEnabled({ emulator: "true", parameterEnabled: false })).toBe(
        false,
      );
      expect(isMexalmLedgerDebugDumpEnabled({ emulator: "true", parameterEnabled: true })).toBe(true);
    });
  });

  describe("fuera del emulador", () => {
    test("no se escribe NADA: ni archivo, ni directorio, ni una línea de la libreta", () => {
      const directory = tempDir();
      const written: string[] = [];
      const writer = createMexalmLedgerDebugDumpWriter({
        emulator: undefined,
        parameterEnabled: true,
        directory,
        now: () => 1_700_000_000_000,
        writeFile: (path) => {
          written.push(path);
        },
        makeDirectory: (path) => {
          written.push(`mkdir ${path}`);
        },
      });

      const result = writer.write({
        text: PAGE_TEXT_LINE,
        response: LEDGER_BLOCKED_RESPONSE,
        matching: [],
      });

      expect(result).toBeNull();
      expect(written).toEqual([]);
      expect(readdirSync(directory)).toEqual([]);
    });

    test("tampoco con el emulador apagado explícitamente", () => {
      const directory = tempDir();
      const written: string[] = [];
      const writer = createMexalmLedgerDebugDumpWriter({
        emulator: "false",
        parameterEnabled: true,
        directory,
        writeFile: (path) => {
          written.push(path);
        },
      });

      expect(
        writer.write({ text: PAGE_TEXT_LINE, response: LEDGER_BLOCKED_RESPONSE, matching: [] }),
      ).toBeNull();
      expect(written).toEqual([]);
    });
  });

  describe("con el emulador y el parámetro encendido", () => {
    test("se escribe el archivo con el texto, la respuesta completa y el emparejamiento", () => {
      const directory = tempDir();
      const writer = createMexalmLedgerDebugDumpWriter({
        emulator: "true",
        parameterEnabled: true,
        directory,
        now: () => 1_700_000_000_000,
      });

      const path = writer.write({
        text: PAGE_TEXT_LINE,
        response: LEDGER_BLOCKED_RESPONSE,
        matching: [{ lineIndex: 0, gemini: { proposedKind: "SALE", amountCents: "25000" } }],
      });

      expect(path).not.toBeNull();
      const contents = JSON.parse(readFileSync(path as unknown as string, "utf8"));
      // Las TRES cosas que hacen falta para entender qué pasó.
      expect(contents.text).toBe(PAGE_TEXT_LINE);
      expect(contents.response).toEqual(LEDGER_BLOCKED_RESPONSE);
      expect(contents.matching).toEqual([
        { lineIndex: 0, gemini: { proposedKind: "SALE", amountCents: "25000" } },
      ]);
      // Y el status del Ledger, que es lo que explica el bloqueo.
      expect(contents.ledgerStatus).toBe("BLOCKED");
    });

    test("el archivo se llama con el momento de la llamada y sin dos puntos", () => {
      const directory = tempDir();
      const writer = createMexalmLedgerDebugDumpWriter({
        emulator: "true",
        parameterEnabled: true,
        directory,
        now: () => Date.parse("2026-10-21T12:34:56.789Z"),
      });

      const path = writer.write({
        text: PAGE_TEXT_LINE,
        response: LEDGER_BLOCKED_RESPONSE,
        matching: [],
      });

      const name = (path as unknown as string).split(/[\\/]/).pop() as string;
      // Los dos puntos no valen en un nombre de archivo de Windows.
      expect(name).not.toContain(":");
      expect(name.endsWith(".json")).toBe(true);
      expect(name).toBe(mexalmLedgerDebugDumpFileName(Date.parse("2026-10-21T12:34:56.789Z")));
    });

    test("el volcado nunca tumba la callable: si el disco falla, no hay archivo y sigue", () => {
      const writer = createMexalmLedgerDebugDumpWriter({
        emulator: "true",
        parameterEnabled: true,
        directory: tempDir(),
        writeFile: () => {
          throw new Error("disco lleno");
        },
      });

      expect(
        writer.write({ text: PAGE_TEXT_LINE, response: LEDGER_BLOCKED_RESPONSE, matching: [] }),
      ).toBeNull();
    });
  });

  describe("el directorio", () => {
    test("cae en el paquete de Functions, no en el directorio desde el que corra el proceso", () => {
      // Un árbol de mentira: el proceso corre en `repo/functions/sub` y el paquete de Functions
      // está un nivel arriba, en `repo/functions`.
      const functionsPackage = join(tmpdir(), "repo", "functions", "package.json");
      const directory = resolveMexalmLedgerDebugDumpDirectory({
        cwd: join(tmpdir(), "repo", "functions", "sub"),
        readPackageName: (path) => (path === functionsPackage ? "functions" : null),
      });

      expect(directory).toBe(join(tmpdir(), "repo", "functions", ".mexalm-debug"));
    });

    test("si no hay paquete de Functions, el directorio de trabajo es el plan B", () => {
      const cwd = join(tmpdir(), "otro", "sitio");
      const directory = resolveMexalmLedgerDebugDumpDirectory({
        cwd,
        readPackageName: () => null,
      });

      expect(directory).toBe(join(cwd, ".mexalm-debug"));
    });
  });

  describe("con la verificación cruzada de verdad", () => {
    test("el Ledger que bloquea deja un volcado con el texto que se le mandó", async () => {
      const directory = tempDir();
      let sentBody = "";
      const captureFetch: MexalmLedgerFetch = async (_url, init) => {
        sentBody = init.body;
        return { ok: true, status: 200, json: async () => LEDGER_BLOCKED_RESPONSE };
      };
      const crosschecker = createMexalmLedgerCrosschecker({
        client: createMexalmLedgerClient({
          baseUrl: "ledger.invalid",
          token: "token-de-prueba",
          fetchImpl: captureFetch,
        }),
        debugDump: createMexalmLedgerDebugDumpWriter({
          emulator: "true",
          parameterEnabled: true,
          directory,
        }),
      });

      const result = await crosschecker.crosscheck({ operations: OPERATIONS });

      // El bloque dice que el Ledger no leyó la página...
      expect(result?.ledgerStatus).toBe("BLOCKED");
      expect(result?.agreed).toBe(false);
      expect(result?.disagreements).toEqual([]);
      // ...y el volcado tiene lo que se mandó y lo que contestó.
      const files = readdirSync(directory);
      expect(files).toHaveLength(1);
      const contents = JSON.parse(readFileSync(join(directory, files[0] as string), "utf8"));
      expect(contents.text).toBe(JSON.parse(sentBody).text);
      expect(contents.response).toEqual(LEDGER_BLOCKED_RESPONSE);
      expect(contents.matching).toEqual([
        {
          lineIndex: 0,
          gemini: { proposedKind: "SALE", customerName: "Rosa", amountCents: "25000" },
        },
      ]);
    });

    test("sin el volcado encendido no se escribe nada, aunque el Ledger bloquee", async () => {
      const directory = tempDir();
      const crosschecker = createMexalmLedgerCrosschecker({
        client: createMexalmLedgerClient({
          baseUrl: "ledger.invalid",
          token: "token-de-prueba",
          fetchImpl: async () => ({
            ok: true,
            status: 200,
            json: async () => LEDGER_BLOCKED_RESPONSE,
          }),
        }),
        debugDump: createMexalmLedgerDebugDumpWriter({
          emulator: undefined,
          parameterEnabled: true,
          directory,
        }),
      });

      await crosschecker.crosscheck({ operations: OPERATIONS });

      expect(readdirSync(directory)).toEqual([]);
    });
  });
});
