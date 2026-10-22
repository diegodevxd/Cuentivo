# HANDOFF CUENTIVO6 — el acuerdo del Ledger resuelve la duda de lectura, y el Ledger se despierta al abrir la app

**Tarea:** `TASK_CUENTIVO6.md` · **Rama:** `mexalm/ledger-crosscheck` (base `c8a5202`) ·
**Worktree:** `C:\Users\pibed\Downloads\CUENTIVO_mexalm_ledger` · **Fecha:** 2026-10-22

**Estado:** las dos partes están **construidas y probadas**, y la bandera
`MEXALM_LEDGER_CROSSCHECK` sigue **APAGADA**. Con la bandera apagada la respuesta de
`extractLedgerPage` es byte a byte la de hoy y la app se comporta exactamente como antes: sin
bloque no hay lista de confirmados, no hay marca en el renglón y ninguna duda se resuelve.

No se hizo push, no se hizo deploy, no se encendió la bandera, **no se arrancó ningún emulador ni
servidor**, no se instaló nada en ningún dispositivo y no se tocó ningún otro worktree.

---

## 1. De dónde salen las "dudas" de un renglón

Esta es la investigación que pedía el encargo, y es la que decide todo lo demás.

### 1.1 En el servidor: `uncertainFields` y `unreadableFields`

`functions/src/ai/ledgerExtraction.ts` (`parseSingleOperation`) lee lo que el modelo devolvió por
renglón. Las dos listas de dudas son **literales del modelo**:

| Lista | Qué es | En la app |
|---|---|---|
| `uncertainFields` | "Lo leí, pero no estoy seguro" — `AMOUNT`, `CUSTOMER`, `DATE`, `KIND` | "Dudoso" |
| `unreadableFields` | "No lo pude leer" — los mismos cuatro campos | "Ilegible" |

Reglas del parser del servidor queFABRICAN dudas y hay que saber:

- Un campo **no puede** estar en las dos listas: si lo está, la operación se **descarta**
  (`INVALID_UNREADABLE_FIELDS`).
- `proposedKind`, `customerName` y `amountCents` sólo pueden **faltar** si su campo está en
  `unreadableFields`. Si falta y no está ahí, la operación se descarta.
- Los **`warnings`** que significan "alguien tiene que mirar esto" se **copian a
  `uncertainFields`** (`WARNING_BLOCKS`), porque un aviso que sólo viaja como texto es un aviso
  que un cliente puede dejar tirado:

  | `warning` | duda que genera |
  |---|---|
  | `AMBIGUOUS_SEPARATOR` | `AMOUNT` |
  | `MULTIPLE_MOVEMENTS_IN_ROW` | `AMOUNT` |
  | `LOW_CONFIDENCE_AMOUNT` | `AMOUNT` |
  | `AMBIGUOUS_KIND` | `KIND` |
  | `INHERITED_CUSTOMER` | `CUSTOMER` |
  | `LOW_CONFIDENCE_CUSTOMER` | `CUSTOMER` |

- Si la **suma de los productos no cuadra** con el monto (`ITEMS_TOTAL_MISMATCH`), el monto va a
  `uncertainFields`.
- Si la lista de productos vino **truncada**, el monto va a `uncertainFields`.
- `NOT_A_MOVEMENT` **descarta** la operación: no es un movimiento dudoso, es que no es un
  movimiento.
- `confidence` y `sources` **no deciden nada**: son información, no una duda.

`pageIncomplete` y `unreadTextRemaining` son de la **página**, no del renglón: avisan de que
quedó texto sin leer.

### 1.2 En la app: qué hace que un renglón esté "por revisar"

`app/src/main/java/com/cuentivo/app/ai/ledger/LedgerReview.kt`:

```kotlin
fun isRegisterableLedgerOperation(op: LedgerOperation): Boolean {
    if (op.unreadableFields.isNotEmpty() || op.uncertainFields.isNotEmpty()) return false
    if (op.proposedKind == null) return false
    // ... cliente no vacío (o customerId) y amountCents canónico
}
```

Y el resumen (`domain/LedgerImportSummaryCalculator.kt`):

- **listo para registrar** = registrable **Y** sin diagnóstico financiero **Y** sin objeción **Y**
  con fecha (del renglón o de la página);
- **por revisar** = detectados − listos − descartes.

### 1.3 Las dudas que NO son de lectura (las de negocio)

Éstas **no** viven en `uncertainFields`: las calcula la app con los datos del negocio, y el Ledger
no las sabe ni puede resolverlas.

| Duda | Dónde vive |
|---|---|
| el año distinto al de la hoja ("¿Los cambio a 2026?") | `LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE` (motivo `YEAR_MISMATCH`) |
| una fecha vieja, futura o faltante | `IMPLAUSIBLE_DATE` (`TOO_OLD` / `FUTURE`) y `hasMissingDates` |
| un cliente nuevo o no registrado | el grupo de la revisión (`isNew`) y `CUSTOMER_CONFIRMATION_REQUIRED` |
| un posible duplicado | `LedgerDuplicateBlocking` |
| el saldo o los límites | `FinancialRejectionReason` (`ZERO_AMOUNT`, `PAYMENT_WITHOUT_DEBT`, `PAYMENT_EXCEEDS_DEBT`, `OVERFLOW`) |
| el precio que no cuadra con el catálogo | `SUSPECT_DECIMAL_SHIFT`, `PRICE_MISMATCH_SUSPECT`, `UNUSUAL_CENTS`, `PAGE_TOTAL_MISMATCH` |

**Éstas son las que el encargo dice que NO se resuelven, y no se tocan.**

---

## 2. La regla nueva

> Un renglón pasa a "listo para registrar" sólo si se cumplen **todas** estas condiciones:
> 1. el bloque `mexalmCrosscheck` existe, `ledgerStatus == "OK"` y ese renglón tiene par en el
>    Ledger;
> 2. Gemini y el Ledger coinciden en **tipo**, **cliente normalizado** y **monto en centavos**, y
>    no hay ningún desacuerdo reportado para ese `lineIndex`;
> 3. la **única** razón de su duda es de lectura (monto, cliente o tipo).

### 2.1 Por qué hizo falta un dato nuevo en el bloque

El bloque que ya existía (`{agreed, disagreements, ledgerStatus, latencyMs}`) **no alcanza**, y
por una razón que es el corazón del encargo:

> `agreed: true` significa "**no hubo desacuerdos**", no "el Ledger confirmó cada renglón".

Un desacuerdo sólo existe cuando **los dos lados** tienen un valor y difieren. Un Ledger que
devuelve `customerName: null` en todas partes (o que no dice nada del monto) **no discrepa de
nada**: deja `agreed: true` con la lista de diferencias vacía. Con el bloque anterior, la app no
tenía forma de distinguir "el Ledger confirmó este renglón" de "el Ledger no dijo nada de este
renglón" — y esa distinción es exactamente la que decide si una duda se resuelve.

Por eso el bloque ahora lleva **una lista más**: `confirmedLineIndexes`, los renglones que el
Ledger confirmó de verdad. Es **aditiva y opcional**: un servidor más viejo no la manda, y su
ausencia significa "no hay renglones confirmados", **nunca** "confirmó todos".

### 2.2 Lo que el Ledger RESUELVE

Sólo la **duda de lectura**: `AMOUNT`, `CUSTOMER` y `KIND`, en las dos listas
(`uncertainFields` e `unreadableFields`).

Un renglón confirmado por el Ledger deja de pedir revisión, y el renglón se registra **con lo que
dijo la lectura de la foto**: el monto, el cliente y el tipo **no se reescriben**. El dueño sigue
pulsando el botón de siempre; **no se registra nada automáticamente**.

### 2.3 Lo que el Ledger NO resuelve

- el año distinto al de la hoja (el aviso "¿Los cambio a 2026?" se queda igual);
- una fecha faltante o rara —la **duda de `DATE` no se toca**, porque el Ledger no compara
  fechas: no sabe nada de ellas;
- un cliente nuevo o no registrado;
- un posible duplicado;
- el saldo o los límites;
- cualquier otra regla de negocio.

Y una más, la más importante de todas:

> **La verificación cruzada NO es un OCR.** Compara dos interpretaciones de la **misma**
> transcripción: el texto lo escribió Gemini y el Ledger vuelve a interpretarlo. Resuelve "cómo
> se interpretó esta línea", **no** "si el papel era legible". Una fila que el modelo no pudo
> leer del papel sigue sin poder resolverse con esta lista, y por eso la fila que el Ledger
> confirma tiene que **afirmar los tres campos**: "no lo sé" no es un acuerdo.

### 2.4 Dónde vive la decisión

- **Servidor** (`functions/src/ai/mexalmCrosscheck.ts`): `confirmAgreedPairs(pairs)` — los renglones
  en los que **los dos lados** afirmaron tipo, cliente y monto, y los tres coinciden (el cliente
  comparado **normalizado**). Es la única fuente de la lista.
- **App** (`app/src/main/java/com/cuentivo/app/ai/ledger/MexalmCrosscheckConfirmation.kt`): el
  objeto puro `MexalmCrosscheckConfirmation` decide, para un renglón, si el Ledger lo confirmó y
  qué dudas de lectura quita. **No toca** la fecha, ni el texto, ni el monto.
- **App** (`LedgerReviewEngine.build`): aplica la resolución **al construir la revisión**, no en
  el parser. El bloque pertenece a la **página** y cada edición reconstruye la revisión desde
  cero (igual que la fecha detectada), así que la resolución tiene que sobrevivir el *rebuild*:
  si se aplicara en el parser, la primera edición de un renglón devolvería la duda.

---

## 3. Qué se construyó

### 3.1 Parte A — el acuerdo resuelve la duda de lectura

**Servidor**

- `functions/src/contract.ts`: `MexalmCrosscheckBlock` gana `confirmedLineIndexes?: readonly number[]`
  (opcional, aditivo).
- `functions/src/ai/mexalmCrosscheck.ts`: `confirmAgreedPairs(pairs)` y el bloque la lleva
  (`confirmedLineIndexes: didNotRead ? [] : confirmAgreedPairs(pairs)`). Cuando el Ledger no leyó
  la página (`BLOCKED`/`REVIEW_REQUIRED` sin operaciones) la lista va **vacía**: no hay veredicto
  que haya confirmado nada.

**App**

- `MexalmCrosscheck.kt`: `MexalmCrosscheckPayload` gana `confirmedLineIndices: Set<Int>` (vacía
  por omisión).
- `LedgerExtractionGateway.kt`: el parser acepta la llave nueva **opcional**. Las cuatro llaves de
  siempre tienen que estar, y la única extra permitida es `confirmedLineIndexes`; una lista mal
  formada (un texto, un decimal, un negativo, un `Int` que no cabe) **tumba el bloque entero, no
  la página** — el bloque es un comentario al lado de la página, y un comentario malo no puede
  costar la página.
- `MexalmCrosscheckConfirmation.kt` (nuevo): el objeto puro con la regla.
- `LedgerReview.kt`: `LedgerReviewEngine.build` aplica la resolución a las operaciones antes de
  contarlas. Todo lo que consume la revisión (el resumen, el planificador del lote, la pantalla)
  ve el renglón ya resuelto, sin cambiar ninguna de las reglas de negocio.
- `LedgerReviewScreen.kt`: la marca discreta del renglón ("Confirmado por el Ledger de MexaLM") en
  el **color positivo de la app** (`secondaryContainer`), en su propia fila, con su `testTag`
  (`ledger-crosscheck-confirmed-<renglón>`). Y en la tarjeta del veredicto, el conteo de
  renglones confirmados ("12 renglones confirmados por el Ledger"), que es lo que explica por qué
  un renglón dejó de pedir revisión.
  - La marca va en **todo** renglón que el Ledger confirmó, incluso si ese renglón todavía espera
    por una fecha: son dos hechos distintos, y la insignia roja de "Dudoso" sigue al lado, así que
    la persona ve las dos cosas ("el Ledger confirmó el monto, pero la fecha es dudosa") en vez de
    un renglón que cambió de color sin explicación.
- `MexalmCrosscheckVerdict.kt`: el veredicto lleva `confirmedRowCount`.
- Textos: `ai_ledger_crosscheck_row_confirmed`, `ai_ledger_crosscheck_confirmed_count` (plural) y
  el texto de la nota de la tarjeta, que cambió para ser cierto: antes decía "Sólo informa: no
  cambia lo que se registra" y ahora dice **"Sólo confirma la lectura: los montos y los tipos
  siguen siendo los de la foto."** (el bloque sólo aparece con la bandera encendida, así que
  con la bandera apagada el texto no se ve en ninguna parte).

### 3.2 Parte B — despertar el Ledger al abrir la app

**Callable nueva `warmMexalmLedger`** (`functions/src/warmMexalmLedger.ts`,
`functions/src/ai/mexalmLedgerWarm.ts`, cableado en `functions/src/index.ts`):

| Regla | Cómo se cumple |
|---|---|
| sólo usuarios autenticados | `uid` presente y **no anónimo**; si no, `unauthenticated` |
| con App Check | la frontera `AI_BOUNDARY` compartida, con `enforceAppCheck: true` |
| con CuentivoAI activo | el mismo documento de suscripción que escribe `verifyAiSubscription` (`aiEntitlements/{uid}`), con la misma regla de siempre: `isEntitlementValid` (habilitada y sin expirar) |
| con la bandera apagada no hace nada | `SKIPPED / DISABLED`, y **ni siquiera lee la suscripción** |
| `GET <MEXALM_LEDGER_URL>/health`, ≤ 2 s, sin bloquear | `AbortSignal.timeout(2000)`, y el handler **no espera** al Ledger: dispara y responde |
| no registra datos del usuario | el log es `MEXALM_LEDGER_WARM` con `status` y `reason`: ni el uid, ni la dirección, ni la respuesta del health check |
| 1 despertar por usuario cada 5 min | `MexalmLedgerWarmLimiter`, en memoria, con la marca puesta **al intentar** (un Ledger caído no se reintenta en bucle) |

**El token NO se liga a esta callable.** El secreto `MEXALM_LEDGER_TOKEN` sigue ligado a una
sola callable (`extractLedgerPage`), y un health check no lo necesita. Es además lo que exige la
prueba de guardia de `functions/test/index.test.ts`.

**App**: `app/src/main/java/com/cuentivo/app/ai/MexalmLedgerWarmGateway.kt` (nuevo) con el
transporte de la callable y el objeto puro `MexalmLedgerWarmPolicy` (sólo el plan `AI`).
`CuentivoApp.kt` lo llama en el **`ON_RESUME`** que ya existía (el mismo que refresca la
disponibilidad de la IA), en una corrutina, sin bloquear la UI, y **ningún error sale**: un
`catch` se traga la red caída, la callable que todavía no existe y el Ledger que no contesta.
`MainActivity.kt` construye el gateway y lo pasa.

**Por qué una callable aparte y no piggyback sobre `getAiAvailability`** (el encargo lo pedía
justificar): `getAiAvailability` dice si el **servicio de IA está encendido para todo el mundo**,
y la app la llama con un TTL de 5 minutos y sólo cuando el plan es de IA. El despertar es otra
cosa —tiene su propio ritmo, su propio límite y su propio motivo— y meterlo ahí haría que un
"AI disponible" significara dos cosas distintas. Con una callable aparte, cada una dice una
cosa, y **apagar la bandera deja la de despertar inerte sin tocar la otra**.

**No hay nada que "apagar" al cerrar la app**: el Ledger es un Cloud Run con `min-instances=0` y
se apaga solo cuando nadie lo usa. La app no abre ni cierra nada; sólo le pide que empiece a
levantarse.

---

## 4. Archivos exactos

### Nuevos

| Archivo | Qué hace |
|---|---|
| `functions/src/warmMexalmLedger.ts` | La callable: autenticación, bandera, suscripción, límite de 5 min y respuesta. |
| `functions/src/ai/mexalmLedgerWarm.ts` | El despertador: `GET <baseUrl>/health` con plazo de 2 s, sin token, nunca lanza. |
| `functions/test/warmMexalmLedger.test.ts` | 20 pruebas de la callable y del despertador. |
| `app/src/main/java/com/cuentivo/app/ai/ledger/MexalmCrosscheckConfirmation.kt` | El objeto puro con la regla: qué resuelve el Ledger y qué no. |
| `app/src/test/java/com/cuentivo/app/ai/ledger/MexalmCrosscheckConfirmationTest.kt` | 16 pruebas de la regla (la última es de punta a punta: la respuesta del servidor, el parser, la revisión y el resumen). |
| `app/src/main/java/com/cuentivo/app/ai/MexalmLedgerWarmGateway.kt` | El transporte del despertar y la política de quién puede pedirlo. |
| `app/src/test/java/com/cuentivo/app/ai/MexalmLedgerWarmGatewayTest.kt` | 3 pruebas (la política y dos guardas de fuente). |
| `MEXALM_TEST_OUTPUT_6.txt` | La salida real de todo lo de esta parte. |

### Modificados

| Archivo | Qué cambió |
|---|---|
| `functions/src/contract.ts` | `MexalmCrosscheckBlock.confirmedLineIndexes?`. |
| `functions/src/ai/mexalmCrosscheck.ts` | `confirmAgreedPairs(pairs)` y la lista en el bloque. |
| `functions/src/ai/firestoreControlPlane.ts` | `readEntitlement(uid)`: la suscripción de la cuenta, leída por su cuenta. |
| `functions/src/index.ts` | La callable `warmMexalmLedger` y su cableado. |
| `functions/test/mexalmCrosscheck.test.ts` | +6 pruebas de los renglones confirmados. |
| `functions/test/index.test.ts` | +2 guardas de la callable nueva (y la lista de callables exportadas ahora incluye `warmMexalmLedger`). |
| `app/.../ai/ledger/MexalmCrosscheck.kt` | `confirmedLineIndices` en el payload. |
| `app/.../ai/ledger/MexalmCrosscheckVerdict.kt` | `confirmedRowCount` en el veredicto. |
| `app/.../ai/ledger/LedgerExtractionGateway.kt` | El parser acepta la llave nueva (opcional) y la lee. |
| `app/.../ai/ledger/LedgerReview.kt` | `LedgerReviewEngine.build` aplica la resolución. |
| `app/.../ui/ai/LedgerReviewScreen.kt` | La marca del renglón y el conteo de confirmados. |
| `app/.../ui/CuentivoApp.kt` | El despertar en el `ON_RESUME`. |
| `app/.../MainActivity.kt` | Construye y pasa el gateway del despertar. |
| `app/src/main/res/values/strings.xml` · `values-en/strings.xml` | 2 `<string>` y 1 `<plurals>` nuevos por idioma, y el texto de la nota de la tarjeta. |
| `app/src/test/.../LedgerExtractionGatewayTest.kt` | +3 pruebas del parser. |

**Ningún otro archivo fue tocado.** `firestore.rules`, `CLAUDE.md`, `AGENTS.md` y
`PROJECT_MEMORY.md` no se tocaron (ver §8).

---

## 5. Cómo probarlo

### 5.1 Lo que se corrió (números reales, en `MEXALM_TEST_OUTPUT_6.txt`)

| | |
|---|---|
| `npm --prefix functions test` | **574/574** (21 archivos). Antes: 546. |
| `npm --prefix functions run lint` | limpio (`tsc --noEmit`, 0 errores). |
| Android `testDebugUnitTest` | **1448/1448**, 0 fallos, 0 errores, 0 omitidas (155 clases). Antes: 1426/153. |
| `assembleDebug -Pcuentivo.functionsEmulatorHost=127.0.0.1` | **BUILD SUCCESSFUL** (APK de 68 MB). **NO se instaló nada.** |
| `BuildConfig` del build debug | `FUNCTIONS_EMULATOR_HOST = "127.0.0.1"` (leído del archivo generado). |
| Puertas de idioma de `CLAUDE.md` | las dos **vacías**. |
| Paridad ES/EN | 701/701 `<string>`, 17/17 `<plurals>`, **cero claves huérfanas**. |
| Regla 1 (dinero en `Long`) | limpia en los siete archivos de producción tocados. |

### 5.2 Verificación por mutación

Porque en este proyecto una prueba en verde ya mintió cinco veces: se rompió producción a
propósito y se comprobó que las pruebas **caen**.

| Mutación | Qué cayó |
|---|---|
| servidor: `confirmAgreedPairs` devuelve `[]` siempre | 4 pruebas |
| servidor: la bandera ya no manda y el límite ya no aplica | 2 pruebas |
| app: la regla no resuelve nada | 5 pruebas |
| app: la regla **también** resuelve la duda de `DATE` | 2 pruebas |

Y una prueba nueva del parser **cayó durante el desarrollo**: la primera versión del parser
exigía el juego de llaves exacto del bloque y tiraba la página entera cuando el servidor mandaba
la lista nueva. La prueba lo dijo antes de que llegara al teléfono.

### 5.3 La prueba real, que falta (es de Diego)

La prueba que hay que hacer contra el **Ledger de verdad**, con la bandera encendida y la libreta
de la clienta que falló:

```bash
# 1) en functions/.env.local (o el .env.<project_id> del emulador)
MEXALM_LEDGER_CROSSCHECK=true
MEXALM_LEDGER_URL=<dirección del Ledger>
MEXALM_LEDGER_TOKEN=<el token>

# 2) levantar el emulador de Functions (lo hace Claude, no esta tarea)
# 3) el build debug con el host, en un teléfono físico
./gradlew.bat assembleDebug -Pcuentivo.functionsEmulatorHost=127.0.0.1
adb reverse tcp:5001 tcp:5001
```

Y en la pantalla "Revisar libreta" tiene que verse:

- la tarjeta **"✓ El Ledger de MexaLM está de acuerdo"**;
- **"12 renglones confirmados por el Ledger"**;
- el resumen **"12 listos para registrar · 0 por revisar"** (y no "0 · 12");
- la marca discreta **"Confirmado por el Ledger de MexaLM"** en cada renglón;
- y el botón de registrar, que **sigue siendo el de siempre**: nadie registra nada solo.

---

## 6. Los riesgos

### 6.1 La lista de confirmados viaja en el bloque, y el bloque es un contrato

El parser de Android es **estricto de la forma**: una llave de más tumba el bloque entero. Se
aceptó **una** llave nueva y **sólo** esa; el resto sigue siendo un bloque inválido. Si mañana
el bloque necesita otro dato, es otro contrato y otra decisión.

Un servidor viejo (sin la lista) contra una app nueva: el bloque se lee sin lista y la lista
vacía — o sea, **ningún renglón se resuelve**. Es lo correcto: sin dato, no hay confirmación.

### 6.2 La duda que se quita es una duda **de lectura**, y sólo de lectura

Ésta es la línea que no hay que cruzar: la lista de lectura del §2.3 (año, fecha, cliente nuevo,
duplicado, saldo, límites) **no se toca**. Está en el código, en las pruebas y en la mutación
que comprueba que la `DATE` no se resuelve.

### 6.3 La verificación cruzada no es un OCR (§2.3)

Resuelve "cómo se interpretó esta línea", no "si el papel era legible". La fila que el Ledger
confirma tiene que **afirmar los tres campos** precisamente por eso: "no lo sé" no es un
acuerdo. Vale la pena decirlo otra vez en voz alta antes de encender la bandera en producción.

### 6.4 La marca y el conteo del resumen

La marca es un hecho, no un premio: dice "otra lectura dijo lo mismo". El texto de la nota de la
tarjeta se cambió para que siga siendo cierto ("Sólo confirma la lectura: los montos y los
tipos siguen siendo los de la foto"). El que **no** se registra es lo que la lectura de la foto
dijo, siempre.

### 6.5 La regla se aplica al construir la revisión, y la revisión se reconstruye

La duda resuelta **no vuelve** al editar otro renglón, porque la resolución se aplica en
`LedgerReviewEngine.build` y el bloque viaja con la revisión. Una excepción conocida: **la
revisión restaurada tras rotar el teléfono** no tiene bloque (el bloque no se serializa en el
`Bundle` — es el hueco que ya dejó la parte 2, C6.2). En ese caso el renglón conserva la duda
resuelta y **no** muestra la marca: el resultado es el mismo, y lo que falta es la explicación.

### 6.6 El despertar: el servidor responde antes de que el Ledger despierte

La callable **no espera** al health check. En Cloud Run la instancia puede congelarse justo
después de responder, y una petición suelta se puede quedar a medias. En la práctica el
despertar se dispara (que es lo que importa: es lo que levanta la instancia) y el health check
es de 2 segundos. La consecuencia honesta: **el despertar es un intento, no una garantía** — y
está bien que lo sea: la lectura de la libreta no depende de que el Ledger despierte, sólo
tarda más.

### 6.7 El despertar se pide en cada vuelta a primer plano

La app llama a `warmMexalmLedger` cada vez que vuelve al primer plano y la cuenta tiene
CuentivoAI. El **servidor** es el que pone el límite de 5 minutos por cuenta, así que el
teléfono puede preguntar más de lo que se despierta. Con la bandera apagada, el servidor
responde `SKIPPED / DISABLED` sin hacer nada: es una ida y vuelta en vacío, y **el
comportamiento de la app es exactamente el de antes**.

### 6.8 La lista `confirmedLineIndexes` no es lo mismo que `agreed`

Ya está dicho en §2.1 y está en el código, pero conviene repetirlo porque es el error fácil:
**"no hubo desacuerdos" no es "el Ledger confirmó cada renglón"**. Un Ledger que no tuvo opinión
de nada deja `agreed: true` y la lista vacía.

---

## 7. Qué falta para producción

1. **El secreto `MEXALM_LEDGER_TOKEN` tiene que existir en Secret Manager antes del primer
   deploy.** Está declarado en `secrets:` de `extractLedgerPage`, y un secreto declarado que no
   existe **rompe el deploy**. (La callable nueva **no** lo usa: el health check no lo necesita.)
2. **Play Data Safety y la política de privacidad**: ya había que declarar que la transcripción
   de la libreta viaja a un tercero (el Ledger de MexaLM) desde la parte 1. Con esta parte **no
   cambia el flujo de datos** —el mismo texto, al mismo destinatario— pero sí cambia **para qué
   se usa**: ahora la lectura de un tercero puede **resolver una duda** de la lectura de la foto
   y, con ella, decidir qué se registra sin que nadie mire ese renglón. Eso es un uso nuevo, y
   la declaración debería decirlo.
3. **La bandera `MEXALM_LEDGER_CROSSCHECK` sigue apagada.** Encenderla es una decisión de
   Diego, después de la prueba real del §5.3.
4. **La versión de la app**: la que sale de este trabajo es la primera en la que un renglón puede
   quedar "listo para registrar" **sin que nadie lo mire**. Conviene subir el número de versión y
   anotar el cambio, porque es un cambio de comportamiento visible.
5. **La callable `warmMexalmLedger` tiene que existir en el servidor antes de que una app nueva
   la llame.** Con un servidor viejo, la llamada falla y la app la traga: no rompe nada, pero
   tampoco despierta. Es un despliegue, no un código.
6. **El `min-instances=0` del Ledger**: si algún día se quiere que no se apague, es una decisión
   de costo del Ledger, no de la app.

---

## 8. Lo que NO se hizo

- **No se hizo push, ni deploy, ni build de release.** Todo es local, en la rama
  `mexalm/ledger-crosscheck`.
- **No se encendió la bandera** en ningún lado. Sigue apagada, que es como vive en producción.
- **No se arrancó ningún emulador ni servidor** (el de Functions en el 5001 no se tocó) y **no se
  instaló** nada en ningún dispositivo. `adb reverse` no se ejecutó.
- **No se corrieron las instrumentadas** ni `lintDebug` (hacen falta emulador o teléfono).
- **No se corrió `npm --prefix functions run release:check`**: ese script levanta el emulador de
  Firestore, y esta tarea prohíbe arrancar emuladores. Se corrieron `npm test` y `npm run lint`.
- **No se tocaron llaves ni secretos**, ni `functions/.secret.local`, ni `keystore.properties`, ni
  ningún `*.jks`.
- **No se cambió el contrato de la petición** (`extract_ledger_page`): siguen siendo las mismas
  cinco llaves. Lo único que creció es el bloque **opcional** de la respuesta.
- **No se cambió el modelo, ni el prompt, ni el parser del servidor de la página.** La regla
  nueva no toca una sola operación: sólo decide qué dudas se quitan.
- **No se registraron las operaciones del Ledger** en ningún lado: la lista de confirmados son
  números de renglón, no datos de la libreta.
- **No se tocó** `CLAUDE.md`, `AGENTS.md`, `firestore.rules` ni `PROJECT_MEMORY.md`.
- **No se tocaron los `TASK_CUENTIVO*.md`** ni `opencode_c6.log`: son de lacoordianción, no
  míos.

---

## 9. Lo que queda abierto (es de Diego)

- **La bandera sigue apagada** y nadie ha corrido esto contra el Ledger real desde este
  worktree. La prueba que falta es la de la libreta de verdad (§5.3).
- **El motivo del Ledger sigue sin llegar al teléfono**: el `status` viaja verbatim y la app lo
  traduce, pero el código de motivo (`reasons`, por ejemplo `plan_uncertifiable_line`) no se
  manda: el bloque no creció por eso. Si hace falta en la pantalla, es un contrato nuevo.
- **`agreed` sigue significando "no hubo desacuerdos"**, no "el Ledger confirmó cada renglón".
  La lista de confirmados es la que dice lo segundo, y ahora existe.
- **La insignia por renglón sigue siendo una por diferencia** (una fila puede llevar "Ledger:
  monto" y "Ledger: cliente"). La marca de "confirmado" es **otra** cosa y va en su propia fila.
