package com.cuentivo.app

/**
 * The region every callable of this app lives in.
 *
 * One constant, shared by the gateways and by the emulator wiring, for a reason that is not
 * tidiness: `FirebaseFunctions.getInstance(region)` returns **one instance per region**, so the
 * emulator can only be pointed at the instance the app actually calls. Two literals are exactly
 * how that drifts, and the symptom (a debug build that talks to the emulator for one callable and
 * to production for another) is miserable to debug.
 *
 * The gateways that use it: `FirebaseCallableAiGateway` (`interpretAiRequest`),
 * `FirebaseAiAvailabilityGateway` (`getAiAvailability`), `LedgerExtractionGateway`
 * (`extractLedgerPage`) and `FirebaseCallableSubscriptionVerificationGateway`
 * (`verifyAiSubscription`).
 */
const val FUNCTIONS_REGION = "us-south1"
