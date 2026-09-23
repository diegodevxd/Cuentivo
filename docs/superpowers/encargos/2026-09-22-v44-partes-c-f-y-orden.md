# v44 · Segunda tanda: F, C, D y E (OpenCode con DeepSeek)

**Rama:** `diegodevxd/v44-libreta`. **Ya hay trabajo sin commitear de la primera tanda (A y B hechos,
C investigado)**: lee `C:/tmp/oc-v44-reporte.md` antes de empezar y **no lo rehagas ni lo reviertas**.
**Implementa:** OpenCode, **sin delegar** (los subagentes de OMO-Slim usan modelos de pago de OpenAI).
**Audita:** Codex. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release. El gate sí se
corre.** Reporte en `C:/tmp/oc-v44-tanda2-reporte.md`.

**Teléfono:** `00170157R000160`, con la app de **debug ya instalada y su token de App Check ya
registrado**. **No desinstales la app**: el token cambiaría. Git Bash: `MSYS_NO_PATHCONV=1`.

## Orden (lo decidió Claude con el enrutador Laya)

1. **F** — primero: escribe dinero equivocado.
2. **C**
3. **E** — `docs/superpowers/encargos/2026-09-22-v44-parte-e-texto-corto.md`
4. **D** — `docs/superpowers/encargos/2026-09-22-v44-parte-d-eliminar-cuenta.md`. **Antes de probarlo
   en el teléfono, para y avisa**: borra el usuario real.

Corre el gate al terminar **cada** parte, no sólo al final.

---

## F · El diálogo de edición guarda los datos del renglón anterior

Lo encontró la primera tanda (punto 4 de pendientes de su reporte) y Claude lo confirmó en el código:
`ui/ai/LedgerReviewScreen.kt` ~`:1162-1175`, `EditLedgerOperationDialog` guarda los campos con
`remember { mutableStateOf(operation.customerName) }` **sin key**. La cola de revisión guiada
(`:427`, `:435`, `:459`) cambia `editingOperation` al siguiente renglón **sin que el diálogo se
desmonte**, así que el renglón N muestra los datos del N-1 y **Guardar los escribe sobre el N**. En la
prueba de la primera tanda, una página Laura $999 / Estefanía $250 / Nosotros $150 quedó como
**3 × Laura $999**.

- Que cada renglón abra con **sus** datos: `remember(operation.lineIndex, …)`, o `key(op.lineIndex) { … }`
  alrededor del diálogo — escoge y justifica.
- Revisa **todos** los `remember` del diálogo (nombre, monto, tipo, fecha, y cualquier otro), no sólo
  el nombre.
- **Prueba instrumentada** (Compose) que recorra la cola con dos renglones distintos y compruebe que el
  segundo muestra y guarda **sus** valores. Tiene que **fallar sin el arreglo**. Córrela en el teléfono.

## C · El posible duplicado frena el renglón hasta confirmarlo

**Decidido por Diego (2026-09-22)**: ni sólo avisar ni bloquear. El posible duplicado se trata **como
las objeciones deterministas**: el renglón queda sin resolver hasta que la persona confirme que es otra
venta. Así no se registra un duplicado sin que alguien lo mire, y una compra repetida legítima (la misma
clienta, lo mismo, dos veces en la semana) sigue siendo registrable.

- Hoy `blockedLineIndices` sale **sólo** de `deterministicObjections` (`AiDebugLabScreen.kt:879`) y el
  detector dice "NEVER blocks" (`LedgerDuplicateDetector.kt:46`). Cambia el contrato **y su comentario**.
- La confirmación reutiliza el mecanismo de las objeciones (huella en `LedgerObjectionConfirmation`), con
  su propio texto: p. ej. "Sí, es otra venta". Si la persona **corrige** el renglón y deja de casar, la
  duda desaparece sola.
- Actualiza las pruebas de la primera tanda que afirman que el duplicado **no** bloquea
  (`LedgerDuplicateAcrossSessionsTest`, y `LedgerDuplicateDetectorTest > flagged duplicate row STILL IS
  registerable`): ahora debe quedar frenado **hasta** confirmar, y registrable **después**.
- ES/EN sin apóstrofos.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo **desde los XML** (referencia **1303**). Paridad ES/EN (hoy **669/669**), las dos puertas de
idioma de `CLAUDE.md`. Instrumentadas que toques: en el teléfono (`ANDROID_SERIAL=00170157R000160`).
**No lances Gradle mientras otro corre en esta carpeta.**

## Entrega

Por parte: archivos con línea, el comando real con su salida, el conteo, **una mutación por regla**, y
lo que viste en el teléfono. Si el contexto pasa del 80%, **para, corre el gate y escribe el reporte**
con lo hecho y lo pendiente claramente separados.
