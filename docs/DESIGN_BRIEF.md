# Brief de diseño — Cuentivo

> Para pegar tal cual en Claude Design. Todo lo de abajo describe la app **como está hoy**: las
> pantallas existen y funcionan; lo que falta es que se vean bien.

---

## Qué es Cuentivo

Una app Android para tiendas de barrio y personas que venden por catálogo **a crédito o "fiado"**.
El modelo es: **negocio → clientes → productos → ventas → cargos → abonos → saldo**.

La pregunta que la app existe para responder es una sola: **¿quién me debe y cuánto?**

Se publica en Google Play con dos planes: **Gratis** (5 clientes, 30 ventas) y **Pro $199 MXN/mes**.

### Quién la usa

Una señora que vende productos de catálogo o abarrotes en su colonia. Hoy anota en un cuaderno.
Tiene un Android de gama baja o media, y a veces la usa **de pie, en la calle, a plena luz del sol**,
con una mano, mientras el cliente espera enfrente. No es una app de escritorio ni de oficina.

Implicaciones que el diseño **no puede ignorar**:

- Los números importantes tienen que leerse de un vistazo, con contraste alto.
- Los toques tienen que ser grandes (mínimo 48 dp), porque se usa caminando.
- Todo en **español mexicano**, informal pero respetuoso. Nada de jerga contable.
- Nada de "elegante y sutil": tiene que verse **confiable**, como algo donde uno guarda dinero.
- **El tema principal es CLARO.** Se decidió justo por el uso a plena luz: un fondo oscuro con
  superficies traslúcidas al 12% de blanco es de lo peor que se puede leer bajo el sol. El tema
  oscuro se conserva como alternativa, no como el diseño principal.

---

## Restricciones técnicas — obligatorias

| Restricción | Por qué |
|---|---|
| **Jetpack Compose + Material 3** | Es lo que hay; no se migra a otra cosa. |
| **`minSdk = 24`** | **`Modifier.blur` NO existe.** Cualquier propuesta de glassmorphism con blur real es inviable. |
| El efecto vidrio se logra **sin blur** | Degradado global detrás de toda la app + colores de superficie translúcidos en el `ColorScheme` (`ui/theme/Theme.kt`). Así Card, ModalBottomSheet y NavigationBar se vuelven de vidrio sin tocar cada pantalla. |
| **Sin dependencias nuevas** | Nada de Coil, Lottie, Accompanist. Solo `material-icons-core` (el set básico; `material-icons-extended` está fuera a propósito). |
| **Marca propia** | Prohibido cualquier parecido con Betterware, Avon, Natura, Jafra o Tupperware. Logo actual: un disco menta con la letra "C" (provisional, se puede mejorar). |
| **El dinero siempre se formatea con `Money.format(cents)`** | Se guarda como `Long` en centavos. Ningún Composable debe formatear dinero por su cuenta. |
| **Claro principal, oscuro alternativo** | Se invierte respecto a hoy. Ambos tienen que funcionar; ninguno puede quedar con texto invisible. |

### Paleta actual (`ui/theme/Color.kt`) — punto de partida, no destino

El acento menta/azul se conserva como identidad. Lo que cambia es cuál manda: **la columna clara
pasa a ser la principal** y hay que construirla en serio, porque hoy es la variante descuidada.
Requisito duro: **contraste mínimo 4.5:1** para texto normal contra su superficie.

```
Claro (pasa a principal)      Oscuro (alternativa)
PaleMist            #EFF4F8   DeepNavy    #0A1220
DeepInk             #0B1B23   DeepTeal    #0D2A2E
TealPrimaryLight    #0F766E   MintAccent  #4ADE9B
BlueSecondaryLight  #2563EB   SkyAccent   #5EA9FF
                              WarmSand    #F2C879

Vidrio oscuro: #1FFFFFFF (card) · #2EFFFFFF (elevada) · #33FFFFFF (borde)
Sobrepuestos casi opacos: #F0142029 (oscuro) · #F7FBFDFF (claro)
```

**Dos bugs de contraste que ya se pagaron caro** — no volver a caer:

1. Pintar el fondo con degradado no basta: hay que proveer `LocalContentColor`, o los textos fuera
   de una Card salen negros sobre fondo oscuro.
2. Diálogos y bottom sheets **no pueden tener la misma opacidad que las Cards**. Se apilan sobre una
   pantalla llena de texto y quedan ilegibles. Por eso `surfaceContainerHigh` (diálogos) y
   `surfaceContainerLow` (hojas) son casi opacos.

---

## Apartados que maneja la app

### Barra inferior — 5 pestañas

| Pestaña | Qué hace |
|---|---|
| **Inicio** | Totales del mes + **la lista de quién me debe** (lo más importante de la app). |
| **Clientes** | Lista con saldo por cliente, buscador, alta/editar/eliminar. Tocar un renglón abre su estado de cuenta. |
| **Vender** | Carrito: elegir cliente, agregar varios productos con cantidad y descuento, elegir fecha límite. |
| **Abonos** | Registrar un pago de un cliente. Se aplica solo a sus ventas más viejas. |
| **Productos** | Catálogo con foto, precio, alta/editar/eliminar, importar de Excel. |

### Pantalla de Inicio, en orden

1. Nombre del negocio.
2. Botón "Nueva venta".
3. **"Por cobrar"** — el número más importante de toda la app.
4. Dos tarjetas lado a lado: "Vendido este mes" / "Cobrado este mes".
5. "Pagos vencidos" (solo si hay; hoy en rojo).
6. **"Quién me debe"** — chip "Compartir lista", filtros *Con deuda / Todos*, y la lista de deudores
   con nombre, teléfono y saldo.
7. "Tu cuenta" — tarjeta del plan actual, "Cerrar sesión", "Eliminar mi cuenta".

### Hojas y diálogos que existen

- **Estado de cuenta del cliente** (`ModalBottomSheet`): movimientos en orden cronológico con
  +cargo / −abono, botones "Compartir CSV" y "Compartir texto", y una sección **"Corregir"** donde se
  cancela una venta o un abono. Cancelar **nunca borra**: escribe un movimiento compensatorio, y el
  diálogo lo dice con todas sus letras.
- **Planes Gratis / Pro** (`ModalBottomSheet`): los dos lado a lado, el actual marcado, ✓ lo incluido
  y en gris lo que le falta al gratis.
- **Límite alcanzado** (`AlertDialog`): "Llegaste a 5 clientes" → "Ver Cuentivo Pro".
- **Alta / edición de cliente** y **de producto** (`AlertDialog` con campos; el de producto trae un
  cuadro de 96 dp para la foto).
- **Resultado de la importación** de Excel: cuántos entraron y qué renglones se quedaron fuera.
- **Confirmaciones**: eliminar producto, eliminar cliente, cerrar sesión, eliminar cuenta.
- **Autenticación**: correo y contraseña, con vista propia para crear cuenta (con confirmación de
  contraseña). Hoy: disco menta con la "C", título grande, formulario en tarjeta sólida.

---

## Qué está mal hoy y por qué

Sé específico al arreglar esto; no es "hazlo bonito":

1. **No hay jerarquía.** "Por cobrar" —el número que justifica la app— usa la misma `MetricCard` que
   "Vendido este mes". Todo pesa igual, así que nada resalta.
2. **Todas las tarjetas son idénticas**: `RoundedCornerShape(20.dp)` + borde de 1 dp, repetido en
   deudores, productos, clientes y métricas. La pantalla se lee como una lista de cajas.
3. **La lista de deudores no se ve como lo más importante**, cuando es la razón de ser de la app.
   Ahí debería estar el peso visual: quién, cuánto, hace cuánto, y qué hago al respecto.
4. **Los botones son `TextButton` sin peso** ("Editar", "Eliminar") metidos dentro de cada renglón.
   Ni se ven ni se aciertan con el pulgar.
5. **Vencido vs. al corriente solo se distingue por color rojo.** No hay forma ni ícono, así que a
   plena luz o para alguien daltónico desaparece la diferencia.
6. **Todo son Columnas con `spacedBy` y `verticalScroll`.** No hay ritmo, ni agrupación, ni secciones
   con encabezado real. Las pantallas largas se sienten infinitas.
7. **Los estados vacíos son una línea de texto gris** ("Aún no tienes productos registrados"). Es el
   primer momento de la app y no invita a nada.
8. **La barra inferior usa íconos genéricos** (Home, Person, ShoppingCart, CheckCircle, **Star para
   Productos**). El de productos es especialmente arbitrario.
9. **`Type.kt` nunca se diseñó.** Sigue siendo la plantilla de Android Studio: sólo sobrescribe
   `bodyLarge` y conserva el bloque comentado que genera el IDE. Aquí es donde de verdad se resuelve
   el punto 1 — sin escala tipográfica no hay jerarquía posible. **Sin fuentes externas**: serían una
   dependencia y peso extra en el APK; `FontFamily.Default` con pesos y tamaños bien elegidos alcanza.

---

## Qué necesito que me entregues

1. **Tokens de diseño** como código Kotlin listo para pegar: paleta completa (claro y oscuro),
   escala tipográfica, espaciado, radios, elevaciones. Debe reemplazar `ui/theme/Color.kt`,
   `Type.kt` y encajar en `Theme.kt`.
2. **Los composables clave rediseñados**, como código Compose real, no imágenes:
   - La tarjeta del total "Por cobrar" (la pieza heroica).
   - El renglón de deudor, con estado al corriente / vencido distinguible **sin depender del color**.
   - La tarjeta de producto con foto.
   - La barra de navegación inferior.
   - Un estado vacío que sirva para las cinco pestañas.
   - El encabezado de sección.
3. **Una regla de jerarquía escrita**: qué nivel tipográfico y qué peso le toca a cada cosa, para
   poder aplicarla al resto de las pantallas sin volver a preguntar.

**Restricciones al entregar:** Compose + Material 3, sin `Modifier.blur`, sin dependencias nuevas,
solo íconos de `material-icons-core`, textos en español mexicano, y el dinero siempre pasando por
`Money.format`.
