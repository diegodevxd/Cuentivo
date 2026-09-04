import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import * as logger from "firebase-functions/logger";
import { defineSecret } from "firebase-functions/params";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createInterpretAiHandler } from "./interpretAiRequest.js";
import { createAuthorizeAiHandler } from "./authorizeAiAction.js";
import { createVerifyAiSubscriptionHandler } from "./verifyAiSubscription.js";
import { createGetAiAvailabilityHandler } from "./getAiAvailability.js";
import { createPlayRtdnHandler } from "./handlePlayRtdnNotification.js";
import { createAiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import { createFirestoreControlPlaneRepository } from "./ai/firestoreControlPlane";
import { createGeminiTextProvider } from "./ai/geminiTextProvider";
import { createDefaultPlaySubscriptionApi } from "./ai/playSubscriptionVerifier";
import { createFirestoreSubscriptionEntitlementRepository } from "./ai/subscriptionEntitlementRepository";

const AI_BOUNDARY = {
  region: "us-south1",
  // Cloud Run ingress must stay publicly invocable: Firebase Auth and App Check are enforced
  // inside each handler, never at the invoker layer. Declared here so every redeploy restores
  // the binding instead of depending on console state, which is how verifyAiSubscription lost it.
  invoker: "public",
  enforceAppCheck: true,
  maxInstances: 1,
  timeoutSeconds: 15,
  memory: "256MiB",
} as const;

const PACKAGE_NAME = "com.cuentivo.app";

// Server-only Gemini credential. Bound exclusively to interpretAiRequest; never available to
// Android, other callables, logs or the repository.
const geminiApiKey = defineSecret("CUENTIVOAI_GEMINI_API_KEY");

// Server-only Google Play service account credential. Bound exclusively to verifyAiSubscription;
// never available to Android, other callables, logs or the repository.
const playServiceAccountKey = defineSecret("CUENTIVOAI_PLAY_SERVICE_ACCOUNT_KEY");

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

function subscriptionEntitlementRepository() {
  return createFirestoreSubscriptionEntitlementRepository(getFirestore(adminApp));
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
      onProviderInvocation: () => {
        logger.info("AI_PROVIDER_INVOCATION");
      },
      onProviderLatency: (durationMillis) => {
        logger.info("AI_PROVIDER_LATENCY", { durationMillis });
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

export const getAiAvailability = onCall({ ...AI_BOUNDARY, concurrency: 1 }, async (request) => {
  const result = await createGetAiAvailabilityHandler({
    getAvailability: () => controlPlaneRepository().getAvailability(),
    onRejected: (reason) => {
      logger.warn("AI_AVAILABILITY_REJECTED", { reason });
    },
  })({
    auth: request.auth ? { uid: request.auth.uid, token: request.auth.token } : undefined,
    data: request.data,
  });
  if ("code" in result) {
    throw new HttpsError(result.code, "Authentication required");
  }
  return result;
});

export const verifyAiSubscription = onCall(
  { ...AI_BOUNDARY, concurrency: 1, secrets: [playServiceAccountKey] },
  async (request) => {
    const serviceAccountJson = playServiceAccountKey.value();
    const result = await createVerifyAiSubscriptionHandler({
      playApi: createDefaultPlaySubscriptionApi({ serviceAccountJson }),
      repository: subscriptionEntitlementRepository(),
      packageName: PACKAGE_NAME,
      now: () => Date.now(),
      onRejected: (reason) => {
        logger.warn("AI_REJECTED", { reason });
      },
    })({
      auth: request.auth ? { uid: request.auth.uid, token: request.auth.token } : undefined,
      data: request.data,
    });
    if ("code" in result) {
      throw new HttpsError(result.code, "Subscription verification failed");
    }
    return result;
  },
);

export const handlePlayRtdnNotification = onMessagePublished(
  {
    topic: "play-rtdn-cuentivo",
    region: "us-south1",
    maxInstances: 1,
    timeoutSeconds: 30,
    memory: "256MiB",
    retry: true,
    secrets: [playServiceAccountKey],
  },
  async (event) => {
    const serviceAccountJson = playServiceAccountKey.value();
    const handler = createPlayRtdnHandler({
      playApi: createDefaultPlaySubscriptionApi({ serviceAccountJson }),
      repository: subscriptionEntitlementRepository(),
      packageName: PACKAGE_NAME,
      now: () => Date.now(),
      onIgnored: (reason) => {
        logger.info("PLAY_RTDN_IGNORED", { reason });
      },
      onProcessed: (details) => {
        logger.info("PLAY_RTDN_PROCESSED", {
          enabled: details.enabled,
          expiresAtEpochMillis: details.expiresAtEpochMillis,
        });
      },
    });
    await handler(event.data.message);
  },
);
