# Encargo iOS — F5-G: fotos de producto, productos y clientes en común

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-G): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5g-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** mover `ProductPhoto`, `ProductsScreen` y `CustomersScreen` a `commonMain` **sin cambiar comportamiento en Android**. No cambies textos, cálculos de dinero, `testTag` ni comentarios, salvo lo que esta hoja indica. `CustomersScreen` registra ventas rápidas y deudas anteriores: **una línea de lógica distinta es un defecto, aunque compile.**

## Por qué así (ya decidido; no lo re-decidas)

- **Mismo paquete en cada archivo.** `internal` se quita **solo** de lo que `:app` o `androidTest` usan de verdad.
- **El almacén y el selector entran por parámetro**, sin `CompositionLocal` ni `expect`. Son los servicios que luego inyectará la raíz común.
- **`ProductImageStore` y `CameraCaptureStore` se quedan en `:app`**, con sus pruebas: `ProductImageStore` ya implementa `ProductPhotoStore`, y `FileProvider` y `product_camera_paths.xml` son de `:app`.
- **El selector de Android es `object AndroidImagePickerFactory : ImagePickerFactory`, en `:app`.** Conserva `rememberSaveable` para la captura pendiente, `deleteStale` al abrir y las carpetas separadas (`product-camera` en cache, `product-images` en files).
- `IosImagePickerFactory`, `IosProductPhotoStore` e `iosMain` **no se tocan**. En esta tanda nadie los llama: los conectará la raíz común.
- **Fuera de esta tanda, no los toques:** `AccountSheet`, `LanguageSheet`, `FirstDebtPreferenceStore`, `MainActivity.kt`, `ui/ai/*`. De `CuentivoApp.kt` solo cambian las dos llamadas de abajo. Si otro consumidor deja de compilar, ajústalo y dímelo.

## Trampa que ya costó una vuelta en iOS

Dentro de una clase que implementa `ImagePickerFactory`, **el método `remember` de la interfaz tapa al `remember` de Compose**. Y como la lambda puede convertirse en `suspend (PickedImage) -> Unit`, `remember { ... }` podría compilar como una **llamada recursiva a sí mismo**. Importa `androidx.compose.runtime.remember as composeRemember`, como en `IosImagePickerFactory.kt:5`, y no uses `remember` sin alias en ese archivo.

## Commit 1: selector de Android y `ProductPhoto` en común

**`shared/.../ui/product/ProductPhotoDraft.kt`:** agrega a `ProductPhotoDraftPolicy`, con KDoc:

```kotlin
/** Al volver del selector. Null si no se pudo guardar: quien llama avisa y el borrador no cambia. */
fun applySaved(draft: PhotoDraft, saved: String?, deleteFile: (String) -> Unit): PhotoDraft? {
    if (saved == null) return null
    val update = picked(draft, saved)
    update.deleteNow.forEach(deleteFile)
    return update.draft
}
```

**`app/.../ui/product/CameraCaptureStore.kt`:** agrega al final, a nivel de archivo:

```kotlin
/** Entrega una foto tomada y borra el temporal pase lo que pase, incluso si falla o se cancela. */
internal suspend fun <T> deliverCapture(capture: T, delete: (T) -> Unit, deliver: suspend (T) -> Unit) {
    try {
        deliver(capture)
    } finally {
        delete(capture)
    }
}
```

**`app/.../ui/product/AndroidImagePickerFactory.kt` (nuevo):** `CaptureUriSaver` y su KDoc se mueven aquí tal cual. El cuerpo de `remember(onPicked)`:

- `LocalContext.current`, `rememberCoroutineScope()`, `pendingCaptureUri` con `rememberSaveable(stateSaver = CaptureUriSaver)`, y `composeRemember(context) { CameraCaptureStore(context) }`.
- `LaunchedEffect(Unit)` con `deleteStale(except = pendingCaptureUri)` en `Dispatchers.IO`, **con su comentario actual**.
- Lanzador `TakePicture`: si `pendingCaptureUri` es null, sale; lo pone en null; si `!success`, `cameraStore.delete(uri)` y sale; si no, `scope.launch { deliverCapture(uri, { cameraStore.delete(it) }) { onPicked(PickedImage(it)) } }`.
- Lanzador `PickVisualMedia`, con su comentario: `uri ?: return`, luego `scope.launch { onPicked(PickedImage(uri)) }`.
- Devuelve con `composeRemember` un `ImagePicker`:
  - `pickFromGallery()`: `launch(PickVisualMediaRequest(ImageOnly))`.
  - `takePhoto()`: el `runCatching` actual, **con su comentario**; si falla, borra la pendiente, la pone en null y devuelve `false`. Si no, `true`.

Corrige el KDoc de `PickedImage.android.kt`, que dice que nadie construye un `PickedImage` en Android.

**`ProductPhoto.kt` pasa a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/product/`, mismo paquete:**

- `Res.string.*` y `stringResource` de `org.jetbrains.compose.resources`, con `import com.cuentivo.shared.resources.*`.
- `Dispatchers.IO` con `import kotlinx.coroutines.IO`, como `CloudRestore.kt:5`.
- `store: ProductPhotoStore` en vez de `ProductImageStore`; `ImageBitmap?` y `store.loadBitmap(name)`. Sin `asImageBitmap`.
- Firma nueva: `ProductPhotoPicker(draft, store, pickers: ImagePickerFactory, targetId, onDraftChange)`.
- `errorMessage` pasa a `StringResource?`.
- El manejo común de las dos fuentes queda así, sin más ramas:

```kotlin
val picker = pickers.remember { source ->
    working = true
    try {
        // Una foto de cámara pesa varios megapixeles: decodificarla en el hilo principal congela el diálogo.
        val saved = withContext(Dispatchers.IO) { store.save(source, targetId) }
        val next = ProductPhotoDraftPolicy.applySaved(draft, saved, store::delete)
        if (next != null) onDraftChange(next) else errorMessage = Res.string.product_photo_save_failed
    } finally {
        working = false
    }
}
```

- Botón de cámara: `showSourceSelector = false`, `errorMessage = null`, y `if (!picker.takePhoto()) errorMessage = Res.string.product_photo_camera_unavailable`.
- Botón de galería: igual, con `picker.pickFromGallery()`.
- El resto, incluida la hoja, los `testTag` y `heightIn`, no cambia.

**Diferencia aceptada, que debes listar en el reporte:** `working = true` ahora se pone al arrancar la corrutina y no justo antes; y una foto de galería cuyo `save` lance deja `working` en falso, cosa que antes no hacía. `save` ya atrapa con `runCatching`.

`ProductsScreen` y `CustomersScreen` siguen en `app` en este commit: pásales `AndroidImagePickerFactory` en sus llamadas a `ProductPhotoPicker`.

## Commit 2: `ProductsScreen`

Mueve `product/ProductsScreen.kt` a `shared/.../ui/product/`, mismo paquete.

- Parámetros nuevos, **sin valor por defecto**, en `ProductsScreen`: `photoStore: ProductPhotoStore` y `imagePickers: ImagePickerFactory`. Quita `LocalContext` e `imageStore`, y pásalos hacia abajo donde hoy va `store`.
- `UUID.randomUUID().toString()` → `newUuid()`.
- `Res.string`, y `pluralStringResource` con `Res.plurals`.
- **`CuentivoApp.kt`:** una sola instancia para las dos pantallas: `val photoStore = remember(context) { ProductImageStore(context) }` con `LocalContext.current`, y `AndroidImagePickerFactory`. **Compartir la instancia comparte su caché acotada de 6 MiB:** lístalo como diferencia en el reporte.

## Commit 3: `CustomersScreen`

Mueve `customer/CustomersScreen.kt` a `shared/.../ui/customer/`, mismo paquete.

- Los mismos dos parámetros, sin valor por defecto, hasta `CustomerFormDialog`. Quita `LocalContext` e `imageStore`.
- `LocalDate.now().toEpochDay()` (línea 198) → `todayEpochDay()`. `UUID.randomUUID().toString()` (líneas 332, 464, 716) → `newUuid()`.
- **Línea 566:** `Text(creditHint.format(Money.format(creditLimitCents)))` → `Text(stringResource(Res.string.customers_form_credit_limit_hint, Money.format(creditLimitCents)))`, y borra `val creditHint` (línea 498). Es la única `.format` de texto; las demás son `Money.format`.
- Quita `import androidx.compose.ui.res.stringResource` (línea 61): queda solo el de `org.jetbrains`. `option.labelRes` y `reliability.labelRes()` ya son `StringResource`.
- `CuentivoApp.kt`: pásale la misma `photoStore` y `AndroidImagePickerFactory`.

## Lo que Native rechaza

Comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`, `java.*` y `Charsets` en común. **Revisa también lo que moviste,** no solo lo que escribes. Busca además `java.`, `android.`, `R.` y `Locale` en los archivos movidos antes de decir que están limpios.

No hay símbolos nuevos de iOS en esta tanda: **no accedas a `C:/tmp/kn-prebuilt`.** Si crees necesitar uno, no lo adivines: anótalo en el reporte.

## Pruebas

- `commonTest/.../ui/product/ProductPhotoDraftPolicyTest.kt`, agrega con `applySaved`: con `saved` null devuelve null y no borra nada; con un provisional en `current`, borra ese y solo ese; **con `current` igual al original persistido, no borra nada**; el borrador devuelto apunta a la nueva foto.
- `app/src/test/.../ui/product/CameraCaptureStoreTest.kt`, agrega con `deliverCapture` y `runBlocking`: entrega y borra; **si `deliver` lanza, borra y la excepción se propaga**; si `deliver` lanza `CancellationException`, borra y se propaga. Afirma con `assertThrows` del tipo exacto, para que no pase por otra razón.

## Control negativo obligatorio, en Windows

Rompe, corre, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. Restaura y confirma con `git diff`.

1. `applySaved` sin el `forEach(deleteFile)`.
2. `applySaved` que devuelva `draft` en vez de `null` cuando `saved` es null.
3. `deliverCapture` sin el `finally`.

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`. **Si una puerta da coincidencias nuevas, repórtalas: no la amplíes.**

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`, no de memoria.
- Conteos reales de los XML. La base es 802 + 768 = 1570: cuántas son nuevas.
- Salida de las tareas, archivos tocados y los **tres controles negativos**, con la prueba que falló.
- **Lista exacta de cada línea que no sea solo cambiar de recurso, de `internal` o de `import`.** La voy a auditar leyendo el diff.
- Qué perdió `internal`, y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que estas pantallas funcionan en iOS: no puedes saberlo desde Windows.** Tampoco que el selector funciona en Android: `ProductPhotoSourceTest` no ha corrido.
