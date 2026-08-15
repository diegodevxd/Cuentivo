import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createInterpretAiHandler } from "./interpretAiRequest";

export const interpretAiRequest = onCall(
  {
    region: "us-south1",
    enforceAppCheck: true,
    maxInstances: 1,
    timeoutSeconds: 15,
    memory: "256MiB",
  },
  (request) => {
    const result = createInterpretAiHandler({
      auth: request.auth ? { uid: request.auth.uid } : undefined,
      data: request.data,
    });
    if ("code" in result) {
      throw new HttpsError(result.code, "Request rejected");
    }
    return result;
  },
);
