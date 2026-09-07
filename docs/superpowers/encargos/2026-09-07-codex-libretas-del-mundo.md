# Consulta a Codex — leer libretas de cualquier letra y cualquier orden

**Fecha:** 2026-09-07
**Quien pregunta:** Claude (orquestador). **Quien decide:** Diego.
**Modo:** solo lectura y opinion. **No edites codigo, no hagas commits, no despliegues.**

## Contexto: que cambio desde tu ultima revision

### 1. `responseJsonSchema` ya no se envia. Ninguna version.

Tu hipotesis fue que Gemini acepta un subconjunto de JSON Schema y que bastaba con reducir el
esquema a `type, properties, required, items, enum, additionalProperties, minimum, maximum,
maxItems`. **Se probo contra la API real y sigue respondiendo `INVALID_ARGUMENT` (HTTP 400).** Dos
intentos, dos rechazos:

- Esquema completo -> 400.
- Esquema reducido al subconjunto que nombraste, sin `pattern`, `minLength`, `uniqueItems` ni
  `exclusiveMinimum` -> 400 tambien.

Lo unico que funciona en produccion es **no mandar `responseJsonSchema` en absoluto**, dejando
`responseMimeType: "application/json"`. Asi esta desplegado ahora (`functions/src/ai/geminiLedgerProvider.ts`).
El modelo es `gemini-3.5-flash-lite`, `temperature: 0`, `maxOutputTokens: 8192`.

**Primera pregunta concreta:** dado que tu subconjunto tampoco pasa, .que crees que esta rechazando
realmente la API? .Es el modelo (`flash-lite`) el que no soporta `responseJsonSchema`? .Vale la pena
que probemos `responseSchema` (el campo viejo, tipo `Schema` con `Type.OBJECT`) en vez de
`responseJsonSchema`? Si crees que hay una forma de recuperar el forzado de forma, dila como un
experimento concreto y falsable, no como teoria: exactamente que campo poner y como sabremos si
funciono. Si crees que no vale la pena y la validacion en `parseLedgerExtraction` basta, dilo
tambien: es una respuesta legitima.

### 2. La instruccion del sistema ahora describe como es una libreta de verdad

Ese fue el cambio que hizo que por fin sacara clientes. La foto de Diego es una hoja de catalogo:
codigo de producto pegado al margen izquierdo (24027), producto en medio, **precio antes que el
nombre del cliente**, centavos escritos en chiquito y volados sobre la linea (`79.??` = 79.90),
comillas heredando el renglon de arriba, nombres abreviados con marcas al lado ("Mayra TS").
Antes el modelo devolvia operaciones sin `customerName` porque la instruccion nunca le dijo que las
columnas pueden venir en cualquier orden.

La instruccion completa esta en `buildLedgerSystemInstruction` (linea 165 en adelante del mismo
archivo). **Leela entera antes de opinar.**

### 3. La foto se prepara antes de subirla

`app/src/main/java/com/cuentivo/app/ai/ledger/LedgerImagePreparer.kt`: escala el lado largo a 2400 px,
JPEG calidad 85, y **aplica la rotacion EXIF a los pixeles** porque el modelo lee pixeles, no
metadatos. Se corrigio una sobre-reduccion que borraba los centavos chiquitos: el muestreo solo
divide entre potencias de dos, asi que ahora se muestrea conservador y se escala exacto al limite.

## La pregunta de Diego, en sus palabras

> "que podriamos hacer para que pueda leer libretas mundiales de cualquier tipo de letra y orden"

Esto no es retorica. Los usuarios son senoras que venden por catalogo y cada libreta es distinta:
cuadricula, raya, hojas sueltas, columnas inventadas, dos paginas fotografiadas juntas, luz mala,
la hoja torcida, boligrafo azul encima de impresion gris.

**Lo que quiero de ti son recomendaciones ordenadas por relacion valor/costo**, y para cada una: que
cambia exactamente, donde, y **como se mediria que mejoro**. Ejes que me interesan, sin que te
limites a ellos:

- **Instruccion**: .esta ya demasiado larga y compite consigo misma? .Que sobra? .Que falta que sea
  universal y no especifico del catalogo de Diego? Una instruccion de 25 vinetas puede diluir las
  tres que importan.
- **Ejemplos**: .conviene mandar uno o dos ejemplos de imagen+JSON (few-shot multimodal) en vez de
  describir con palabras como es una libreta? Es la palanca mas fuerte que se me ocurre y la mas
  cara. .Cual seria el minimo util?
- **Modelo**: `flash-lite` es lo mas barato. .Vale la pena `flash` para paginas dificiles, o dos
  pasadas (una barata, y una cara solo si la barata declaro mucha incertidumbre)? Diego cobra
  $299 MXN/mes por CuentivoAI, asi que el costo por foto importa de verdad.
- **Imagen**: 2400 px y JPEG 85, .es lo correcto para letra a mano fina? .Conviene enderezar la hoja
  o subir el contraste en el telefono antes de mandarla? Ojo: cualquier preproceso que "limpie"
  puede borrar un trazo.
- **Fallo honesto**: hoy, si el modelo no entiende la hoja, la persona ve "no se pudo registrar la
  libreta". .Como deberia degradarse en vez de fallar? Por ejemplo, devolver los renglones que si
  entendio y declarar el resto, que ya es lo que hace `pageIncomplete` y `unreadableFields`.

## Lo que NO quiero

- No propongas reescribir la arquitectura ni cambiar de proveedor.
- No propongas nada que rompa las invariantes: dinero en `Long` centavos, nada financiero se edita,
  la revision humana antes de escribir es obligatoria y no se salta.
- No inventes capacidades de la API. Si no estas seguro de que un campo existe, dilo como duda.

## Aparte: una decision de UI que quiere opinion

Debajo del campo de texto del laboratorio hay **dos botones**: "elegir imagen" (la captura OCR vieja)
e "importar libreta / ticket" (el flujo nuevo, completo). Diego dice, textual:

> "hay dos botones abajo de apartado de escribir uno de elegir imagen que ese no le veo sentido y
> otro aparte de importar libreta/ticket siento que la gente se puede confundir"

Estoy de acuerdo con el. Mi inclinacion es **dejar un solo boton**, el de libreta/ticket, y quitar el
viejo. .Ves alguna razon para conservar el camino OCR viejo que yo no este viendo (por ejemplo, que
sea el unico camino para algo que el flujo nuevo no cubre)? Mira `AiDebugLabScreen.kt`. Si el viejo
alimenta algo que el nuevo no, dilo; si no, confirma que se puede quitar y di que hay que borrar con
el.
