# CuentivoAI Fase 1B: Gateway `us-south1` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Steps use checkbox syntax.

**Goal:** Desplegar controladamente `interpretAiRequest` en `organitazionlabs` / `us-south1` y añadir un transporte Android debug-only que no puede interpretar texto ni mutar dinero.

**Architecture:** Functions v2 callable conserva el contrato v1 fail-closed y especifica región, App Check, límites de desarrollo y Auth. Android obtiene una callable en la región explícita y traduce exclusivamente su respuesta fija; la app no la invoca por UI y `AiExperiment` continúa apagado.

**Tech Stack:** Firebase Functions v2 Node 22, Firebase Android Functions SDK, Firebase Auth/App Check existentes, Kotlin/JUnit, Emulator Suite y APK debug.

## Global Constraints

- Proyecto único `organitazionlabs`; Firestore queda en `northamerica-south1`; Function sólo en `us-south1`.
- Sin DeepSeek, Secret Manager, Cloud Storage/fotos, Firestore Admin, Billing AI, roles, cuota, idempotencia facturable, prompts, OCR, voz, UI financiera, Room ni mutación financiera.
- No desplegar antes de confirmar Firebase CLI autenticada, proyecto activo y rol IAM `Firebase App Check Token Verifier` de la cuenta de servicio de Functions.
- Deploy exclusivamente `--only functions:interpretAiRequest`; no cambiar `.firebaserc`, reglas, app id, versiones, AAB o Play.
- `AiExperiment` se conserva desactivado; cliente sólo existe para test/debug y nunca tiene fallback HTTP/DeepSeek.

### Task 1: Runtime Functions regional y pruebas de configuración

**Files:** Modify `functions/src/index.ts`; Create `functions/test/index.test.ts` if needed.

- [ ] Escribir prueba que importe el export `interpretAiRequest` y confirme que el código fuente contiene las opciones `region: "us-south1"`, `enforceAppCheck: true`, `maxInstances: 1`, `timeoutSeconds: 15`, `memory: "256MiB"`; ejecutar `npm test -- --run test/index.test.ts` y observar RED antes de cambiar el export.
- [ ] Modificar sólo el objeto de opciones de `onCall` para declarar exactamente esas cinco opciones. No importar Admin SDK, provider, secreto, logger ni Firestore.
- [ ] Ejecutar Node 22: `node22 node_modules/vitest/vitest.mjs run`, `node22 node_modules/typescript/bin/tsc -p tsconfig.json --noEmit`, `node22 node_modules/typescript/bin/tsc -p tsconfig.json`; usar la ruta efímera documentada en Fase 1A, no instalar Node global.
- [ ] Commit: `git add -- functions/src/index.ts functions/test/index.test.ts` y `git commit -m "feat(ai): configure regional callable gateway"`.

### Task 2: Transporte Android aislado

**Files:** Modify `app/build.gradle.kts`; Create `app/src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt`; Test `app/src/test/java/com/cuentivo/app/ai/FirebaseCallableAiGatewayTest.kt`.

- [ ] Añadir el SDK principal `com.google.firebase:firebase-functions` bajo el BoM Firebase existente; no usar KTX retirado ni dependencias HTTP.
- [ ] Escribir test RED para un parser puro que acepte sólo el mapa `contractVersion=1`, mismo `requestId`, `status=AI_NOT_CONFIGURED`, y convierta cualquier mapa/Task/error distinto en `AiProviderResult.Unavailable("AI_NOT_CONFIGURED")`. Nunca devolver `Interpreted` ni `AiIntent` desde el transporte.
- [ ] Implementar `FirebaseCallableAiGateway` con `FirebaseFunctions.getInstance("us-south1")`, nombre literal `interpretAiRequest`, request map sólo con contrato v1 sin texto y parser puro interno. La llamada es suspendible sólo mediante el bridge Task existente/aprobado; no se registra ni se conecta a UI, ViewModel, Room o `AiProvider` productivo.
- [ ] Ejecutar test dirigido y `testDebugUnitTest`; confirmar GREEN. Commit: `git add -- app/build.gradle.kts app/src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt app/src/test/java/com/cuentivo/app/ai/FirebaseCallableAiGatewayTest.kt` y `git commit -m "feat(ai): add debug callable gateway transport"`.

### Task 3: Despliegue de desarrollo y prueba manual controlada

**Files:** Create ignored `.superpowers/sdd/phase-1b-worker-report.md`; Modify source of truth sólo con estado pendiente de auditoría.

- [ ] Verificar identidad/proyecto por CLI usando el binario Firebase directo documentado en 1A. Si no hay sesión autenticada, detenerse y pedir al propietario login interactivo; no ejecutar login ni modificar IAM automáticamente.
- [ ] Antes de deploy, el propietario o Sol confirma que la cuenta de servicio usada por Functions posee únicamente el rol `Firebase App Check Token Verifier`; registrar sólo la confirmación, nunca credenciales.
- [ ] Ejecutar `firebase deploy --only functions:interpretAiRequest --project organitazionlabs`; capturar región, URL y opciones. Si región no es `us-south1`, detenerse y no hacer llamada desde Android.
- [ ] En APK debug autenticado, registrar el token de App Check debug exclusivamente en Firebase Console, llamar el endpoint con contrato v1 y comprobar `AI_NOT_CONFIGURED`; repetir sin sesión y con token debug retirado, esperando rechazo. No incluir token, uid ni requestId en reporte.
- [ ] Ejecutar `testDebugUnitTest`, `assembleDebug`, `lintDebug`, tests Node 22, `git diff --check`; escribir resultados en reporte. Actualizar source of truth como pendiente auditoría Sol y detenerse, sin Fase 2.

## Rollback

Revertir los commits Android/Functions locales y deshabilitar la llamada debug. Si el deploy requiere reversión, sólo el propietario autoriza `functions:delete interpretAiRequest` o redeploy del commit previo; no hay datos, proveedor ni migración que revertir.

## Coverage review

Tasks 1–2 prueban región, App Check declarado, contrato fijo y ausencia de interpretación. Task 3 cubre la frontera real Auth/App Check, deploy limitado, APK debug y gates. DeepSeek no declara DONE; Sol audita diff, IAM, output de deploy y pruebas negativas.
