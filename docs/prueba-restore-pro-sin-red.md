# Prueba manual: restauración de Pro con pérdida de red

Objetivo: verificar que, si la descarga desde la nube se interrumpe, la app **no borra ni
reemplaza** los datos locales y muestra un fallo recuperable.

> ⚠️ REGLA INNEGOCIABLE: esta prueba se hace SOLO con una cuenta de prueba y datos desechables.
> Está PROHIBIDO usar, modificar o borrar datos reales de testers. Si no hay cuenta desechable
> disponible, no se hace la prueba.

## Preparación (cuenta desechable)

1. Crear una cuenta de correo de prueba (p. ej. `cuentivo-prueba-<fecha>@gmail.com`) y verificarla.
2. En la app, con esa cuenta: suscribirse a Pro (compra de prueba) y crear un negocio con **datos
   ficticios**: 2-3 clientes inventados y 2 ventas de prueba.
3. Dejar que la sincronización suba todo (ver Firestore en consola: `businesses/{id}` con
   subcolecciones) antes de continuar.

## Preparación (datos locales)

1. Con la cuenta de prueba activa, registrar **un cliente y una venta adicionales SOLO en local**
   (modo avión antes de guardar, o directamente sin señal).
2. Anotar el saldo local que debería sobrevivir: clientes totales y ventas totales en pantalla.

## La prueba

1. Con la app cerrada, poner el teléfono en **modo avión** (o cortar Wi-Fi + datos).
2. Abrir la app. Si el arranque reutiliza la sesión, forzar una reinstalación de la copia local del
   ledger **sin tocar la sesión ni la nube** para simular el peor caso:
   ```powershell
   adb shell run-as com.cuentivo.app rm ./databases/cuentivo.db{,-wal,-shm}
   ```
   (Respaldar `databases/` antes y restaurarlo al terminar si se necesita el estado original.)
3. Reabrir la app con señal restaurada **a medias**: dejar que empiece el restore (pantalla de
   carga) y cortar la red a los pocos segundos.
4. Resultado esperado:
   - La app NO muestra "perdiste tus datos": el saldo local y las filas locales siguen ahí, o bien
     el fallo se comunica como recuperable con opción de reintentar.
   - La copia local existente **no se vacía ni se sobrescribe** con un estado parcial de la nube.
   - Reintentar con red completa termina el restore y reconcilia.

## Criterios de aceptación

- [ ] Datos locales intactos tras el corte de red a media descarga.
- [ ] Falla comunicada como recuperable (sin mensaje que parezca pérdida de datos).
- [ ] Con red completa, el restore termina y los datos de la nube aparecen.
- [ ] No se tocó ningún dato de testers reales (cuenta desechable únicamente).

## Limpieza

- Cerrar sesión de la cuenta de prueba en el teléfono y, si aplica, borrar la cuenta desechable
  (esto sí se puede: es la cuenta de prueba, no la de un tester).
- Anotar el resultado en `PROJECT_MEMORY.md` con fecha, API/dispositivo y el resultado del paso 4.
