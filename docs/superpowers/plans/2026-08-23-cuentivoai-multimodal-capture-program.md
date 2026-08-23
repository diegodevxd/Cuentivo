# CuentivoAI: captura multimodal hacia borradores seguros

> Estado: plan ejecutable. No autoriza secreto, despliegue, bootstrap remoto,
> cambio comercial, Storage ni mutación financiera por IA.
>
> Amplía `2026-08-14-cuentivoai-source-of-truth.md` y
> `specs/2026-08-23-cuentivoai-multimodal-draft-capture-design.md`.

## Objetivo

Entregar, en APK debug y por incrementos auditables, captura por texto, foto de
libreta/ticket y voz. Todas convergen en texto editable y una propuesta tipada.
Gemini 2.5 Flash-Lite es el único proveedor futuro, sólo detrás de Firebase
Auth + App Check + gateway server-side. DeepSeek es únicamente implementador.

El flujo obligatorio es: entrada -> propuesta Gemini/OCR/transcripción ->
validación pura -> resolución local contra datos reales -> preview editable ->
confirmación explícita -> camino de negocio existente. El primer bloque de este
plan no llega a la confirmación financiera: ningún modelo, OCR, voz o UI puede
escribir ventas, pagos, saldos, Room, Firestore, auditoría u outbox.

## Invariantes

1. Cents: `Long`; cantidades: `Int`; Gemini declara y Cuentivo calcula.
2. Sin red, cuota, Auth/App Check, kill switch, timeout, OCR/voz mala, permiso
   denegado o cancelación producen estado seguro y cero mutaciones.
3. No guardar prompts, transcripciones, imagen, audio, nombres, IDs ni montos
   en logs, Analytics, Crashlytics ni Firestore de control.
4. AI apagada no altera Free/Pro, pagos ni el funcionamiento local-first.
5. Temporales de foto/audio son privados, acotados y se borran al terminar o
   cancelar. No se usan como fotos de producto ni se agrega Firebase Storage.
6. Sin cambios de versión, `applicationId`, Room schema, reglas Firestore,
   Billing, AAB o Play Console.

## Incrementos y commits

### A. Propuesta versionada y resolución pura — Fase 3a

**Meta:** extender `app/src/main/java/com/cuentivo/app/ai/AiContracts.kt` con
propuestas strictas para venta, abono, consulta y `requires_clarification`.
Los nombres son candidatos, sin IDs; importes declarados, sin balances.

**Archivos:** `AiContracts.kt`, nuevo `AiProposalSafety.kt`, tests JVM bajo
`app/src/test/java/com/cuentivo/app/ai/`. Cambiar Functions sólo si requiere
compartir el contrato; no se despliega.

**Pasos:**

1. RED: ventas/abonos/consultas válidos, cliente/producto ambiguo, lista vacía,
   campos extra, importe negativo/desbordado y pago declarado inválido.
2. Crear tipos sellados con `schemaVersion` y validador fail-closed.
3. Definir resolver puro que recibe candidatos de Room y retorna
   `ResolvedPreview`, `NeedsUserSelection` o `Rejected`; sin DAO/I/O.
4. Migrar/adaptar `AiProvider` sin dejar contratos ambiguos.

**Gate:** tests JVM completos, `assembleDebug`, `lintDebug`, `git diff --check`;
verificar que `ai` puro no importa Room/Firestore. Rollback: revert de un commit
sin migración ni persistencia.

Commits: `test(ai): specify versioned safe proposals`; `feat(ai): validate and
resolve proposals without I/O`.

### B. Callable de texto tipada, sólo debug — Fase 3b

**Meta:** cambiar la callable v1, que hoy no manda texto, a request/response
versionado. Antes de configurar secreto/desplegar seguirá devolviendo un estado
seguro no configurado.

**Archivos:** `functions/src/interpretAiRequest.ts`,
`functions/src/geminiProvider.ts`, sus tests; y
`app/src/debug/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt` con tests.
No tocar Room, DAOs, sync ni pantallas financieras.

**Pasos:**

1. RED: aceptar exactamente versión, requestId, acción allow-listed y texto
   trim de máximo 1000 UTF-16; rechazar extra keys, vacío y multimedia.
2. Definir Structured Output JSON Schema para A; servidor hace segundo parser
   allow-listed y falla cerrado, nunca retorna texto libre.
3. Conservar Auth, App Check, edge abuse guard, claim durable, timeout, salida
   acotada, cero retry del proveedor y secreto ligado sólo a la callable.
4. El adapter debug verifica requestId/versión y convierte cualquier error en
   estado no ejecutable.

**Gate:** Functions test/lint/build; Android JVM/assemble/lint/diff check y
revisión de secretos/red/logs. No configurar key, no deploy, no provider call.
Rollback: revert; la Function ya desplegada sigue fail-closed.

Commits: `test(functions): specify v2 typed text interpretation`; `feat(ai):
transport typed Gemini text proposals`.

### C. Laboratorio debug de texto, preview no ejecutable — Fase 3c

**Meta:** entrada experimental visible en APK debug: escribir, editar, enviar y
ver unavailable/aclaración/propuesta. Debe decir “Borrador de prueba — no
registra nada”; no hay botón para confirmar ventas/pagos.

**Archivos:** paquete main `ui/ai/` independiente de Firebase; bindings en
`src/debug`/contraparte segura `src/release` si son necesarios; cambio mínimo a
`CuentivoApp.kt`; tests JVM/Compose.

**Pasos:** RED para editar/enviar/doble toque/cancelar/unavailable/aclaración;
separar UI y transporte; impedir imports de FirebaseFunctions en `main`; mostrar
campos candidatos, no deuda calculada; bloquear doble envío.

**Gate:** estado/Compose, assemble debug+release, lint/diff. Prueba física con
datos descartables: texto, vacío, offline, sin App Check y cancelación.

Commits: `test(ai-ui): cover debug proposal state machine`; `feat(ai-ui): add
non-executable debug text lab`.

### D. Foto de libreta/ticket y OCR local — Fase 6a

**Meta:** cámara o selector -> ML Kit OCR on-device -> editor de texto de C.
Una libreta puede dar varias líneas, pero no crea ni importa operaciones.

**Archivos:** `app/build.gradle.kts` sólo con dependencia ML Kit necesaria,
`src/debug/AndroidManifest.xml` si requiere capability debug, nuevo
`ai/capture/`, FileProvider acotado separado de fotos de producto, UI debug y
tests. Usar `CameraCaptureStore` sólo como referencia, nunca como almacén AI.

**Pasos:** RED para URI/nombre/eliminación/path traversal; crear
`AiCaptureStore` aislado en `cacheDir/ai-capture`; MIME allow-list, dimensiones
acotadas, limpieza en error/cancelación; OCR local y resultado editable. No
subir imagen a Gemini aún.

**Gate:** JVM almacenamiento/límites, instrumentada de URI/cámara cuando sea
posible, fixture OCR no sensible, assemble/lint/diff. Prueba física: foto clara,
borrosa, selector, cancelar, rotación y verificación de limpieza.

Commits: `test(ai-capture): cover private OCR capture lifecycle`; `feat(ai-capture):
add local notebook OCR flow`.

### E. Voz a transcripción editable local — Fase 7a

**Meta:** pulsar para hablar -> `SpeechRecognizer` de Android/dispositivo ->
transcripción editable. No se guarda ni sube audio y Gemini no responde hablando.

**Archivos:** permiso `RECORD_AUDIO` sólo debug, `ai/capture/VoiceTranscriber.kt`
abstraído y UI/test de C.

**Pasos:** RED de permiso denegado/recognizer no disponible/error/parcial/final/
cancelar/doble toque; crear/destruir recognizer con ciclo de vida; pedir permiso
justo a tiempo; enviar sólo tras pulsar interpretar; no bloquear Cuentivo si no
hay servicio de voz.

**Gate:** JVM, instrumentada cuando el dispositivo lo permita, build/lint/diff;
prueba física de permiso, frase correcta, mala transcripción, cancelar y offline.

Commits: `test(ai-voice): cover editable device transcription`; `feat(ai-voice):
add debug voice-to-text flow`.

### F. Confirmación financiera determinista — Fase 3d, bloqueado

No empieza sin autorización y plan propio. Antes se mapean factories/use-cases/
DAOs de venta/abono, `OperationEntity`, auditoría y outbox; se fija clave de
idempotencia y se prueban doble toque, retry, timeout, dos dispositivos, sync y
compatibilidad desde `v1.16.6-31`. Sólo el dominio calcula total/saldo/alocación.
La preview es editable, confirmar es único y cancelar no escribe. Requiere
revisión adversarial disponible.

### G. Fallback Gemini de imagen/audio y beta — Fases 6b/7b/8, bloqueado

Sólo tras autorización de secret+deploy, control remoto durable, entitlement,
cuotas, presupuesto, kill switch, privacidad/Data Safety y cohort. Requiere
consentimiento por envío, MIME/dimensiones/bytes/duración allow-listed,
compresión temporal, timeout, borrado comprobable, cero logs crudos y métricas
agregadas. No Firebase Storage.

## Validación común

Al cerrar cada incremento: inspección de diff, tests pertinentes,
`./gradlew testDebugUnitTest assembleDebug lintDebug`, Functions `test/lint/build`
si cambian y `git diff --check`. Tras cada gate aprobado se puede instalar la
APK debug en el teléfono USB con datos descartables; no se genera AAB.

## Fuera de alcance

Key Gemini, deploy, bootstrap remoto, Storage, AAB/Play, cambio Free/Pro/AI,
multi-provider, DeepSeek de producción, chatbot, TTS/Gemini Live y cualquier
escritura financiera previa a F.
