# HANDOFF CUENTIVO1 — verificación cruzada con el Ledger de MexaLM en `extractLedgerPage`

**Tarea:** `TASK_CUENTIVO1.md` · **Rama:** `mexalm/ledger-crosscheck` (base `76242e5`) ·
**Worktree:** `C:\Users\pibed\Downloads\CUENTIVO_mexalm_ledger` · **Fecha:** 2026-10-20

**Estado:** la verificación cruzada está **construida, probada y APAGADA**. Con la bandera apagada
la salida de `extractLedgerPage` es byte a byte la de hoy (verificado contra el código anterior,
no sólo por inspección). No se hizo push, no se hizo deploy, no se tocó la app Android y no se
tocó ningún otro worktree.

---

## 1. Qué se construyó

Cuando `MEXALM_LEDGER_CROSSCHECK` está en `true`, después de la extracción de Gemini la página se
manda al Ledger de MexaLM **en texto**, y las dos lecturas se comparan renglón por renglón. El
resultado viaja en una llave **nueva y aditiva** de la respuesta:

```jsonc
"mexalmCrosscheck": {
  "agreed": true,                    // true SÍ O NÚCLEO significa "los dos lados dicen lo mismo"
  "disagreements": [                 // sólo las diferencias
    {
      "lineIndex": 1,
      "field": "AMOUNT",             // TYPE | CUSTOMER | AMOUNT | MISSING_IN_LEDGER | MISSING_IN_GEMINI
      "gemini": { "proposedKind": "SALE", "customerName": "Toño", "amountCents": "3800" },
      "ledger": { "proposedKind": "SALE", "customerName": "Toño", "amountCents": "3900" }
    }
  ],
  "ledgerStatus": "OK",              // el status del Ledger, verbatim
  "latencyMs": 812                   // ida y vuelta medida en el servidor
}
```

**Lo que NO hace:** no cambia ni una de las operaciones que Gemini devuelve. El desacuerdo se
marca **sólo dentro de ese bloque** y el cliente Android decide después. No escribe en Room, ni en
Firestore, ni en la cola de sincronización, ni toca dinero.

### De dónde sale el texto que se manda al Ledger

El Ledger es un lector de **texto**, y en este repo **no existe una transcripción de la página como
tal**: lo que hay es el `rawText` de cada renglón, que el modelo escribe como "el texto de ESE
renglón tal como está escrito en la hoja" (obligatorio desde el bloque v4 del contrato). La página
que se manda al Ledger se arma con esos renglones, **uno por línea y en orden de `lineIndex`**.

> **Lo que esto NO es, y conviene decirlo claro:** no es una segunda lectura de la foto. El texto lo
> transcribió Gemini; el Ledger vuelve a interpretar ese mismo texto. Lo que se caza es "Gemini
> interpretó mal lo que escribió" (cliente equivocado, monto mal partido, tipo equivocado). Lo que
> **no** se caza es "Gemini leyó mal el papel": para eso haría falta OCR independiente, que aquí no
> existe.

### Emparejamiento de los renglones

Por `lineIndex`, que es la identidad natural del renglón. Si el Ledger numera distinto (otra base,
o cuenta sólo los renglones con movimiento) y no hay **ni un** `lineIndex` en común, se empareja
**por posición**. El síntoma de un emparejamiento roto es que **todos** los renglones discrepan, y
eso se ve. Una operación del Ledger sin `lineIndex` (el contrato C1 la exige) se reporta con
`lineIndex: -1` en vez de perderse en silencio.

### Qué se compara y qué no

| | |
|---|---|
| **Se compara** | `proposedKind` (tipo), `customerName` **normalizado** (NFD, sin diacríticos, minúsculas, espacios colapsados — la misma regla que `CustomerNameNormalizer` en la app) y `amountCents` (centavos, comparados como cadena canónica: `Long` de JavaScript perdería centavos exactos) |
| **No se compara** | la fecha, la descripción, los productos, la confianza, las advertencias |
| **No es desacuerdo** | que a un lado le falte un campo: es que uno de los dos no lo pudo leer, y eso ya lo dice el renglón con `unreadableFields`. Sí es desacuerdo que un lado diga "aquí hay un movimiento" y el otro diga "aquí no hay nada" (`MISSING_IN_LEDGER` / `MISSING_IN_GEMINI`) |

---

## 2. Archivos exactos

### Nuevos

| Archivo | Qué hace |
|---|---|
| `functions/src/ai/mexalmLedgerClient.ts` | Cliente HTTP del Ledger: `POST <MEXALM_LEDGER_URL>/v1/extract`, `Authorization: Bearer <token>`, cuerpo `{"text", "knownCustomers"}`. Plazo de 8 s. Nunca lanza: todo fallo vuelve `null` con una categoría. **Nunca registra el texto.** |
| `functions/src/ai/mexalmCrosscheck.ts` | La comparación: arma el texto de la página, normaliza nombres, empareja renglones y devuelve SOLO las diferencias. |
| `functions/test/mexalmCrosscheck.test.ts` | 23 pruebas nuevas (se listan en §5). |
| `MEXALM_TEST_OUTPUT.txt` | Salida real de `npm test`, `npm run lint` y del gate del emulador de Firestore. |

### Modificados

| Archivo | Qué cambió |
|---|---|
| `functions/src/contract.ts` | Tipos del bloque (`MexalmCrosscheckBlock`, `MexalmCrosscheckDisagreement`, `MexalmCrosscheckOperationSummary`, `MexalmCrosscheckDisagreementField`) y la llave **opcional** `mexalmCrosscheck?` en las respuestas v3, v4 y v5 (v5 la hereda de v4). |
| `functions/src/extractLedgerPage.ts` | Dependencia nueva `crosscheck?: MexalmLedgerCrosschecker \| null` (`null` = apagada) y el bloque se agrega **al final** de la respuesta y **sólo si existe**. |
| `functions/src/index.ts` | Los tres parámetros de Functions, el armado del cliente y el `logger.warn("MEXALM_LEDGER_FAILURE", ...)`. |
| `functions/test/index.test.ts` | 5 guardas nuevas sobre el composition root (bandera apagada por defecto, el secreto ligado sólo a `extractLedgerPage`, el valor del secreto leído sólo dentro del handler, el log sin texto de página, ninguna URL literal). Las 12 pruebas anteriores se conservan **sin tocar**. |
| `.gitignore` | `/functions/.env` y `/functions/.env.*` (el `.env` del emulador puede llevar el token y antes **no** estaba ignorado). |

### Ningún archivo fuera de `functions/` y `.gitignore` fue tocado

La app Android (`app/`) no se tocó. `CLAUDE.md`, `AGENTS.md`, `firestore.rules` y
`PROJECT_MEMORY.md` no se tocaron (ver §7, "qué no se hizo").

---

## 3. Cómo encender la bandera en el emulador de Functions

**Todo apagado es el estado por defecto. Para encenderlo hacen falta las TRES cosas:**

### 3.1 El secreto (una vez, en el proyecto real)

```bash
firebase functions:secrets:set MEXALM_LEDGER_TOKEN
```

Pega el token que te da el Ledger de MexaLM. Sale del prompt sin eco.

### 3.2 Los parámetros, en el emulador

Crea `functions/.env.local` (ya está en `.gitignore`):

```
MEXALM_LEDGER_CROSSCHECK=true
MEXALM_LEDGER_URL=http://127.0.0.1:8000
MEXALM_LEDGER_TOKEN=el-mismo-token-de-arriba
```

El emulador de Functions lee `.env`, `.env.local` y `.env.<project_id>` del directorio `functions/`
y los pone en `process.env`; los tres parámetros se leen de ahí. **El valor de la bandera tiene que
ser exactamente `true`** (en minúsculas): cualquier otra cosa la deja apagada.

> En el emulador el secreto también se resuelve desde el `.env`: `defineSecret` lee `process.env`.
> En **producción** no: el secreto tiene que existir en Secret Manager (ver 3.1) y el parámetro
> `MEXALM_LEDGER_CROSSCHECK` hay que ponerlo en el entorno de la función (archivo `.env.<project_id>`
> en el deploy, o la variable de entorno en Cloud Run).

### 3.3 Levantar el emulador y probar

```bash
# desde la raíz del repo
firebase emulators:start --only functions,firestore,auth

# en otra terminal, la prueba real de la callable: la app Android de debug con
# -Pcuentivo.useEmulators=true
```

Si sólo quieres ver la respuesta cruda, llama la callable con el emulador de Functions
(`http://127.0.0.1:5001/<project>/us-south1/extractLedgerPage`) mandando
`{"contractVersion": 5, "requestId": "<uuid v4>", "action": "extract_ledger_page",
"imageBase64": "<base64>", "imageMimeType": "image/jpeg"}` con un `Authorization: Bearer <token de
Firebase Auth>` y un `X-Firebase-AppCheck` válido.

**Para apagarla de vuelta:** `MEXALM_LEDGER_CROSSCHECK=false` (o borrar la variable) y reiniciar el
emulador. Con la bandera apagada no se hace **ninguna** llamada al Ledger.

### 3.4 Cómo saber que está encendida

En los logs del servidor:

- `MEXALM_LEDGER_FAILURE` con `category` y `status` — sale cuando el Ledger falla. **Nunca** lleva
  el texto de la página.
- La respuesta de la callable trae la llave `mexalmCrosscheck`. Si NO trae esa llave, o la bandera
  está apagada, o el Ledger no pudo dar veredicto (sección 6).

---

## 4. Qué secretos hay que crear

| Nombre | Tipo | Dónde | Para qué |
|---|---|---|---|
| `MEXALM_LEDGER_TOKEN` | **Secreto** (`defineSecret`) | Secret Manager del proyecto | `Authorization: Bearer <token>` del Ledger. **Ligado sólo a `extractLedgerPage`.** |
| `MEXALM_LEDGER_URL` | Parámetro (`defineString`) | entorno de la función / `.env` del emulador | Dónde vive el Ledger (p. ej. la URL del Space de Hugging Face). No es secreto: es una dirección. |
| `MEXALM_LEDGER_CROSSCHECK` | Parámetro (`defineBoolean`, default `false`) | entorno de la función / `.env` del emulador | La bandera. Apagada por defecto. |

> **OJO AL DESPLEGAR (esto rompe el deploy si no se hace primero):** un secreto declarado en
> `secrets:` tiene que **existir** en Secret Manager antes del deploy, o `firebase deploy` falla.
> O sea: **crear `MEXALM_LEDGER_TOKEN` antes del primer deploy de este cambio**, aunque la bandera
> siga apagada. Si todavía no quieres crearlo, quita `mexalmLedgerToken` del arreglo `secrets` de
> `extractLedgerPage` en `functions/src/index.ts` (y el `defineSecret`), y la verificación cruzada
> simplemente nunca podrá encenderse hasta que se vuelva a ligar.

---

## 5. Las pruebas (y qué prueba cada cosa)

`MEXALM_TEST_OUTPUT.txt` tiene la salida real. Resumen:

| | |
|---|---|
| `npm test` | **519 pruebas, 519 pasan, 0 fallan** (19 archivos) |
| `npm run lint` | limpio (`tsc --noEmit`, 0 errores) |
| gate del emulador de Firestore | **9 de 9** (comando aparte, como manda `CLAUDE.md`) |
| identidad byte a byte contra el código anterior | **3 de 3** (v3, v4, v5) |

Las 28 pruebas nuevas:

| Prueba | Qué garantiza |
|---|---|
| la salida es byte a byte la de hoy (comparada con el handler sin la dependencia de crosscheck) | la bandera apagada no cambia **nada** |
| snapshot en línea de la respuesta apagada | los bytes exactos, para que un cambio futuro se note |
| el Ledger de acuerdo da `agreed=true` | el camino feliz |
| las operaciones de Gemini no se tocan | el bloque es aditivo: las operaciones, el status, los descartes y `pageIncomplete` son idénticos a los de hoy |
| un monto distinto aparece como desacuerdo | y el renglón sigue siendo el de Gemini (no se reescribe) |
| un renglón que el Ledger no tiene | `MISSING_IN_LEDGER` |
| timeout → la respuesta es igual a la de hoy | y la falla queda registrada **sin texto de página** |
| una llamada que no contesta se corta | el plazo del cliente existe de verdad (20 ms en la prueba) |
| una respuesta inútil se descarta | `INVALID_RESPONSE`, sin cambiar la respuesta |
| un status sin veredicto (`INVALID_OUTPUT`) | no se compara: no hay veredicto que inventar |
| un status desconocido (`success`) | **no** apaga la verificación |
| una página sin texto no llama al Ledger | ni un gasto ocioso |
| la telemetría no lleva el texto de la página | ni el `rawText`, ni los nombres, ni los montos |
| el bloque de desacuerdos no lleva el texto de la página | el `rawText` no sale del servidor |
| el cuerpo que viaja al Ledger lleva el texto, y sólo el de los renglones | y `knownCustomers` va en `null` |
| normalización de nombres | acentos, mayúsculas y espacios no cuentan |
| el texto de la página es un renglón por línea | y un salto de línea dentro de un renglón no rompe la numeración |
| tipo, cliente y monto son los tres campos comparados | |
| el mismo cliente con otra grafía NO es desacuerdo | |
| un campo que a un lado le falta no es contradicción | |
| una operación del Ledger sin renglón se reporta | no se pierde en silencio |

Las pruebas del cliente usan el **código de producción** (cliente HTTP real + comparación real) con
un `fetch` falso inyectado: la prueba no reimplementa la regla, la ejercita.

**Lo que no se corrió y por qué** (está también en `MEXALM_TEST_OUTPUT.txt`): la app Android (esta
tarea no la toca); el gate de Android (sin cambios en `app/`, y exige Gradle con el trust store de
Norton); y `firebase emulators:exec` (la receta de la skill baja `firebase-tools` de la red, y esta
tarea prohíbe la red — el emulador se levantó con el JAR que ya estaba cacheado en la máquina, en el
puerto 8188 porque el 8080 estaba ocupado, y el gate corrió igual).

---

## 6. Riesgos

### 6.1 El cliente Android rechaza la página entera si la bandera se enciende sin actualizarlo

**El riesgo más serio de esta tarea.** En `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt`
el parser compara el **juego de llaves de la raíz**:

- v3 (línea 120): `if (rootKeys != baseKeys) return Unavailable` — exige **exactamente** las mismas
  llaves.
- v4/v5 (línea 122-125): `if (!allowedKeys.containsAll(rootKeys) || !rootKeys.containsAll(baseKeys))`
  — una llave de más también lo tumba.

Las dos cosas que se necesitan **antes** de encender la bandera:

1. Que el cliente acepte la llave `mexalmCrosscheck` en las llaves permitidas (y la acepte sólo si
   existe; la ausencia tiene que seguir siendo válida).
2. Que el cliente la **sepa usar**, o que por lo menos no la rechace.

Mientras eso no exista, **la bandera tiene que seguir apagada**. Con la bandera apagada no hay
riesgo: la respuesta no lleva la llave y el parser no cambia.

### 6.2 La transcripción de la libreta sale del servidor

**Esto es un flujo de datos nuevo.** Al encender la bandera, el texto de la libreta —nombres de
clientas y montos— viaja a un servicio de terceros (el Ledger de MexaLM). Antes no salía: salía la
foto a Gemini, y ya eso está declarado. Con el Ledger hay un destinatario más.

Consecuencias que hay que tener presentes:

- **La política de privacidad y la declaración de Play Data Safety** tienen que mencionar al
  destinatario nuevo antes de encender la bandera en producción.
- La regla del repo "Nada de PII, saldos ni tokens en logs" se cumple en el servidor: el texto
  **no** se registra nunca (ni en el log de fallas, ni en la respuesta de error). Pero el texto sí
  viaja en el cuerpo de la petición HTTP al Ledger, que es el punto del ejercicio.
- El Ledger es un servicio de MexaLM, no de Google: es un tercero con su propio manejo de datos.

### 6.3 Latencia

El camino de la libreta pasa de "Gemini y ya" a "Gemini **y después** el Ledger": hasta **+8 s**
por página (el plazo del Ledger). Con los tiempos de hoy, 45 s de Gemini + 8 s del Ledger = 53 s,
dentro del plazo de 60 s de la callable, pero **queda justo**. Si alguna vez se sube el plazo de
Gemini, hay que revisar el de la callable.

Mientras la bandera está apagada, la latencia es exactamente la de hoy.

### 6.4 El Ledger se está construyendo en paralelo

El contrato de `TASK_API1.md` dice `{"status","needsReview","reasons","operations","page",
"latencyMs","model"}` pero **no enumera los valores de `status`**. El cliente es defensivo:

- `operations` que no es un arreglo → `INVALID_RESPONSE`, no hay veredicto.
- `status` `INVALID_OUTPUT` o `REFUSED` (los de C1) → no hay veredicto, no se compara.
- **cualquier otro `status` se toma como veredicto** y se compara: un valor desconocido no puede
  apagar la verificación, y el `status` viaja verbatim en `ledgerStatus` para que se vea lo que dijo.
- `needsReview`, `reasons` y `page` **no** se usan todavía: no se comparan contra nada y no se
  exponen.

Si el Ledger termina usando otro nombre de campo o otro formato de centavos, hay que tocar
`readOperation` en `functions/src/ai/mexalmLedgerClient.ts` (un solo lugar).

### 6.5 La forma de los desacuerdos es provisional

`disagreements[]` es una lista nueva: **el cliente Android todavía no la conoce** y el formato
puede cambiar antes de que se use. La parte que no va a cambiar es la garantía: el bloque es
aditivo y no toca las operaciones.

### 6.6 `knownCustomers` va en `null`

El contrato de `extract_ledger_page` son **cinco llaves exactas** y el cliente Android no manda la
lista de clientes de la tienda, así que hoy no hay lista que mandar. El hueco quedó en el seam
(`crosscheck.crosscheck({ operations, knownCustomers })`): cuando el flujo la traiga, se pasa ahí y
no se toca nada más. Mandarla hoy exigiría **cambiar el contrato de la petición**, que el cliente
Android valida con lista blanca: no se hizo a propósito.

### 6.7 Lo que se comparó NO es dinero

La comparación es entre dos **lecturas** de la misma transcripción. El libro mayor no se toca: ni
una escritura, ni un movimiento compensatorio, ni un cambio de estado. El bloque es un comentario
al lado de la página.

---

## 7. Qué NO se hizo

- **No se tocó la app Android.** Ni una línea de `app/`. El bloque `mexalmCrosscheck` todavía no lo
  entiende ningún cliente instalado.
- **No se encendió la bandera en ningún lado.** Vive apagada en el código, y apagada es lo que se
  probó.
- **No se hizo push, ni deploy, ni build de release.** No se tocó `cuentivo-release.jks` ni ninguna
  llave. No se tocó ningún secreto.
- **No se usó la red.** `functions/node_modules` no estaba en este worktree y se armó con el
  `node_modules` ya instalado del worktree hermano
  (`C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\feature-cuentivo-ai\functions\node_modules`,
  **copiado, no modificado**: los `package-lock.json` de los dos worktrees tienen exactamente las
  mismas 245 entradas). `npm ci --offline` falló porque el tarball de `ws@8.21.3` no está en la
  caché de npm de esta máquina.
- **No se cambió `knownCustomers`.** Va en `null`, como se explica en 6.6.
- **No se hizo OCR independiente.** La verificación compara dos interpretaciones del **mismo**
  texto de Gemini (ver §1). Para cazar un error de lectura del papel hace falta otro lector.
- **No se comparan fecha, descripción, productos, confianza ni advertencias.** Sólo tipo, cliente y
  monto, como pide el encargo.
- **No se expusieron `needsReview`, `reasons` ni `page` del Ledger.** No hay con qué compararlos y
  no hacen falta para el veredicto.
- **No se corrió el gate de Android** (JVM, instrumentadas, `lintDebug`): no hubo cambios en
  `app/`.
- **No se tocaron `CLAUDE.md`, `AGENTS.md`, `firestore.rules` ni `PROJECT_MEMORY.md`.** El estado
  vivo de `PROJECT_MEMORY.md` no se actualizó porque este trabajo no cambia el estado de la app
  (la bandera está apagada y la app no se toca); cuando se encienda la bandera, ese archivo sí
  tiene que cambiar.
- **No se deprecó ni se tocó `functions/node_modules`** (está en `.gitignore`).

---

## 8. Qué haría falta para mostrar el bloque en la UI (para el encargo siguiente)

**1. Aceptar la llave nueva (imprescindible antes de encender la bandera).**
En `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt`:

- línea 122: añadir `"mexalmCrosscheck"` a `allowedKeys` (v4/v5);
- línea 110-117: `baseKeys` **no** cambia — la llave es opcional, y el chequeo de v4/v5 ya exige que
  las llaves de `baseKeys` estén presentes, no que no haya de más. Ojo: la rama de **v3** (línea
  120) exige igualdad exacta, así que para v3 la llave tiene que **no** enviarse, o el cliente
  tendría que tratarla aparte.
- añadir un parser del bloque: `agreed: Boolean`, `disagreements: List<...>` con
  `{lineIndex: Int, field: String, gemini: {...}?, ledger: {...}?}`, `ledgerStatus: String`,
  `latencyMs: Long`; y **ignorar** lo que no se sepa (mismo espíritu que el resto del parser: lo
  inesperado colapsa, no se interpreta a medias).

**2. Cargar en el modelo de la pantalla.** `LedgerExtractionPayload` (línea 54) gana un campo
opcional, por ejemplo `mexalmCrosscheck: MexalmCrosscheckPayload? = null`. Los parsers de Android
son del mismo estilo estricto, así que conviene un tipo de dato propio y no un `Map` suelto.

**3. La UI.** Lo mínimo honesto:

- una insignia en el renglón con desacuerdo: "el Ledger leyó $39.00 donde la hoja dice $38.00"
  (el `rawText` ya está en la operación, así que se puede mostrar la línea tal como está escrita);
- y un resumen en la pantalla de la página: "el Ledger de MexaLM estuvo de acuerdo" / "el Ledger
  leyó 1 renglón distinto de los 12";
- **nada de esto cambia lo que se registra**: el renglón sigue registrando lo que dijo Gemini. Si
  el renglón además ya iba marcado con dudas (`uncertainFields`), la insignia del Ledger es
  **información adicional**, no una razón nueva para bloquear. Lo que el Ledger discrepa debería
  entrar por la misma puerta que las dudas existentes, si se decide que entre.

**4. La decisión de producto que falta (es de Diego, no de quien implemente).** ¿Qué hace la app
con un desacuerdo? Lo mínimo que se puede hacer sin tocar el dinero: **marcarlo para revisión**,
igual que un `uncertainFields`. Lo que **no** se debe hacer sin que él lo decida: descartar el
renglón, o cambiar el monto por el del Ledger.

**5. La prueba de Android que hace falta** (el repo ya tiene el patrón): un caso instrumentado que
reciba una respuesta con `mexalmCrosscheck` y verifique que (a) la página **no** se rechaza y (b)
la insignia aparece. Y otro que verifique que una respuesta **sin** el bloque (servidor viejo o
bandera apagada) sigue funcionando igual.

---

## 9. Resumen para el coordinator

- La pieza de servidor está **completa y probada**: cliente HTTP con plazo corto, comparación por
  renglón, bloque aditivo, bandera apagada por defecto, y **cero** cambios en el comportamiento
  actual (verificado byte a byte contra el código anterior, no por inspección).
- Lo que **bloquea** encender la bandera en producción es **el cliente Android**: hoy rechaza la
  página entera si aparece una llave nueva en la raíz. Ese es el trabajo siguiente (§8).
- Antes del primer deploy hay que **crear el secreto `MEXALM_LEDGER_TOKEN`**, o el deploy falla.
- Con la bandera encendida hay un **flujo de datos nuevo** (la transcripción de la libreta hacia un
  tercero) que hay que declarar en la política de privacidad y en Play Data Safety.
- `MEXALM_TEST_OUTPUT.txt` tiene los números reales: 519/519 pruebas, lint limpio, gate del
  emulador 9/9.
