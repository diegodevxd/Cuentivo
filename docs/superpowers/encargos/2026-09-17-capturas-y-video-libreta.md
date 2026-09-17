# Capturas de la libreta para Play, y un video grabable (OpenCode)

**Base:** `3bbb61a`. **Implementa:** OpenCode, **sin delegar**. **Revisa:** AGY.
Lee `CLAUDE.md`. **No commitees.** Reporte en `C:/tmp/oc-capturas-reporte.md`.

Esto es **material de promoción** para la ficha de Play de la v41, que ya está publicada. La
importación de libreta por foto es **nueva para quien está en producción** (venían de la `v36`), así
que necesita capturas propias.

**Regla que manda:** **datos inventados, siempre.** Las libretas reales de la familia de Diego llevan
nombres, montos y teléfonos. **No entran ni al repo, ni a las capturas, ni al video.**

---

# 1 · Capturas nuevas en `PlayStoreCaptureTest`

`app/src/androidTest/java/com/cuentivo/app/ui/PlayStoreCaptureTest.kt` ya genera las seis de la
ficha, con `CaptureFixtures`, a 1080×1920, dibujando el Composable directamente —sin cuenta, sin red
y sin Firestore—. **Sigue ese mismo patrón**: mismo helper `capture(...)`, mismo tamaño, misma forma
de escribir el archivo.

Añade **tres**, con el nombre en el orden en que se suben:

| Archivo | Qué tiene que mostrar |
|---|---|
| `7-libreta-revision.png` | La revisión de una libreta recién leída: varias clientas, sus montos y el resumen de arriba ("N movimientos detectados"). Es la captura que vende la función: se ve que la foto se convirtió en cuentas. |
| `8-libreta-aviso.png` | Un renglón **dudoso** con su aviso en palabras — el de precio que no coincide o el de cliente que hay que confirmar — y el botón "Registrar" **desactivado**. Vende lo que de verdad diferencia a Cuentivo: no registra nada sin que la persona lo revise. |
| `9-libreta-resumen.png` | La misma página ya resuelta, con "Registrar N movimientos" activo. Cierra la historia. |

La libreta de ejemplo va en `CaptureFixtures`, con **nombres inventados** (Rosa, Juana, Carmen…) y
montos redondos y creíbles para una tienda. Ni un dato real.

**Úsalo también como prueba:** si mañana la pantalla cambia y deja de dibujarse, la captura falla y
nos enteramos.

---

# 2 · Video grabable

Objetivo: un recorrido corto y **repetible** de la pantalla de revisión, para la ficha o para redes.
Nada de un agente tocando al azar: tiene que salir igual cada vez.

**2.1 · Una prueba instrumentada que hace de guion.** En `app/src/androidTest`, algo como
`LedgerDemoRecordingTest`, que use el host de depuración que ya existe
(`app/src/debug/.../LedgerLabHostActivity.kt`) con la **misma libreta inventada** de las capturas, y
recorra despacio:

1. la revisión recién abierta, con el resumen;
2. bajar por los renglones;
3. detenerse en el renglón dudoso y su aviso;
4. resolverlo;
5. "Registrar N movimientos" activándose.

Con **pausas deliberadas** entre pasos (del orden de un segundo), porque es un video para mirar, no
una prueba de velocidad. Que sea evidente en el nombre y en un comentario que **existe para grabar**,
no para verificar: así nadie la borra pensando que es una prueba redundante, ni la "optimiza"
quitándole las pausas.

**2.2 · Un script que graba.** `scripts/grabar-demo.ps1` (crea la carpeta), que en orden:

1. **Modo demostración de Android**, para que la barra de estado salga limpia — hora fija, batería
   llena, sin iconos de notificación. Es lo que separa un video de producto de una captura de
   teléfono personal.
2. `adb shell screenrecord` en segundo plano.
3. Lanza la prueba del punto 2.1.
4. Para la grabación, trae el `.mp4` con `adb pull` a `dist/` (que está en `.gitignore`).
5. **Apaga el modo demostración**, pase lo que pase. Si el script muere a medias y lo deja puesto,
   el teléfono se queda con la hora falsa.

Va contra el **emulador** (`emulator-5554`). Que acepte un serial por parámetro, pero **nunca** que
adivine el dispositivo: con el teléfono de Diego conectado, equivocarse sería grabar su app real.

---

## Gate

- `testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin` con el trust store
  (referencia **1246**).
- Las capturas **corridas de verdad** en `emulator-5554`, leyendo el XML, y los PNG traídos con
  `adb exec-out run-as com.cuentivo.app cat files/play-captures/<archivo>` como documenta el propio
  archivo. Déjalos en `dist/play-captures-v41/`.
- El script de grabación **ejecutado una vez** de principio a fin, con el `.mp4` resultante.
- **No lances Gradle mientras otro corre en esta carpeta.**

## Qué entregar

Lo de siempre: qué cambiaste con archivo y línea, el comando real con su salida, el conteo desde los
XML, y **dónde quedaron los nueve PNG y el video** para que Diego los mire.
