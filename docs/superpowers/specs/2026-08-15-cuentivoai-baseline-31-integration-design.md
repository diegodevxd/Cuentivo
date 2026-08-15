# Integración de baseline 31 en CuentivoAI — Diseño

## Propósito

Integrar la referencia histórica local de Cuentivo `31 / 1.16.6` en la rama aislada `diegodevxd/feature-cuentivo-ai`, sin modificar la referencia ni habilitar CuentivoAI para usuarios.

## Referencias inmutables

- Baseline: `e40eb4e04404a63f9fa0d15e330834780961cd63`.
- Tag: `v1.16.6-31` en `release/1.16.6-baseline`.
- Ancestro común: `e4815f2767fae0f9761e29be6835a878bb43e308`.
- La rama CuentivoAI conserva sus commits locales de Fase 0A y Fase 0.

## Estrategia

Se realizará un merge no destructivo de la baseline hacia la rama CuentivoAI. No se usa rebase ni cherry-pick porque ambos reescribirían o duplicarían historia y harían más difícil auditar la procedencia del código de release.

El merge toma la baseline como autoridad para las funciones existentes de Cuentivo: Android, Gradle, Auth, Firestore, Room, WorkManager, Billing, sincronización, UI, reglas y pruebas existentes. Los únicos cambios exclusivos que deben sobrevivir desde CuentivoAI son:

- `app/src/main/java/com/cuentivo/app/ai/**` y sus pruebas JVM;
- documentación bajo `docs/superpowers/**` creada para CuentivoAI;
- la corrección de lint de Fase 0A en `NeoFintechShell.kt`, adaptada al árbol 31 de modo que la lambda de `AnimatedContent` dependa de `targetDestination`.

No se crea ni cambia proveedor real, gateway, Firebase Functions/Run, Secret Manager, cuota, entitlement, red IA, secreto, Room/Firestore financiero, Billing AI, versión, `applicationId`, AAB o Play.

## Resolución de conflictos

1. Antes de confirmar el merge, el implementador lista cada conflicto y su decisión.
2. Para archivos de producto sin cambios AI, gana la baseline 31.
3. Para archivos AI y documentos AI exclusivos, gana CuentivoAI.
4. Para `NeoFintechShell.kt`, se conserva el comportamiento de UI 31 y se agrega sólo la asociación determinista del destino al subárbol animado; no se altera navegación, dinero ni acciones de negocio.
5. Cualquier conflicto en Room, DAO, entidad, migración, Firestore, sincronización, Billing, Auth, reglas o lógica financiera detiene el merge y se escala a Sol antes de resolverlo.

## Criterios de aceptación

- `git merge-base HEAD e40eb4e` queda como ancestro de la rama CuentivoAI y el merge commit declara la baseline como segundo padre.
- El tag `v1.16.6-31` y la rama `release/1.16.6-baseline` no cambian.
- `versionCode` y `versionName` del árbol integrado son `31` y `1.16.6`.
- Permanecen los contratos, fake y validadores locales de Fase 0A; siguen sin red ni proveedor real.
- `testDebugUnitTest`, `assembleDebug`, `lintDebug` y `git diff --check` pasan desde la rama integrada.
- Ningún archivo de secretos, artefacto AAB/APK, `google-services.json` ni carpeta de herramientas se añade al commit de integración.
- `docs/superpowers/handoffs/` permanece sin rastrear e intacto.

## Riesgo conocido y verificación

La baseline histórica reproduce un error de lint de `AnimatedContent`. La integración no modifica el tag; incorpora la corrección local ya revisada mediante un test de contrato de fuente y exige que `lintDebug` pase en el árbol fusionado.

## Rollback

Mientras el merge esté sin commit, `git merge --abort` devuelve exactamente la rama CuentivoAI previa. Tras un merge commit aprobado, el rollback es un `git revert -m 1 <merge-commit>` en la rama CuentivoAI; nunca se mueve ni reetiqueta `v1.16.6-31`.

## Fuera de alcance

Esta integración es un prerrequisito de compatibilidad. No constituye Fase 1, no es un GO público y no autoriza infraestructura externa ni una APK de prueba nueva.
