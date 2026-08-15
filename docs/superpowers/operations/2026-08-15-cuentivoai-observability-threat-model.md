# CuentivoAI Observability and Threat Model

## Allowed future telemetry vocabulary
request_type, outcome, latency_bucket, input_size_bucket, output_size_bucket, retry_count, schema_result, quota_result, feature_state and server-side cost buckets.

## Explicitly prohibited data
No raw prompts, OCR text, transcript, customer name, product name, email, uid, business identifier, individual amount, image, purchase token, Auth token, App Check token, API key or raw provider response.

## Trust boundaries and controls
Android input is untrusted and draft-only. The gateway requires Auth, App Check, role, entitlement, quota, rate limit and idempotency before provider invocation. Provider output is schema-allow-listed and deterministically validated. Invalid state must fail closed for AI while Gratis and Pro remain available.

## Kill switch
The future server-owned kill switch is global, per user and per capability, denies AI before provider invocation, and is independently testable. AiExperiment is not an authority.
