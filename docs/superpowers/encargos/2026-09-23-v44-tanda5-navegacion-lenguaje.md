# v44 · Tanda 5: navegación, callejones y lenguaje (OpenCode con DeepSeek)

**Rama:** `diegodevxd/v44-libreta`, **encima de la tanda 4** (sin commitear o commiteada, según te diga
Claude). **Implementa:** OpenCode, **sin delegar**. Lee `CLAUDE.md` y
`docs/superpowers/reportes/2026-09-23-recorrido-dona-mary.md`. **No commitees, no generes APK ni AAB de
release. El gate sí se corre.** Reporte en `C:/tmp/oc-v44-tanda5-reporte.md`. Mismas reglas del
teléfono que la tanda 4 (`leaveApksInstalledAfterRun=true`, nunca desinstalar).

## 1 · Callejones sin salida

- **Nueva venta sin clientas**: la hoja dice "Primero agrega un cliente en la pestaña Clientes"
  (`new_sale_customer_empty`) sin botón. Poner **"Agregar cliente"** ahí mismo que abra el formulario
  de nuevo cliente y, al guardar, deje a esa clienta elegida en la venta.
- **La foto de la libreta está escondida.** En Clientes, "Traer deudas de mi libreta"
  (`opening_import_action`) abre un formulario a mano. Renómbralo a lo que es ("Anotar deudas a mano")
  y agrega al lado **"Tomar foto de mi libreta"** que abra directo la importación por foto de
  CuentivoAI (Cámara / Galería).

## 2 · Navegación

- La barra inferior marca **la pestaña anterior** estando en Nueva venta (y en otras pantallas).
  Que marque la actual.
- Tocar la pestaña en la que ya estás (p. ej. "Clientes" desde el detalle de una clienta) debe
  **volver a la raíz** de esa pestaña.
- El botón **atrás** del teléfono debe cerrar la hoja de Cuenta (y cualquier hoja abierta) antes de
  salir de la pantalla.

## 3 · Lenguaje de tiendita, no de banco (ES; EN equivalente sin apóstrofos)

| Hoy | Propuesta |
|---|---|
| "CAPTURA GUIADA" (Nueva venta) | quitarlo |
| "MOVIMIENTO ENTRANTE" (abono) | quitarlo |
| "BALANCE TOTAL" (`detail_balance_total`) | "Te debe" |
| "Cupo de crédito (opcional)" (`customers_form_credit_limit_label`) | "¿Hasta cuánto le fías? (opcional)" |
| "Compartir CSV" (`detail_share_csv`) | "Compartir en Excel (CSV)" |
| "Clientes críticos" (`dashboard_critical_customers`) | "Quién te debe más" |
| "Precio habitual: $X" para un producto recién creado (`ai_sale_preview_new_product_habitual_price`) | no mostrarlo si el producto es nuevo |

Y la deuda anterior en el historial aparece como **"Venta"** con carrito: que diga **"Deuda anterior"**
con otro ícono (el dato ya se distingue en `FinancialDao`, `kindName = 'PRIOR_DEBT'`).

## 4 · Un detalle visual

En Detalle de Cliente la línea de calificación ("Aún sin calificación…") **se sale del margen** de la
tarjeta: que respete el padding y no toque el borde.

## Gate

El mismo de la tanda 4. Conteo desde los XML, paridad ES/EN, las dos puertas de idioma. Capturas en el
teléfono de lo visual. **Una mutación por regla** donde haya regla (navegación a la raíz, texto del
producto nuevo, deuda anterior en el historial).
