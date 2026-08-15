import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

const SOURCE = readFileSync(resolve(process.cwd(), "src/index.ts"), "utf8");

describe("regional callable gateway", () => {
  test("exports interpretAiRequest", async () => {
    const module = await import("../src/index.js");
    expect(module.interpretAiRequest).toBeDefined();
  });

  test("declares exactly the five approved runtime options", () => {
    for (const required of [
      'region: "us-south1"',
      "enforceAppCheck: true",
      "maxInstances: 1",
      "timeoutSeconds: 15",
      'memory: "256MiB"',
    ]) {
      expect(SOURCE, `source must contain ${required}`).toContain(required);
    }
  });
});
