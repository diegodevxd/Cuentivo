# v41 · bloque 4 — enderezar la foto (OpenCode)

**Base:** `09697ab` (bloques 1, 2 y 3 cerrados). **Implementa:** OpenCode, **sin delegar**.
**Revisa:** AGY. Lee `CLAUDE.md`.

**No despliegues, no commitees.** Reporte completo en `C:/tmp/oc-bloque4-reporte.md`.

## El caso real

De cinco fotos reales de libretas, **una fotografiada de lado no devolvió nada**. Otra, también de
lado, sí se leyó. Así que la rotación no lo explica todo, pero es la primera sospecha, y es barata de
comprobar.

## 1 · Primero, un hallazgo concreto (no "auditar")

Lee `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerImagePreparer.kt` y el camino desde la captura
hasta que la imagen se codifica y se envía. Responde con archivo y línea:

- ¿Se lee la orientación EXIF de la foto?
- ¿Se **aplica** a los píxeles antes de escalar y comprimir, o se pierde al decodificar a `Bitmap` y
  volver a codificar en JPEG (que no conserva el EXIF)?
- ¿Qué pasa con una foto de la **galería** frente a una de la **cámara**?

## 2 · Si se pierde, arreglarlo

Leer la orientación EXIF y **rotar los píxeles** antes de escalar y comprimir, para que la imagen que
llega al modelo esté derecha. Con `androidx.exifinterface` **si el proyecto ya lo tiene**; **no añadas
dependencias** sin decirlo antes.

**Prudente:** si no se puede leer la orientación o algo falla al rotar, **se manda la imagen como
estaba**. Nunca se descarta la foto por esto.

**Lo que NO se hace en este bloque:** enderezado por contenido (detectar la inclinación de los
renglones), recorte automático de bordes, cambios de contraste o "limpieza". Cualquier preproceso que
"limpie" puede borrar un trazo, y un renglón perdido es peor que uno torcido. Una hoja fotografiada de
lado **sin** EXIF (el píxel ya viene girado) tampoco se arregla aquí.

## 3 · El hueco conocido de `prepareInPlace`

Está anotado desde hace tiempo que `prepareInPlace` **sobrescribe el original**, lo que impide
reintentar con más detalle. Si tu cambio pasa por ahí, **no sobrescribas el original**: prepara en un
archivo aparte y sustitúyelo sólo si todo salió bien. Si no pasa por ahí, dilo y déjalo.

## Pruebas

Esto depende de `Bitmap` y del EXIF de Android, así que las pruebas reales son **instrumentadas**:

- Una imagen **sintética** con orientación EXIF **6** (90°) y otra con **8** (270°): la imagen
  preparada sale con las dimensiones y la orientación correctas (por ejemplo, un marcador de color en
  una esquina que tiene que acabar en la esquina esperada).
- Orientación **1** (normal): la imagen no se toca.
- EXIF ilegible o ausente: la imagen sale igual que antes, **sin fallar**.
- Si tocas `prepareInPlace`: un fallo a mitad **no** deja el original dañado.

Imágenes **inventadas**, generadas en la prueba. **Nunca** fotos reales de libretas.
Cada prueba tiene que fallar si se quita la rotación (mutación).

## Gate

- `testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin` con el trust store
  (referencia **1246**).
- Instrumentadas en `emulator-5554`: `LedgerImagePreparerTest` y las nuevas, **leyendo el XML**.
- `ProductPhotoSourceTest` aborta la suite completa: no la corras entera, corre las clases por nombre.
- **No lances Gradle mientras otro corre en esta carpeta.**
