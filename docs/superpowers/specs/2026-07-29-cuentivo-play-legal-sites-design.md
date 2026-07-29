# Sitios legales de Cuentivo para Google Play

Fecha: 29 de julio de 2026

## Objetivo

Crear dos sitios públicos, independientes y en español para completar los requisitos de Google Play de Cuentivo:

1. Una política de privacidad accesible sin iniciar sesión.
2. Una página para solicitar la eliminación de la cuenta y sus datos asociados.

Los sitios pertenecen a Capilabs, con operación en México. No incluirán términos y condiciones, blog, cuentas de usuario, cookies publicitarias ni analítica web.

## Repositorios y alcance

### `diegodevxd/Cuentivo-politicasdeprivacidad`

Sitio estático de una sola página con:

- Identificación de Cuentivo y Capilabs.
- Fecha de entrada en vigor y alcance.
- Datos tratados:
  - correo electrónico e identificador de Firebase;
  - información del negocio;
  - clientes, productos, ventas, abonos, saldos y movimientos ingresados por el usuario;
  - actividad de la aplicación e identificadores técnicos recopilados por Firebase Analytics;
  - estado de suscripción y datos administrados por Google Play Billing;
  - comunicaciones de soporte y solicitudes de eliminación.
- Finalidades: autenticación, operación de la app, sincronización Pro, administración de suscripciones, seguridad, soporte y mejora del servicio.
- Proveedores: Firebase Authentication, Cloud Firestore, Firebase Analytics, Google Play Billing, Vercel y Resend.
- Explicación de cifrado en tránsito, acceso limitado y no venta de datos.
- Conservación y eliminación.
- Derechos ARCO y canal de contacto.
- Tratamiento de datos de menores: el servicio está dirigido a personas adultas y negocios.
- Enlace visible al sitio de eliminación de cuenta.

### `diegodevxd/Eliminacion-Cuentivo`

Sitio de una sola página con:

- Identificación de Cuentivo y Capilabs.
- Instrucciones para eliminar la cuenta desde la aplicación:
  1. Iniciar sesión.
  2. Ir a Inicio y desplazarse hasta “Tu cuenta”.
  3. Pulsar “Eliminar mi cuenta”.
  4. Confirmar “Sí, eliminar todo”.
- Alternativa web para quien no pueda entrar a la aplicación.
- Explicación de qué datos se eliminan y qué información puede conservarse.
- Formulario que solicita únicamente:
  - correo registrado en Cuentivo;
  - confirmación expresa de que se desea eliminar la cuenta;
  - campo trampa invisible para reducir envíos automatizados.
- Confirmación o error accesibles en la misma página, sin recargar.
- Enlace a la política de privacidad.

La solicitud se enviará a `diegomancera.dev@gmail.com`. Capilabs deberá borrar manualmente la cuenta en Firebase Authentication y los datos asociados en Cloud Firestore mientras no exista un proceso automático de borrado remoto. El plazo comunicado será de hasta 30 días. El correo de solicitud podrá conservarse hasta 90 días como comprobante de atención y después deberá eliminarse.

## Arquitectura

Cada repositorio tendrá una estructura pequeña y portable:

- `index.html`: contenido y estructura semántica.
- `styles.css`: identidad visual y diseño adaptable.
- `favicon.png`: icono oficial de Cuentivo.
- `README.md`: publicación en Vercel y configuración requerida.

El sitio de eliminación añadirá:

- `script.js`: validación de interfaz y envío del formulario.
- `api/solicitar-eliminacion.js`: función serverless de Vercel.
- `package.json`: dependencia mínima para Resend y metadatos del proyecto.
- `.env.example`: nombre de la variable requerida, sin secretos.

La función aceptará únicamente solicitudes `POST` JSON del mismo sitio, limitará el tamaño de los campos, validará el formato del correo, rechazará el campo trampa cuando esté lleno y devolverá mensajes genéricos para no revelar si una cuenta existe. No almacenará información en una base de datos.

Resend usará la variable privada `RESEND_API_KEY`. La dirección receptora será `diegomancera.dev@gmail.com`. Si Resend exige verificar un remitente o dominio, se configurará en Vercel sin incluir credenciales en Git.

## Identidad visual

Los dos sitios reutilizarán el icono oficial de Cuentivo y la identidad de la app:

- Fondo claro con degradado suave entre `#F7FAFC`, `#E6EDFB`, `#E2F7F0` y `#FBE9F3`.
- Texto principal `#0B1B23` y secundario `#4A5D68`.
- Acento menta `#00E5A0`, con `#00795A` para texto accesible.
- Tarjetas blancas translúcidas, bordes sutiles y esquinas redondeadas.
- Movimiento decorativo discreto y desactivado cuando el usuario prefiera reducir animaciones.
- Diseño prioritario para teléfono, pero legible en escritorio.
- Contraste, foco visible, etiquetas explícitas y navegación mediante teclado.

No se crearán ilustraciones adicionales ni se cargarán recursos desde redes de publicidad.

## Flujo de eliminación

1. El usuario completa el formulario con su correo registrado y confirma la solicitud.
2. El navegador valida los campos y envía JSON a `/api/solicitar-eliminacion`.
3. La función vuelve a validar, comprueba el campo trampa y envía un correo a Capilabs mediante Resend.
4. La página confirma solamente que la solicitud fue recibida; no confirma la existencia de la cuenta.
5. Capilabs localiza la cuenta, elimina Firebase Authentication y los documentos asociados en Firestore, y conserva el correo de solicitud como máximo 90 días.

Los errores de red permitirán reintentar. Los errores internos se mostrarán sin detalles técnicos ni credenciales.

## Publicación

Cada repositorio se importará por separado en Vercel. Esto producirá dos URLs públicas. Las URLs finales se colocarán recíprocamente en ambos sitios después del primer despliegue:

- La política enlazará a eliminación de cuenta.
- La página de eliminación enlazará a la política.

La URL de política se introducirá en el campo “Política de privacidad” de Play Console. La URL de eliminación se introducirá en “URL de eliminación de cuenta”.

## Verificación

Antes de entregar:

- Abrir ambas páginas localmente y validar su presentación en ancho móvil y escritorio.
- Verificar enlaces, favicon, títulos y metadatos.
- Confirmar que no existen secretos en los repositorios.
- Probar validación de correo, confirmación obligatoria, campo trampa, respuesta exitosa simulada y fallo de red.
- Ejecutar una solicitud real únicamente después de configurar `RESEND_API_KEY` en Vercel.
- Abrir las dos URLs desplegadas en una ventana sin sesión iniciada.
- Confirmar que Google Play puede leer todo el contenido sin cookies, autenticación ni redirecciones.

## Fuera de alcance

- Términos y condiciones.
- Panel administrativo.
- Base de datos de solicitudes.
- Automatización del borrado de Firebase.
- Analítica web.
- Cookies publicitarias.
- Despliegue de una nueva versión de la aplicación Android.
