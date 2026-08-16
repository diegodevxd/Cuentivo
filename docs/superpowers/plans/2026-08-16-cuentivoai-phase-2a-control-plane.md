# CuentivoAI Fase 2A Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir un control plane server-side, multi-provider y fail-closed para entitlement, cuota, rate limit e idempotencia, sin llamar a modelos ni mutar datos financieros.

**Architecture:** Una callable nueva `authorizeAiAction` valida Auth/App Check y delega a un servicio puro; éste obtiene su estado mediante un repositorio Firestore transaccional ejecutado únicamente con Admin SDK. La configuración ausente, los proveedores apagados, el entitlement ausente o expirado y cualquier error de almacenamiento deniegan la acción. `interpretAiRequest` no cambia.

**Tech Stack:** Node 22, TypeScript, Firebase Functions v2, Firebase Admin SDK, Cloud Firestore Native Standard, Vitest.

## Global Constraints

- Base objetivo: Firestore `(default)`, Standard, `northamerica-south1`; Functions permanecen en `us-south1`.
- Sólo Functions/Admin SDK accede a documentos `aiControl`, `aiEntitlements`, `aiUsage`, `aiRateWindows` y `aiRequestLedger`; las reglas actuales ya los niegan por defecto al cliente y no deben ampliarse.
- Ninguna API key, URL de proveedor, prompt, texto, imagen, audio, cliente, producto, monto ni secreto se persiste, devuelve o registra.
- DeepSeek y Gemini son flags independientes (`DEEPSEEK`, `GEMINI`); ambos apagados es seguro y esperado.
- No modificar `interpretAiRequest`, Room, Firestore financiero, ventas, pagos, saldos, sync, Billing, versiones, Android UI ni AAB.
- Sin deploy remoto en esta fase: primero evidencia local, auditoría Sol y aprobación explícita de deploy.

---

### Task 1: Contrato del control plane y política pura

**Files:**
- Create: `functions/src/ai/controlPlane.ts`
- Create: `functions/test/controlPlane.test.ts`

**Interfaces:**

```ts
export type AiProviderId = "DEEPSEEK" | "GEMINI";
export type AiCapability = "TEXT";
export type AiAuthorizationDecision =
  | "AI_DISABLED"
  | "AI_ENTITLEMENT_REQUIRED"
  | "AI_RATE_LIMITED"
  | "AI_QUOTA_EXHAUSTED"
  | "AI_AUTHORIZED";

export type AiControlConfig = Readonly<{
  enabled: boolean;
  providers: Readonly<Record<AiProviderId, boolean>>;
  monthlyActionLimit: number;
  perMinuteLimit: number;
}>;

export type AiEntitlement = Readonly<{ enabled: boolean; expiresAtEpochMillis: number }>;
export type AiUsage = Readonly<{ usedActions: number }>;
export type AiRateWindow = Readonly<{ usedActions: number }>;
export type AuthorizeAiInput = Readonly<{ uid: string; requestId: string; capability: AiCapability; nowEpochMillis: number }>;
export type AuthorizeAiResult = Readonly<{ decision: AiAuthorizationDecision; provider: AiProviderId | null; fallbackUsed: false }>;
```

- [ ] **Step 1: Write failing policy tests**

```ts
expect(decideAuthorization(input, disabledConfig, entitlement, usage, rate)).toEqual({
  decision: "AI_DISABLED", provider: null, fallbackUsed: false,
});
expect(decideAuthorization(input, deepSeekOnlyConfig, validEntitlement, usage, rate)).toEqual({
  decision: "AI_AUTHORIZED", provider: "DEEPSEEK", fallbackUsed: false,
});
```

Cover: absent configuration represented as disabled; both providers disabled; expired/missing entitlement; quota exactly at limit; rate exactly at limit; DeepSeek-only; Gemini-only; both enabled chooses DeepSeek; and invalid numeric limits deny as `AI_DISABLED`.

- [ ] **Step 2: Run the new test file**

Run from `functions/`: `npm test -- --run test/controlPlane.test.ts`

Expected: FAIL because `controlPlane.ts` does not exist.

- [ ] **Step 3: Implement the pure allow-list policy**

`decideAuthorization` must never throw, must choose exactly one text provider (`DEEPSEEK` before `GEMINI`), and must return no provider for every denial. It must reject non-positive/non-safe-integer limits and use `nowEpochMillis < expiresAtEpochMillis` for entitlement validity.

- [ ] **Step 4: Verify the policy test**

Run from `functions/`: `npm test -- --run test/controlPlane.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add functions/src/ai/controlPlane.ts functions/test/controlPlane.test.ts
git commit -m "feat(ai): add provider agnostic authorization policy"
```

### Task 2: Repositorio Firestore transaccional y ledger idempotente

**Files:**
- Modify: `functions/package.json`
- Modify: `functions/package-lock.json`
- Create: `functions/src/ai/firestoreControlPlane.ts`
- Create: `functions/test/firestoreControlPlane.test.ts`

**Interfaces:**

```ts
export interface AiControlPlaneRepository {
  authorize(input: AuthorizeAiInput): Promise<AuthorizeAiResult>;
}
export function createFirestoreControlPlaneRepository(
  firestore: FirebaseFirestore.Firestore,
): AiControlPlaneRepository;
```

Document paths are fixed and server-owned:

```text
aiControl/global
aiEntitlements/{uid}
aiUsage/{uid}_{YYYYMM}
aiRateWindows/{uid}_{YYYYMMDDHHmm}
aiRequestLedger/{uid}_{requestId}
```

The global configuration has exactly `enabled`, `providers`, `monthlyActionLimit`, `perMinuteLimit`. Entitlement has exactly `enabled`, `expiresAtEpochMillis`. Usage/rate documents store only `usedActions` and server timestamps. A ledger is created only for `AI_AUTHORIZED` and stores request metadata only: `uid`, `requestId`, `decision`, `provider`, `fallbackUsed`, `createdAtEpochMillis`; never input payload or provider output. Denials create no document, preventing disabled, non-entitled, quota-exhausted and rate-limited callers from creating Firestore write cost.

- [ ] **Step 1: Add failing transactional tests with an injected Firestore-like transaction fake**

Tests must model two concurrent calls with the same uid/requestId and assert one ledger decision and one quota/rate increment; a repeat reads and returns the original result. Model concurrent distinct request IDs at quota/rate boundaries and assert no limit is exceeded. The transaction must read global config first, then entitlement; a missing/disabled/malformed config must stop after the config read, and a missing/expired entitlement must stop before any ledger, usage or rate read. Model those cases plus quota exhaustion and rate limiting with zero writes to usage, rate and ledger documents.

- [ ] **Step 2: Run tests to verify failure**

Run from `functions/`: `npm test -- --run test/firestoreControlPlane.test.ts`

Expected: FAIL because repository/dependency do not exist.

- [ ] **Step 3: Add Firebase Admin SDK and lockfile**

Add `firebase-admin` as a runtime dependency using npm in `functions/`; commit the resulting package lock. Do not add credential files, service-account JSON, dotenv secrets or provider SDKs.

- [ ] **Step 4: Implement one Firestore transaction per authorization**

Within `runTransaction`, read global config first and return `AI_DISABLED` without further reads if it is missing, malformed, disabled or has no enabled provider. Then read entitlement and return `AI_ENTITLEMENT_REQUIRED` without reading ledger/counters if it is missing or expired. Only then read the ledger; a matching authorized ledger returns its stored result without counter reads or increments. A new eligible authorization reads month usage and minute rate window and calls `decideAuthorization`. Only `AI_AUTHORIZED` writes a ledger and increments both usage and rate by exactly one in the same transaction. Every denial returns without any write. Any malformed stored document, transaction error or ownership mismatch returns `AI_DISABLED` and must not increment counters or create a ledger.

- [ ] **Step 5: Verify repository tests and all Functions tests**

Run:

```bash
npm test
npm run lint
npm run build
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add functions/package.json functions/package-lock.json functions/src/ai/firestoreControlPlane.ts functions/test/firestoreControlPlane.test.ts
git commit -m "feat(ai): add transactional authorization control plane"
```

### Task 3: Callable fail-closed boundary

**Files:**
- Modify: `functions/src/contract.ts`
- Create: `functions/src/authorizeAiAction.ts`
- Modify: `functions/src/index.ts`
- Create: `functions/test/authorizeAiAction.test.ts`

**Interfaces:**

```ts
export type AuthorizeAiActionRequest = Readonly<{
  contractVersion: 1;
  requestId: string;
  action: "authorize_text";
}>;
export type AuthorizeAiActionResponse = Readonly<{
  contractVersion: 1;
  requestId: string;
  decision: AiAuthorizationDecision;
  provider: AiProviderId | null;
  fallbackUsed: false;
}>;
```

- [ ] **Step 1: Write failing boundary tests**

Tests must assert unauthenticated → callable `unauthenticated`; extra/missing/wrong contract fields → `invalid-argument`; valid authenticated request delegates with authenticated uid and a server clock; repository failure → `AI_DISABLED`; valid denied/authorized results mirror exactly the response contract. Assert no logger call and no dependency on provider SDK.

- [ ] **Step 2: Run the boundary test**

Run from `functions/`: `npm test -- --run test/authorizeAiAction.test.ts`

Expected: FAIL because the callable boundary is absent.

- [ ] **Step 3: Implement validation and callable export**

Use the existing UUID/allow-list validation pattern; do not relax `interpret_text`. Export `authorizeAiAction` from `functions/src/index.ts` as v2 callable with the same `us-south1`, `enforceAppCheck: true`, `maxInstances: 1`, `timeoutSeconds: 15`, `memory: "256MiB"` boundary. Instantiate Admin SDK only inside the new callable composition root; no Admin SDK is imported by pure policy modules.

- [ ] **Step 4: Verify all Functions checks**

Run:

```bash
npm test
npm run lint
npm run build
```

Expected: all pass, including existing `interpretAiRequest` regressions.

- [ ] **Step 5: Commit**

```bash
git add functions/src/contract.ts functions/src/authorizeAiAction.ts functions/src/index.ts functions/test/authorizeAiAction.test.ts
git commit -m "feat(ai): expose fail closed authorization callable"
```

### Task 4: Auditoría local y evidencia de no regresión

**Files:**
- Create: `docs/superpowers/handoffs/2026-08-16-cuentivoai-fase-2a-worker-report.md` only if that directory is already intentionally untracked; never stage another user handoff.

- [ ] **Step 1: Confirm scope**

Run:

```bash
git diff e12255e..HEAD -- functions app firestore.rules firebase.json
git diff --check
```

Expected: no Android/financial/rules mutation and no whitespace error.

- [ ] **Step 2: Run complete local verification**

Run from repository root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Run from `functions/`:

```bash
npm test
npm run lint
npm run build
```

Expected: all pass. Report exact command outputs, commit IDs, changed files and any limitation. Do not deploy and do not install an APK in this task.

- [ ] **Step 3: Stop for Sol audit**

Do not declare Phase 2A DONE. Sol must inspect the diff, repeat the relevant tests, review transaction semantics and obtain an adversarial GLM review before a DONE decision. A separate owner authorization is required for deploy or device test.
