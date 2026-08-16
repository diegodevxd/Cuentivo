import { defineConfig } from "vitest/config";

// Dedicated config for the emulator-only concurrency gate. The regular suite
// (vitest.config.ts) never includes this file; the gate runs explicitly via
// test:firestore-emulator, which requires FIRESTORE_EMULATOR_HOST.
export default defineConfig({
  test: {
    environment: "node",
    include: ["test/firestoreEmulator.gate.ts"],
  },
});
