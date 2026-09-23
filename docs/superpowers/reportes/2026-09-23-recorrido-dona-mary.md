# Recorrido "Doña Mary" — v44 debug en el teléfono de Diego (2026-09-23)

**Quién:** Claude, manejando el teléfono A059P por `adb` + `uiautomator`, con la app de debug de la
rama `diegodevxd/v44-libreta` (sin commitear) y la cuenta `pibedieguillo` recién recreada (plan
CuentivoAI de prueba). **Persona:** dueña de una tiendita de abarrotes, ~52 años, fía a vecinas, lleva
libreta, usa WhatsApp, poca experiencia con apps.

Datos de prueba: clienta ficticia "Rosa Martinez". La hoja de libreta fotografiada fue **real** (la
eligió Diego); aquí no se copian nombres ni montos de ella.

## Historias recorridas

1. Anotar una deuda previa ("Rosa me debe $150") → Nueva venta → callejón → Clientes → Nuevo cliente.
2. Venta con carrito (2 productos, plazo 15 días).
3. Abono de $100.
4. Compartir estado de cuenta (hasta el menú de Android; no se envió nada).
5. Venta por texto en CuentivoAI.
6. Importar hoja de libreta real (galería) → revisión guiada, sin registrar.
7. Inicio al final: cifras correctas ($150 por cobrar, $100 cobrado).

## Funciona bien

- "¿Ya te debía de antes?" al crear clienta, con confirmación del monto.
- Detalle de clienta: "Le vendiste · Te ha abonado · Debe hoy".
- CuentivoAI por texto: desambigua la clienta, borrador claro, "Venta registrada con éxito".
- Compartir: imagen + texto corto (parte E de v44 comprobada).
- v44 en real: objeción de fecha imposible frenó la hoja (B); el diálogo abre cada renglón con sus
  datos (F); eliminar cuenta completo (D).

## Problemas, por prioridad

1. **Fecha imposible: el botón fácil es aceptarla.** "Sí, es correcto" es el botón grande y morado; con
   prisa se confirma 2023 en todos los renglones. Falta "Cambiar a <año de la página>" en un toque.
2. **Corregir el año es renglón por renglón**, con formato `AAAA-MM-DD`. La IA leyó 2023 en los 12
   renglones de la hoja real. Falta corregir el año de toda la página de una vez.
3. **Registrar una venta no confirma nada visible**: el formulario se vacía y queda en la misma
   pantalla. Riesgo de registrar dos veces (misma trampa que causó el duplicado de la libreta).
4. **Nueva venta sin clientas = callejón**: "Primero agrega un cliente en la pestaña Clientes", sin botón
   para crearla ahí.
5. **"Traer deudas de mi libreta" no es la foto** (es un formulario a mano). La foto está en el robot →
   "Importar libreta / ticket", enlace chico abajo.
6. **Deudas sin fecha límite**: deuda anterior (Nuevo cliente) y ventas por CuentivoAI no preguntan
   plazo → nunca vencen, sin recordatorio ni calificación.
7. **"Vendido este mes" incluye la deuda anterior** (salió $250 con $100 de ventas reales + $150 previo).

## Detalles de pantalla y lenguaje

- Chips de tipo en el diálogo de libreta se parten letra por letra ("Abon/o", "Deud/a/anteri/or").
- "Editar renglón 0" (índice desde cero).
- Hueco vacío grande donde iría el recorte de la foto.
- En revisión: "Saldo: $0.00 → $0.00" en un renglón de $999 mientras está en duda.
- Tres avisos rojos seguidos al abrir una hoja dudosa; "Gemini leyó…" (marca ajena a la persona).
- Jerga: "CAPTURA GUIADA", "MOVIMIENTO ENTRANTE", "BALANCE TOTAL", "Cupo de crédito", "Compartir CSV",
  "Clientes críticos" (para quien no debe nada vencido).
- "Abono aplicado a 2 venta(s)" → mejor "Rosa ahora debe $125".
- "Precio habitual: $25.00" para un producto recién creado por CuentivoAI.
- La deuda anterior aparece como "Venta" con ícono de carrito.
- Navegación: la barra inferior marca la pestaña anterior; tocar "Clientes" desde un detalle no vuelve a
  la lista; "atrás" no cierra la hoja de Cuenta.
- La línea de calificación en Detalle de Cliente se sale del margen de la tarjeta.

## Recomendación de Claude

- **Antes de cerrar v44:** 1 y 3 (chicos, evitan datos malos).
- **v45:** 2, 4, 5, 6, 7 y el lenguaje.
