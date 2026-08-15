# CuentivoAI Fase 1A: Gateway Local Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear un callable Cloud Functions v2 local, autenticado y fail-closed que sólo responda `AI_NOT_CONFIGURED` al contrato v1 válido.

**Architecture:** `contract.ts` conserva tipos y validación pura sin Firebase. `interpretAiRequest.ts` adapta esa decisión a errores callable. `index.ts` declara el único endpoint v2 con App Check obligatorio. No hay proveedor, IO externo, Firestore, Secret Manager ni cliente Android.

**Tech Stack:** Node.js 22 runtime objetivo, TypeScript, Firebase Cloud Functions v2 callable, Vitest y Emulator Suite local.

## Global Constraints

- Trabajar sólo en `diegodevxd/feature-cuentivo-ai`; baseline: `v1.16.6-31` / `e40eb4e04404a63f9fa0d15e330834780961cd63`.
- Prohibidos deploy, cambios a `.firebaserc`, recursos remotos, proveedor IA, HTTP saliente, Secret Manager, `.env`, credenciales, secretos, Firestore Admin, Room, Billing, Sync y WorkManager.
- No modificar Android, reglas Firestore, `applicationId`, `versionCode`, `versionName` ni la baseline.
- El único payload válido es `{ contractVersion: 1, requestId: UUID, action: "interpret_text" }`; campos extra se rechazan.
- El endpoint usa `enforceAppCheck: true`, exige Auth y responde sólo `{ contractVersion: 1, requestId, status: "AI_NOT_CONFIGURED" }`.
- No registrar uid, requestId, contenido, montos, secretos ni respuestas crudas.
- No rastrear `functions/lib/`, `functions/node_modules/`, `functions/coverage/`, APK/AAB, `.firebase/`, ni `docs/superpowers/handoffs/`. Nunca usar `git add -A`.

## Interfaces obligatorias

```ts
export type InterpretAiRequest = Readonly<{ contractVersion: 1; requestId: string; action: "interpret_text" }>;
export type InterpretAiResponse = Readonly<{ contractVersion: 1; requestId: string; status: "AI_NOT_CONFIGURED" }>;
export type ContractValidation = Readonly<{ ok: true; value: InterpretAiRequest }> | Readonly<{ ok: false; reason: "invalid-request" }>;
export function validateInterpretAiRequest(input: unknown): ContractValidation;
export function createNotConfiguredResponse(request: InterpretAiRequest): InterpretAiResponse;
export type CallableLikeRequest = Readonly<{ auth?: Readonly<{ uid: string }>; data: unknown }>;
export type CallableFailure = Readonly<{ code: "unauthenticated" | "invalid-argument" }>;
export function createInterpretAiHandler(request: CallableLikeRequest): InterpretAiResponse | CallableFailure;
```

`requestId` acepta sólo `/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i` y longitud 36. El adaptador Firebase convierte `CallableFailure` a `new HttpsError(result.code, "Request rejected")` sin detalles internos.

### Task 1: Scaffold compilable y reversible

**Files:** Create `functions/package.json`, `functions/tsconfig.json`, `functions/vitest.config.ts`, `functions/src/index.ts`; Modify `.gitignore`, `firebase.json`.

**Produces:** `npm test`, `npm run lint`, `npm run build`; salida `lib/` ignorada.

- [ ] Escribir `functions/test/config.test.ts` que haga `await expect(import("../src/index.js")).resolves.toBeDefined()`; ejecutar `npm test -- --run test/config.test.ts` desde `functions` y comprobar RED porque aún no existe paquete ni entrypoint.
- [ ] Crear `package.json` CommonJS, `main: "lib/index.js"`, `engines: { "node": "22" }`; scripts exactos: `build: tsc -p tsconfig.json`, `test: vitest run`, `test:watch: vitest`, `lint: tsc -p tsconfig.json --noEmit`. Instalar `firebase-functions` como producción y `typescript`, `vitest`, `@types/node` como desarrollo. No instalar `firebase-admin`, SDK IA, axios ni librería de secretos.
- [ ] Crear `tsconfig.json` estricto con `target: ES2022`, `module: CommonJS`, `rootDir: src`, `outDir: lib`, `esModuleInterop`, `skipLibCheck`, `noUncheckedIndexedAccess`. Configurar Vitest para `test/**/*.test.ts` bajo node. Añadir `/functions/lib/`, `/functions/node_modules/`, `/functions/coverage/` a `.gitignore`; añadir sólo `functions: { port: 5001 }` a `firebase.json.emulators`, preservando Auth/Firestore/UI; crear `src/index.ts` con `export {};`.
- [ ] Ejecutar GREEN: `npm test -- --run test/config.test.ts`, `npm run lint`, `npm run build`; borrar el test temporal y confirmar exit code 0.
- [ ] Commit: `git add -- .gitignore firebase.json functions/package.json functions/package-lock.json functions/tsconfig.json functions/vitest.config.ts functions/src/index.ts` y `git commit -m "build(ai): scaffold local functions gateway"`.

### Task 2: Contrato puro allow-list

**Files:** Create `functions/src/contract.ts`; Test `functions/test/contract.test.ts`.

**Produces:** `validateInterpretAiRequest` y `createNotConfiguredResponse` con las firmas exactas.

- [ ] Escribir RED: fixture `{ contractVersion: 1, requestId: "123e4567-e89b-12d3-a456-426614174000", action: "interpret_text" }` debe validar y devolver respuesta fija; `null`, `{}`, versión 2, `action: "create_sale"`, UUID inválido y campo extra `text` deben devolver `{ ok: false, reason: "invalid-request" }`. Ejecutar `npm test -- --run test/contract.test.ts`; debe fallar por módulo inexistente.
- [ ] Implementar `contract.ts` sin imports Firebase, con `UUID_V1_TO_V5` y `ALLOWED_KEYS = ["contractVersion", "requestId", "action"] as const`. Aceptar sólo objeto plano no nulo cuyas claves sean exactamente esas tres; exigir versión/acción/requestId. Todo fallo retorna la falla fija: no lanzar, normalizar, generar IDs, almacenar ni loguear.
- [ ] Ejecutar GREEN: `npm test -- --run test/contract.test.ts`, `npm run lint`, `npm run build`; deben terminar 0.
- [ ] Commit: `git add -- functions/src/contract.ts functions/test/contract.test.ts` y `git commit -m "feat(ai): add fail-closed callable contract"`.

### Task 3: Auth, App Check y callable v2

**Files:** Create `functions/src/interpretAiRequest.ts`; Modify `functions/src/index.ts`; Test `functions/test/interpretAiRequest.test.ts`.

**Produces:** factory pura y export `interpretAiRequest`.

- [ ] Escribir RED: sin `auth`, fixture válido retorna `{ code: "unauthenticated" }`; con `{ uid: "test-user" }` y campo extra retorna `{ code: "invalid-argument" }`; con Auth + fixture válido retorna únicamente la respuesta `AI_NOT_CONFIGURED`. Ejecutar `npm test -- --run test/interpretAiRequest.test.ts`; debe fallar por módulo inexistente.
- [ ] Implementar factory: si `!request.auth?.uid`, retornar `unauthenticated`; validar `request.data`; si falla, retornar `invalid-argument`; si pasa, retornar `createNotConfiguredResponse`. En `index.ts`, importar `onCall` y `HttpsError` desde `firebase-functions/v2/https`; exportar `interpretAiRequest = onCall({ enforceAppCheck: true }, handler)`. El handler pasa sólo uid/data a la factory y transforma el resultado con `code` en `new HttpsError(result.code, "Request rejected")`.
- [ ] Ejecutar GREEN: `npm test`, `npm run lint`, `npm run build`. Desde raíz, ejecutar `rg -n "firebase-admin|https?://|DeepSeek|OPENAI|GEMINI|defineSecret|firestore|logger\\." functions/src functions/package.json`; debe no devolver coincidencias.
- [ ] Commit: `git add -- functions/src/interpretAiRequest.ts functions/src/index.ts functions/test/interpretAiRequest.test.ts` y `git commit -m "feat(ai): add app-check callable gateway boundary"`.

### Task 4: Gates y evidencia sin despliegue

**Files:** Modify `docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`; Create ignored `.superpowers/sdd/phase-1a-worker-report.md`.

**Produces:** evidencia verificable y estado factual pendiente de auditoría Sol.

- [ ] Ejecutar desde raíz `npx -y firebase-tools@latest --version` y `npx -y firebase-tools@latest emulators:exec --only functions "cmd /c exit 0"`. No autenticar, inicializar ni desplegar. Si el emulador falla por dependencia local, guardar error exacto y detenerse; no sustituir con deploy.
- [ ] Ejecutar gates: en `functions`, `npm ci`, `npm test`, `npm run lint`, `npm run build`; en raíz, `./gradlew.bat testDebugUnitTest`, `./gradlew.bat assembleDebug`, `./gradlew.bat lintDebug`, `git diff --check`, `git status --short`. Todo debe terminar 0; status puede conservar sólo `?? docs/superpowers/handoffs/` preexistente y archivos ignorados.
- [ ] Inspeccionar: `git diff --name-only 62243997a8e3a5f8c8cf2bd30585db66856a3141..HEAD`; `git diff --check 62243997a8e3a5f8c8cf2bd30585db66856a3141..HEAD`; `rg -n -i "api[_-]?key|secret|deepseek|openai|gemini|firebase-admin|https?://" functions --glob "!package-lock.json"`. No debe haber coincidencias prohibidas ni rutas no permitidas.
- [ ] Añadir al changelog del source of truth: Fase 1A implementada localmente, pendiente de auditoría Sol; callable `interpretAiRequest`, Auth/App Check y `AI_NOT_CONFIGURED`; exclusiones deploy/proveedor/secretos/Firestore/Android/datos financieros. No marcar Fase 1 completa ni GO público. Guardar hashes, resultados y limitación App Check remoto en reporte ignorado, sin IDs externos, payloads ni secretos.
- [ ] Commit documental: `git add -- docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md` y `git commit -m "docs(ai): record phase 1a local gateway evidence"`. No hacer push, deploy, tag, AAB ni empezar Fase 2; entregar reporte a Sol.

## Cobertura y self-review

- Tasks 2–3 cubren contrato, Auth, App Check, fail-closed y respuesta fija.
- Constraints y búsquedas de Tasks 3–4 bloquean proveedor, secretos, red, Firestore, Android y dinero.
- Task 4 prueba emulador local, tests/build Functions y regresiones Android.
- Rol, entitlement, cuota, rate limit, idempotencia y Secret Manager pertenecen a Fase 2/infraestructura futura; por ello el endpoint queda `AI_NOT_CONFIGURED`.
