# v45 · Lo que encontró Claude probando la v44 de Play (prueba interna, 2026-09-23)

**Para Codex (orquestador)**: pásalo a OpenCode/DeepSeek en **sesión nueva**, sin delegar, sobre la
rama que corresponda desde `diegodevxd/v44-libreta` (v44 = `1.23.0`, versionCode 44, ya en prueba
interna). La siguiente versión es **versionCode 45**. Verifica tú el gate y audita antes de la release.

Probado en el teléfono A059P con la **v44 instalada desde Play** (`installerPackageName=com.android.vending`),
como invitada y luego con `pibedieguillo`. Datos de prueba ficticios ("Rosa Martinez").

## 🔴 1 · El detalle de la clienta muestra el saldo viejo tras registrar (bloqueante)

La v44 lleva al detalle de la clienta después de registrar una venta o un abono. Ese detalle se abre
con el **`CustomerBalanceRow` de antes** de la escritura y **no se actualiza**:

- Venta de $75 a Rosa (que debía $150): el historial dice "Quedó debiendo $225.00" y "Le vendiste
  $225.00", pero arriba **"Te debe $150.00"** y **"Debe hoy $150.00"**. Esperar no lo corrige.
- Abono de $100: el aviso dice bien "Abono registrado. Rosa Martinez ahora debe $125.00", pero el
  detalle muestra "Te ha abonado $100.00" con **"Te debe $225.00"** y **"Debe hoy $225.00"**.
- Salir y volver a entrar al detalle sí muestra el saldo correcto; la lista de Clientes también.

El saldo tiene que salir del ledger tras la escritura (regla de `CLAUDE.md`: el saldo se deriva, nunca
de un valor viejo). Probablemente el detalle guarda la fila de clienta capturada al navegar en vez de
releerla cuando cambia `refreshKey`. **Prueba que falle sin el arreglo** (registrar → el detalle muestra
el saldo nuevo), sobre el código que corre, no una copia.

## 🟡 2 · Detalles

- **La etiqueta "Nueva venta" de la barra inferior quedó cortada** ("\ueva", la N mocha). En la v43 se
  veía completa. Revisa ancho/overflow del ítem central a 360-411 dp.
- **"Actividad reciente" de Inicio** sigue mostrando la deuda anterior como "Nueva venta $150". En el
  detalle de la clienta ya dice "Deuda anterior": que Inicio use lo mismo.
- **El selector de clientas** (hoja "Cliente" de Nueva venta) sigue diciendo "Primero agrega un cliente
  en la pestaña Clientes" (`new_sale_customer_empty`) aunque ya existe "Agregar cliente" en el
  formulario. Que la hoja tenga el botón o diga "Usa Agregar cliente".
- **Entrar con Google desde invitada pide elegir la cuenta dos veces** (una antes y otra después del
  aviso "¿Entrar con otra cuenta?"). Que baste una vez.
- En **Nuevo cliente**, "¿Cuándo debe pagar?" quedó al fondo, después de "¿Se llevó algo?", lejos de la
  deuda anterior. Que quede junto a "¿Ya te debía de antes?".

## ❓ 3 · Revisar (no confirmado como bug)

- Ayer con la debug v44 y `pibedieguillo` (plan CuentivoAI de prueba) se anotó "Rosa Martinez". Hoy, al
  entrar con esa cuenta en la v44 de Play, **salió la bienvenida de cuenta nueva y Rosa no regresó**.
  La explicación probable: la suscripción de prueba de Play **ya expiró** (las de tester se renuevan cada
  pocos minutos y se cancelan solas) y la app mostró "Obtener CuentivoAI $299". Confirma si con plan
  Gratis la cuenta debe o no traer sus datos de la nube, y si ayer esas filas llegaron a Firestore.

## Lo que quedó comprobado como arreglado en la v44 de Play

"Atrás" cierra la hoja de Cuenta; sin "CAPTURA GUIADA" ni "MOVIMIENTO ENTRANTE"; "Agregar cliente" en Nueva
venta y vuelve con la clienta elegida; deuda anterior con plazo; "Abono registrado. X ahora debe $Y";
"Vendido" sin la deuda anterior; "Te debe", "¿Hasta cuánto le fías?", "Quién te debe más", "Deuda
anterior" en el historial del detalle; la barra marca la pestaña actual; aviso claro al entrar con otra
cuenta. **Pendiente de probar**: la libreta con foto ("Cambiar a <año>", "Cambiar todos"), que necesita
el plan CuentivoAI activo (Diego debe volver a comprar la suscripción de prueba).

## Nota de entorno

Antes de instalar desde Play hubo que `adb uninstall com.cuentivo.app` y `com.cuentivo.app.test`: la debug
de las pruebas instrumentadas (`leaveApksInstalledAfterRun=true`) tiene otra firma y Play decía "No se puede
instalar". Tras correr instrumentadas en el teléfono, **desinstalar la debug** antes de que Diego instale de Play.
