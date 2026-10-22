import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import * as logger from "firebase-functions/logger";
import { defineBoolean, defineSecret, defineString } from "firebase-functions/params";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createInterpretAiHandler } from "./interpretAiRequest.js";
import { createExtractLedgerPageHandler } from "./extractLedgerPage.js";
import { createAuthorizeAiHandler } from "./authorizeAiAction.js";
import { createVerifyAiSubscriptionHandler } from "./verifyAiSubscription.js";
import { createGetAiAvailabilityHandler } from "./getAiAvailability.js";
import { createWarmMexalmLedgerHandler, MexalmLedgerWarmLimiter } from "./warmMexalmLedger.js";
import { createPlayRtdnHandler } from "./handlePlayRtdnNotification.js";
import { createAiEdgeRequestGuard } from "./ai/edgeRequestGuard";
import { createFirestoreControlPlaneRepository } from "./ai/firestoreControlPlane";
import { createGeminiTextProvider } from "./ai/geminiTextProvider";
import { createGeminiLedgerProvider } from "./ai/geminiLedgerProvider";
import { createMexalmLedgerClient } from "./ai/mexalmLedgerClient";
import { createMexalmLedgerCrosschecker } from "./ai/mexalmCrosscheck";
import { createMexalmLedgerDebugDumpWriter } from "./ai/mexalmLedgerDebugDump";
import { createMexalmLedgerWarmer } from "./ai/mexalmLedgerWarm";
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
 * Verificación cruzada contra el Ledger de MexaLM. APAGADA por defecto.
 *
 * La bandera es un parámetro de Functions, no un secreto: es pública, se cambia sin redeploy y su
 * valor tiene que ser exactamente "true" para encenderla. Apagada —como vive hoy— la respuesta de
 * `extractLedgerPage` es byte a byte la de siempre: no se llama al Ledger y no aparece la llave
 * `mexalmCrosscheck` en la respuesta.
 */
const mexalmLedgerCrosscheck = defineBoolean("MEXALM_LEDGER_CROSSCHECK", { default: false });

/**
 * Dónde vive el Ledger de MexaLM. No es secreto: es una dirección. Sin ella, la verificación
 * cruzada no se puede encender aunque la bandera esté en "true".
 */
const mexalmLedgerUrl = defineString("MEXALM_LEDGER_URL", { default: "" });

/**
 * Server-only bearer token del Ledger de MexaLM. Bound exclusively to extractLedgerPage; never
 * available to Android, other callables, logs or the repository.
 *
 * OJO AL DESPLEGAR: un secreto declarado en `secrets` tiene que EXISTIR en Secret Manager antes
 * del deploy, o el deploy falla. Crear antes: `firebase functions:secrets:set MEXALM_LEDGER_TOKEN`.
 */
const mexalmLedgerToken = defineSecret("MEXALM_LEDGER_TOKEN");

/**
 * Volcado de diagnóstico de la verificación cruzada. APAGADO por defecto y **SÓLO en el emulador**.
 *
 * Cuando el Ledger de MexaLM no puede certificar la página (`BLOCKED`), el `ledgerStatus` del
 * bloque dice que pasó pero no dice POR QUÉ. Con este parámetro encendido, el emulador escribe en
 * `functions/.mexalm-debug/<timestamp>.json` el texto exacto que se mandó, la respuesta completa
 * del Ledger y el emparejamiento renglón por renglón.
 *
 * La regla dura: **en producción este parámetro no hace nada.** El volcado exige las DOS cosas —
 * el emulador (`FUNCTIONS_EMULATOR === "true"`) y el parámetro en `true`— y la decisión vive en un
 * solo lugar, `isMexalmLedgerDebugDumpEnabled`. El archivo lleva el texto de la libreta de una
 * clienta: por eso vive en un directorio ignorado por Git y nunca se registra.
 */
const mexalmLedgerDebugDump = defineBoolean("MEXALM_LEDGER_DEBUG_DUMP", { default: false });

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

/**
 * Reading a photographed notebook page. Shares the AI boundary, the secret, the edge guard, the
 * control plane quota and the durable claim with interpretAiRequest on purpose: an image costs far
 * more than a sentence, so it must not get its own unmetered door into the provider.
 *
 * Longer timeout and more memory than the text path: a page carries an image in the request body.
 */
export const extractLedgerPage = onCall(
  {
    ...AI_BOUNDARY,
    timeoutSeconds: 60,
    memory: "512MiB",
    concurrency: 1,
    secrets: [geminiApiKey, mexalmLedgerToken],
  },
  async (request) => {
    const apiKey = geminiApiKey.value();
    const provider = apiKey
      ? createGeminiLedgerProvider({
          apiKey,
          timeoutMillis: 45000,
          onFailure: (failure) => {
            logger.warn("AI_PROVIDER_FAILURE", {
              category: failure.category,
              status: failure.status,
            });
          },
        })
      : null;
    // La verificación cruzada se arma sólo si la bandera está encendida Y hay con quién hablar.
    // Apagada, `crosscheck` es null y el handler devuelve la respuesta de siempre, sin una llave
    // de más.
    const ledgerUrl = mexalmLedgerUrl.value();
    const ledgerToken = mexalmLedgerToken.value();
    const crosscheck =
      mexalmLedgerCrosscheck.value() && ledgerUrl.length > 0 && ledgerToken.length > 0
        ? createMexalmLedgerCrosschecker({
            client: createMexalmLedgerClient({
              baseUrl: ledgerUrl,
              token: ledgerToken,
              onFailure: (failure) => {
                // Categoría y código HTTP. NUNCA el texto de la página: son nombres de clientas
                // y montos, y un log no es el lugar de eso.
                logger.warn("MEXALM_LEDGER_FAILURE", {
                  category: failure.category,
                  status: failure.status,
                });
              },
            }),
            // El volcado se arma siempre, pero sólo escribe en el emulador con el parámetro
            // encendido: la decisión está dentro del escritor, no en este `if`.
            debugDump: createMexalmLedgerDebugDumpWriter({
              emulator: process.env.FUNCTIONS_EMULATOR,
              parameterEnabled: mexalmLedgerDebugDump.value(),
            }),
          })
        : null;
    const repository = controlPlaneRepository();
    const result = await createExtractLedgerPageHandler({
      authorize: (input) => repository.authorize(input),
      claimProviderInvocation: (input) => repository.claimProviderInvocation(input),
      provider,
      guard: aiEdgeGuard,
      now: () => Date.now(),
      crosscheck,
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
    // Never log the operations themselves: they carry customer names and amounts.
    logger.info("AI_LEDGER_RESULT", {
      status: result.status,
      operationCount: result.operations?.length ?? 0,
      pageIncomplete: result.pageIncomplete,
    });
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

/**
 * Despertar el Ledger de MexaLM.
 *
 * El Ledger es un Cloud Run con `min-instances=0`: se apaga solo y tarda entre 30 y 60 s en
 * volver. Abrir la app con CuentivoAI lo despierta por adelantado, para que la primera lectura de
 * la libreta no espere a que la instancia levante.
 *
 * Tres cosas y ninguna negociable:
 *
 * - **Sólo para cuentas con CuentivoAI.** La suscripción se lee del mismo documento que escribe
 *   `verifyAiSubscription`, con la misma regla de siempre: habilitada y sin expirar.
 * - **Con la bandera apagada no hace nada.** Es la misma bandera de la verificación cruzada: sin
 *   ella el Ledger no se usa, y despertarlo sería pagar por nada.
 * - **Un despertar por cuenta cada 5 minutos.** En memoria, en la instancia: el límite evita el
 *   ciclón de "abrir y cerrar la app"; no protege un recurso scarce.
 *
 * El secreto del Ledger NO se liga aquí: un health check no lo necesita, y el secreto sigue
 * ligado a una sola callable (`extractLedgerPage`).
 */
const mexalmLedgerWarmLimiter = new MexalmLedgerWarmLimiter();

export const warmMexalmLedger = onCall({ ...AI_BOUNDARY, concurrency: 1 }, async (request) => {
  const result = await createWarmMexalmLedgerHandler({
    isEnabled: () => mexalmLedgerCrosscheck.value(),
    ledgerUrl: () => mexalmLedgerUrl.value(),
    readEntitlement: (uid) => controlPlaneRepository().readEntitlement(uid),
    warm: () => {
      createMexalmLedgerWarmer({ baseUrl: mexalmLedgerUrl.value() }).warm();
    },
    now: () => Date.now(),
    limiter: mexalmLedgerWarmLimiter,
  })({
    auth: request.auth ? { uid: request.auth.uid, token: request.auth.token } : undefined,
    data: request.data,
  });
  if ("code" in result) {
    throw new HttpsError(result.code, "Authentication required");
  }
  // El resultado y nada más: ni el uid, ni la dirección del Ledger, ni la respuesta del health
  // check. Un log es un destino público.
  logger.info("MEXALM_LEDGER_WARM", { status: result.status, reason: result.reason });
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
        // TOKEN_NOT_BOUND means a real Play event arrived for a purchase this backend never
        // verified, so there is no uid to act on and nothing revokes. Retrying cannot help —
        // no replay creates a binding — but it must be visible: a rising count means purchases
        // are landing while verification is broken. Reason only; never uid or token.
        // LEGACY_SOURCE_UNRESOLVED joins it: an entitlement written before source-token tracking
        // cannot be revoked from a notification alone, so those events need an explicit
        // reconciliation rather than silence.
        if (reason === "TOKEN_NOT_BOUND" || reason === "LEGACY_SOURCE_UNRESOLVED") {
          logger.warn("PLAY_RTDN_UNRESOLVED", { reason });
          return;
        }
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
