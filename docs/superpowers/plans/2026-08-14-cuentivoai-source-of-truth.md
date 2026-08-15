# CuentivoAI — arquitectura, seguridad y plan de ejecución

> **Estado:** el lanzamiento público y cualquier AAB/activación para usuarios permanecen en **NO-GO**. Excepciones aprobadas: **Fase 0A — fundaciones locales off-by-default**, ya cerrada; y desde 2026-08-15, **Fase 0 — preparación interna para APK debug**, únicamente en la rama aislada `diegodevxd/feature-cuentivo-ai` y para pruebas del propietario. La excepción interna no autoriza proveedor, secretos, operaciones financieras IA, AAB, Play ni el inicio automático de fases posteriores.
>
> **Source of truth:** este documento prevalece para CuentivoAI hasta que el propietario apruebe una fase explícitamente. Las decisiones y supuestos deben actualizarse aquí antes de cambiar producción.
>
> **Fecha de auditoría:** 2026-08-14. **Rama inspeccionada:** `codex/neo-fintech`.

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
| No hay backend de ejecución | `firebase.json` solo Firestore/Auth/emuladores | Crear backend mínimo protegido, no exponer DeepSeek desde Android. |
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
5. DeepSeek V4 Flash es el candidato inicial de inferencia de producción, detrás de `AIProvider` intercambiable.
6. El modelo nunca tiene autoridad financiera: interpretación → schema → validación → lógica determinista → preview → confirmación → operación idempotente → DB.
7. La key de DeepSeek jamás reside en APK/AAB; un gateway seguro es requisito previo a todo proveedor real.
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
                                                               |-- AIProvider (DeepSeek inicialmente; intercambiable)
                                                               '-- devuelve AiDraft o QueryIntent firmado por requestId

Android -- validación de dominio + preview + confirmación --> Room transaction existente
                                                        --> OperationEntity + AuditLog + Sync outbox
                                                        --> Firestore actual
```

### Backend elegido

Usar **Cloud Functions for Firebase v2 (callable/HTTP) en el proyecto Firebase existente**, con Secret Manager para la key del proveedor. Es el mínimo que reutiliza Auth, App Check, Firestore, emuladores y observabilidad. Alternativa aceptable si la carga/streaming exige más control: Cloud Run detrás de Firebase Auth/App Check; no se decide ni se crea hasta medir beta.

El gateway no escribe operaciones financieras. Sus únicas colecciones nuevas propuestas son de control: `aiEntitlements/{uid}`, `aiUsage/{uid}/{period}`, `aiRequests/{requestId}` y métricas agregadas. Toda lectura/escritura queda con Admin SDK y reglas Firestore niegan acceso directo del cliente a esas rutas.

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
3. Gateway autoriza, debita/reserva una acción idempotente y llama proveedor en JSON mode, sin pensamiento/streaming para este caso.
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
2. Preferir `SpeechRecognizer.createOnDeviceSpeechRecognizer` únicamente donde esté disponible; el API estándar puede depender de un servicio/red del dispositivo, por lo que no se promete offline ni se envía audio a DeepSeek.
3. Mostrar transcripción editable; únicamente el texto confirmado llega al gateway y continúa como Texto → operación.
4. Si no existe reconocimiento on-device, ofrecer escribir o activar explícitamente el reconocedor del dispositivo; no contratar STT remoto en v1.

### Consultas naturales

El modelo solo devuelve `BusinessQuery` de una allow-list: deuda por cliente, mayor deudor, ventas por periodo, clientes con saldo. Android ejecuta consultas deterministas locales y enseña el resultado. Para resúmenes, enviar al proveedor solo agregados mínimos si la versión beta lo habilita. Nunca enviar toda la base ni permitir que el modelo declare saldos.

### Barcode

ML Kit Barcode Scanning on-device → normalizar código → buscar `Product.sku` local → seleccionar producto si hay una coincidencia; si no, ofrecer formulario precargado. No LLM, no cuota AI, no red obligatoria.

## 5. Seguridad, privacidad y rollback

### Controles obligatorios

- Secret de DeepSeek solo en Secret Manager; jamás `BuildConfig`, APK, repositorio, Crashlytics o logs.
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

### DeepSeek V4 Flash — costo de modelo

La documentación oficial a fecha de auditoría publica cache miss $0.14/M input y $0.28/M output (USD), con precios sujetos a cambio. Modelo normal conservador: 2,000 input + 500 output por acción = **$0.00042 USD/acción**. Intensivo: 8,000 + 1,000 = **$0.00140 USD/acción**. Multiplicar USD por el tipo de cambio que el propietario confirme; no se fija uno en código.

| Acciones AI por usuario/mes | Normal USD | Intensivo USD |
|---:|---:|---:|
| 100 | 0.042 | 0.140 |
| 500 | 0.210 | 0.700 |
| 1,000 | 0.420 | 1.400 |

| Usuarios todos CuentivoAI, 500 acciones | Acciones/mes | modelo normal USD | modelo intensivo USD |
|---:|---:|---:|---:|
| 10 | 5,000 | 2.10 | 7.00 |
| 100 | 50,000 | 21.00 | 70.00 |
| 1,000 | 500,000 | 210.00 | 700.00 |
| 10,000 | 5,000,000 | 2,100.00 | 7,000.00 |

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
- Estado: iniciada el 2026-08-15 por autorización explícita del propietario. Referencia actual: AAB `31 / 1.16.6` en prueba cerrada de Play Console con 12 testers y 13 días; todavía no constituye lanzamiento público ni baseline Git congelado.
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
- Contrato de intent versionado, proveedor DeepSeek Flash, schema, candidates, preview y confirmación usando dominio/Room existentes.
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
| DeepSeek proveedor/región, DPA/retención | Revisar contrato y privacidad antes de transferir contenido real. |
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

No crear ahora cuenta/API key DeepSeek, gateway AI, Cloud Functions/Cloud Run, Secret Manager, Firebase Storage, producto CuentivoAI de Play ni dependencias nuevas.

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

## 17. HANDOFF A ORCA — FASE 0 INTERNA AUTORIZADA

### Punto de partida

- **Fuente de verdad:** este archivo.
- **Estado actual:** Cuentivo Android local-first con Room, Firebase Auth/Firestore, WorkManager, Billing FREE/PRO y fotos locales. No existe gateway AI, proveedor AI, Cloud Functions/Run ni Storage cloud de fotos para CuentivoAI.
- **Aprobado:** sección 2 y changelog. CuentivoAI es posterior, incluye Pro; $299 y ~500 acciones son hipótesis experimentales.
- **Pendiente:** inventariar evidencia de prueba cerrada, congelar referencia Git de `31 / 1.16.6`, y evaluar el GO/NO-GO público. Backend/región/proveedor, presupuesto/cohorte comercial, precio final, documentación de privacidad y fotos cloud siguen pendientes.

### Entrada y salida de la siguiente fase

La fase actual es **Fase 0: contratos, flags, modelo de amenazas y observabilidad mínima**, no conexión inmediata a DeepSeek. Fue autorizada como alcance interno sin proveedor para una futura APK debug del propietario. Su salida exige contratos revisables, flags/rollback, telemetría mínima sin PII innecesaria, modelo de amenazas y plan de pruebas; no maneja API key, operaciones financieras ni habilitación pública. Su cierre no autoriza Fase 1: ésta requiere nueva aprobación explícita, gate revisado y baseline Git de release congelado.

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
- Monetización/modelo: [DeepSeek API Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing), [Google Play service fees](https://support.google.com/googleplay/android-developer/answer/112622).
