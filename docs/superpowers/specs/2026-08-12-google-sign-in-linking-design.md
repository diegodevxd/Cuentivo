# Diseño: inicio con Google y vinculación segura

## Objetivo

Permitir que una persona entre a Cuentivo con su cuenta de Google, sin reemplazar el correo/contraseña existente ni crear dueños de datos duplicados. El acceso debe funcionar en Android compatible con Cuentivo y conservar el `uid` que ya protege los datos de negocio en Firebase y Firestore.

## Flujo de usuario

1. La pantalla de autenticación conserva correo/contraseña y agrega el botón visible **Continuar con Google**.
2. Una persona nueva elige una cuenta de Google mediante Credential Manager. Firebase crea o inicia su usuario y la aplicación continúa con el `uid` devuelto.
3. Una persona ya autenticada con correo/contraseña puede vincular Google desde Cuenta. Firebase une la credencial Google al mismo usuario y conserva el `uid`, negocio y datos existentes.
4. Si la persona cerró sesión y Google devuelve un correo que ya pertenece a una cuenta de correo/contraseña, Cuentivo no crea otra cuenta ni fusiona UIDs en silencio. Muestra una explicación clara: debe iniciar una vez con su contraseña para vincular Google; tras vincularlo, Google queda disponible en los siguientes inicios.
5. Cancelar el selector de Google no cambia la sesión ni muestra un error técnico. Fallos de red, configuración u OAuth se convierten en texto localizado, sin exponer mensajes crudos de Firebase.

## Seguridad y datos

- No se cambia el `uid` de un usuario existente y no se mueve ninguna colección Firestore como parte de este cambio.
- La unión de proveedores usa `linkWithCredential` únicamente cuando Firebase tiene una sesión autenticada que prueba la titularidad de la cuenta manual.
- Un conflicto de correo/proveedor no se resuelve creando un usuario ni con una fusión cliente a cliente; se dirige a la autenticación manual y vinculación posterior.
- Al cerrar sesión se limpia también el estado de Credential Manager, para que el selector no sugiera una sesión anterior como si siguiera autenticada.
- El proyecto Firebase `organitazionlabs` debe tener Google como proveedor habilitado, Email/Password permanece habilitado y conserva la opción recomendada de una cuenta por correo.

## Integración Android y Firebase

- Usar Credential Manager con `credentials`, `credentials-play-services-auth` y `googleid`; no usar la API antigua de Google Sign-In.
- Crear una solicitud `GetGoogleIdOption` con el `default_web_client_id` de `google-services.json`, pedir primero cuentas previamente autorizadas y reintentar con el selector general si no hay una.
- Convertir el token de Google a una credencial Firebase y llamar a `signInWithCredential` para inicio nuevo/existente o `linkWithCredential` cuando exista una sesión manual.
- Registrar en Firebase las huellas SHA-1 y SHA-256 de debug y de la firma release/Play, descargar el `google-services.json` actualizado y mantenerlo en `app/`.
- Habilitar el proveedor Google en Firebase Authentication antes de distribuir el AAB de prueba cerrada.

## Arquitectura

- Un gateway de autenticación encapsula Credential Manager y FirebaseAuth, devolviendo resultados de dominio: éxito, cancelado, cuenta manual por vincular o fallo localizado.
- `MainActivity` conserva la coordinación de sesión y los callbacks existentes; no implementa detalles de OAuth dentro del Composable.
- `AuthenticationScreen` recibe una acción Google y un estado de trabajo para deshabilitar doble toque; el diseño conserva controles de al menos 48 dp y escala de fuente del sistema.
- Cuenta recibe una acción de vinculación Google sólo cuando hay sesión correo/contraseña no vinculada aún.

## Pruebas y aceptación

- Pruebas unitarias cubren mapeo de resultados Firebase/Credential Manager y la política de vinculación; incluyen cancelación, conflicto de cuenta manual y errores genéricos sin mensajes crudos.
- Pruebas Compose verifican que el botón Google sea accesible, que no se pueda invocar dos veces mientras está trabajando y que el aviso de vinculación sea localizado.
- Prueba instrumentada con un gateway falso verifica que un inicio Google exitoso entrega el `uid` al flujo de sesión sin crear negocio ni modificar Firestore.
- Validación manual en un dispositivo físico con cuenta de Google: nuevo usuario, usuario manual ya autenticado que vincula, conflicto de correo manual cerrado y cancelación. No se usan cuentas reales de testers para pruebas destructivas.
- No se cambian reglas Firestore, colecciones, datos locales, datos remotos, facturación ni comportamiento de venta.
