import { HttpsError, onCall } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { defineSecret } from "firebase-functions/params";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createInterpretAiHandler } from "./interpretAiRequest";
import { createAuthorizeAiHandler } from "./authorizeAiAction";
import { createAiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import { createFirestoreControlPlaneRepository } from "./ai/firestoreControlPlane";
import { createGeminiTextProvider } from "./ai/geminiTextProvider";

const AI_BOUNDARY = {
  region: "us-south1",
  enforceAppCheck: true,
  maxInstances: 1,
  timeoutSeconds: 15,
  memory: "256MiB",
} as const;

// Server-only Gemini credential. Bound exclusively to interpretAiRequest; never available to
// Android, other callables, logs or the repository.
const geminiApiKey = defineSecret("CUENTIVOAI_GEMINI_API_KEY");

/**
 * Process-level warm-instance edge guard: a bounded cost mitigation, NOT a durable security
 * boundary (cold starts reset it). Caps are fixed in code so the guard never needs a Firestore
 * read to deny. Saturated maps fail closed for AI without touching Firestore.
 */
const aiEdgeGuard = createAiEdgeRequestGuard({
  maxNewRequestsPerMinute: 30,
  maxTrackedUids: 200,
});

const adminApp = initializeApp();

function controlPlaneRepository() {
  return createFirestoreControlPlaneRepository(getFirestore(adminApp));
}

export const interpretAiRequest = onCall(
  { ...AI_BOUNDARY, concurrency: 1, secrets: [geminiApiKey] },
  async (request) => {
    // The secret value is read only here, inside the interpret handler. An empty value means
    // the server configuration is missing: the callable recovers without consuming a claim.
    const apiKey = geminiApiKey.value();
    const provider = apiKey
      ? createGeminiTextProvider({
          apiKey,
          timeoutMillis: 12000,
          onFailure: (failure) => {
            logger.warn("AI_PROVIDER_FAILURE", {
              category: failure.category,
              status: failure.status,
            });
          },
        })
      : null;
    const repository = controlPlaneRepository();
    const result = await createInterpretAiHandler({
      authorize: (input) => repository.authorize(input),
      claimProviderInvocation: (input) => repository.claimProviderInvocation(input),
      provider,
      guard: aiEdgeGuard,
      now: () => Date.now(),
      onRejected: (reason) => {
        logger.warn("AI_REJECTED", { reason });
      },
    })({
      auth: request.auth ? { uid: request.auth.uid, token: request.auth.token } : undefined,
      data: request.data,
    });
    if ("code" in result) {
      throw new HttpsError(result.code, "Request rejected");
    }
    logger.info("AI_INTERPRET_RESULT", { status: result.status });
    return result;
  },
);

export const authorizeAiAction = onCall({ ...AI_BOUNDARY, concurrency: 1 }, async (request) => {
  const result = await createAuthorizeAiHandler(
    controlPlaneRepository(),
    () => Date.now(),
    aiEdgeGuard,
    (reason) => {
      logger.warn("AI_REJECTED", { reason });
    },
  )({
    auth: request.auth ? { uid: request.auth.uid, token: request.auth.token } : undefined,
    data: request.data,
  });
  if ("code" in result) {
    throw new HttpsError(result.code, "Request rejected");
  }
  return result;
});
