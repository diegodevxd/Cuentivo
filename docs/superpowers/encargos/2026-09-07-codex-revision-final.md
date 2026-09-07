# Revisión final — los tres commits de hoy

**Fecha:** 2026-09-07. **Solo lectura y opinión.** No edites, no comitees, no despliegues.

Diego pregunta una cosa concreta: **¿estos cambios están bien?** Si algo está mal, dilo con el
archivo y la línea. Si está bien, dilo sin adornar.

## Los tres commits

- `8ffdaa1` — `functions/src/ai/geminiLedgerProvider.ts`: se dejó de enviar `responseJsonSchema`.
- `caf6e71` — `LedgerImagePreparer.kt` + su prueba instrumentada: escalado preciso al límite.
- `5dd42e2` — `AiDebugLabScreen.kt`: un solo botón de foto, y que exista en release.

Míralos con `git show <sha>` y `git diff 9f94e6e..HEAD -- <ruta>` si te sirve.

## Lo que quiero que verifiques, en este orden

### 1. Tu pregunta pendiente: `responseJsonSchema`

Se te acabó la cuota antes de contestar esto. Los hechos: `gemini-3.5-flash-lite` responde
**HTTP 400 INVALID_ARGUMENT** tanto con el schema completo como con uno reducido al subconjunto que
tú nombraste (`type, properties, required, items, enum, additionalProperties, minimum, maximum,
maxItems`). Sin `responseJsonSchema`, solo con `responseMimeType: "application/json"`, funciona y
así está desplegado.

Tres respuestas breves:
1. ¿Qué crees que rechaza realmente la API? ¿Es el modelo `flash-lite` el que no soporta el campo?
2. ¿Vale la pena probar `responseSchema` (el campo viejo, `Schema` con `Type.OBJECT`) en vez de
   `responseJsonSchema`? Si sí, dime el experimento **exacto y falsable**: qué poner y cómo sabremos
   si funcionó.
3. ¿Tu recomendación es quedarnos sin forzado de forma, confiando en `parseLedgerExtraction`?

### 2. La eliminación del camino OCR viejo — ¿quedó algo suelto o algo roto?

Tú avisaste de lo importante y era verdad: el botón de libreta estaba dentro de
`if (ocrEngine != null)` y `debugAiOcrEngine` devuelve `null` en release, así que **la importación de
libreta no existía en el AAB publicado**. Ya está desanidado; ahora depende de
`extractLedgerPage != null && activeBusinessId?.isNotBlank() == true`.

Se quitaron: `showSourceSelector` y su `ModalBottomSheet`, `pickImageLauncher`, `cameraLauncher`,
`pendingCaptureUri`, la creación de `ocrEngine`, y el string `ai_debug_lab_capture` en ambos idiomas.

Comprueba con lectura de código, no de memoria:
- ¿Quedó **algo que el flujo de libreta necesitaba** y se borró por accidente? En particular
  `captureStore`, `cameraStore`, `CaptureUriSaver`, `deleteStaleExcept`, los permisos y rutas de
  cámara del `FileProvider`.
- El barrido pasó de `deleteStaleExcept(listOf(pendingCaptureUri, pendingLedgerUri))` a
  `deleteStaleExcept(listOf(pendingLedgerUri))`. ¿Es correcto ahora que solo hay una captura viva?
- `closeLab` y el diálogo de "borrar el día" ya no borran `pendingCaptureUri`. ¿Puede quedar basura
  en el directorio de capturas por algún camino?
- ¿La nueva condición del botón puede dejarlo **invisible cuando debería verse**? Piensa en el
  arranque: `activeBusinessId` mientras aún no hidrata, y `extractLedgerPage` en release.
- **`AiDebugLabMachine.onOcrResult` se quedó sin ningún llamador de producción**, pero conserva 7
  pruebas en `AiDebugLabMachineTest`. Lo mismo `debugAiOcrEngine` y `AiOcrEngine`. ¿Lo borramos con
  sus pruebas, o hay razón para conservarlo? Es una decisión, no un descuido: quiero tu opinión
  antes de tocar código probado.

### 3. Las dos pruebas de guarda que tuve que cambiar

`AiDebugLabGuardsTest` lee el `.kt` **como texto** y afirma sobre él. Dos fallaron al borrar el
camino viejo y las reapunté al camino de libreta:
- `camera capture uses existing store...`: el bloque ahora va de `val ledgerCameraLauncher` a
  `Dialog(`, así que abarca **las dos** launchers de libreta.
- `pending capture uri survives activity recreation...`: ahora exige
  `deleteStaleExcept(listOf(pendingLedgerUri))`.

Pregunta incómoda a propósito: **¿estas pruebas valen algo?** Afirman sobre el texto fuente, no
ejercitan el Composable. ¿Siguen cazando el defecto que las motivó, o al reapuntarlas las dejé
pasando en verde sobre otra cosa? Si son teatro, dilo.

### 4. El mensaje de fallo que agregué

En `ledgerPickLauncher`, cuando `prepared == null`, ahora se añade
`AiChatMessage.AssistantLedgerFailed` además de `discardLedgerCapture()`, como ya hacía la cámara.
¿Es correcto ese mensaje para **todas** las causas que llevan a `prepared == null` (mime no
permitido, no se pudo crear el archivo, copia fallida, preparación fallida)? ¿O alguna merece decir
otra cosa?

### 5. `LedgerImagePreparer`

`sampleSizeFor` usa `generateSequence(1) { it * 2 }.takeWhile { ... }.lastOrNull() ?: 1` sobre una
secuencia **infinita**. Confirma que `takeWhile` antes de `lastOrNull` no puede colgar, para
cualquier entrada incluida `width` o `height` en 0 o negativos.

Tú señalaste dos cosas que **siguen sin arreglar** y quiero que digas si bloquean publicar o no:
- La rotación EXIF no cubre orientaciones espejadas (`FLIP_HORIZONTAL`, `TRANSPOSE`, etc.).
- `prepareInPlace` **sobrescribe el original**, así que no se puede reintentar con más detalle.
- Y una tuya de antes: la prueba de rotación usa una imagen uniforme, así que 270° pasaría como 90°.

## Formato de respuesta

Breve. Por cada punto: **bien** / **mal, y por qué** / **duda**. Al final, una línea: ¿esto se puede
publicar como está, o hay algo que arreglar antes?
