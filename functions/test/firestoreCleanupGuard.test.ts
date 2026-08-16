import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

const SOURCE = readFileSync(resolve(process.cwd(), "test/firestoreEmulator.gate.ts"), "utf8");

describe("firestore emulator gate cleanup scope", () => {
  test("never lists collections or documents", () => {
    expect(SOURCE).not.toContain("listDocuments");
    expect(SOURCE).not.toContain("listCollections");
  });

  test("every delete targets an exact document path", () => {
    const lines = SOURCE.split("\n").filter((line) => line.includes(".delete()"));
    expect(lines.length).toBeGreaterThan(0);
    for (const line of lines) {
      expect(
        line,
        `delete without an exact document path: ${line.trim()}`,
      ).toMatch(/\.doc\(.*\)\.delete\(\)/);
    }
  });

  test("ledger counting is scoped to the gate uid, not the whole collection", () => {
    expect(SOURCE).not.toMatch(/collection\("aiRequestLedger"\)\.count/);
    expect(SOURCE).toContain('where("uid"');
  });

  test("uses a unique per-run uid", () => {
    expect(SOURCE).toContain("randomUUID");
  });
});
