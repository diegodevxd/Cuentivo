# Fase B — La fecha de página (encargo para AGY)

**Fecha:** 2026-09-07
**Base:** los 5 primeros pasos están commiteados y subidos (`d433b4b` es la punta). La libreta ya se
fotografía, se revisa y se registra en una sola transacción.
**Por qué esto y no otra cosa:** de todo lo que queda, **es lo único que Codex llamó bloqueante para
publicar**. Sin esto no sacamos AAB.
**Alcance:** SOLO la fecha de página. No toques el planificador más allá de lo que se pide aquí, no
rehagas la pantalla, no arregles otros pendientes. Sin commits, sin deploys.

## El problema, en una frase

`LedgerBatchCoordinator.confirm(...)` recibe hoy `pageDateEpochDay = today` desde `MainActivity`, y
nadie le pregunta nada al usuario. **Importar una libreta de hace tres meses la registra entera como
de hoy** — justo la historia que esta función promete migrar.

## Qué hay que construir

### 1. Que el usuario elija la fecha de la página

En la pantalla de revisión, **sólo cuando algún renglón registrable no traiga su propia fecha**, pide
una fecha para la página. Si todos los renglones traen fecha, no molestes: no hay nada que decidir.

Reusa el campo de fecha que ya existe en el diálogo de edición (`ledger-edit-date-field`, formato
`AAAA-MM-DD`, con `parseLedgerDateInput`). No inventes otro control ni metas una librería.

Reglas, las mismas que ya aplica `LedgerReviewEdits.isAcceptableDate`:

- Una fecha que no existe (31 de abril) no vale.
- Una fecha en el futuro no es una fecha de libreta.
- **No se puede confirmar** un lote que tenga renglones sin fecha mientras no haya fecha de página
  válida. El botón se queda deshabilitado y **di por qué**, como ya hacemos con los errores
  financieros. Un botón muerto sin explicación es el defecto que ya arrastramos en `AI_UNAVAILABLE`.

### 2. Que esa fecha viaje hasta la escritura

Hoy el camino es: pantalla → `AiDebugLabMachine.onConfirmLedger` → `confirmLedgerBatch` →
`LedgerBatchCoordinator.confirm(pageDateEpochDay = today)`.

La fecha elegida tiene que llegar ahí en lugar de `today`. Guárdala donde vive el resto del estado de
la revisión, **no en una variable suelta del Composable**: si sobrevive a una recomposición por
accidente y no por diseño, mañana deja de sobrevivir.

### 3. Distinguir fecha ausente de fecha inválida — **esto es lo que marcó Codex**

En `AiLedgerBatchPlanner`, hoy:

```kotlin
val effectiveDate = opDateEpoch ?: pageDateEpochDay
```

Eso trata igual **dos cosas distintas**: un renglón que la libreta nunca fechó, y un renglón cuya
fecha se leyó mal. El primero debe tomar la fecha de página; **el segundo no**, porque sustituirla en
silencio es inventar una fecha que nadie verificó.

Un renglón con fecha inválida ya no es registrable —`LedgerReviewEdits.applyDate` le pone la marca—,
así que lo correcto es que **no llegue** hasta aquí. Deja el código de forma que esa distinción sea
explícita y no dependa de que otro la haya filtrado antes.

Ojo también con `resolvedDatesFor`, que hoy repite esa misma resolución. Que la regla viva en un solo
lugar y ambos la usen: si se separan, la clave del lote y lo que se escribe pueden dejar de coincidir.

## Idioma

Los textos nuevos van en `values/strings.xml` **y** en `values-en/strings.xml`. La paridad debe
cuadrar (cuenta, no confíes en un número escrito) y la puerta del idioma de `CLAUDE.md` debe salir
vacía salvo las etiquetas de animación de Compose. En inglés, redacta **sin apóstrofos**.

## Pruebas

- **JVM**: que una fecha ausente tome la de página; que una **inválida no la tome**; que un lote con
  renglones sin fecha y sin fecha de página no se pueda confirmar; que elegir una fecha válida lo
  habilite. Las pruebas invocan producción, no una copia de la regla.
- **Instrumentada, en el teléfono**: que la fecha elegida llegue a `saleDateEpochDay` en Room, por el
  camino real. Sigue el estilo de `LedgerBatchIsolationTest`, que ya usa `LedgerBatchCoordinator`.

**El teléfono A059P de Diego está conectado en depuración.** Corre ahí, no en emulador. La app
instalada es la debug, así que `installDebug` actualiza encima: **no la desinstales**, se borraría la
base local y el token de App Check.

## Gate

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Más `assembleDebug`, la clase instrumentada nueva, la paridad de claves y la puerta del idioma.
Cuenta las pruebas desde los XML de `app/build/test-results/`, no del texto de Gradle. Reporta
números reales y los comandos exactos. **La suite instrumentada completa aborta en
`ProductPhotoSourceTest`; ese aborto no cuenta como verde**, así que corre tu clase por su nombre.

## Lo que NO es de este encargo

Quedan anotados y son de otra vuelta: los límites de plan en el callback de confirmación, que el
planificador duplica la validación financiera del 4a, su suma de deuda inicial sin `Math.addExact`,
el digest de la clave que concatena texto libre sin escape, y que la máquina de estados no sobreviva
la recreación de la Activity.

---

# Ronda 2 — revisión de Codex (2026-09-07)

Codex confirma lo importante: **el bloqueo para publicar está resuelto.** `resolveDate` separa de
verdad *ausente* de *inválida* en todos los caminos, la clave del lote y lo que se escribe no se
desincronizan, la fecha vive en `LedgerReview` y sobrevive a ediciones y a un fallo, y la prueba
instrumentada recorre coordinador → planificador → repositorio → Room con tres fechas distintas, así
que no pasa por coincidencia con hoy.

Quedan dos defectos, los dos en el control de la fecha.

## 1. Editar una fecha válida borra el campo entero

`LedgerReviewScreen.kt:295` — `dateText` usa `remember(pageDateEpochDay)`. Si el campo dice
`2026-06-15` y la persona borra el mes para corregirlo, el texto deja de parsear, se emite `null`,
**cambia la llave del `remember` y el campo se reinicia vacío**. También reformatea lo que todavía se
está escribiendo.

Es el control principal de este encargo: corregir una fecha es justo lo que la gente va a hacer.
El borrador de texto tiene que ser estado propio del campo, y sólo **lo ya parseado** debe viajar
hacia arriba.

## 2. Editar un renglón puede quitar el bloqueo de una fecha futura

`onUpdateLedgerPageDate` valida contra `todayEpochDay`, pero `AiDebugLabModel.kt:1034`
(`onUpdateLedgerOperations`) **reconstruye la revisión sin pasarlo**. Secuencia: se fija mañana por
la máquina, queda bloqueado, se edita cualquier renglón y la reconstrucción pierde la referencia de
hoy.

Que `todayEpochDay` viaje con el estado de la revisión, o que la reconstrucción lo reciba siempre.
Una comprobación que depende de por dónde entró el cambio no es una comprobación.

## Gate

El mismo: `testDebugUnitTest --rerun-tasks`, `assembleDebug`, la clase instrumentada por su nombre en
el A059P, paridad y puerta del idioma. Y **una prueba por cada uno de los dos defectos**: escribir
una fecha a medias no debe perder lo escrito, y editar un renglón no debe desbloquear una fecha
futura.
