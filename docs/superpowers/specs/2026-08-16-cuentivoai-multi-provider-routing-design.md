# CuentivoAI: diseño de routing multi-provider

## Propósito

CuentivoAI soportará DeepSeek y Gemini sin acoplar el dominio financiero a ninguno. Ambos proveedores sólo interpretan entradas no confiables y devuelven un contrato de intent/draft; Cuentivo conserva la validación determinista, cálculos, preview, confirmación explícita, auditoría e idempotencia de la operación financiera.

## Decisión

- DeepSeek V4 Flash es el proveedor principal de texto: texto a draft, detección de intent, texto obtenido por OCR, consultas naturales y clasificación.
- Gemini es secundario: multimodal para imagen o audio cuando el procesamiento local no baste, y fallback de DeepSeek sólo si la política de la acción lo permite.
- No se envía una misma petición a ambos proveedores salvo un benchmark explícito, aislado y aprobado.
- El backend expone un único contrato de salida. El cliente y el dominio no reciben ni deciden secretos, cuotas ni proveedor.
- Cada proveedor tiene secretos, feature flag/kill switch, timeout, límite de tokens, retry y protección contra fallos propios.
- El routing y la telemetría registran solamente provider, model, tipo de acción, contadores de tokens, costo estimado, latencia, resultado y `fallback_used`; nunca texto, imagen, audio, clientes, productos, montos ni prompts crudos.

## Límites de fase

Fase 2A construirá únicamente el control plane server-side, agnóstico de proveedor: entitlement, cuota, rate limit, idempotencia y flags por proveedor. Comienza fail-closed para todos, sin API key, sin llamada a modelo, sin proveedor habilitado y sin cambio en `interpretAiRequest` ya desplegada.

Fase 3 implementará el contrato común, `AiRouter` y proveedores textuales. Gemini multimodal para imagen queda condicionado a Fase 6 (OCR local primero) y audio a Fase 7 (STT Android primero). Ninguna fase habilita producción sin gates independientes.

## Routing

1. Texto normal: DeepSeek si está habilitado y sano.
2. Imagen: OCR local; si produce texto suficiente, DeepSeek. Si no y Gemini multimodal está autorizado y habilitado, Gemini.
3. Voz: STT local/Android; si produce texto suficiente, DeepSeek. Audio multimodal Gemini sólo cuando la capacidad esté aprobada.
4. Caída/degradación DeepSeek: Gemini únicamente si la acción permite fallback y ambos producen el mismo contrato seguro. De lo contrario, error recuperable sin mutación.
5. Toda salida pasa por validación de schema, validación determinista, preview y confirmación; ningún fallback omite esos pasos.

## Componentes previstos

- `AiProvider`: interfaz backend/provider-agnostic.
- `DeepSeekProvider` y `GeminiProvider`: implementaciones aisladas, con secretos independientes del backend.
- `AiRouter`: selecciona una única ruta según capacidad, disponibilidad, costo, flags y política de fallback.
- `AiProviderPolicy`: allow-list de capacidades, límites y fallback.
- Control plane Firestore sólo desde Functions: entitlement, cuota, idempotencia, rate limit y kill switches. Ningún cliente puede ser autoridad.
- `AiProviderTelemetry`: eventos agregados sin PII ni payload.

## Seguridad y compatibilidad

- No API key en APK/AAB, Git, logs, Crashlytics ni Firestore del cliente.
- No cambios a Room, ventas, pagos, saldos, sync, Billing actual ni reglas financieras.
- La ausencia, desactivación o fallo de uno o ambos proveedores no afecta Cuentivo normal ni Pro.
- La configuración de proveedor se controla server-side y se despliega apagada por defecto.

## Verificación

- Tests de routing para DeepSeek-only, Gemini-only, ambos habilitados y ambos apagados.
- Tests de fallback permitido/prohibido, contrato inválido, timeout, retry y circuit breaker.
- Tests transaccionales concurrentes de cuota, rate limit e idempotencia.
- Tests que prueben ausencia de mutaciones financieras ante cualquier respuesta/error de proveedor.
- Antes de producción: benchmark A/B con batería de intents anonimizados que mida JSON válido, interpretación correcta, draft acceptance rate, latencia, costo, errores y fallback rate.

## Fuera de alcance

No crea credenciales, Secret Manager, llamadas a DeepSeek/Gemini, Gemini multimodal, UI financiera, producto Play CuentivoAI, cambio de versión ni AAB.
