# La imagen del estado de cuenta (OpenCode)

**Rama:** sigue en `diegodevxd/mensaje-al-cliente`, sobre `e7ffb06`. **Implementa:** OpenCode, **sin
delegar**. **Revisa:** AGY. Lee `CLAUDE.md`.
**No commitees, no generes ningún build, no despliegues.** Reporte en `C:/tmp/oc-imagen-reporte.md`.

Decidido con Diego: al compartir el resumen se manda **imagen + el texto de siempre**, y la imagen
muestra **los últimos 10 movimientos**. La imagen se ve dentro del chat de WhatsApp; un PDF llega
como archivo y mucha gente no lo abre.

## Cómo está hoy, para que no lo busques

- **La app nunca ha mandado un archivo.** Incluso "Compartir CSV" manda el CSV **como texto**
  (`MainActivity.kt:2460`). El `share(...)` de `:2586` es `ACTION_SEND` de `text/plain` +
  `createChooser`.
- **El `FileProvider` ya existe** (`AndroidManifest.xml:53`, autoridad `${applicationId}.fileprovider`)
  pero sólo con rutas de cámara (`res/xml/product_camera_paths.xml`: `product-camera` y `ai-camera`).
- El texto del mensaje lo genera `domain/DebtorShareText.forCustomer(...)`, ya corregido.

---

# PASO 1 · Sólo la imagen, para que Diego la apruebe

**No cablees nada del compartir todavía.** Primero que la imagen exista y se vea bien.

## 1.1 · Qué dice la imagen

Arriba el **nombre del negocio** (si está configurado; si no, sólo "Estado de cuenta"), debajo el
**nombre del cliente**, luego **los últimos 10 movimientos** con la misma forma que el texto
—`03/08/2026 · Compra · $799.00`, con su detalle debajo si lo tiene—, y al final el **saldo
pendiente en grande**, que es lo que la persona mira primero.

- **Si hay más de 10**, una línea sobria: `y N movimientos más`. **El saldo es siempre el real**, no
  el de los diez que se ven. Que eso quede claro en la imagen y en una prueba.
- **Sello de Cuentivo abajo, con la misma regla que el pie del texto**: sólo en plan Gratis
  (`includesProBenefits == false`). En Pro la imagen sale limpia, sin marca.
- **Ni un dato de otro cliente.** Es la misma invariante del texto y aquí pesa más, porque una imagen
  se reenvía sin pensar.

## 1.2 · Cómo se construye

**Parte el trabajo en dos**, que es lo que hace que se pueda probar:

1. **Qué se dibuja** → un objeto **puro en `domain/`**, sin `Context` ni `R.string` (la regla de
   `CLAUDE.md`): recibe cliente, saldo, movimientos y plan, y devuelve las líneas ya resueltas,
   incluido el recorte a 10 y el `y N movimientos más`. **Esto se prueba en `testDebugUnitTest`.**
2. **Cómo se dibuja** → el bitmap, en la capa de UI.

Para dibujar, usa **`Canvas` y `Paint` sobre un `Bitmap`**, no un Composable renderizado fuera de
pantalla: no necesita árbol de vistas ni ventana, sale igual siempre y se puede generar desde una
función normal. **Ancho 1080**, alto variable según el contenido. Fondo sólido (no transparente:
WhatsApp lo pinta de negro). Respeta los colores del tema de la app.

**Si vas a añadir una dependencia, pregunta antes.** Esto se hace con lo que ya trae Android.

## 1.3 · Lo que entregas en el paso 1

**Tres PNG de muestra en `dist/muestras-estado-cuenta/`**, generados de verdad en el emulador con una
prueba instrumentada, con **datos inventados** (Abarrotes Don Luis, Papelería La Estrella, Sra.
Mendoza; nunca las libretas reales):

| Archivo | Caso |
|---|---|
| `1-gratis-pocos.png` | Plan Gratis, 3 movimientos, con nombre de negocio |
| `2-gratis-muchos.png` | Plan Gratis, **40 movimientos** → 10 + "y 30 movimientos más" |
| `3-pro-sin-marca.png` | Plan Pro, con abonos, **sin sello** |

**Y paras ahí.** Diego los mira y aprueba el diseño antes del paso 2.

---

# PASO 1 bis · Aprobado, con dos ajustes (2026-09-20)

Diego vio las tres muestras y **aprobó el diseño**. Las generó Claude corriendo
`StatementImageRendererTest` en el emulador; están en `dist/muestras-estado-cuenta/`. Dos cambios
antes de seguir:

1. **`y N movimientos más` → `y N movimientos anteriores`.** La lista va del más viejo al más nuevo,
   así que los que faltan son **anteriores**; "más" hace pensar que vienen después. Cambia también el
   singular (`y 1 movimiento anterior`) y la prueba que lo fija.
2. **Añade la fecha del estado de cuenta**, en pequeño junto al pie: `Estado de cuenta al 20/09/2026`.
   Si la clienta guarda la imagen, dentro de dos semanas tiene que poder saber de cuándo es. **La
   fecha entra como parámetro**, no se toma del reloj dentro del objeto puro: si no, la prueba no
   puede fijarla y el objeto deja de ser determinista.

Regenera las tres muestras después, en el mismo sitio.

---

# PASO 2 · El cableado (sólo con el visto bueno)

- Escribir el PNG en `cacheDir`, en una ruta **nueva** del `FileProvider`
  (`res/xml/product_camera_paths.xml` — el nombre del archivo ya se le quedó corto, dilo en el
  reporte pero **no lo renombres** sin avisar, que toca el manifiesto).
- Compartir con `ACTION_SEND`, `type = "image/png"`, `EXTRA_STREAM` con la URI del provider,
  `EXTRA_TEXT` con el mensaje de siempre y **`FLAG_GRANT_READ_URI_PERMISSION`**.
- **Borrar el archivo después.** Un estado de cuenta de un cliente no se queda tirado en la caché.

**Una trampa que hay que mirar de frente:** al mandar **imagen + texto**, varias apps —WhatsApp entre
ellas— **se quedan sólo con la imagen y tiran el `EXTRA_TEXT`**, o lo ponen como pie de foto. **No lo
des por hecho en ninguna dirección**: dilo en el reporte como cosa a verificar en el teléfono de
Diego, y deja el código preparado para que, si el texto se pierde, la decisión (pie de foto, o dos
mensajes) sea un cambio pequeño.

---

## Pruebas

**En JVM**, sobre el objeto puro: 0, 1, 10, 11 y 40 movimientos (que 11 ya diga "y 1 movimiento
más", en singular); que el **saldo sea el real** aunque se recorten movimientos; con y sin nombre de
negocio; **Gratis con sello y Pro sin sello**; y **que no aparezca ningún otro cliente**.

**Instrumentadas**, sobre el dibujo: que el bitmap tenga el ancho esperado y **no salga en blanco**;
que no reviente con 0 movimientos, con 40, ni con un nombre larguísimo; y que **dos clientes distintos
produzcan imágenes distintas** (comparando su hash, que es lo que caza que se devuelva siempre la
misma).

Mutación de cada una. Datos inventados siempre.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo desde los XML (referencia **1254**). Instrumentadas en `emulator-5554` **leyendo el XML**. Si
tocas `strings.xml`: paridad ES/EN e inglés sin apóstrofos. **No lances Gradle mientras otro corre en
esta carpeta.**
