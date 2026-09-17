# Capturas, vuelta 2 · y los dos defectos que enseñaron (OpenCode)

**Base:** `3bbb61a` más lo que ya tienes sin commitear de la vuelta 1. **Implementa:** OpenCode,
**sin delegar**. **Revisa:** AGY. Lee `CLAUDE.md`. **No commitees.**
Reporte en `C:/tmp/oc-capturas-v2-reporte.md`.

Claude miró las tres capturas nuevas una por una. **La tubería está bien** —se generan solas, salen
en 1080×1920 y no cuestan nada—, pero **las imágenes no se pueden subir todavía**. Y dos de los
problemas **no son de la captura: están en la app y tus usuarios ya los ven**.

---

# PARTE A · Dos arreglos de la app (esto sí toca producción)

## A.1 · "Revisar 1 renglones con dudas"

`app/src/main/res/values/strings.xml:678` y su par en `values-en/` son un `string` con `%1$d`:

```xml
<string name="ai_ledger_action_review_uncertain">Revisar %1$d renglones con dudas</string>
```

Se usa en `LedgerReviewScreen.kt:563`. Con un solo renglón sale **"Revisar 1 renglones"**.

Conviértelo en `<plurals>` en **español e inglés**, y léelo con **`pluralStringResource`**.
**Cuidado, esto ya reventó una pantalla en este proyecto:** `stringResource` sobre un `plurals`
lanza `Resources$NotFoundException` **en ejecución** y ninguna prueba JVM lo ve. El patrón correcto
ya está en `ai_ledger_page_unreadable_rows`, hecho en el bloque 3: cópialo.

**Revisa si hay más casos iguales**: busca otros `%1$d` en cadenas que cuenten cosas y dilo en el
reporte, aunque no los arregles.

## A.2 · La etiqueta encimada a 360 dp

`LedgerReviewScreen.kt:903-908`: cuando un renglón no tiene recorte de la foto, se dibuja el texto
`ai_ledger_crop_not_available` ("Sin recorte disponible") dentro del hueco de la miniatura. A **360 dp**
—un teléfono normal— el texto **no cabe, se parte y se monta encima del renglón**. Se ve en las dos
capturas nuevas.

Arréglalo de forma que **no dependa de la traducción ni del ancho**: el hueco tiene un tamaño fijo y
el texto no. Piensa en un texto más corto, en recortarlo con puntos suspensivos, o en no poner texto
y dejar el hueco vacío con su descripción para lectores de pantalla — decide y **justifica cuál
elegiste**. Lo que no puede quedar es texto encimado.

**Prueba instrumentada a 360 dp** que lo fije: hoy no hay ninguna que mire ese ancho.

---

# PARTE B · Las capturas, otra vuelta

Casi todo es de los datos de ejemplo (`CaptureFixtures`), no de la app:

1. **Ningún renglón puede decir "Sin fecha en la libreta".** Es correcto, pero al que mira la tienda
   le dice *"la app no supo leer las fechas"*, justo lo contrario de lo que vendemos. Dales fecha a
   los renglones del ejemplo.
2. **La caja "Fecha de la página (AAAA-MM-DD)" domina la pantalla** con un formato técnico. Encuadra
   para que se vean las clientas y sus montos, que es lo que enamora.
3. **La captura 8 tiene que enseñar el aviso en palabras** — el de precio que no coincide con tu
   producto, o el de cliente que hay que confirmar. Hoy sólo se ve el renglón en rojo, y **ese aviso
   es justo lo que diferencia a Cuentivo**: no registra nada sin que la persona lo revise.
4. Con A.1 y A.2 arreglados, el "1 renglones" y el texto encimado desaparecen solos.

Vuelve a generarlas **de verdad** en el emulador y déjalas en `dist/play-captures-v41/`.

## Y el video

Tiene los mismos defectos, así que **regrábalo** con `scripts/grabar-demo.ps1` después de todo lo
anterior. Es un comando.

---

## Gate

- `testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin` con el trust store
  (referencia **1246**).
- **Instrumentadas en `emulator-5554`**, leyendo el XML: las de la pantalla de revisión, la nueva de
  360 dp, y las capturas.
- **Paridad ES/EN** y la puerta del idioma de `CLAUDE.md`. En `values-en/`, **sin apóstrofos**.
- **No lances Gradle mientras otro corre en esta carpeta.**

## Entrega

Lo de siempre, y además: **qué se ve distinto en cada captura** respecto de la vuelta 1, para que
Diego no tenga que compararlas a ojo.
