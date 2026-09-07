# Fase B — Paso 4b: la pantalla de revisión (encargo para AGY)

**Fecha:** 2026-09-06
**Base:** 4a commiteado en `903d12e`. `ai/ledger/LedgerReview.kt` ya deriva la agrupación, los
conteos, los diagnósticos y qué entra; la máquina ya tiene `LedgerExtracting`, `LedgerReviewing` y
`LedgerConfirming`, y ya bloquea la confirmación ante errores financieros.
**Alcance:** SOLO la pantalla. **No** el registro del lote (paso 5). Sin commits, sin deploys.

## La regla que gobierna este encargo

**Ninguna decisión vive en el Composable.** La pantalla dibuja lo que `LedgerReview` ya derivó y
manda eventos a la máquina. Si te descubres calculando un saldo, decidiendo si una fila entra o
contando clientes dentro de un `@Composable`, **eso pertenece al 4a**: extráelo ahí, con su prueba
JVM, y que la pantalla sólo lo muestre.

## Qué construir

### 1. Entrar a la importación

Botón en la pantalla de CuentivoAI. **Sin permisos nuevos**: cámara por intent externo y
**Photo Picker** para elegir una foto existente. `ai/capture/AiCameraCaptureStore.kt` y
`AiCapturePolicy.kt` ya existen; úsalos, no escribas otro almacén.

La **sesión de importación se identifica antes de abrir la cámara** y la máquina ya sabe descartar
resultados de otra sesión: la pantalla debe pasarle esa identidad, no inventarse una al volver.

### 2. La foto

- Archivo **temporal local**, borrado al terminar o al descartar, y limpieza de lo que quedó de
  interrupciones anteriores (`deleteStale` ya existe).
- **Nunca** se guarda como archivo permanente ni se sube a la infraestructura del proyecto.
- La promesa correcta, para cuando toque escribirla, es **"sin archivo permanente de fotos en
  Cuentivo"**, no "no se guarda en ningún lado": la cámara escribe un temporal y el proveedor
  retiene registros de abuso. No pongas en la UI una promesa más fuerte que la verdad.

### 3. La revisión

- **Lista de operaciones editables**, con **una sola confirmación al final**.
- **El recorte de la foto al lado de cada renglón**, usando `region`. Esto no es decoración: revisar
  el texto que produjo la IA contra otro texto de la IA no es verificar. Si un renglón no trae
  `region`, di que no hay recorte en vez de mostrar la foto entera como si lo fuera.
- **El tipo es editable**: deuda anterior, venta o abono.
- **Tocar un renglón dudoso abre la corrección**, no activa una casilla. Si el campo en duda no se
  resuelve, ese renglón no entra — y eso ya lo decide el 4a, tú sólo lo reflejas.
- **Los ilegibles llegan vacíos** y el usuario los escribe mirando el recorte. Es la decisión de
  producto de Diego del 2026-09-06.
- **Agrupación por cliente** con deuda anterior → resultante, y marca de cuáles se van a crear.
- **Ventas y abonos separados**, con sus conteos y **cuántas filas quedaron excluidas**.
- Resumen final del tipo "crear 3 clientes y registrar 18 movimientos", tomado del modelo.
- Cuando la confirmación esté bloqueada por un error financiero, **di cuál es**. "No se puede
  registrar" sin motivo es el defecto que ya tenemos en `AI_UNAVAILABLE` y no queremos otro.

### 4. Voz

Deshabilitada durante extracción, revisión y confirmación. La máquina ya lo dice; la pantalla tiene
que **respetarlo de verdad**, no sólo esconder el botón.

## Idioma

Cada texto nuevo va en `values/strings.xml` **y** en `values-en/strings.xml`. La puerta del idioma
tiene que salir vacía:

```bash
grep -rnE '(Text\(|text = |label = |title = |detail = |placeholder = |contentDescription = |emptyText = |actionLabel = |eyebrow = |note = |backDescription = )"[^"]*[a-zA-ZáéíóúñÁÉÍÓÚÑ¿¡][^"]*"' app/src/main/java/com/cuentivo/app/ui --include=*.kt | grep -vE '"\$ "|" · "|testTag|neo-action-press|"Cuentivo"'
```

Y la paridad tiene que cuadrar — **cuenta, no confíes en un número escrito**:

```bash
grep -c "<string name=" app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
```

**Cuidado con los apóstrofos en `values-en/`**: Android exige `\'`, y escribirlos desde un script
con varias capas de shell mangla el escapado. Lo más seguro es redactar el inglés sin apóstrofos
("do not" en vez de "don't").

## Pruebas

- Lo que decida algo, en JVM, contra el 4a.
- La pantalla, en `androidTest/` con Compose, al estilo de `CuentivoAppTest`. Necesita emulador; si
  no logras correrlas, **dilo**.
- **Pendiente que dejó Codex en el 4a**: en las pruebas financieras nuevas todos los casos tienen
  además cero operaciones ejecutables, así que quitar la condición de error financiero las dejaría
  verdes. Añade una fila válida junto a una inválida para aislarlo.

## Gate

`testDebugUnitTest --rerun-tasks`, `assembleDebug`, la puerta del idioma, la paridad de claves, y
las instrumentadas si el emulador aguanta. Cuenta desde los XML. Números reales y comandos exactos.
