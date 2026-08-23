# CuentivoAI — arquitectura, seguridad y plan de ejecución

> **Estado:** el lanzamiento público y cualquier AAB/activación para usuarios permanecen en **NO-GO**. En la rama aislada `diegodevxd/feature-cuentivo-ai`, Fase 0A, Fase 1B (gateway fail-closed) y Fase 2A (control plane Gemini-only local) están cerradas. **Fase 2A no tiene autorización de deploy ni proveedor real.** La excepción interna no autoriza operaciones financieras IA, AAB, Play ni el inicio automático de fases posteriores.
>
> **Source of truth:** este documento prevalece para CuentivoAI hasta que el propietario apruebe una fase explícitamente. Las decisiones y supuestos deben actualizarse aquí antes de cambiar producción.
>
> **Fecha de auditoría:** 2026-08-14. **Rama inspeccionada:** `codex/neo-fintech`.
>
> **Reconciliación actual (2026-08-22):** la rama vigente es `diegodevxd/feature-cuentivo-ai`;
> HEAD local de implementación en el momento del incremento documental Task 4: `4e04052`
> (antes del commit documental de Task 4). El incremento local Gemini Debug Gateway está
> **aceptado por Codex**, pendiente de autorización explícita de deploy, bootstrap del control
> plane server-side (config/entitlement Admin-only) y revisión GLM diferida por instrucción del
> propietario. No se marca producción, Fase 2 ni Fase 3 como completadas; el release/AAB público
> sigue NO-GO.

## 0. Mandato y límites

CuentivoAI debe reducir captura y consulta manual; no será un chatbot ni autoridad financiera. La versión pública actual es prioritaria: no se mezclará CuentivoAI con el release cercano, ni se habilitará para usuarios sin una beta separada, feature flags y rollback probado. La aprobación de esta arquitectura no autoriza código, dependencias, proveedores, infraestructura ni cambios de productos de Play.

Invariantes no negociables:

1. El modelo solo interpreta texto; nunca escribe Room, Firestore, dinero, entitlements ni auditoría.
2. La lógica determinista existente calcula importes, asigna pagos y ejecuta transacciones.
3. Toda mutación financiera propuesta muestra preview y requiere confirmación explícita.
4. Cada confirmación usa una clave de idempotencia; doble toque, retry y reintento offline no duplican una operación.
5. El backend es autoridad para API keys, plan CuentivoAI, cuota, rate limit y costo; el APK no contiene secretos.
6. Apagar AI no degrada Gratis ni Pro: texto/cámara/voz normales siguen siendo opcionales y Cuentivo funciona offline.

## 1. Estado actual verificado

### Android y dominio

- Un solo módulo Android `app`, Kotlin/Jetpack Compose, `minSdk 24`, `targetSdk 36`, Room v5.
- Local-first: Room conserva `Business`, `Customer`, `Product`, `Sale`, `SaleItem`, `Payment`, `PaymentAllocation`, `AccountMovement`, `AuditLog`, `SyncOperation`, `SyncState` y `OperationEntity` (idempotencia).
- Los montos son centavos enteros. Ventas/pagos/movimientos y auditoría son append-only; existen cálculos y asignación de pago deterministas.
- Productos tienen `sku`, precio, costo y `stock` opcionales. Las fotos se reducen a 1024 px y quedan **solo en almacenamiento privado local**; hoy no se suben a Cloud Storage.
- Backup/export local cifra el archivo con AES-GCM + PBKDF2. El backup automático de Android está deshabilitado de forma deliberada.

### Identidad, nube y seguridad existentes

- Firebase Authentication: email/contraseña y Google mediante Credential Manager.
- Cloud Firestore es el único backend de datos actual. No hay Cloud Functions, Cloud Run, Firebase Storage, servidor propio, Supabase ni proveedor IA en el repositorio.
- WorkManager drena una outbox a Firestore y realiza restore/pull. App Check usa Play Integrity en release y debug provider únicamente en debug.
- Reglas Firestore validan datos, email verificado, miembro activo y roles `OWNER`, `ADMIN`, `EDITOR`, `VIEWER`; datos financieros ordinarios no se borran. La eliminación de negocio es una cascada acotada tras una marca irreversible.
- Crashlytics y Analytics están incluidos. Crashlytics solo recibe eventos técnicos de vocabulario fijo, sin PII, ids ni montos. No hay FCM; los recordatorios son locales.

### Monetización real en código

- Solo hay `FREE` y `PRO`; producto Play `cuentivo_pro_mensual`.
- Gratis: 5 clientes activos y 30 ventas registradas; pagos nunca se bloquean.
- Pro, verificado por el cliente de Play: sync/backup cloud, fotos de producto/comprobantes como capacidad, importación/exportación de hojas de cálculo, compartir lista completa, equipo/roles y auditoría. Nota: las fotos actuales son locales, por lo que la promesa de fotos cloud requiere Storage antes de afirmarla a usuarios.
- El entitlement actual vive en `SharedPreferences`, asociado al uid y refrescado por Play; el sync lo acepta hasta 24 h desde la confirmación. No existe verificación de compra en servidor.

### Hallazgos y consecuencias

| Hallazgo | Evidencia | Consecuencia para AI |
|---|---|---|
| Gateway server-side inicial | `functions/`, `interpretAiRequest` callable v2 fail-closed desplegada en `us-south1`; `authorizeAiAction` permanece local y no desplegada | Nunca exponer Gemini desde Android; todo proveedor queda detrás del gateway. |
| Billing es client-side | `BillingGateway`, `EntitlementStore` | Insuficiente para vender cuota AI; servidor debe verificar/autorizar. |
| Hay base para confirmación segura | `OperationEntity`, DAOs transaccionales, auditoría | Reutilizarla; no inventar un ledger paralelo. |
| Fotos no son cloud | `ProductImageStore` | OCR debe procesar temporalmente en dispositivo; no asumir archivo remoto. |
| Roles existen | reglas y `BusinessMemberEntity` | Backend debe respetar el permiso actual antes de responder o aceptar una acción. |
| Árbol de trabajo está sucio | cambios release/UI/skills sin commit | Crear una rama/worktree aislado y partir de un commit estable antes de fase 0. |

## 2. Decisiones aprobadas de producto

Las siguientes decisiones están **aprobadas** y solo cambian por una nueva decisión explícita del propietario:

1. CuentivoAI es posterior al lanzamiento y estabilización de Cuentivo actual; el **NO-GO público** se mantiene. El propietario autorizó una pista interna, reversible y aislada de APK debug para preparar y probar CuentivoAI sin AAB ni activación para usuarios; cada fase posterior requiere aprobación explícita y conserva sus propios gates.
2. Precio objetivo experimental: $299 MXN/mes; deberá revisarse con uso, costo y conversión reales.
3. Jerarquía prevista: Gratis → Pro $199 → CuentivoAI $299. CuentivoAI **incluye Pro**; no son suscripciones acumulativas.
4. Cuota inicial de referencia: aproximadamente 500 acciones IA/mes, configurable en servidor y no compromiso comercial definitivo.
5. **Gemini 2.5 Flash-Lite es el único proveedor de inferencia previsto para la primera producción**, detrás de `AIProvider` intercambiable. DeepSeek V4 Pro se usa para desarrollo mediante OpenCode, no dentro de CuentivoAI.
6. El modelo nunca tiene autoridad financiera: interpretación → schema → validación → lógica determinista → preview → confirmación → operación idempotente → DB.
7. La key de Gemini jamás reside en APK/AAB; un gateway seguro es requisito previo a todo proveedor real.
8. OCR intenta primero ejecución local/on-device; voz inicia experimentalmente y no promete offline universal.
9. Barcode se considera inicialmente Pro, no exclusivo de CuentivoAI.
10. Apagar CuentivoAI no puede afectar Gratis ni Pro.
11. Las fotografías presentes son locales; no se afirmará respaldo cloud de fotos sin implementación y verificación independiente.

Barcode no usa IA ni tiene costo marginal de modelo. Mantenerlo como Pro evita mezclar una herramienta operacional con la propuesta de IA.

## 3. Arquitectura propuesta

```text
Android (UI/captura) -- Firebase Auth ID token + App Check --> Cuentivo AI Gateway
                                                               |-- verifica uid, App Check, rol, entitlement y cuota
                                                               |-- rate limit + idempotency + telemetría sin PII cruda
                                                               |-- AIProvider (Gemini 2.5 Flash-Lite inicialmente; intercambiable)
                                                               '-- devuelve AiDraft o QueryIntent firmado por requestId

Android -- validación de dominio + preview + confirmación --> Room transaction existente
                                                        --> OperationEntity + AuditLog + Sync outbox
                                                        --> Firestore actual
```

### Backend elegido

Usar **Cloud Functions for Firebase v2 (callable/HTTP) en el proyecto Firebase existente**, con Secret Manager para la key del proveedor. Es el mínimo que reutiliza Auth, App Check, Firestore, emuladores y observabilidad. Alternativa aceptable si la carga/streaming exige más control: Cloud Run detrás de Firebase Auth/App Check; no se decide ni se crea hasta medir beta.

El gateway no escribe operaciones financieras. La implementación local de Fase 2A propone sólo documentos de control Admin SDK: `aiControl/global`, `aiEntitlements/{uid}`, `aiUsage/{uid}_{YYYYMM}`, `aiRateWindows/{uid}_{YYYYMMDDHHmm}` y `aiRequestLedger/{uid}_{requestId}`. Las reglas de cliente no se han modificado y niegan acceso directo a esas rutas. Esta implementación no está desplegada ni concede entitlement real.

### Interfaces propuestas

```kotlin
interface AiProvider {
    suspend fun interpret(request: AiInterpretRequest): AiProviderResult
}

sealed interface AiIntent {
    data class CreateSaleDraft(/* referencias candidatas, líneas, pago declarado */) : AiIntent
    data class RecordPaymentDraft(/* cliente candidato, importe declarado */) : AiIntent
    data class BusinessQuery(/* operación permitida y filtros */) : AiIntent
    data object NeedClarification : AiIntent
    data object Refuse : AiIntent
}
```

`AiIntent` es un contrato versionado y con allow-list, no JSON libre. El gateway valida JSON schema, tamaño, enum, cantidades y referencias; Android vuelve a validar contra Room y convierte nombres ambiguos en selección humana. El dominio calcula totales y saldos desde sus entidades existentes. La respuesta no contiene comandos SQL, rutas Firestore, herramientas arbitrarias ni instrucciones ejecutables.

## 4. Flujos de usuario

### Texto → operación

1. Usuario escribe texto y elige “interpretar”.
2. Cliente limita tamaño, adjunta solo contexto mínimo permitido (por ejemplo candidatos locales, nunca la base completa) y llama Gateway.
3. Gateway autoriza y llama a Gemini con **Structured Outputs/JSON Schema** estricto para el tipo de intent permitido; nunca acepta texto libre como contrato. La reserva/consumo exacto se decidirá sólo cuando la política de reintentos y coste esté cerrada.
4. App resuelve candidatos contra Room; si hay 0 o >1 coincidencias pide selección/aclaración.
5. App usa factories/DAOs existentes para cálculo, muestra venta/pago y saldo resultante.
6. Confirmar ejecuta una transacción Room una sola vez, guarda `OperationEntity`/`AuditLog`, encola sync. Cancelar no muta dinero.

### Foto/ticket → operación

1. Cámara/selector guarda imagen temporal privada; no se sube por defecto.
2. ML Kit Text Recognition on-device extrae texto; mostrar texto y permitir corregirlo.
3. El texto, no la imagen, sigue el flujo de texto. OCR local no consume acción AI.
4. Una imagen difícil puede ofrecer fallback multimodal futuro con consentimiento separado, tamaño límite, borrado inmediato y política revisada; queda fuera de MVP.

### Voz → operación

1. Solicitar `RECORD_AUDIO` solo al tocar micrófono y explicar uso.
2. Preferir `SpeechRecognizer.createOnDeviceSpeechRecognizer` únicamente donde esté disponible; el API estándar puede depender de un servicio/red del dispositivo, por lo que no se promete offline. Un fallback multimodal a Gemini requiere consentimiento, límites y una fase posterior.
3. Mostrar transcripción editable; únicamente el texto confirmado llega al gateway y continúa como Texto → operación.
4. Si no existe reconocimiento on-device, ofrecer escribir o activar explícitamente el reconocedor del dispositivo; no contratar STT remoto en v1.

### Consultas naturales

El modelo solo devuelve `BusinessQuery` de una allow-list: deuda por cliente, mayor deudor, ventas por periodo, clientes con saldo. Android ejecuta consultas deterministas locales y enseña el resultado. Para resúmenes, enviar al proveedor solo agregados mínimos si la versión beta lo habilita. Nunca enviar toda la base ni permitir que el modelo declare saldos.

### Barcode

ML Kit Barcode Scanning on-device → normalizar código → buscar `Product.sku` local → seleccionar producto si hay una coincidencia; si no, ofrecer formulario precargado. No LLM, no cuota AI, no red obligatoria.

## 5. Seguridad, privacidad y rollback

### Controles obligatorios

- Secret de Gemini solo en Secret Manager; jamás `BuildConfig`, APK, repositorio, Crashlytics o logs.
- Callable endpoint exige Firebase Auth, App Check válido, uid verificado, miembro activo y rol que pueda proponer la operación. Mutación final aún se somete a las reglas actuales de Firestore.
- Server-side entitlement verifica compra con Google Play Developer API antes de conceder/renovar AI. No confiar en `SharedPreferences` ni en una flag de UI.
- Cuota mensual transaccional por uid/periodo, máximo por solicitud, rate limits uid+IP/App Check, concurrencia baja, timeouts, máximo de reintentos y hard cap monetario diario/mensual configurado en servidor.
- Idempotency key obligatoria por intento y confirmación. Reservar/consumir/reembolsar una acción con estado auditable; repetir la misma key devuelve el mismo resultado y no cobra doble.
- Redactar/minimizar PII: no guardar prompt/respuesta por defecto; telemetría con tipo, versión, tokens, costo, latencia, resultado y código de error. Para debugging beta, consentimiento explícito, TTL corto y redacción de teléfonos/emails.
- Resistir prompt injection: texto OCR/voz se trata como datos no confiables; prompt de sistema fijo, schema estricto, tool allow-list, no acciones automáticas y validación independiente.
- Feature flags server-side: global, por uid/beta, por capacidad (`text`, `query`, `ocr`, `voice`, `barcode`), por proveedor y kill switch. Fallar cerrado para AI, nunca para Cuentivo normal.

### Privacidad/documentación a revisar antes de beta

Actualizar política de privacidad, Data Safety y términos si se transmiten texto, transcripciones o OCR al gateway/proveedor. Divulgar proveedor, propósito, categorías de datos, retención, consentimiento y cómo desactivar AI. Confirmar requisitos aplicables con revisión legal/Play; este documento no ofrece asesoría legal.

## 6. Costos y economía (supuestos explícitos)

### Costos observados hoy

| Servicio | Uso actual real | Riesgo/costo |
|---|---|---|
| Firebase Auth | Email y Google | normalmente sin costo directo en este flujo; confirmar plan y país en consola. |
| Firestore | sync/restore Pro, roles, auditoría | Spark: 1 GiB, 50k reads/día, 20k writes/día, 20k deletes/día y 10 GiB/mes egress; pasar a Blaze/uso mayor cobra por uso. |
| Firebase Analytics/Crashlytics | incluidos, Crashlytics con datos técnicos fijos | sin costo directo previsto; no usar para prompts. |
| WorkManager/notificaciones | local | $0 de servicio. |
| Fotos/backup | almacenamiento privado y export cifrado local | $0 cloud hoy; no hay Firebase Storage. |
| Play Billing | una suscripción Pro | comisión depende de cuenta/mercado/programa; no inferible desde repo. |

No se puede saber desde Git si el proyecto está Spark o Blaze, región Firestore, consumo real, facturación, impuestos, ni precio final Play. Son acciones manuales de verificación.

### Modelo de uso para planificación, no pronóstico

Supuesto base de sync: 30 operaciones financieras/usuario/mes × 4 documentos escritos y 400 lecturas/usuario/mes. No incluye fotos ni varios miembros. A 10/100/1k/10k usuarios: 1.2k/12k/120k/1.2M writes al mes y 4k/40k/400k/4M reads al mes. Promediado: 10k usuarios (~40k writes/día, ~133k reads/día) supera Spark; 1k normalmente no bajo este supuesto. Medir antes de convertir esta tabla en presupuesto.

### Gemini 2.5 Flash-Lite — costo de modelo

No reutilizar estimaciones ni precios de DeepSeek. Antes de beta/producción se verificará el precio vigente de Gemini 2.5 Flash-Lite, modalidad de facturación, región y límites oficiales; entonces se calculará costo por acción con tokens y telemetría reales. OCR/Barcode local y consultas deterministas siguen sin costo de modelo.

OCR/Barcode local y consultas deterministas cuestan $0 de modelo. Cloud Functions/Run, Firestore de control, egress y Storage futuro se presupuestan separadamente por región en Google Cloud Pricing Calculator al elegir región; no están inventados aquí.

### Economía unitaria antes de impuestos

Si aplica el 15% de comisión de Play (confirmar elegibilidad/mercado): Pro $199 → $169.15 MXN antes de infraestructura; AI $299 → $254.15 MXN antes de infraestructura/IA. Si se aplicara 30%, los netos serían $139.30 y $209.30. Impuestos, devoluciones, tipo de cambio y soporte no están incluidos.

Para AI, margen antes de impuestos = `neto Play − (acciones × (inputTokens×0.14 + outputTokens×0.28)/1,000,000 × FX) − backend − Firestore/control − soporte`. Con 500 acciones normal/intensivo, el modelo no parece el costo dominante; límites de abuso, fotos cloud futuras y soporte son riesgos mayores. No fijar $299 ni 500 hasta medir beta y fijar hard cap.

## 7. Orquestamiento Orca

| Tipo de trabajo | Propietario | Revisor/gate |
|---|---|---|
| Arquitectura, criterios, seguridad, cierre | Codex/Sol | Codex/Sol independiente de reporte |
| Backend, secretos, entitlements, cuotas, idempotencia, sincronización | DeepSeek V4 Pro | GLM 5.3 adversarial + Codex/Sol |
| UI mecánica, tests auxiliares, documentación, inventario | DeepSeek V4 Flash | Codex/Sol |
| Concurrencia, abuso, dinero, threat model | GLM 5.3 | Codex/Sol decide findings |
| Tareas bajo/medio riesgo cuando esté validado | Qwen local | V4 Pro si dos intentos fallan; Codex/Sol gate |

Regla objetiva de escalamiento: Flash/Qwen → V4 Pro al primer contacto con dinero, backend, Auth, secretos, permisos, cuota, idempotencia, sync o migración; o tras dos intentos verificables fallidos, falta de compilación, regresión o ambigüedad de contrato. V4 Pro → GLM/Codex cuando la solución toca frontera de confianza o un reviewer no puede demostrar invariantes. Cada dispatch incluye alcance, archivos, tests, rollback y `worker_done`; ningún “terminado” cierra fase sin auditoría independiente.

## 8. Fases pequeñas y reversibles

### Fase 0 — Preparación interna y gate post-lanzamiento
- Estado: iniciada el 2026-08-15 por autorización explícita del propietario; readiness interno registrado el 2026-08-15 con tres documentos y sus commits: `docs/superpowers/operations/2026-08-15-cuentivoai-closed-test-gate.md` (`717db89`), `docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md` (`068632c`) y `docs/superpowers/operations/2026-08-15-cuentivoai-owner-debug-apk-protocol.md` (`810ebbe`). Referencia de compatibilidad congelada localmente: rama `release/1.16.6-baseline`, commit `e40eb4e04404a63f9fa0d15e330834780961cd63`, tag anotado `v1.16.6-31`; declara `versionCode 31` y `versionName 1.16.6`. El AAB permanece en prueba cerrada de Play Console con 12 testers y 13 días; no constituye lanzamiento público.
- Gate de cierre: **no hay GO público ni veredicto GO/NO-GO posible sin métricas agregadas** de la prueba cerrada. La baseline histórica conserva su fallo de `lintDebug`, pero se integró sin reescribir el tag mediante el merge `62243997a8e3a5f8c8cf2bd30585db66856a3141` (padres `ba20581` y `e40eb4e`); en la rama CuentivoAI el remedio de `AnimatedContent` preserva la UI 31 y los gates `testDebugUnitTest`, `assembleDebug`, `lintDebug` y `git diff --check` pasaron bajo auditoría independiente de Sol. El registro de readiness no autoriza activación pública.
- Objetivo: freeze verificable de release, inventario de evidencia de prueba cerrada, matriz de datos, contratos de observabilidad/flags, modelo de amenazas, criterios de rollback y estrategia de APK debug para el propietario, sin UI disponible para usuarios.
- Dependencias: alcance estrictamente sin proveedor mientras no exista evidencia suficiente de estabilidad; antes de cambios compatibles con release, `31 / 1.16.6` debe identificarse por commit/tag inmutable.
- Modelo: Flash para documentación/inventario de bajo riesgo; Sol auditoría. V4 Pro sólo se asignará cuando una fase posterior aprobada toque gateway, seguridad, cuotas, idempotencia o backend.
- Done: evidencia de prueba cerrada inventariada, métricas/alertas y kill-switch definidos sin PII, modelo de amenazas y plan de APK debug revisados, y cero endpoint AI público/secretos/proveedor.
- Rollback: documentación y contratos sin cambio funcional se revierten por commit; cualquier futuro APK debug se desinstala sin tocar datos de la app estable.

### Fase 0A — Fundaciones locales CuentivoAI (cerrada 2026-08-15)

- Objetivo: seam local off-by-default sin proveedor: contratos inmutables, `FakeAiProvider` determinista y validador de drafts puro.
- Rama: `diegodevxd/feature-cuentivo-ai`. Alcance: únicamente `com.cuentivo.app.ai` + tests JVM + este registro, con una excepción de UI mínima autorizada el 2026-08-15 para corregir el lint existente en `NeoFintechShell.kt`. Sin Room/DAO, Firebase, Billing, red, secretos, permisos, versión, dependencias ni cambios financieros.
- Commits (en orden): `35ab790 docs(ai): plan Fase 0A local foundation`; `8d35b54 feat(ai): add local experiment contracts`; `b535517 feat(ai): add deterministic fake provider`; `0dea240 feat(ai): validate local drafts safely`; `8de067d fix(ai): isolate sale draft lines from caller mutation`; `98cedaf fix(ai): make sale draft lines truly unmodifiable`.
- Evidencia RED→GREEN (TDD, una suite por unidad): cada clase de prueba compiló primero con error por API ausente (`Unresolved reference`) y pasó completa tras implementar; `:app:testDebugUnitTest` completo verde; `:app:assembleDebug` verde; `git diff --check` limpio. Auditoría 0A: tres pruebas nuevas en `AiContractsTest` demostraron RED que las líneas del draft eran mutables (referencia del llamador y cast a `MutableList`); la clase final congela cada construcción con `Collections.unmodifiableList` de una copia — la mutación del llamador no tiene efecto y la mutación directa del draft se rechaza con `UnsupportedOperationException` — y quedaron GREEN. Remediación autorizada de lint: `NeoFintechShellSourceContractTest` fue RED al ignorarse el target de `AnimatedContent` y GREEN al asociar el subárbol a `key(targetDestination)`; `:app:lintDebug` finalmente quedó verde el 2026-08-15.
- Sin GLM: la fase no roza dinero, backend, Auth, secretos, cuota, idempotencia, sync ni frontera de confianza; son contratos puros y un gate local. El gate adversarial GLM se mantiene para fases 1–3 y 8 según §7.
- Costo de servicio: **$0**. No hay proveedor, red, cuota ni llamada a modelo; los fixtures son locales.
- Apagado por defecto: `AiExperiment.isEnabled()` es `false`; nada en Gratis/Pro invoca un `AiProvider`.
- Diferido: preview/confirmación, resolución de candidatos contra Room, `BusinessQuery` y cualquier proveedor real quedan fuera de 0A.
- Cierre: todos los criterios DONE de 0A están verificados; no autoriza iniciar una fase posterior.

### Fase 1 — Fundaciones server-side
- Crear Functions v2, Auth/App Check, Secret Manager, config por entorno, emulator tests y `AIProvider` desacoplado.
- Modelo: V4 Pro; GLM seguridad; Sol auditoría.
- Tests: request sin Auth/App Check/rol/flag es 401/403; secreto no aparece en repo/APK/logs.
- Done: endpoint dummy no financiero con observabilidad mínima y deploy de staging; rollback flag global/despliegue anterior.

### Fase 2 — Entitlement, cuota y costo
- Crear `AI` como tier/entitlement server-side y verificación Play; cuota mensual transaccional, idempotency, rate limits y hard caps.
- Dependencias: Fase 1 y decisión de producto Play.
- Modelo: V4 Pro + GLM.
- Done: 500 es configuración remota, no literal cliente; reintentos no duplican consumo; usuario sin AI no llega a proveedor.

### Fase 3 — Texto a draft seguro
- Contrato de intent versionado, Gemini 2.5 Flash-Lite mediante Structured Outputs/JSON Schema, candidates, preview y confirmación usando dominio/Room existentes.
- Tests adversariales de mutación nula antes de confirmar y cálculo local después.
- Done: venta/pago de textos permitidos solo en beta; ambiguo/incorrecto pide aclaración.

### Fase 4 — Consultas naturales deterministas
- Allow-list de `BusinessQuery`, ejecución local y respuestas con datos reales. Sin resumen LLM inicialmente.
- Done: pregunta no permitida se rechaza; ningún saldo nace de respuesta LLM.

### Fase 5 — Barcode Pro local
- ML Kit Barcode, lookup SKU, formulario de alta; no backend/AI/cuota.
- Puede adelantarse tras Fase 0 por bajo riesgo, pero solo fuera del release actual.
- Done: offline, cámara cancelada, código desconocido y duplicados cubiertos.

### Fase 6 — OCR local y foto a draft
- ML Kit Text Recognition, texto editable, privacidad/retención temporal y conexión a Fase 3.
- Done: imagen no sale por red en flujo normal; OCR erróneo no puede confirmar sin usuario.

### Fase 7 — Voz experimental
- On-device primero, transcripción editable, permiso just-in-time; sin audio a LLM.
- Done: servicio no disponible/red caída deja alternativa escrita, no bloquea app.

### Fase 8 — Beta limitada y observabilidad
- Flag por cohort, dashboards costo/latencia/error, feedback, carga/abuso y rollback drill.
- Done: costo real por acción bajo hard cap y 0 defectos financieros críticos durante ventana acordada.

### Fase 9 — Producción CuentivoAI
- Crear producto Play, disclosure final, soporte, runbook e incident response.
- Done: todas las puertas de seguridad/negocio aprobadas por propietario y Sol; activar por porcentaje con kill-switch probado.

## 9. Test plan adversarial

### Tests unitarios/contratos

- Schema inválido, JSON truncado, enum no permitido, precio/importe negativo, overflow, fecha inválida, nombres/productos duplicados o ambiguos → `NeedClarification`/rechazo, **cero escritura**.
- “Diego me debe 500”, “pagó 200”, “2 perfumes de 300 y pagó 200”, corrección, deshacer y eliminar último pago → solo drafts; confirmación usa factories y auditoría existente.
- LLM alucina customerId, total, saldo, role, ruta, instrucción de sistema o herramienta → ignorado/rechazado.
- Misma idempotency key, doble tap, retry, timeout tras respuesta, request simultáneo y offline → una operación y una acción facturable como máximo.
- Sin cuota/plan/rol, expiración de entitlement, request gigante, flood y replay → backend no llama proveedor.

### Integración/emuladores/dispositivo

- Firebase emulator para Auth/Firestore/Functions; App Check/Auth rechazados y permitidos según caso.
- Room transaction + sync outbox + segundo dispositivo/offline: ningún saldo doble ni conflicto silencioso.
- OCR borroso/rotado, ticket con prompt injection, cámara cancelada, sin storage, Barcode conocido/desconocido.
- Voz sin servicio on-device, permiso denegado, transcripción equivocada y sin red.
- Android tests para preview/confirm/cancel, Compose semantics, TalkBack, texto grande, dark/light, teléfono pequeño y físico.

### Gates de fase

- `testDebugUnitTest`, tests Functions/emulator, compilación Android instrumentada y las clases afectadas ejecutadas en dispositivo.
- GLM revisa amenazas/concurrencia en fases 1–3 y 8; Sol verifica evidencia, diffs, reglas, secretos y rollback.
- Un fallo financiero, autorización rota o secreto expuesto es bloqueo absoluto de beta/producción.

## 10. Riesgos y decisiones pendientes

| Decisión/riesgo | Estado requerido |
|---|---|
| Empaquetado CuentivoAI | Aprobado: incluye Pro y no suma obligatoriamente $199 + $299. Falta configurar el producto sólo antes de producción AI. |
| Elegibilidad/fee Play, impuestos, precio local | Confirmar en Play Console/contador antes de modelo financiero final. |
| Firebase Spark vs Blaze, región y presupuesto | Confirmar en Firebase/Google Cloud antes de Functions/Storage. |
| Producto/subscription Play y server verification | No crear hasta fase comercial aprobada; debe existir antes de beta pagada. |
| Gemini proveedor/región, DPA/retención | Revisar contrato y privacidad antes de transferir contenido real. |
| Contexto enviado al modelo | Definir allow-list por intent y retención; default mínimo. |
| “Deshacer/eliminar último pago” | No dejar que IA borre: debe crear reversa usando reglas de ledger existentes y confirmación; posponer si no hay API de reversa segura. |
| Fotos cloud prometidas a Pro | Corregir copy o implementar Storage con presupuesto/retención antes de prometerlo. |

### Fotos cloud: proyecto separado

La sincronización/respaldo cloud de fotografías **no es dependencia ni subfase de CuentivoAI**. Hoy las fotos son privadas y locales. Si se decide resolver fotos cloud, se abrirá un diseño independiente que cubra Firebase Storage u otra alternativa, compresión, límites por plan, lifecycle/borrado, reglas de seguridad, privacidad, cuotas, egress y margen. Ningún agente añadirá Storage mientras implementa AI.

## 11. CUENTIVOAI GO/NO-GO GATE

El gate se evalúa después del lanzamiento público; no se autoriza por sensación ni por una cantidad mínima arbitraria de usuarios. El objetivo es demostrar simultáneamente estabilidad de Cuentivo base, interés de captura rápida y capacidad de limitar una beta.

### Métricas post-lanzamiento con privacidad mínima

| Métrica | Por qué importa | Infraestructura actual / cambio futuro | Datos que no se deben recopilar |
|---|---|---|---|
| Instalaciones, usuarios activos, sesiones y retención agregada | Dimensiona adopción y frecuencia sin exigir miles de usuarios | Firebase Analytics está incluido; revisar eventos/agregados existentes y agregar solo eventos de bajo cardinalidad después del launch | nombre, email, teléfono, businessId, contenido financiero |
| Conversión Gratis → Pro, cancelaciones y errores Billing | Valida disposición a pagar y salud del producto actual | Billing/entitlement existen; hace falta instrumentación agregada server-side solo si se implementa gateway | purchase token, correo, datos de pago |
| Ventas, pagos, clientes y frecuencia por usuario en rangos agregados | Revela si hay trabajo manual que AI podría reducir | Room ya tiene datos; futura telemetría debe enviar buckets/contadores por periodo, no registros | textos de venta, montos individuales, nombres de clientes/productos |
| Uso de capacidades Pro | Prioriza AI/barcode/fotos según valor real | Requiere eventos de capacidad agregados, con opt-out/consentimiento según política | archivos, fotos, CSV, lista compartida |
| Sync, Room, WorkManager, restore, multiusuario y auth | Identifica riesgo de corrupción/pérdida antes de ampliar superficie | Crashlytics ya usa eventos técnicos limitados; agregar códigos fijos y contadores, no payloads | ledger, stack traces con PII, uid, IDs, prompt |
| Crash-free users/sessions, crashes y ANRs | Puerta de estabilidad base | Crashlytics/Play Console; revisar métricas reales en consola | PII y valores de negocio en breadcrumbs/logs |
| Feedback, reseñas y solicitudes de captura rápida | Señal cualitativa de product-market interest AI | Play Console/soporte; clasificar manualmente por categoría | publicar feedback con identidad sin consentimiento |
| Interés en texto/foto/voz | Decide qué beta construir primero | Encuesta/entrevista opt-in y señales de uso, no vigilancia pasiva | grabaciones, fotos o mensajes completos sin consentimiento |

### Bloqueadores absolutos

- Corrupción, pérdida, duplicación o saldo financiero incorrecto.
- Billing que cobra/desbloquea incorrectamente, o entitlement/sync peligroso.
- Autorización rota entre negocios/roles, exposición de datos o secreto.
- Crash/ANR grave, restore que borra/adopta datos incorrectos, o regresión de autenticación/sincronización sin mitigación.
- Incapacidad de apagar AI o limitar gasto con efecto inmediato.

Un solo bloqueador sin causa, corrección, test de regresión y verificación independiente mantiene NO-GO.

### Señales de estabilidad

- Tendencia de crash-free users/sessions y ANRs aceptable para el volumen observado, sin severidades críticas abiertas.
- Billing Pro, Auth, Room, WorkManager, sync/restore y roles funcionan en datos reales durante una ventana suficiente para observar ciclos de uso; la longitud de ventana se decide por frecuencia real, no por un número impuesto.
- No existe incidente activo de datos/seguridad y el equipo puede reproducir, diagnosticar y revertir los incidentes que aparezcan.
- Presupuesto/alertas Firebase visibles y consumo entendido por usuario activo.

### Señales de product-market interest para AI

- Usuarios expresan repetidamente que capturar venta/pago o consultar deuda toma tiempo, y pueden describir un caso concreto de texto/foto/voz.
- Hay usuarios que registran operaciones de forma frecuente y aceptan participar con datos de prueba/consentimiento.
- La necesidad se concentra en uno o dos flujos, permitiendo beta pequeña; no se requiere miles de usuarios.
- Existe capacidad para cohort limitado, soporte y kill switch. Si hay estabilidad pero no interés claro, se conserva Cuentivo estable y se pospone AI.

## 12. Beta conceptual de CuentivoAI

Secuencia obligatoria: feature flag global apagado → cohort beta seleccionado → cuota baja configurable → observación de calidad/costo/error → expansión gradual reversible. La beta no se activa para todos ni se vende masivamente antes de evidencia.

Métricas beta de bajo riesgo de privacidad:

- acciones IA por usuario y por tipo;
- input/output tokens, costo estimado por acción/usuario y presupuesto restante;
- latencia, retries, timeouts, schema errors y rechazos de cuota;
- intents ambiguos, candidatos no resueltos y errores de proveedor;
- draft acceptance rate, draft correction rate y draft cancellation rate;
- feedback explícito y satisfacción voluntaria.

**Draft acceptance rate** es la métrica principal de calidad: un draft confirmado sin edición cuenta como aceptación; uno cambiado antes de confirmar cuenta como corrección; cancelado no prueba calidad. Se calcula con contadores/estados de request, no guardando el texto financiero completo. La expansión requiere calidad suficiente para el flujo concreto y costo por usuario dentro del hard cap aprobado; no hay un porcentaje universal fijado antes de observar beta.

## 13. Economía real posterior al lanzamiento

Antes del GO comercial definitivo se sustituyen supuestos por datos observados y agregados: costo real por usuario Gratis/Pro/AI, ARPU, margen antes de impuestos, costo IA por usuario, Firebase por usuario, requests/usuario, Storage por usuario si existe y carga de soporte. El precio $299 y referencia de 500 acciones se revisan contra esos datos; no deben quedar congelados por este documento.

## 14. ACCIONES MANUALES DEL USUARIO ANTES DE IMPLEMENTAR

### AHORA

1. **Monitorear el lanzamiento de Cuentivo actual.** Dónde: Play Console, Crashlytics y Firebase Console ya configuradas. Por qué: el gate requiere evidencia, no sensación. Traer: resumen agregado de crashes/ANR, instalaciones, uso disponible, errores de Billing, sync/restore y feedback. Secretos: ninguno. Posponer: no si se quiere evaluar el gate.
2. **Conservar feedback sobre captura rápida.** Dónde: Play Console, correo o canal actual. Por qué: prueba interés real en texto/foto/voz. Traer: temas agregados o anonimizados. No recolectar: nombres, tickets, deudas, fotos o texto financiero sólo para métricas. Posponer: sí, pero retrasa evidencia de interés.

No crear ahora cuenta/API key Gemini, gateway AI adicional, Cloud Run, Secret Manager, Firebase Storage, producto CuentivoAI de Play ni dependencias nuevas fuera de una fase autorizada.

### DESPUÉS DEL LANZAMIENTO

1. **Presentar evidencia para el GO/NO-GO.** Dónde: Play Console/Firebase/Crashlytics y feedback agregado. Por qué: decidir beta controlada. Traer: métricas observadas, incidentes abiertos, restores, problemas de autorización/sync y solicitudes de captura rápida. Secretos: ninguno. Posponer: hasta una ventana representativa de uso real.
2. **Decidir si se autoriza beta limitada.** Dónde: planificación de producto. Por qué: fija cohorte y presupuesto. Traer: tipo/número de participantes, presupuesto máximo y soporte. No compartir PII de clientes finales. Posponer: sí.
3. **Decidir por separado si se estudian fotos cloud.** Dónde: planificación de producto. Por qué: no es dependencia AI. Traer, sólo si se aprueba estudio: límites, retención y presupuesto; no credenciales. Posponer: sí, sin bloquear AI.

### ANTES DE BETA AI

1. **Autorizar explícitamente Fase 0.** Dónde: instrucción del propietario. Por qué: es el desbloqueo formal de Orca. Traer: aprobación del gate y orden de inicio. Posponer: sí; sin ella no se ejecuta handoff.
2. **Elegir backend seguro, región, presupuesto y alertas.** Dónde: cuenta cloud del propietario. Por qué: controla datos y gasto. Traer: proyecto/región/límites aprobados, no secretos. Posponer: hasta que Fase 1 lo requiera.
3. **Crear cuenta/API key del proveedor sólo cuando el gateway pueda guardar secretos.** Dónde: consola del proveedor. Por qué: la clave nunca vive en APK o repositorio. Traer: confirmación de secreto configurado, nunca su valor. Posponer: hasta Fase 1.
4. **Definir beta, consentimiento/disclosure y hard caps.** Dónde: mecanismo de acceso/Play Console aprobado. Por qué: beta reversible y segura. Traer: cohorte, cuota, presupuesto, soporte y criterio de apagado. Posponer: no antes de datos reales.
5. **Revisar privacidad, términos y Data Safety aplicables.** Dónde: web/Play Console, con asesoría apropiada. Por qué: texto OCR/transcripción puede salir del dispositivo. Traer: copy aprobado y conclusión de retención/consentimiento. Posponer: no antes de beta con datos reales.

### ANTES DE PRODUCCIÓN AI

1. **Configurar CuentivoAI en Play Console.** Debe ser el tier que incluye Pro, no una suma obligatoria de $199 + $299. Traer: precio final, regiones, impuestos, pruebas de compra y migración desde Pro. Posponer: hasta que beta pruebe valor/economía.
2. **Confirmar precio, cuota y límites con datos reales.** Traer: ARPU, costo real Gratis/Pro/AI, costo IA/Firebase/backend/storage si existe y soporte. $299 y ~500 acciones son referencias revisables.
3. **Aceptar contratos, billing y configuraciones externas definitivas sólo si la arquitectura los requiere.** Los secretos de producción permanecen en el gestor del backend, jamás en Git, chat, documentación o APK/AAB.

## 15. Checklist de aceptación de programa

- [ ] Release público estable separado y congelado.
- [ ] Fase 0 aprobada por propietario y Sol.
- [ ] Ninguna key/proveedor en APK, Git, logs o Crashlytics.
- [ ] Backend aplica Auth + App Check + rol + entitlement + cuota + límite antes de proveedor.
- [ ] Tests prueban que salida LLM fallida/maliciosa no modifica saldos.
- [ ] Preview/confirm/cancel/idempotencia/auditoría probados en dispositivo y multi-dispositivo.
- [ ] Flag global y rollback ensayados; Cuentivo normal funciona con gateway apagado.
- [ ] Costo real/acción y presupuesto revisados en beta.
- [ ] Política/Data Safety/términos revisados antes de datos reales.
- [ ] Activación producción gradual aprobada explícitamente.

## 16. Changelog de decisiones

| Fecha | Decisión | Estado |
|---|---|---|
| 2026-08-14 | Arquitectura base aprobada; implementación permanece NO-GO. | Aprobada |
| 2026-08-14 | CuentivoAI es posterior al lanzamiento/estabilización, incluye Pro y tiene referencia experimental de $299 MXN / ~500 acciones. | Aprobada; validar con datos reales |
| 2026-08-14 | IA es intérprete; Cuentivo conserva autoridad financiera, validación, preview, confirmación e idempotencia. | Aprobada |
| 2026-08-14 | Gateway seguro e AIProvider intercambiable antes de proveedor; jamás key en APK/AAB. | Aprobada |
| 2026-08-14 | Flash es candidato inicial; Barcode Pro/on-device; OCR local primero; voz experimental sin promesa offline universal. | Aprobada |
| 2026-08-14 | Fotos cloud se separan de CuentivoAI; actualmente son locales. | Aprobada como separación; capacidad cloud pendiente |
| 2026-08-14 | Fase 0A ejecutada en rama aislada: contratos locales, gate off-by-default, `FakeAiProvider` determinista y validador de drafts; sin red, proveedor ni costo. | Ejecutada con evidencia RED→GREEN; preview/confirmación diferidos |
| 2026-08-15 | Propietario autorizó Fase 0 como pista interna aislada: APK debug para prueba personal, sin AAB/Play público, proveedor, secretos, mutaciones IA ni autorización automática de fases posteriores. AAB `31 / 1.16.6` permanece en prueba cerrada con 12 testers durante 13 días. | Aprobada; requiere evidencia del gate y baseline Git inmutable antes de compatibilidad/release |
| 2026-08-15 | Fase 0: readiness interno documentado con tres documentos de operaciones — gate de prueba cerrada (`717db89`), observabilidad y modelo de amenazas (`068632c`) y protocolo de APK debug del propietario (`810ebbe`). No hay GO público sin métricas agregadas; Fase 1 bloqueada hasta aprobación explícita y baseline `31 / 1.16.6` congelado. | Readiness documentado; pendiente gate final de Sol/propietario |
| 2026-08-15 | Se congeló la fuente de compatibilidad `31 / 1.16.6` en `release/1.16.6-baseline` / `e40eb4e` con tag anotado `v1.16.6-31`. | Referencia histórica local; `lintDebug` falla por `AnimatedContent`; no equivale a GO público ni a validación completa |
| 2026-08-15 | Se integró la baseline en `diegodevxd/feature-cuentivo-ai` por merge `6224399`; único conflicto `NeoFintechShell.kt`, resuelto conservando `LocalMotionEnabled` y `key(targetDestination)`. | Auditada: JVM, assemble, lint y diff-check verdes; no inicia Fase 1 server-side ni habilita usuarios |
| 2026-08-15 | Fase 1A implementada localmente en `functions/` (scaffold `7b968f6`, contrato fail-closed `ab75d3b`, callable v2 Auth/App Check `24323a1`): `interpretAiRequest` con `enforceAppCheck: true` responde sólo `AI_NOT_CONFIGURED` al contrato v1 válido. Sin deploy, proveedor, secretos, Firestore, Android ni datos financieros. | Pendiente auditoría Sol; no marca Fase 1 completa ni GO público |
| 2026-08-15 | Auditoría Sol de Fase 1A: pruebas Functions (13), compilación/lint Functions, JVM/assemble/lint Android y diff-check fueron verificados localmente. La Function emulator fue iniciada por el implementador tras `ca03bba` (`functions.source`), pero la repetición independiente de Sol no terminó tras dos ventanas de 50 s y fue cancelada; Node 22 y `npm audit` tampoco pudieron verificarse por entorno local. | **NOT DONE:** mantener el endpoint fail-closed y no avanzar hasta reproducir el emulador en una sesión verificable con Node 22 y revisar dependencias cuando la CA local permita `npm audit`; no hay GO público ni proveedor |
| 2026-08-15 | Cierre de auditoría Fase 1A: Node `v22.23.2` validó independientemente los 13 tests, lint y build de Functions; JVM/assemble/lint Android y diff-check verdes. El emulador local descubrió `interpretAiRequest` con `functions.source`; el wrapper `npx.cmd` deja procesos huérfanos en este host, por lo que el procedimiento reproducible usa `firebase.js` directo y no altera cloud. | **Fase 1A DONE (sólo fundación local fail-closed).** App Check con token real, Auth real, rol, entitlement, cuota, idempotencia, Secret Manager, `npm audit` con CA reparada, proveedor y despliegue siguen fuera de alcance y bloquean Fase 1 completa/GO público. |
| 2026-08-15 | Propietario aprobó la preparación local completa pre-Blaze para una futura APK debug personal: UI/UX, contratos, fake, preview y capacidades locales se harán por paquetes auditables; la integración real queda desacoplada y se conectará sólo tras Blaze, secretos y autorización posterior. Diseño: `docs/superpowers/specs/2026-08-15-cuentivoai-pre-blaze-local-complete-design.md`. | Aprobada como diseño; pendiente revisión del documento y planes por paquete. **Fase 1B remota sigue bloqueada en Spark; no hay proveedor, key, deploy, Billing ni AAB.** |
| 2026-08-15 | Tras confirmación visual de Blaze en `organitazionlabs`, Sol desplegó exclusivamente `interpretAiRequest`: callable Functions v2 `us-south1`, Node.js 22 y 256 MiB. No se desplegaron otras Functions. Artifact Registry `gcf-artifacts` en `us-south1` recibió política de limpieza de imágenes antiguas a 1 día. | Despliegue remoto verificado por `functions:list`; permanece fail-closed (`AI_NOT_CONFIGURED`) y sin proveedor, secreto, Firestore Admin, cuota, entitlement, UI ni mutación financiera. **Fase 1B no está DONE** hasta comprobar en APK debug los rechazos de App Check/Auth y la respuesta válida autenticada, y auditar esa evidencia. |
| 2026-08-16 | Auditoría Sol cerró la verificación física de Fase 1B en A059P con APK debug descartable: la callable `us-south1` devolvió el contrato v1 exacto `AI_NOT_CONFIGURED` con Firebase Auth + token App Check debug registrado; una petición con ID token válido pero sin `X-Firebase-AppCheck` recibió HTTP 401 exacto. Los tests no enviaron texto, datos financieros ni secretos, y no alteraron Room/Firestore. Commits de prueba `e12255e` y `d713005`; JVM, assemble, lint, Functions Node 22 (15), diff-check y `functions:list` verificados. | **Fase 1B DONE, sólo gateway fail-closed.** El dispositivo de prueba es descartable y puede instalar/desinstalar APK debug por autorización explícita del propietario. No habilita proveedor, key, prompts, rol, entitlement, cuota, idempotencia de operación, UI productiva, mutación financiera ni GO público; esas capacidades siguen sujetas a sus fases y gates propios. |

| 2026-08-16 | Arquitectura multi-provider aprobada: DeepSeek V4 Flash es principal para texto; Gemini es multimodal y fallback condicionado. | **Supersedida el 2026-08-21** por Gemini-only para la primera producción; se conserva `AIProvider`, no se implementa routing multi-provider. |
| 2026-08-21 | Decisión de producción: Gemini 2.5 Flash-Lite es el único proveedor inicial. DeepSeek V4 Pro continúa sólo como implementador/revisor de desarrollo en OpenCode. Toda salida de Gemini deberá usar Structured Outputs/JSON Schema y sólo proponer intents/drafts; no muta dinero, Room, Firestore, outbox ni inventario. | Aprobada. Requiere reconciliar los enums/configuración DeepSeek+Gemini locales antes de cualquier integración real; no autoriza secret, SDK Gemini, llamada externa ni deploy. |
| 2026-08-21 | Auditoría de reanudación: `HEAD 4744fb2`, rama `diegodevxd/feature-cuentivo-ai`; Functions 64/64, lint, build y diff-check pasan. Fase 1B es el último incremento cerrado. Fase 2A contiene control plane local y un commit de throttle transaccional rechazado: después del límite todavía realiza 4 lecturas por petición distinta y `maxAttempts: 50` puede amplificar coste/latencia. | **Fase 2A NOT DONE.** No desplegar `authorizeAiAction`; definir primero defensa de borde y semántica de coste/retry. `docs/superpowers/handoffs/` no rastreado se preserva. |
| 2026-08-22 | Incremento de abuso/coste de Fase 2A auditado: `57bc5bb` restaura que sólo `AI_AUTHORIZED` escribe ledger/uso/rate y elimina `maxAttempts:50`; `7424259` agrega un guard cálido acotado antes de Firestore; `8654b1b` evacúa ventanas vencidas y restringe replay a IDs admitidos. Codex verificó Functions 75/75, lint, build, ambos diff checks y Firestore Emulator 2/2; GLM revisó el diff dos veces, el hallazgo medio de lockout vencido fue corregido y el recheck no halló bloqueos. | Incremento **aceptado localmente**. El guard es mitigación de costo por instancia, no rate limit durable: cold starts lo reinician y una saturación de UIDs activos del mismo minuto falla cerrada sólo para AI. Sin deploy, proveedor/SDK/secret, Android, Room, dinero, reglas ni AAB. **Fase 2A/2 siguen NOT DONE:** falta Task 3 Gemini-only y gates posteriores. |
| 2026-08-22 | Task 3 Gemini-only local cerrada en `c435c31`: `AiProviderId` es exactamente `GEMINI`, `geminiEnabled:true` es la única configuración habilitante y ledger histórico no-Gemini falla cerrado. Codex verificó 75/75, lint, build, ambos diff checks y Emulator 2/2; GLM revisó seguridad, idempotencia y el retry acotado del gate de contención sin bloqueador. | **Fase 2A DONE localmente.** No hay deploy de `authorizeAiAction`, SDK/API key/Secret Manager/prompt/red Gemini, UI Android, Room, datos financieros ni release. Fase 2 de producción y Fase 3 siguen sujetas a autorización/gates propios. |
| 2026-08-22 | Incremento local Gemini Debug Gateway — Tasks 1-3 aceptados por Codex (`9baefbd`, `12cb5ac`, `ab1f4fe`, `0753b07`, `8e659a3`, `4852510`, `ba5a615`, `430fe8b`, `4e04052`): contrato estricto de texto (máx. 1000 unidades UTF-16 tras trim); `SafeInterpretation` no-financiera de cuatro pares allow-listed; adapter server-only Gemini 2.5 Flash-Lite con JSON Schema, sin retry y con timeout; Secret Manager `CUENTIVOAI_GEMINI_API_KEY` enlazado sólo a `interpretAiRequest`; Auth/App Check preservados; claim durable Firestore antes del provider; edge guard de ingreso cálido; sin persistencia/logging de prompt ni salida; sin Android/Room/mutación financiera. | Incremento local **aceptado por Codex**; NO es lanzamiento producción/beta ni cierre de fase. **Revisión GLM diferida explícitamente por instrucción del propietario** (ahorro de tokens): no se afirma que ocurrió. Sin deploy de esta implementación, sin secret configurado, sin llamada Gemini y sin control-plane remoto; `interpretAiRequest` desplegado (Fase 1B) permanece fail-closed. Release/AAB/beta siguen **NO-GO**; Fase 2 producción y Fase 3 texto-a-draft-financiero siguen **NOT DONE**. Evidencia Task 4: `docs/superpowers/plans/2026-08-22-cuentivoai-gemini-debug-gateway-deploy-checklist.md`. |
| 2026-08-23 | Diseño y plan de captura multimodal aprobados: texto, foto de libreta/ticket con OCR local primero y voz a transcripción editable convergen en propuestas tipadas Gemini-only. Toda vía conserva validación determinista, preview y confirmación; fallback directo multimedia, ejecución financiera y beta son incrementos/gates posteriores. | Documentado en `specs/2026-08-23-cuentivoai-multimodal-draft-capture-design.md` y `plans/2026-08-23-cuentivoai-multimodal-capture-program.md`; no autoriza secreto, deploy, Storage, cambio comercial ni mutación. |
| 2026-08-23 | Incremento A / Fase 3a cerrado localmente en `d0e6c82`: propuestas versionadas y puras para venta, abono, consulta allow-listed y aclaración; validador fail-closed sin Room/Firestore/I-O; colecciones inmutables y comparación de períodos sin overflow. Codex observó RED real de orden de fechas desbordado (18 tests, 1 fallo) y verificó el GREEN corregido. | **Incremento A aceptado.** Tests JVM forzados, `assembleDebug`, `lintDebug` y `git diff --check` verdes. No hay UI, red Gemini, deploy, secreto, Room, cálculo financiero, confirmación ni mutación. Siguiente incremento: contrato callable de texto tipado, aún debug/no ejecutable. |
| 2026-08-23 | Incremento B1 / Fase 3b cerrado localmente en `fd2331a`: callable v2 `interpret_text_proposal` y Gemini 2.5 Flash-Lite devuelven sólo propuestas Structured Output estrictas para aclaración, venta, abono o consulta. Centavos viajan como strings decimales canónicas para evitar pérdida JS/Kotlin; parser server-side vuelve a validar schema, fechas, shapes y límites. El schema separa las seis ramas exactas (incluidos tres tipos de consulta), sin campos cruzados; salida limitada a 512 tokens, 8 líneas y nombres de 80 caracteres. | **Incremento B1 aceptado localmente.** Codex verificó Functions 199/199, lint, build y diff-check. Sin deploy, secret configurado, llamada Gemini, Android, Room, confirmación ni mutación. La Function remota desplegada sigue siendo v1 fail-closed; no se debe mezclar esta afirmación con producción. Siguiente incremento: transport Android debug v2/no ejecutable. |
| 2026-08-23 | Incremento B2 / Fase 3b cerrado localmente en `a2e5abb`: Android debug construye el request callable v2 y convierte únicamente respuestas exactas del contrato en `AiProposal` tipadas. El parser rechaza shapes, claves, montos no canónicos, cantidades, nombres, fechas, períodos, slots y selectores inválidos; cualquier error de red o contrato se reduce a `Unavailable`. | **Incremento B2 aceptado localmente.** Codex verificó `testDebugUnitTest`, `assembleDebug`, `lintDebug` y `git diff --check`; el primer intento expuso concurrencia de archivos mientras OpenCode aún escribía, y la repetición estable pasó completa. Sin UI conectada, deploy v2, secret, llamada Gemini, Room, ejecución, confirmación ni mutación financiera. Siguiente incremento: preview de texto debug no ejecutable. |
| 2026-08-23 | Incremento C / Fase 3c cerrado localmente en `e9e6346`: laboratorio de texto visible sólo en debug, con edición, single-flight, cancelación por generación, preview tipado y estado fail-closed. El bridge release devuelve `null`, por lo que no hay entrada visual ni transporte Firebase en release; la UI principal no importa Firebase. | **Incremento C aceptado localmente.** Codex verificó JVM, `assembleDebug`, `assembleRelease`, `lintDebug` y `git diff --check` en una ejecución serial sin daemon. Un primer gate falló por bloqueo concurrente de Gradle en `app/build`; no fue fallo de código y se reprodujo verde tras detener sólo el daemon. No hay deploy, secret, llamada Gemini real, Room, confirmación, cálculo financiero ni mutación. Siguiente incremento: OCR local privado de libreta/ticket. |

## 17. HANDOFF A ORCA — FASE 0 INTERNA AUTORIZADA

### Punto de partida

- **Fuente de verdad:** este archivo.
- **Estado actual:** Cuentivo Android local-first con Room, Firebase Auth/Firestore, WorkManager, Billing FREE/PRO y fotos locales. No existe gateway AI, proveedor AI, Cloud Functions/Run ni Storage cloud de fotos para CuentivoAI.
- **Aprobado:** sección 2 y changelog. CuentivoAI es posterior, incluye Pro; $299 y ~500 acciones son hipótesis experimentales.
- **Pendiente:** inventariar evidencia de prueba cerrada, congelar referencia Git de `31 / 1.16.6`, y evaluar el GO/NO-GO público. Backend/región/proveedor, presupuesto/cohorte comercial, precio final, documentación de privacidad y fotos cloud siguen pendientes.

### Entrada y salida de la siguiente fase

Este handoff histórico describe la antigua Fase 0; el estado vigente se encuentra en el encabezado y changelog. No autorizaba conexión a proveedor, API key, operaciones financieras ni habilitación pública.

### Roles, escalamiento y pruebas

- Codex/Sol define fase/criterios, audita seguridad y decide DONE con evidencia.
- V4 Pro implementa backend, seguridad, cuotas, entitlement server-side, idempotencia y debugging complejo.
- V4 Flash hace tareas mecánicas, documentación, UI simple y tests auxiliares; también es el candidato inicial de inferencia.
- GLM 5.3 revisa adversarialmente dinero, seguridad, concurrencia, abuso e idempotencia; Qwen local sólo riesgo bajo/medio tras validación.
- Escalar Flash/Qwen a V4 Pro tras dos intentos sin progreso, fallos persistentes de compilación/tests, ambigüedad o cualquier cambio de seguridad, dinero, sync, concurrencia o backend sensible.
- Nadie declara DONE sólo por generar código: exige compilación/tests, evidencia, review adversarial cuando aplique y auditoría Codex/Sol.
- Ejecutar obligatoriamente la sección 9: schemas inválidos, ambigüedad, retry/doble tap/idempotencia, offline/timeout, auth/cuota, concurrencia/sync, feature flag/kill switch y regresión Gratis/Pro. Debe demostrarse que un LLM erróneo no altera datos financieros.

### Acciones manuales al handoff

Aplicar sección 14: orden explícita, evidencia del gate, cohorte/presupuesto y, sólo cuando corresponda, configuraciones externas y secretos fuera del repositorio.

## 18. Fuentes externas consultadas

- Firebase: [pricing](https://firebase.google.com/pricing), [Firestore pricing](https://firebase.google.com/docs/firestore/pricing), [Functions quotas](https://firebase.google.com/docs/functions/quotas).
- Android/ML Kit: [Text Recognition Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [Barcode Scanning Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).
- Monetización: [Google Play service fees](https://support.google.com/googleplay/android-developer/answer/112622). El precio vigente de Gemini debe verificarse en documentación oficial antes de beta/producción.
