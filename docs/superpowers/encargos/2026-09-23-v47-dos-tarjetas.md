# v47 · Las tarjetas "Cambiar todos" y "Confirmar todos" no pueden salir juntas (OpenCode)

**Rama:** `diegodevxd/v47-giro-congelado` (sale de `25d39c3`, v46). El cuelgue al girar queda **pendiente
vigilado**: no lo toques. **Implementa:** OpenCode, **sin delegar**. **No commitees, no generes APK ni AAB
de release. El gate sí se corre.** Reporte en `C:/tmp/oc-v47-tarjetas-reporte.md`. Instrumentadas sólo
en `emulator-5554`, sólo las clases que toques.

## Lo que vio Claude en el teléfono con la v46 y una hoja real

Al abrir la revisión aparecen **las dos tarjetas a la vez**:

- "12 renglones dicen 2023. ¿Los cambio a 2026?" → **Cambiar todos** (`LedgerYearCorrection`)
- "12 renglones son de hace más de 6 meses. ¿Son correctos?" → **Confirmar todos** (`LedgerOldDatesBulkCorrection`)

Si la persona pulsa primero **"Confirmar todos"**, **acepta las fechas de 2023**. En el orden correcto
(primero "Cambiar todos") todo sale bien: la tarjeta vieja queda sola y confirma los 12.

## Qué debe pasar

- Un renglón cuyo año no coincide con el de la hoja **no cuenta** para "Confirmar todos": lo único
  válido para él es corregir el año. Revisa por qué hoy cuenta (¿el motivo se calcula con otra fecha de
  página u otro "hoy" que en la objeción? ¿la tarjeta mira la antigüedad sin mirar el año?) y usa **la
  misma** decisión de motivo (`dateReason`) que la objeción, no una paralela.
- Mientras haya renglones con año distinto, la tarjeta de "más de 6 meses" no aparece para ellos. Tras
  "Cambiar todos", si siguen siendo viejos, aparece sola.
- Un renglón que ya confirmaste como viejo y luego cambias de año, vuelve a pedir lo que corresponda.

## Pruebas

JVM sobre los objetos puros: con 12 renglones de 2023 en hoja de 2026 → sólo la tarjeta de año; tras
aplicar el cambio de año → sólo la de viejos; mezcla (unos con año mal, otros sólo viejos) → cada tarjeta
cuenta sólo los suyos. Instrumentada en la pantalla de revisión si cabe: que no se vean las dos a la vez.
Mutación de la regla. Gate de siempre, referencia **1393** JVM, paridad **689/689**.
