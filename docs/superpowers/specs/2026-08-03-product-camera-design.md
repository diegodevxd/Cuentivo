# Cámara para fotos de producto

## Objetivo

Permitir tomar una foto con cámara o elegir una de galería al crear o editar un producto, sin perder la foto actual ni dejar archivos temporales.

## Experiencia

Al tocar `Agregar foto`, se muestra un selector compacto con dos acciones: `Tomar foto` y `Elegir de galería`. La galería conserva su comportamiento actual. La cámara abre la aplicación de cámara del sistema.

## Seguridad y datos

- La cámara recibe un `content://` temporal emitido por `FileProvider`, limitado al directorio privado de temporales de cámara.
- Al confirmar una captura, la imagen se copia/optimiza con el `ProductImageStore` existente y el borrador adopta únicamente el nombre interno resultante.
- Si se cancela la cámara, se deniega el flujo o se cancela el formulario, se elimina solo el temporal de cámara; nunca la foto original ni un borrador ya adoptado.
- La foto anterior solo se elimina cuando el producto se guarda correctamente, igual que el flujo actual de galería.

## Contratos de verificación

- El selector expone ambas acciones con objetivos táctiles accesibles de al menos 48 dp.
- Cancelar o fallar una captura no modifica la foto vigente.
- Una captura exitosa llega al borrador como imagen privada y respeta reemplazo/cancelación existente.
- `FileProvider` solo expone el directorio temporal necesario.
