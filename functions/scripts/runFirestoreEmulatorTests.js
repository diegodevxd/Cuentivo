// Runs the Firestore-emulator-only concurrency gate. Refuses to run without
// FIRESTORE_EMULATOR_HOST so no command can ever touch production Firestore.
"use strict";

const { spawnSync } = require("child_process");
const path = require("path");

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  console.error(
    "FIRESTORE_EMULATOR_HOST is not set. Refusing to run: the emulator-only concurrency gate must never target production Firestore.",
  );
  process.exit(1);
}

process.env.GCLOUD_PROJECT = "demo-cuentivoai-phase2a";
process.env.FIRESTORE_PROJECT_ID = "demo-cuentivoai-phase2a";

const vitest = path.join(__dirname, "..", "node_modules", "vitest", "vitest.mjs");
const result = spawnSync(
  process.execPath,
  [vitest, "run", "--config", "vitest.gate.config.ts"],
  { stdio: "inherit", env: process.env },
);
process.exit(result.status === null ? 1 : result.status);
