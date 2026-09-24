# v46 · El negocio se crea solo, y las fechas viejas se confirman de una vez (OpenCode)

**Rama:** `diegodevxd/v46-negocio-y-fechas`, ya creada desde `2d91f00` (v45, `1.23.1`). **Implementa:**
OpenCode, **sin delegar**. Lee `CLAUDE.md`. **No commitees, no generes APK ni AAB de release. El gate sí se
corre.** Reporte en `C:/tmp/oc-v46-reporte.md`. **Diego no está en casa:** instrumentadas en
`emulator-5554` (no el teléfono), con `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

Lo encontró Claude probando la v45 de Play en el teléfono de Diego, como una dueña de tiendita.

## 1 · Una cuenta sin negocio no puede quedar atorada: el negocio se crea solo

Diego entró con su cuenta Google "desde cero" y **no existía negocio**. Resultado:

- "Guardar" en **Nuevo cliente** no hacía nada. El aviso "primero crea tu negocio en Inicio" salía como
  mensaje **debajo del formulario, tapado**: la persona no lo ve.
- "Tomar foto de mi libreta" abría el chat de CuentivoAI **sin cámara, galería ni "Importar libreta"**: el botón
  de importar sólo existe con negocio activo (`AiDebugLabScreen.kt`, `if (extractLedgerPage != null &&
  activeBusinessId?.isNotBlank() == true)`).
- Sólo se destrababa yendo a Inicio → "Crear mi negocio", cosa que nadie haría sola.

**Decisión de Diego: el negocio se crea automáticamente.** Cuando hay sesión (invitada o Google), la
cuenta ya terminó de resolver qué negocio le toca (`BusinessClaimPolicy` / el claim de `MainActivity`, sin
negocio remoto que adoptar), y **no hay negocio**, se crea uno por defecto ("Mi negocio", el mismo valor y
la misma ruta que usa hoy "Crear mi negocio" en Inicio, `MainActivity.kt` cerca de `uiMessage` de negocio
creado), **sin pedir nada**. Cuidado:

- **No** crearlo mientras el claim todavía está consultando la nube, ni si falló la consulta (sería duplicar
  el negocio de una cuenta que sí lo tiene). Respeta la misma espera que hoy usa el claim.
- **Una sola vez**, idempotente: dos arranques seguidos no crean dos negocios.
- Que "Empezar sin anotar deudas" y la primera clienta funcionen a la primera.
- La tarjeta "Crea tu primer negocio para comenzar" de Inicio queda como respaldo, no como paso obligatorio.
- Y aparte: el aviso "primero crea tu negocio" no puede volver a salir tapado detrás de un diálogo.

**Aclaración para que no lo "arregles" de más:** el atajo "Importar libreta / ticket" del chat de CuentivoAI
**sí aparece** cuando hay negocio: no desapareció, sólo dependía del negocio. Con el negocio creado solo,
vuelve a verse. **No lo muevas ni lo dupliques.** Pruébalo: sin negocio → se crea → el atajo está.

La decisión de cuándo crear el negocio va en un **objeto puro probado** (estado del claim + si hay negocio →
crear / esperar / no hacer nada); `MainActivity` sólo lo llama.

## 2 · Fechas de hace más de 180 días: decir la verdad y confirmar todas de una vez

Con la hoja real, "Cambiar todos" corrigió el año a 2026, pero las fechas quedaron en **marzo de 2026**
(más de 180 días atrás) y la objeción `IMPLAUSIBLE_DATE` **siguió en los 12 renglones**, con el mismo texto
"revisa el año" (que ya está bien) y sólo "Sí, es correcto" renglón por renglón. Para una libreta con deudas
de hace meses eso es normal.

- En `domain/LedgerDeterministicObjections.kt`, distingue **el motivo**: año distinto al de la página /
  más de 180 días / futura. Cada uno con **su propio mensaje** (ES/EN, sin apóstrofos):
  - año: el de hoy ("revisa el año").
  - vieja: p. ej. "Esta fecha es de hace más de 6 meses. ¿Es correcta?".
  - futura: "Esta fecha todavía no llega. Revisa el día y el mes.".
- Cuando **2 o más** renglones tienen la objeción de **vieja**, una tarjeta arriba como la de "Cambiar
  todos": "N renglones son de hace más de 6 meses. ¿Son correctos?" con **"Confirmar todos"**, que marca
  como confirmados **sólo** esos renglones (misma huella de `LedgerObjectionConfirmation`, que incluye
  fecha y tipo: si después cambian la fecha, vuelve a objetar). No toca renglones con otras dudas.
- El botón "Cambiar a <año>" sigue siendo el primario sólo cuando el motivo es el año.
- Objeto puro + pruebas: motivo correcto en cada caso y límites (180 / 181 días, hoy / mañana);
  "Confirmar todos" sólo afecta a los viejos; cambiar la fecha tras confirmar vuelve a objetar.

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Referencia **1368** JVM desde los XML, paridad ES/EN **686/686** (mismas claves), las dos puertas de idioma.
**Una mutación por regla.** Instrumentadas que toques, en `emulator-5554`. **No** corras la suite completa
(Claude la coordina): sólo las clases que cambies. `LedgerReviewRotationTest` se cuelga después de ~230
pruebas seguidas en el emulador; sola pasa.
