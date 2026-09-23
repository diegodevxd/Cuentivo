# v44 · Parte E — Con imagen, el texto va corto (OpenCode)

**Rama:** `diegodevxd/v44-libreta`, encima de A-D. **Implementa:** OpenCode, **sin delegar**.
**Audita:** Codex. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release. El gate sí se
corre.** Reporte en `C:/tmp/oc-v44-parte-e-reporte.md`.

## Por qué

Diego compartió un estado de cuenta por WhatsApp con la v43. **WhatsApp manda imagen y texto juntos**
(el texto queda como pie de foto), así que los movimientos salen **dos veces**: en la imagen y en el
texto. Decidido por Diego (2026-09-22): **cuando va con imagen, el texto se acorta.**

## Qué cambia

- Sólo en la ruta **imagen + texto** (`onShareStatementText`, `MainActivity.kt` cerca de `:2480`), el
  texto queda:

  ```
  Hola Xóchitl, te comparto tu estado de cuenta.

  Saldo pendiente: $677.00
  ```

  con el mismo encabezado que hoy (con o sin nombre de negocio, que se sigue ocultando si es el valor
  por defecto) y **el pie de Cuentivo en plan Gratis**, igual que hoy.
- **Sin movimientos** en ese texto: ya van en la imagen.
- Saldo en cero / a favor: el mismo texto que hoy usa `forCustomer` para esos casos.
- Hazlo en `domain/DebtorShareText` como función pura nueva (p. ej. `forCustomerWithImage`), sin
  duplicar la lógica del encabezado y el pie: **reutiliza** lo que ya usa `forCustomer`.
- **No cambian**: el recordatorio (`onRemindCustomer`), la lista de deudores, el CSV, ni el texto
  completo cuando se comparte sin imagen.

## Un defecto que Claude encontró de paso: si la imagen falla, la app se cae

`MainActivity.kt:2639` `shareStatementImage` llama a
`statementImageShareStore.createShareIntent(...)`, que **lanza** si no puede escribir el PNG (sin
espacio, error de disco) — y nadie lo atrapa. Arréglalo así:

- Si la imagen falla, se comparte **el texto completo de siempre** (`forCustomer` con movimientos) por
  la ruta `share(...)` de texto. La persona pierde la imagen, no el estado de cuenta.
- Por eso esa ruta necesita **los dos textos**: el corto para la imagen y el completo de respaldo.
- La decisión "qué texto mandar según si hubo imagen" va en un objeto puro y se prueba; no dentro del
  `try` de la Activity.

## Pruebas (JVM)

- Con imagen: sin ninguna línea de movimiento, con el saldo, con el encabezado correcto (con y sin
  nombre de negocio).
- Gratis → con pie; Pro/AI → sin pie.
- Saldo en cero y a favor.
- **La invariante de siempre**: no aparece ningún otro cliente.
- Respaldo: si la imagen falla, el texto elegido es el completo con movimientos.

Mutación de cada una.

## Gate

El mismo de las partes anteriores. Conteo desde los XML, paridad ES/EN, las dos puertas de idioma.
**No lances Gradle mientras otro corre en esta carpeta.**
