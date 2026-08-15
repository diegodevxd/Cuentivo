# CuentivoAI: diseño de preparación local completa pre-Blaze

**Estado:** diseño aprobado por el propietario el 2026-08-15; pendiente de revisión de este documento antes de escribir planes de implementación.

## Propósito

Preparar en la rama aislada la experiencia y los límites técnicos de CuentivoAI para una APK `debug` personal, sin publicar AAB ni requerir todavía Blaze, saldo de DeepSeek, una API key o un despliegue cloud. El objetivo es dejar cada capacidad lista para conectarse después a un gateway seguro, no simular una integración de producción.

La referencia de compatibilidad es `v1.16.6-31` en `e40eb4e04404a63f9fa0d15e330834780961cd63`. La rama de trabajo es `diegodevxd/feature-cuentivo-ai`. La release estable y su checkout protegido no se modifican.

## Invariantes no negociables

- CuentivoAI es opcional y empieza desactivada; al apagarla, Gratis y Pro conservan su comportamiento existente.
- Ningún resultado de IA, fake, OCR o voz escribe Room, Firestore, ventas, pagos, saldos o deudas directamente.
- Todo registro potencialmente financiero sigue el flujo: entrada no confiable -> draft tipado -> validación determinista -> preview editable -> confirmación explícita -> comando existente idempotente -> auditoría/sync existente.
- No se añaden secretos, API keys, proveedor real, llamadas directas a DeepSeek, funciones desplegadas, Storage, AAB, cambios de `applicationId`, `versionCode` ni `versionName`.
- No se guardan prompts, OCR, transcripciones ni respuestas crudas en Analytics o Crashlytics.

## Decisión de arquitectura

Se adopta una pista local por capas. La UI depende de contratos de dominio ya existentes (`AiProvider`, drafts y validador puro), no de Firebase Functions ni de DeepSeek. En `debug` podrá usarse `FakeAiProvider` determinista para producir escenarios repetibles; la capa callable actual permanece una frontera de integración fail-closed y no se activa para usuario final.

Cuando haya Blaze y se autorice, el gateway regional sustituirá la implementación local detrás del mismo contrato. El gateway verificará Auth, App Check, rol, entitlement, cuota, límites, idempotencia y kill switch antes de invocar a un proveedor. Esa activación es una fase separada: no está implícita en esta preparación.

## Alcance descompuesto

Cada paquete será objeto de su propio plan, implementación delegada y gate de Sol; no se implementarán como un cambio masivo.

1. **Fundación de producto y flag.** Entrada CuentivoAI claramente experimental/opt-in, estado off por defecto y mensajes honestos de disponibilidad local. El tier CuentivoAI se presenta como futuro tier que incluye Pro; no modifica Play Billing ni autoriza compra.
2. **Texto a draft y preview.** Entrada editable, resultado del fake, errores y estados de carga; preview sin persistencia automática. Sólo una confirmación explícita puede llamar al caso de uso financiero existente, después de comprobar que la ruta concreta conserva idempotencia y auditoría.
3. **Consultas naturales deterministas.** El intérprete produce una intención allow-listed y la app consulta datos reales mediante repositorios existentes. El modelo nunca calcula ni inventa importes y la vista distingue errores, ambigüedad y ausencia de datos.
4. **Barcode Pro local.** Cámara/lector local y búsqueda de producto existente; si no existe, sólo ofrecer el flujo normal de alta. No usa LLM ni consume cuota AI.
5. **OCR local.** Texto obtenido en dispositivo, siempre editable antes de pasar al mismo flujo de texto a draft. No se suben imágenes ni se implementa fotos cloud.
6. **Voz experimental.** Transcripción Android editable, con disponibilidad dependiente del dispositivo. No se promete funcionamiento offline universal ni se confirma una operación sin preview.
7. **Preparación de activación cloud.** Contratos, errores y adaptadores necesarios para reemplazar fake por gateway sin que la UI conozca el proveedor. La callable debug existente sigue excluida del release; no se crean secretos ni se despliega infraestructura.

## Experiencia y estados

La UI debe mostrar de forma explícita: apagado por flag, no configurado, entrada vacía, interpretando, draft válido, draft ambiguo/invalidable, sin conectividad cuando aplique, cancelado y confirmado. "Confirmado" significa que el usuario confirmó un preview y que el caso de uso existente reportó éxito; nunca significa que un modelo lo ejecutó.

Para la APK personal, la primera prueba usa cuenta y datos ficticios. El upgrade desde `1.16.6` y la coexistencia se analizan antes de introducir un `applicationIdSuffix`; no se cambia ahora. Toda prueba física tendrá instrucciones de instalación, backup, reset y rollback antes de involucrar datos reales.

## Verificación por paquete

Cada paquete exige pruebas unitarias de contrato/validador y los casos adversariales pertinentes; pruebas de UI cuando introduzca UI; y al menos `testDebugUnitTest`, `assembleDebug`, `lintDebug` y `git diff --check`. Cualquier cambio que alcance persistencia, Room, sync, Billing o identidad añade las pruebas de compatibilidad y regresión correspondientes, y requiere revisión adversarial cuando haya dinero, concurrencia o idempotencia.

El gate final no depende de que compile: Sol revisa diff, alcance, secretos, rutas de persistencia y evidencia reproducible. GLM sólo entra ante riesgo alto real (dinero, seguridad, concurrencia, cuotas, abuso, sync o Billing).

## Activación posterior de Blaze y DeepSeek

La Fase 1B remota permanece bloqueada mientras el proyecto `organitazionlabs` esté en Spark. Tras una autorización futura y Blaze activo, se verificará el despliegue regional `us-south1`, App Check real, Auth, controles server-side y secretos fuera del repositorio. Sólo después se crea/configura la API key de DeepSeek en el gestor de secretos del backend y se hacen pruebas de gasto con hard caps. El backend no escribe operaciones financieras.

No se toma ahora decisión de migrar a Cloudflare ni de usar fotos cloud: ambas son decisiones independientes y requerirían una nueva evaluación de seguridad, costo y operación.

## Rollback

El rollback local es apagar el flag CuentivoAI y, si hiciera falta, revertir los commits de la capacidad afectada en la rama de feature. No debe requerir migración destructiva ni afectar datos de la release. El rollback cloud futuro será apagar el kill switch, que debe conservar Cuentivo normal y Pro operativos.

## Fuera de alcance

- Despliegue Functions/Run, Blaze, Secret Manager, DeepSeek real y gasto del proveedor.
- Cobro, productos Play o cambios de entitlement comercial.
- AAB, publicación, producción AI, beta pública o migración de versión.
- Firebase Storage/fotos cloud.
- Automatizar borrado, deshacer o modificación financiera desde lenguaje natural.
