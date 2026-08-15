# CuentivoAI — Fase 1A: gateway local fail-closed

> **Estado:** diseño aprobado por el propietario el 2026-08-15; pendiente de revisión del documento antes de plan e implementación.
>
> **Ámbito:** preparación local y emulable de Cloud Functions for Firebase v2 callable. No autoriza despliegue, recursos cloud, proveedor IA, secreto, Billing, AAB ni activación de usuarios.

## Propósito

Crear una frontera server-side mínima y verificable para CuentivoAI sin conectarla aún a Android ni a un proveedor. Debe demostrar que el futuro gateway puede fallar cerrado y rechazar entradas no autorizadas o malformadas, sin leer ni modificar datos de negocio.

La referencia de compatibilidad permanece congelada en `v1.16.6-31` / `e40eb4e04404a63f9fa0d15e330834780961cd63`; la integración de esa base en esta rama es `62243997a8e3a5f8c8cf2bd30585db66856a3141`.

## Decisión y alternativas

Se usará **Firebase Cloud Functions v2 callable** como forma de entrada. Su contrato callable integra el transporte esperado por Firebase Auth y App Check y permite probar localmente con Emulator Suite. No sustituye verificaciones de rol, entitlement, cuota o idempotencia de fases posteriores.

Alternativas descartadas para esta fase:

1. **Cloud Run:** es válido cuando haya necesidad demostrada de contenedor, streaming o control de infraestructura; ahora añadiría operación y recursos sin necesidad.
2. **Integrar DeepSeek ahora:** requeriría secreto, control de coste, cuota, idempotencia y revisión de privacidad que no pertenecen a 1A.
3. **Endpoint HTTP genérico:** obliga a implementar manualmente un protocolo de autenticación/App Check que callable ya estructura para este caso.

## Límites no negociables

- No se despliega ninguna Function ni se crean/configuran recursos remotos.
- No se usa la credencial de ningún proveedor, Secret Manager, HTTP saliente, modelo o red externa.
- No se modifica Android, Room, Firestore, WorkManager, Billing, Sync, roles ni datos financieros.
- No se lee ni escribe Firestore. El endpoint no recibe referencias de negocio ni contextos de usuarios.
- No se registran prompts, textos del usuario, identificadores, montos, secretos ni respuestas crudas.
- No se cambia `applicationId`, `versionCode`, `versionName`, el tag de baseline ni la configuración de release.
- La carpeta no rastreada `docs/superpowers/handoffs/` queda fuera de cualquier `git add` o commit.

## Componentes locales

### Paquete Functions

Se crea `functions/` como paquete Node/TypeScript aislado. Sus dependencias sólo habilitan compilación, callable v2 y pruebas locales. La configuración de emuladores añade Functions, pero no modifica reglas ni el comportamiento de Auth/Firestore existentes.

La compilación generará artefactos ignorados por Git. No se acepta una key, archivo `.env` con valores reales, credencial de servicio, `google-services.json` adicional ni una dependencia de proveedor IA.

### Callable único

El único endpoint será `interpretAiRequest`. Su contrato inicial es deliberadamente mínimo:

```ts
type InterpretAiRequest = {
  contractVersion: 1;
  requestId: string;
  action: "interpret_text";
};

type InterpretAiResponse = {
  contractVersion: 1;
  requestId: string;
  status: "AI_NOT_CONFIGURED";
};
```

`requestId` es un UUID no vacío con longitud máxima explícita. En esta fase sólo correlaciona una respuesta; no implementa persistencia ni idempotencia de consumo. Cualquier campo adicional, acción distinta, versión distinta, valor vacío o tipo incorrecto se rechaza antes de cualquier procesamiento.

La Function se declara con `enforceAppCheck: true`. El handler además exige contexto autenticado. Una llamada sin Auth, sin App Check válido o con payload inválido termina con error callable estable, sin detalles internos. No realiza consultas de membresía/rol todavía porque requerirían Firestore; esa autorización completa queda obligatoriamente en Fase 2 antes de proveedor o beta.

Si supera esas barreras, devuelve únicamente `AI_NOT_CONFIGURED`. Nunca interpreta texto, no llama un proveedor y no puede originar una mutación financiera.

### Límites y errores

Los validadores puros se separan del adaptador Firebase para permitir unit tests sin emulador. El adaptador convierte sus fallos a códigos callable seguros:

| Condición | Resultado externo |
|---|---|
| Sin identidad Firebase | `unauthenticated` |
| App Check inválido o ausente | rechazo por callable/App Check, sin fallback |
| Payload/versión/acción/requestId inválido | `invalid-argument` |
| Entrada autorizada y válida | `AI_NOT_CONFIGURED` |
| Excepción no prevista | `internal`, sin causa ni datos de entrada |

No habrá logging del request. Sólo se permite, si la plataforma lo requiere, un evento técnico constante sin requestId, uid ni contenido; por defecto no se añade telemetría en 1A.

## Flujo y frontera de confianza

```text
Cliente futuro (sin cambio Android en 1A)
  -> Callable v2 enforceAppCheck
  -> Firebase Auth context requerido
  -> validador puro de contrato allow-list
  -> respuesta fija AI_NOT_CONFIGURED

No hay proveedor, Secret Manager, Firestore, Room ni operación financiera.
```

App Check y Auth no equivalen a entitlement ni autorización financiera. Fase 2 debe añadir verificación server-side de miembro/rol, entitlement, cuota, rate limit, idempotencia y flags antes de permitir que una solicitud llegue a `AIProvider`.

## Pruebas y evidencia requerida

El implementador debe aplicar TDD y conservar evidencia de RED → GREEN para el validador. Como mínimo:

1. contrato válido retorna la respuesta fija con el mismo `requestId`;
2. rechaza versión, acción, requestId, tipo y campo adicional inválidos;
3. handler sin Auth retorna `unauthenticated`;
4. handler con Auth y payload inválido retorna `invalid-argument`;
5. el código no contiene llamadas HTTP, SDK/proveedor IA, Firestore Admin ni secreto;
6. `npm`/TypeScript test y build de Functions pasan localmente;
7. `testDebugUnitTest`, `assembleDebug`, `lintDebug` y `git diff --check` vuelven a pasar en Android;
8. diff revisado: sólo archivos de Functions, `firebase.json` necesaria y documentación/plan explícitamente aprobada; sin artefactos, secretos, APK/AAB ni handoffs.

La prueba de validación real de App Check en Emulator Suite requiere configuración/compatibilidad específica del emulador; se documentará exactamente como limitación si no es soportada por el entorno local. No se simula como evidencia de producción ni se despliega para suplirla.

## Rollback

La reversión local es retirar el commit de Fase 1A de la rama de características. Como no hay despliegue, secreto, base de datos ni cambio Android, no existe migración ni dato remoto que revertir. Si posteriormente se despliega una Function, su rollback requerirá una fase explícita y un runbook propio; no está autorizado aquí.

## Criterio DONE de Fase 1A

Fase 1A estará cerrada sólo si Sol confirma independientemente los tests, build, lint, `git diff --check`, ausencia de secretos/red/proveedor/Firestore/datos financieros, y coherencia del contrato fail-closed. El reporte del implementador no basta. La fase no habilita Android, una llamada real, Secret Manager, Cloud Functions desplegadas, ni el inicio automático de Fase 2.
