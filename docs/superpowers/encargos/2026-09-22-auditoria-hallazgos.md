# Auditoría de cinco hallazgos (Codex)

**Base:** `226fa59` en `diegodevxd/feature-cuentivo-ai`. **Audita:** Codex, **sólo lectura**.
Lee `CLAUDE.md`. **No edites archivos, no commitees, no corras Gradle.**
Reporte en `C:/tmp/codex-auditoria-reporte.md`.

Claude hizo un barrido de la app y propone esto para la v43. Tu trabajo es **confirmar o tumbar cada
punto con evidencia del código** (archivo:línea), no darle la razón. Si un hallazgo está mal, dilo
de frente; si falta algo más grave que lo que aquí aparece, añádelo.

## Hallazgo 1 · Las deudas importadas por foto de libreta nunca se vencen

Afirmación: las ventas que crea la importación de libreta se guardan con `dueDateEpochDay = null`
(`domain/AiLedgerBatchPlanner.kt:365` y `:407`). Consecuencias que Claude **supone** y que tienes
que verificar una por una:

1. `CustomerScoreCalculator` nunca las califica (exige 3 ventas con fecha límite) → el cliente
   importado se queda en "sin calificación" para siempre.
2. `SaleStatusCalculator` nunca las pone en `OVERDUE`.
3. El recordatorio de cobro (`CollectionReminderWorker`) nunca las incluye.
4. El recuadro "vence en N días / vencido" de Detalle de Cliente nunca sale.

Preguntas: ¿hay **otra** ruta que les asigne fecha después (un default en el DAO, en la migración
legacy, en la venta manual)? ¿La venta **manual** también puede quedar sin fecha, y con qué
frecuencia la UI lo permite?

Claude propone a Diego tres salidas: **A)** plazo fijo (ej. venta + 15 días), **B)** plazo por negocio
configurable en Cuenta aplicado a toda venta sin fecha, **C)** preguntarlo en la pantalla de revisión.
Recomienda **B**. Opina con argumentos, y señala qué pasa con **las ventas ya importadas**: ¿se les
puede asignar fecha sin violar la regla 2 de `CLAUDE.md` (nada financiero se edita)? ¿O la fecha
tiene que ser un cálculo derivado en lectura, no una columna escrita?

## Hallazgo 2 · 18 mensajes en español fijo en `MainActivity.kt`

`grep -nE '(uiMessage = |share\()"[^"]*[a-záéíóúñ]' app/src/main/java/com/example/organizationlabs/MainActivity.kt`
da 18. Ejemplos: `:842`, `:950`, `:963`, `:1694`, `:1988`. La puerta de idioma de `CLAUDE.md` sólo
escanea `ui/`, así que nunca los vio. Confirma el conteo, busca los que el grep no caza (otros
patrones: `Toast`, `getString` ausente, asuntos de `share`, `SyncWorker`, notificaciones de
`CollectionReminderWorker`), y dilo si alguno **no debe** traducirse.

## Hallazgo 3 · Crashlytics

Últimos 30 días: nada en 1.21/1.22. Un FATAL en 1.20.0 dentro de
`com.android.billingclient.api.ProxyBillingActivity.onCreate` (NPE en `PendingIntent.getIntentSender`,
1 usuario). La app usa `billing-ktx:8.0.0`. ¿Es un fallo conocido de la librería, y lo arregla una
versión posterior? Si no puedes consultar la red, dilo en vez de suponer.

## Hallazgo 4 · El nombre del negocio no se puede editar

No hay `businessDao().update()` en ningún sitio. Ahora sale de encabezado en la imagen que recibe el
cliente. Confirma, y dime qué tocaría una pantalla de edición (sync: `BUSINESS` es descriptivo y se
sobrescribe por más nuevo; reglas: `UPDATE_BUSINESS` es sólo OWNER).

## Hallazgo 5 · Deuda técnica

`MainActivity.kt` de 2640 líneas; el TODO de índices en `FinancialDao.kt:461`. ¿Alguno es urgente a
la escala real (decenas o cientos de clientes por negocio), o se queda como está?

## Entrega

Por hallazgo: **CONFIRMADO / PARCIAL / FALSO**, con archivo:línea. Luego tu orden de prioridad para
la v43 (sale a más tardar el 2026-09-28) y cualquier hallazgo nuevo que pese más que estos.
