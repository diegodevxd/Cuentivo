# CuentivoAI — Fase 1B: gateway desplegado y fail-closed

> **Estado:** diseño aprobado en cuanto a plataforma y región; pendiente de revisión del propietario antes de plan e implementación.
>
> **Proyecto:** `organitazionlabs`. **Firestore:** `northamerica-south1` (Querétaro). **Functions v2:** `us-south1` (Dallas).

## Propósito

Desplegar el callable ya creado (`interpretAiRequest`) como frontera de red de desarrollo controlada, para verificar Firebase Auth y App Check reales desde la misma app Android. El endpoint seguirá respondiendo sólo `AI_NOT_CONFIGURED`: no habrá DeepSeek, texto de usuario, datos de negocio, Firestore, Secret Manager ni cobro de cuota.

Functions no ofrece `northamerica-south1` como región documentada, mientras que Firestore sí reside allí. Se conserva Functions v2 porque callable integra los tokens Firebase y `enforceAppCheck`, reduciendo superficie de implementación manual. Se fija `us-south1`, región Functions v2 de precio Tier 1 y más próxima al proyecto que las opciones por defecto; la separación con Firestore queda documentada y no se consulta Firestore durante esta fase.

## Límites no negociables

- Sólo se despliega `interpretAiRequest` en el proyecto existente `organitazionlabs`, región `us-south1`.
- El Android existente se conecta mediante el SDK oficial de Cloud Functions únicamente para este endpoint y sólo en build debug/protocolo de propietario.
- El contrato sigue siendo exactamente v1: `contractVersion`, `requestId` UUID y `action: "interpret_text"`; no incorpora texto, OCR, voz, cliente, producto, monto, negocio ni contexto financiero.
- El callable exige Firebase Auth y `enforceAppCheck: true`; debe fallar cerrado sin identidad/App Check/contrato válido.
- No se añade proveedor, URL HTTP genérica, Secret Manager, DeepSeek key, Firestore Admin, Cloud Storage, Billing AI, roles, entitlement, cuota, rate limit, idempotencia facturable, prompt, telemetría PII ni mutación financiera.
- No se cambia el producto Play, `applicationId`, `versionCode`, `versionName`, AAB ni la base 31/1.16.6.
- No se despliega una función sin `maxInstances: 1`, timeout corto y memoria mínima soportada; son límites de coste de desarrollo, no sustituyen Fase 2.

## Componentes

### Function v2 de desarrollo

`interpretAiRequest` se declara con:

```ts
onCall(
  {
    region: "us-south1",
    enforceAppCheck: true,
    maxInstances: 1,
    timeoutSeconds: 15,
    memory: "256MiB",
  },
  handler,
)
```

El handler conserva la fábrica pura de 1A. Sin Auth devuelve `unauthenticated`; un contrato inválido devuelve `invalid-argument`; una llamada autorizada y válida devuelve el objeto `AI_NOT_CONFIGURED`. No hay fallback a red directa ni reintento automático.

### Cliente Android debug-only

Se añade la dependencia Firebase Functions y una abstracción de transporte pequeña en la capa AI existente. El código no conoce DeepSeek ni ejecuta operaciones financieras. El cliente obtiene una callable mediante región explícita `us-south1`, adjunta tokens por el SDK Firebase, y expone el resultado `AI_NOT_CONFIGURED` como estado técnico no habilitado. No habrá UI de captura ni acción visible de IA todavía.

La prueba física usa el APK debug del propietario con App Check debug provider ya existente. El token debug se registra sólo en Firebase Console para el app debug y nunca entra al repositorio, documentación, logs o chat. Release sigue usando Play Integrity.

## Flujo

```text
APK debug autenticado
  -> Firebase Functions callable (us-south1)
  -> Firebase Auth + App Check automáticos
  -> contrato v1 puro allow-list
  -> AI_NOT_CONFIGURED

No hay proveedor, datos financieros, Room, Firestore ni Storage.
```

## Despliegue y rollback

Antes de desplegar, verificar login del propietario y proyecto activo `organitazionlabs`, revisar el diff y ejecutar tests locales. El deploy se limita a `functions:interpretAiRequest`; no se usa `firebase deploy` sin `--only`.

Rollback inmediato: deshabilitar la llamada desde el cliente debug y desplegar la versión previa conocida de la Function o borrar sólo `interpretAiRequest` con confirmación explícita del propietario. Como no persiste datos ni consume proveedor, no hay migración ni reversión financiera.

## Evidencia requerida

1. Pruebas Node 22, lint y build Functions.
2. Tests Android JVM, `assembleDebug`, `lintDebug`, `git diff --check`.
3. Deploy exclusivo de `functions:interpretAiRequest` a `organitazionlabs`/`us-south1` con la cuenta del propietario.
4. Verificación remota de región y opciones del deploy; confirmar que la URL corresponde a `us-south1`.
5. Prueba desde APK debug autenticado: Auth + App Check válido llega y retorna `AI_NOT_CONFIGURED`.
6. Pruebas negativas desde emulador/dispositivo: sin sesión y App Check inválido/ausente son rechazados.
7. Inspección de secretos/diff: ninguna key, prompt, endpoint DeepSeek, Firestore, Storage o dato financiero.

## Done y límites posteriores

Fase 1B sólo estará DONE con toda la evidencia anterior auditada independientemente por Sol. No habilita usuarios finales ni proveedor. Fase 2 deberá añadir autorización de negocio/rol, entitlement, cuota, rate limit, idempotencia y presupuestos server-side antes de que una solicitud pueda alcanzar un proveedor; Fase 3 podrá agregar texto a draft, preview y confirmación.
