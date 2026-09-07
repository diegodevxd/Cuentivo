# Fase B — Paso 1: contrato v3 y schema de lista (encargo para AGY)

**Fecha:** 2026-09-05
**Alcance:** SOLO el paso 1 del plan `docs/superpowers/plans/2026-09-05-cuentivoai-foto-libreta.md`.
No implementes extracción, ejecutores, pantalla de revisión ni registro del lote (pasos 2 a 5).
**No commits. No deploys. No tocar Play Store.**

## Qué se construye

El contrato de servidor para "foto de la libreta": una petición nueva que lleva la imagen y una
respuesta que devuelve **una lista de operaciones propuestas**, cada una con su tipo propuesto y su
incertidumbre **por campo**. Todo en `functions/`, TypeScript puro y probable sin red.

## Regla número uno: v2 no se toca

El parser de v2 exige **exactamente cuatro claves en la raíz**. En T4 ya se comprobó que agregar una
sola clave rompe a todo cliente publicado. Por lo tanto:

- `InterpretAiRequest`, `InterpretAiResponse`, `validateInterpretAiRequest`,
  `createNotConfiguredResponse` y `SafeAiProposal` se quedan **byte por byte como están**.
- v3 son **tipos y funciones nuevos y separados**, no un campo opcional dentro de v2.
- Si algo de v2 te estorba, el diseño está mal: para y dilo, no relajes v2.

## Archivos

- `functions/src/contract.ts` — tipos y validador de la petición v3.
- `functions/src/ai/ledgerExtraction.ts` (**nuevo**) — parser del JSON que devuelve el modelo,
  al mismo estilo que `functions/src/ai/interpretation.ts`. Reexpórtalo desde `contract.ts` igual
  que ya se hace con `parseSafeAiProposal`.
- `functions/test/contract.test.ts` y `functions/test/ledgerExtraction.test.ts` (**nuevo**).

## Petición: `extract_ledger_page`

Mismo patrón de lista blanca que los dos validadores que ya existen (objeto plano, no nulo, no
arreglo, sin símbolos, **claves exactas**, un solo motivo de rechazo fijo, nunca lanza, nunca
registra, nunca normaliza ni genera ids).

```
contractVersion: 3
requestId: UUID de 36 caracteres, mismo regex que ya hay
action: "extract_ledger_page"
imageBase64: string base64 no vacío
imageMimeType: "image/jpeg" | "image/png" | "image/webp"
```

- Tamaño máximo `MAX_LEDGER_IMAGE_BASE64_LENGTH = 8_000_000` unidades de código UTF-16; pasarse es
  rechazo, igual que cualquier otro fallo.
- Valida que `imageBase64` sea base64 bien formado (alfabeto y relleno), no sólo que sea string.
- La constante y el regex de base64 van junto al resto de constantes del archivo.

## Respuesta v3

```
contractVersion: 3
requestId: string
status: reusa InterpretAiStatus tal cual está
operations: readonly LedgerOperation[] | null
pageIncomplete: boolean
```

`pageIncomplete` es `true` cuando la página **no cupo entera**: se alcanzó el tope de renglones o
el modelo declaró que quedó texto sin leer. Nunca se trunca en silencio; ése es el punto del campo.

## `LedgerOperation`

```
lineIndex: entero >= 0, único dentro de la lista, orden ascendente
proposedKind: "PRIOR_DEBT" | "SALE" | "PAYMENT"
customerName: string no vacío, tope MAX_DISPLAY_NAME_LENGTH (reusa el de interpretation.ts)
amountCents: STRING decimal canónico de centavos, mismo regex que CANONICAL_CENTS_RE
description?: string acotado (concepto libre; un cargo sin producto es válido y NO se inventa producto)
date?: SafeDateCandidate (reusa el tipo y la validación de fecha que ya existe)
uncertainFields: readonly ("AMOUNT" | "CUSTOMER" | "DATE" | "KIND")[]  // sin duplicados, puede ir vacío
region?: { x, y, width, height }  // recorte normalizado 0..1 de la foto, para la evidencia del paso 4
```

Reglas duras del parser:

- **El dinero jamás es un `number` de JSON.** Si llega un número —entero o con decimales— la
  operación se rechaza. Es exactamente por ahí por donde entraría dinero fraccionario a un sistema
  de centavos enteros; `RemoteMerger` ya toma esta misma postura.
- `MAX_LEDGER_OPERATIONS = 25`. Una lista más larga **no se recorta**: se rechaza el documento.
- Nada de ids de entidad, rutas, SQL, llamadas a herramientas ni instrucciones ejecutables, igual
  que en `SafeAiProposal`.
- `region` sólo se acepta con los cuatro números dentro de 0..1 y `width`/`height` > 0; si viene mal,
  se descarta **la región**, no la operación (la evidencia es ayuda, no identidad).
- Una operación malformada se descarta **contando los descartes**, sin abortar la lista, y eso
  vuelve `pageIncomplete` verdadero. Un documento cuya raíz está mal sí se rechaza entero.
- Fallo cerrado siempre: ante la duda, rechaza.

## Presupuesto de tokens (no lo implementes, déjalo anotado)

`geminiTextProvider.ts:316` fija `maxOutputTokens: 512`. Una lista de 25 renglones no cabe ahí y se
cortaría a media respuesta. **Eso es del paso 2**, pero deja un comentario en `ledgerExtraction.ts`
que lo diga, para que nadie conecte v3 al proveedor sin subir el presupuesto.

## Pruebas

Las pruebas tienen que **ejercitar el código de producción** y **fallar si ese código se rompe**.
Una prueba que copia la regla dentro de sí misma, o que afirma contra un objeto paralelo que nadie
llama, ya nos pasó cinco veces en este proyecto y la va a revisar Codex. Como mínimo:

1. **Compatibilidad**: una petición v2 válida sigue validando igual; el validador v2 **rechaza** una
   petición v3; el validador v3 **rechaza** una petición v2. Nada de v2 cambió de forma.
2. Petición v3: clave de más, clave de menos, versión mala, acción mala, UUID mal formado, mime no
   permitido, base64 basura, imagen sobre el tope.
3. Parser: lista vacía; una operación de cada `proposedKind`; monto como número JSON **rechazado**
   (prueba también `1234.5` y `1234`); monto con formato no canónico; 25 operaciones aceptadas y 26
   rechazadas; `lineIndex` duplicado o desordenado; `uncertainFields` con valor desconocido o
   duplicado; `region` fuera de rango que se descarta dejando viva la operación; operación
   malformada en medio que se descarta y pone `pageIncomplete`.
4. Verificación de honestidad: neutraliza una a una las guardas nuevas (el rechazo de números, el
   tope de 25, la lista blanca de claves) y comprueba que **caen exactamente las pruebas que deben
   caer**. Cuenta las ocurrencias antes y después del reemplazo y **aborta si son cero** — un script
   que dice "guardas neutralizadas" sin haber reemplazado nada ya nos dio un falso verde.

## Gate antes de entregar

```
npm --prefix functions run lint
npm --prefix functions test
npm --prefix functions run build
```

No hace falta el gate del emulador: este paso no toca Firestore. Reporta el número real de pruebas
antes y después, y el comando exacto que corriste. No inventes resultados de build.

## Entrega

Deja el árbol limpio y sin commitear, y reporta: archivos tocados, qué decidiste donde el encargo
dejaba margen, y qué NO hiciste.

---

# Ronda 2 — correcciones de la revisión de Codex (2026-09-05)

Codex revisó la entrega. **v2 quedó intacto y la guarda del dinero es correcta** (el regex está
anclado a toda la entrada y JavaScript, a diferencia de Python, no deja que `$` case antes de un
salto de línea final). Lo que sigue son los huecos que sí hay que cerrar.

## 1. Página completa con exactamente 25 operaciones — **defecto**

`parseLedgerExtraction` sólo enciende `pageIncomplete` por declaración del modelo o por descartes.
Con exactamente 25 operaciones devuelve `pageIncomplete: false`, y
`ledgerExtraction.test.ts:115` **afirma ese `false`**, dejando fijada la conducta equivocada.

Alcanzar el tope es justo el caso que el plan manda avisar: 25 renglones leídos casi siempre
significan que la página seguía. Corrige el parser para que `operations.length === MAX_LEDGER_OPERATIONS`
encienda `pageIncomplete`, y **corrige la prueba** para que exija `true`. Que una prueba llame a
producción no la vuelve correcta si afianza la expectativa contraria al plan.

## 2. Los descartes pierden información que la pantalla necesita — **defecto**

`discardedCount` se calcula y se tira: una fila inválida y veinte producen el mismo booleano. El
plan pide mostrar en la revisión **cuántas filas quedaron excluidas**, y con esto no se puede.

Saca del parser un diagnóstico seguro por descarte: **posición en el arreglo de entrada**,
`lineIndex` si resultó válido, y un **motivo de un catálogo fijo** (`MALFORMED`, `OUT_OF_ORDER`,
etc.). Nunca devuelvas el objeto malformado: es texto del modelo y no queremos pasearlo por la app.

Distingue además dos cosas que hoy se confunden en un solo booleano:
- **descartes conocidos**, que sabes contar; y
- **texto que el modelo no alcanzó a leer**, cuya cantidad es desconocida.

Ajusta `LedgerExtractionResult` y `ExtractLedgerPageResponse` en consecuencia. Sigue siendo v3 sin
publicar, así que puedes cambiar su forma libremente; **v2 sigue sin tocarse**.

## 3. "Objeto plano" y "nunca lanza" no se cumplen del todo — endurecimiento

`isPlainObject` no comprueba el prototipo, y `hasOnlyKeys` sólo mira claves propias: un objeto con
los campos requeridos **heredados** pasa. Y un getter que lance deja escapar una excepción del
parser y del validador v3, que prometen no lanzar nunca.

Acota el prototipo a `Object.prototype` o `null`, y asegura que ninguna entrada rara pueda hacer
que estas funciones lancen. **No cambies `interpretation.ts` ni nada de v2** para esto: es
endurecimiento local de v3.

## 4. Huecos de cobertura

Faltan: fechas límite **incluyendo años bisiestos** (29 de febrero válido e inválido), los máximos
de las cadenas (`customerName` y `description` en el tope y uno por encima), símbolos y prototipos
en el parser, y los extremos del dinero de **18 y 19 dígitos** (el regex admite hasta 18).

## 5. La verificación de honestidad no es verificable

Reportaste haber corrido `verify_honesty.js` con cuatro guardas neutralizadas, pero **ese archivo
no existe en el árbol** y no queda evidencia de la corrida. Un resultado que nadie puede reproducir
no cuenta como evidencia — es exactamente el falso verde que ya nos costó una sesión.

Deja el script **committeable** en `functions/scripts/verifyLedgerGuards.js`, que:
- cuente las ocurrencias del patrón **antes y después** del reemplazo y **aborte si son cero**;
- neutralice una guarda a la vez y restaure el archivo siempre, incluso si falla;
- imprima qué pruebas cayeron con cada mutación;
- salga con código distinto de cero si alguna mutación **no** hizo caer pruebas.

Inclúyelo en el reporte con su salida real.

## Gate de la ronda 2

Los mismos tres comandos (`lint`, `test`, `build`) más la corrida del script de mutaciones. Reporta
el número real de pruebas y los comandos exactos. Sin commits, sin deploys.

---

# Ronda 3 — el script de mutaciones se puede dar un falso verde (2026-09-05)

Codex aprobó **el contrato y el parser**: las 25 operaciones ya avisan, los diagnósticos de descarte
sirven para la pantalla sin pasear texto malformado por la app, y el endurecimiento de prototipo y
getters está cubierto. Queda **un solo defecto**, y está en el verificador mismo.

## El defecto

En `functions/scripts/verifyLedgerGuards.js`, `runTest` devuelve `success: false` ante **cualquier**
salida distinta de cero, y `verifyMutation` trata ese `false` como "la guarda fue detectada". Por lo
tanto cuentan como éxito cosas que no lo son:

- que la mutación rompa la **sintaxis** o los tipos y vitest ni siquiera cargue el archivo;
- que falle `npx` o el entorno;
- que el patrón `-t` **no case con ninguna prueba** (vitest también sale distinto de cero);
- que la prueba ya estuviera roja **antes** de mutar.

Las líneas `FAIL`/`AssertionError` se imprimen como evidencia para el humano, pero **no se exige que
existan** ni que correspondan a la prueba esperada. Es el mismo patrón que nos dio el falso verde de
ayer, sólo que una capa más adentro: esta vez el que miente es el verificador.

Que yo haya visto caer las pruebas correctas en mi corrida es evidencia válida; **el código 0 del
script, por sí solo, todavía no lo es.**

## Qué tiene que hacer el script

1. **Línea base verde primero.** Corre la suite sin mutar y **aborta** si no está verde. Sin base
   verde, ninguna caída posterior prueba nada.
2. **Distinguir aserción de infraestructura.** Usa el reporter JSON de vitest
   (`npx vitest run --reporter=json`) y exige, para cada mutación:
   - que el archivo de pruebas **cargara** (nada de error de compilación o de importación);
   - que el número de pruebas ejecutadas sea **mayor que cero** — si el patrón `-t` no casó con
     nada, eso es un **fallo del script**, no una guarda detectada;
   - que haya **al menos una prueba fallida por aserción**, y que su nombre **coincida** con el que
     esa mutación debía tumbar.
3. **Un error de carga o de entorno aborta**, con un mensaje que lo diga. Nunca se cuenta como
   detección.
4. **Restaura siempre** (el `finally` ya está bien) y, al final de todo, vuelve a correr la suite
   para confirmar que quedó **verde otra vez**: eso demuestra que la restauración funcionó.
5. Sale distinto de cero si cualquiera de esas condiciones no se cumple.

## Detalle menor, sin urgencia

Si un getter lanza dentro de `region`, el `catch` descarta **toda la operación**, cuando la regla
dice descartar sólo la región. No ocurre con JSON real del modelo. Arréglalo si sale barato; no
bloquea.

## Gate

`lint`, `test`, `build` y la corrida del script nuevo. Además, **demuestra que el script ya no se
engaña**: mete a propósito una mutación que rompa la sintaxis y enseña que ahora **aborta** en vez
de reportar éxito. Sin commits ni deploys.
