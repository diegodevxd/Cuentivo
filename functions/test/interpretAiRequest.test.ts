import { describe, expect, test } from "vitest";
import { createInterpretAiHandler } from "../src/interpretAiRequest.js";

const VALID_DATA = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text",
} as const;

describe("createInterpretAiHandler", () => {
  test("without auth returns unauthenticated", () => {
    expect(createInterpretAiHandler({ data: VALID_DATA })).toEqual({ code: "unauthenticated" });
  });

  test("with auth and extra field returns invalid-argument", () => {
    expect(
      createInterpretAiHandler({
        auth: { uid: "test-user" },
        data: { ...VALID_DATA, text: "hola" },
      }),
    ).toEqual({ code: "invalid-argument" });
  });

  test("with auth and valid payload returns only the AI_NOT_CONFIGURED response", () => {
    expect(
      createInterpretAiHandler({ auth: { uid: "test-user" }, data: VALID_DATA }),
    ).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_NOT_CONFIGURED",
    });
  });
});
