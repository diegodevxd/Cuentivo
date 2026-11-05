# Encargo iOS — Pruebas pendientes e interfaces de la Fase 4

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
Misma carpeta, reglas, puerta y formato que los encargos anteriores.
**Hora límite dura: reporte escrito antes de las 03:15.** A las 03:10 deja de empezar tareas nuevas;
lo que no termines, revertido o en un commit limpio, va a pendientes.

**T1. Las 14 pruebas que quedaron en `app/src/test/.../domain/`** (pendiente 1 de
`C:/tmp/oc-ios-r-reporte.md`): pásalas a `commonTest` con el método de F1.4 (`java.time` →
`kotlinx-datetime` solo en la construcción de datos de prueba; mismos casos y valores esperados).
Si una prueba ejercita código que sigue en `app`, se queda.

**T2. Fase 4, solo interfaces (sin dependencias nuevas).** Lee la "Fase 4" del plan. **No agregues
GitLive ni ninguna librería.** Solo:
- Identifica las interfaces que ya usan `SyncWorker`/`SyncDrainer`/`CloudRestore` en
  `sync/SyncContracts.kt` y archivos vecinos. Si son Kotlin puro, muévelas a `commonMain`
  (mismo paquete). Las clases de Firestore (`FirestoreSyncGateway`, `FirestoreDeletionGateway`,
  `RemoteMerger` si usa tipos de Firebase) se quedan en `app` como implementación Android.
- Si `RemoteMergePlanner`, `RemoteMergePolicy`, `SyncPayloads` u otra lógica de sync es pura, muévela
  a `commonMain` con sus pruebas. **El lector de dinero que rechaza `Double` no se toca.**
- Documenta en el reporte qué interfaz falta implementar en iOS y con qué tipos.

## Reporte

`C:/tmp/oc-ios-t-reporte.md`. Al terminar: `ENCARGO-IOS-T-TERMINADO`.
