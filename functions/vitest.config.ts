import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    // The emulator-only gate lives in test/firestoreEmulator.gate.ts, outside this glob, and
    // runs explicitly via test:firestore-emulator with FIRESTORE_EMULATOR_HOST set.
    include: ["test/**/*.test.ts"],
  },
});
