# v44 · La libreta: refrescar al registrar, años imposibles y el duplicado (OpenCode)

**Rama:** `diegodevxd/v44-libreta`, ya creada desde `18102af` (v43). **Implementa:** OpenCode, **sin
delegar**. **Audita:** Codex. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release, no
despliegues. El gate sí se corre.** Reporte en `C:/tmp/oc-v44-reporte.md`.

**Probar en el teléfono de Diego (A059P, `adb -s 00170157R000160`), no en el emulador.** Todos sus
datos son de prueba: puedes desinstalar la v43 de Play e instalar el debug. Desde Git Bash usa
`MSYS_NO_PATHCONV=1` o `adb shell` recibe rutas de Windows.

## Lo que vio Diego con la v43, y lo que Claude ya comprobó

Diego registró una hoja de libreta con plazo de 15 días. **No apareció nada** hasta cerrar y abrir la
app, así que la **registró otra vez**. Resultado en su teléfono (leído con `uiautomator`):

- Laura **$1,998 = 2 × $999**: la página quedó registrada dos veces.
- Laura, Estef. y "Nosotros": **"Vencido (1286 días)"**. La IA leyó los renglones como
  **2023-03-01** (se ve en su captura de la revisión). 2023-03-01 + 15 = 2023-03-16, y de ahí al
  2026-09-22 son **exactamente 1286 días**. El plazo se calculó bien; el **año** estaba mal leído.

Es una cadena: sin refresco → registra dos veces → duplicado. Y el año malo pasó sin que nada lo
frenara.

---

## A · Refrescar al registrar

`MainActivity.kt:1556`: `confirmLedgerBatch` escribe con `LedgerBatchCoordinator.confirm(...)` pero
**nunca incrementa `refreshKey`** (`:791`). Todas las demás escrituras sí (`:1479, :1505, :1541…`), y
de `refreshKey` dependen Inicio, Clientes, alertas y calificaciones (`:1085-1131`).

- Tras una escritura **con éxito** (resultado no nulo), `refreshKey++`. Hazlo en el hilo principal:
  el callback corre en `Dispatchers.IO` (`AiDebugLabScreen.kt:878`).
- **Prueba** que falle sin el arreglo. Si el código vive en el composable y no se puede probar, la
  salida es **extraer** la decisión, no copiarla en el test (regla de `CLAUDE.md`).
- Verifícalo **en el teléfono**: registrar una hoja, volver, y que el cliente aparezca sin reiniciar.

## B · Años imposibles

Hoy una fecha de 2023 en una libreta fotografiada en 2026 entra sin objeción. **Una fecha así casi
siempre es un año mal leído**, y con el plazo nuevo se convierte en "vencido hace 3 años".

- Una **objeción determinista** (junto a las de `domain/LedgerDeterministicObjections.kt`) cuando la
  fecha de un renglón:
  1. cae en un **año distinto** al de la fecha de la página (`pageDateEpochDay`), o
  2. es **más de 180 días anterior a hoy**, o
  3. es **futura**.
- Mensaje en palabras, ES/EN, que diga qué hacer: revisar el año.
- Como toda objeción: sólo **añade duda**, nunca quita; el renglón no se registra hasta que la
  persona lo confirme o corrija la fecha. Así una libreta vieja de verdad sigue siendo registrable.
- Averigua qué significa la etiqueta **"↑ Fecha"** que se ve en la revisión y si la fecha 2023 vino
  del modelo o de otro sitio (página, renglón anterior). Dilo con archivo:línea.
- Pruebas JVM sobre el objeto puro: mismo año / otro año / 181 días atrás / futura / límites.

## C · Por qué pasó el duplicado — **investigar antes de tocar**

Existe un detector (`domain/LedgerDuplicateDetector.kt`, `ledgerRegisteredResolver` en
`MainActivity.kt:1606`, ventana de ±14 días alrededor de las fechas de los renglones en
`AiDebugLabScreen.kt:2112-2129`). **Aun así la página entró dos veces.** Averigua cuál de estas fue:

- el detector **sí avisó** y la objeción se confirmó (entonces el aviso no se entiende);
- el segundo registro leyó **fechas o montos distintos** y no casó;
- la consulta del rango no devolvió las ventas de 2023;
- otra cosa.

Reprodúcelo en una **prueba** (registrar la misma página dos veces con `importSessionId` distintos) y
di qué pasa. **Arregla sólo si la causa es clara**; si es una decisión de producto (p. ej. bloquear
en vez de avisar), **para y dilo en el reporte**.

---

## Lo que NO se toca

Los datos ya registrados en el teléfono de Diego (regla 2: nada financiero se edita). Si hace falta un
teléfono limpio, se desinstala.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo **leído de los XML** (referencia **1285**). Las instrumentadas que toques, en el teléfono
(`ANDROID_SERIAL=00170157R000160`). Paridad ES/EN, inglés sin apóstrofos, y las dos puertas de idioma
de `CLAUDE.md`. **No lances Gradle mientras otro corre en esta carpeta.**

## Entrega

Por parte: archivos con línea, el comando real con su salida, el conteo, **una mutación por regla**, y
para C la causa con evidencia. Y qué viste en el teléfono.
