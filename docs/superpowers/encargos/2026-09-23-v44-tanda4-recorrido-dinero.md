# v44 · Tanda 4: lo que el recorrido encontró que toca dinero y fechas (OpenCode con DeepSeek)

**Rama:** `diegodevxd/v44-libreta`, base **`7a6dadc`** (A-F ya commiteados). **Implementa:** OpenCode,
**sin delegar**. Lee `CLAUDE.md` y `docs/superpowers/reportes/2026-09-23-recorrido-dona-mary.md`.
**No commitees, no generes APK ni AAB de release. El gate sí se corre tras cada punto.** Reporte en
`C:/tmp/oc-v44-tanda4-reporte.md`. Si pasas del 80% de contexto: gate + reporte con hecho/pendiente.

**Teléfono** `00170157R000160` con la app **debug** y token App Check registrado. **Nunca lo
desinstales**: toda instrumentada en el teléfono lleva
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (sin eso Gradle desinstala y el token
cambia — ya pasó). Git Bash: `MSYS_NO_PATHCONV=1`.

La persona: dueña de una tiendita, poca experiencia con apps, **con prisa**. Decisiones de producto
ya tomadas por Claude (Diego puede cambiarlas):

## 1 · La fecha imposible: el botón fácil debe ser corregirla

Hoy el diálogo de edición (`ui/ai/LedgerReviewScreen.kt`, `EditLedgerOperationDialog`) muestra la
objeción de fecha y **el botón grande es "Sí, es correcto"** (`ai_ledger_action_confirm_objection`).
Con prisa se acepta 2023 en todo.

- Cuando la objeción del renglón es `IMPLAUSIBLE_DATE` y la página tiene fecha (o, si no, el año de
  hoy), el botón **primario** es **"Cambiar a <año>"** (mismo mes y día, año corregido) y aplica con
  `LedgerReviewEdits.applyDate`. "Sí, es correcto" pasa a botón **secundario** (texto, no relleno).
- Si mes/día no existen en ese año (29-feb), cae al último día del mes. Lógica en un objeto puro
  probado; la pantalla sólo lo llama.

## 2 · Corregir el año de toda la hoja de una vez

La IA leyó 2023 en **los 12 renglones** de una hoja real. Corregir uno por uno no es viable.

- En la revisión, cuando **2 o más** renglones tienen `IMPLAUSIBLE_DATE` por año, una tarjeta arriba:
  "N renglones dicen <año leído>. ¿Los cambio a <año>?" con botón **"Cambiar todos"**.
- Aplica sólo a los renglones con esa objeción; los demás no se tocan. Después de cambiar, la objeción
  desaparece sola si la fecha ya es plausible (no se marca como confirmada).
- Objeto puro + prueba; que la prueba cubra "no toca renglones sin objeción".

## 3 · Registrar una venta tiene que decir que se registró, y en qué quedó

`ui/transaction/NewSaleScreen.kt`: tras "Registrar venta" el formulario se vacía en la misma pantalla,
sin nada visible. Lleva al doble registro.

- Tras registrar: mensaje claro **"Venta registrada. <Cliente> ahora debe $X"** con el saldo real
  (de `FinancialDao.customerBalanceCents`, no calculado en la UI), y **llevar al detalle de esa
  clienta**. Igual para el abono (hoy dice "Abono aplicado a 2 venta(s)",
  `message_payment_applied_with_credit`): **"Abono registrado. <Cliente> ahora debe $X"**.
- La venta por CuentivoAI ("Venta registrada con éxito.") también añade "…ahora debe $X".

## 4 · Deudas que nacen sin fecha límite

Sin fecha no hay vencido, ni recordatorio, ni calificación.

- **Nuevo cliente con "¿Ya te debía de antes?"** (`customers_form_*`, `CustomersScreen.kt`): si se
  escribe deuda anterior, mostrar el mismo selector de plazo de la venta (`DueOption`), **15 días
  marcado**, contado desde hoy.
- **Venta por CuentivoAI** (`ai/AiSaleExecution.kt`): el borrador muestra el plazo (15 días por
  defecto, cambiable) antes de "Confirmar venta".
- Ventas ya existentes **no se tocan** (regla 2).

## 5 · "Vendido este mes" no puede incluir la deuda anterior

Inicio mostró **Vendido $250** con $100 de ventas reales + $150 de deuda anterior. La deuda anterior
se guarda como venta con `notes = 'ALTA_CLIENTE_SALDO_ANTERIOR'` (ver `FinancialDao.kt`, la consulta que
ya la clasifica como `PRIOR_DEBT`). Exclúyela del "Vendido" del mes (sí cuenta en "por cobrar").
Prueba de Room (instrumentada, en el teléfono) o del objeto que arme el resumen.

## 6 · Detalles de la revisión de libreta

- Chips de tipo (Venta / Abono / Deuda anterior) **se parten letra por letra** en el diálogo: que no
  se partan a 360 dp (una fila que se ajuste, o texto más corto). Prueba a 360 dp.
- "Editar renglón **0**" → número desde **1** (`ai_ledger_dialog_edit_title`).
- Hueco vacío grande cuando no hay recorte: no reservar ese espacio.
- "Saldo: $0.00 → $0.00" mientras un renglón está en duda: mostrar el pendiente en duda aparte
  (p. ej. "+ $999 por revisar") en vez de un 0 → 0 que parece que no se va a anotar nada.
- Tres avisos rojos seguidos al abrir una hoja dudosa: uno solo que resuma, con el detalle dentro.
- "Gemini leyó…" (`ai_ledger_error_rows_need_review`) → "CuentivoAI leyó…".

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo desde los XML (referencia **1334**), paridad ES/EN (hoy **673/673**), las dos puertas de idioma,
inglés sin apóstrofos. **Una mutación por regla.** Lo visual, míralo en el teléfono con captura.
