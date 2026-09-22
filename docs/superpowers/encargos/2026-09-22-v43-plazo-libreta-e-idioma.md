# v43 · Plazo en la libreta, y los avisos fijos en español (OpenCode)

**Rama:** `diegodevxd/v43-plazo-e-idioma`, créala desde `226fa59`. **Implementa:** OpenCode, **sin
delegar**. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release, no despliegues. El
gate sí se corre.** Reporte en `C:/tmp/oc-v43-reporte.md`.

Viene de la auditoría de Codex (`C:/tmp/codex-auditoria-reporte.md`), y Diego ya decidió las dos
cosas. Son **dos partes independientes**: termina y prueba la A antes de empezar la B.

---

# PARTE A · El plazo de pago al registrar una página de libreta

## El problema

Toda venta que entra por foto de libreta se guarda **sin fecha límite**:
`domain/AiLedgerBatchPlanner.kt:365` y `:407` → `dueDateEpochDay = null`. Una venta sin fecha no puede
vencerse, así que esos clientes **no salen como vencidos, no entran al recordatorio de cobro
(`FinancialDao.kt:441-458`), no se califican (`CustomerScoreCalculator`) y no aparecen en el filtro de
vencidos**. La venta manual no tiene el problema: propone 15 días por defecto
(`ui/transaction/NewSaleScreen.kt:153`, enum `DueOption` en `:98`).

## Lo decidido por Diego (2026-09-22)

- En la **pantalla de revisión de la libreta** (`ui/ai/LedgerReviewScreen.kt`), un selector de plazo
  con las **mismas opciones que la venta manual**: Sin fecha / 7 / 15 / 30 días, **con 15 marcado**.
  Reutiliza `DueOption` y sus textos; no inventes un segundo enum.
- **Un solo plazo para toda la página**, no uno por renglón.
- **Lo ya importado no se toca.** Nada de rellenar fechas a ventas existentes: la regla 2 de
  `CLAUDE.md` lo prohíbe, y las ventas sincronizadas son create-only (`firestore.rules`).

## Cómo se calcula, y la trampa

- `dueDateEpochDay = saleDateEpochDay + días`, donde `saleDateEpochDay` es la **fecha efectiva del
  renglón** (`effectiveDate`, ya resuelta por `resolveDate`), **no hoy**. Así una venta del 1 de agosto
  a 15 días vence el 16 de agosto, que es lo que habría pasado en la libreta.
- **La trampa:** si fotografían una página vieja, con 15 días marcados varias ventas **nacen
  vencidas**. Eso puede ser correcto o no, pero **no puede pasar sin que la persona lo vea**. Debajo
  del selector, cuando aplique, un aviso: `N ventas quedarían vencidas desde hoy` (`plurals` ES/EN,
  leído con `pluralStringResource`, **nunca** `stringResource` sobre un `plurals`: ya reventó una
  pantalla así). El cálculo de ese número va en el objeto puro, no en el Composable.
- **Sólo a los cargos** (las dos rutas que hoy ponen `null`). Abonos no llevan fecha límite.
- `AiLedgerBatchPlanner.plan(...)` recibe el plazo como **parámetro** (días o `null`), y
  `LedgerBatchCoordinator.kt:47` lo pasa. El planner sigue puro y sin reloj.

## Cosas que tienes que comprobar tú, no suponer

1. **La confirmación y la idempotencia.** La clave del lote es `(businessId, importSessionId)`
   (`batchKeyFor`, `:88`). Revisa `LedgerObjectionConfirmation` y la huella de confirmación: si el
   plazo cambia después de confirmar, ¿se registra con el plazo viejo o el nuevo? El que se registra
   tiene que ser **el que la persona ve al pulsar Registrar**. Dilo en el reporte con archivo:línea.
2. **Qué pasa con el borrador guardado** (la revisión sobrevive a salir de la app, v39): si el
   borrador no guarda el plazo, al volver debe quedar en 15, no en `null`. Si guardarlo exige tocar
   `LedgerReviewSchema` (`CURRENT = 3`, match exacto), **para y dilo antes de subir la versión**.

## Pruebas (JVM, sobre el planner y el objeto puro)

- 15 días → cada cargo lleva `saleDate + 15`; **Sin fecha → `null`** (lo de hoy); abonos siempre `null`.
- Renglón con fecha de hace 40 días + 15 → cuenta como "nacería vencida"; de ayer + 15 → no.
- El conteo de "quedarían vencidas" coincide con lo que después calcula `SaleStatusCalculator`
  para esas ventas: **invoca el calculador de producción**, no copies la regla.
- Una prueba que atraviese el flujo: con plazo, el cliente importado **sí** aparece en
  `salesWithDueDate` (instrumentada, Room) — si no cabe en el tiempo, dilo.

Cada prueba debe **fallar si se rompe su regla** (mutación). Datos inventados siempre.

---

# PARTE B · Los avisos fijos en español

La app se cambia a inglés, pero estos avisos siguen saliendo en español porque están escritos a mano
en código y la puerta de idioma de `CLAUDE.md` sólo mira `ui/`.

**Pásalos a `strings.xml` (ES y EN):**

- `MainActivity.kt`: `:842, 950, 963, 1694, 1706, 1815, 1877, 1988, 1990, 2025, 2030, 2048, 2060,
  2080, 2097, 2143`, más "Cliente agregado" (`:1855-1860`) y los tres de importación de productos
  (`:2136-2140`). Los números son de `226fa59`; **búscalos por texto si se movieron.**
- Los **asuntos** de compartir (`:2473`, `:2480-2482`, `:2501`, "Estado de cuenta - …", "Clientes por
  cobrar").
- El error de compra de `billing/BillingGateway.kt:94-104`.
- En `:963` traduce la frase pero **conserva el código técnico** `stage/failure` tal cual.

**Decidido por Diego: NO se traduce lo que le llega al cliente.** `DebtorShareText`, `StatementImage`,
`StatementLineFormat` y el CSV se quedan en español: sus clientes están en México. No los toques.

**Amplía la puerta de idioma** de `CLAUDE.md` (sección "Puerta de verificación del idioma") para que
también escanee `MainActivity.kt` y `billing/`, con un patrón que cace `uiMessage = "..."` y los
asuntos de `share(...)`. Córrela y pega su salida: **tiene que salir vacía**.

Paridad ES/EN contando claves (hoy 639/639), e **inglés sin apóstrofos**.

---

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo **leído de los XML** (referencia **1273**). **No lances Gradle mientras otro corre en esta
carpeta.** El emulador puede no estar arrancado: si una prueba instrumentada no se puede correr,
dilo, no la des por buena.

## Entrega

Archivos con línea, el comando real con su salida, el conteo, las mutaciones, la salida vacía de la
puerta de idioma, y las respuestas a las dos preguntas de "Cosas que tienes que comprobar".
