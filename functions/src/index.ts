import { HttpsError, onCall } from "firebase-functions/v2/https";
import { initializeApp, getApp, getApps } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createInterpretAiHandler } from "./interpretAiRequest";
import { createAuthorizeAiHandler } from "./authorizeAiAction";
import { createFirestoreControlPlaneRepository } from "./ai/firestoreControlPlane";

const AI_BOUNDARY = {
  region: "us-south1",
  enforceAppCheck: true,
  maxInstances: 1,
  timeoutSeconds: 15,
  memory: "256MiB",
} as const;

function controlPlaneRepository() {
  const app = getApps().length > 0 ? getApp() : initializeApp();
  return createFirestoreControlPlaneRepository(getFirestore(app));
}

export const interpretAiRequest = onCall(AI_BOUNDARY, (request) => {
  const result = createInterpretAiHandler({
    auth: request.auth ? { uid: request.auth.uid } : undefined,
    data: request.data,
  });
  if ("code" in result) {
    throw new HttpsError(result.code, "Request rejected");
  }
  return result;
});

export const authorizeAiAction = onCall(AI_BOUNDARY, async (request) => {
  const result = await createAuthorizeAiHandler(controlPlaneRepository(), () => Date.now())({
    auth: request.auth ? { uid: request.auth.uid } : undefined,
    data: request.data,
  });
  if ("code" in result) {
    throw new HttpsError(result.code, "Request rejected");
  }
  return result;
});
