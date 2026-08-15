# Ciclo de confiabilidad y cobro — diseño

## Objetivo

Mejorar la confianza operativa de Cuentivo sin alterar el libro mayor, las reglas de Firestore, la sincronización ni el contacto directo con los clientes.

## Alcance

1. Registrar fallos no fatales y crashes de producción con Firebase Crashlytics, sin nombres, teléfonos, saldos, correos, IDs de negocio, tokens ni contenido financiero.
2. Añadir recordatorios **locales** para el vendedor: una notificación diaria a las 09:00 si existen ventas vencidas con saldo pendiente.
3. Mantener el compartir manual existente (WhatsApp/hoja de estado) como única vía de contacto a clientes. Nunca se enviarán SMS, WhatsApp, correo, push remoto ni mensajes automáticos a terceros.
4. Añadir a Cuenta un ajuste para activar/desactivar los recordatorios y elegir una hora local.
5. Añadir un checklist reproducible de publicación y una matriz de prueba para la restauración cloud de Pro con pérdida de red.

## Diseño de recordatorios

`FinancialDao` ya produce las ventas vencidas y sus saldos. Una política de dominio pura recibe esas filas y el día local para devolver un resumen: cero deudas no genera notificación; una deuda nombra al cliente y el monto; dos o más muestran un conteo y el total pendiente. Sólo cuenta una venta con fecha de vencimiento anterior al día actual, saldo positivo y no cancelada.

Un `WorkManager` periódico de ejecución diaria consulta el resumen local y publica una única notificación agrupada. La programación calcula el siguiente instante de la hora elegida; puede ejecutarse después de esa hora por las garantías de Android, pero nunca antes. El worker no requiere red ni acceso a Firebase.

Tocar la notificación inicia `MainActivity` con un extra explícito. El host consume ese extra una sola vez y navega a Clientes con el filtro existente de deuda; no abre, edita ni comparte datos por sí mismo.

En Android 13+ se solicita `POST_NOTIFICATIONS` sólo tras activar el ajuste. Si se deniega, el ajuste explica que Android bloqueó los avisos y no vuelve a pedir el permiso en bucle. En Android 12 o inferior la programación funciona sin ese permiso.

Las preferencias se guardan en `SharedPreferences`, separadas de Room y del negocio: `enabled` inicia en `false` y `hourOfDay` inicia en `9`. Al cerrar sesión o borrar cuenta se cancela el trabajo, para que nunca se recuerden deudas del propietario anterior en ese teléfono.

## Crashlytics

Se aplica el plugin oficial y se añade el SDK. La aplicación registra únicamente claves técnicas de baja cardinalidad (`screen`, `operation`, `result`, versión de aplicación); no se añaden logs ni claves con PII o información financiera. Crashlytics queda habilitado por defecto en release y deshabilitado para debug/emulador. La política de privacidad debe nombrar el proveedor y la finalidad técnica, sin cambiar los datos declarados en Play hasta verificar el comportamiento final del SDK.

## No objetivos

- No hay notificaciones push, servidor, Cloud Functions, Blaze ni FCM.
- No se cambia Firebase Auth, Firestore, reglas, modelo Room ni datos remotos.
- No se implementan recordatorios por cliente, frecuencia individual, PDF ni automatización de WhatsApp.
- No se activa App Check enforcement durante este ciclo.

## Pruebas y aceptación

- Pruebas JVM fijan la política de vencimiento, formato y casos de saldo cero/cancelado/futuro.
- Pruebas instrumentadas verifican permiso, preferencias, programación/cancelación y que el deep link abra Clientes con deuda.
- Se prueba en teléfono físico: activar, crear una deuda vencida de prueba, recibir/tocar el aviso, pagar/cancelar y confirmar que no vuelve a aparecer; desactivar y confirmar cancelación.
- La restauración Pro se prueba en teléfono físico cortando la red durante la descarga, confirmando que no borra ni reemplaza datos locales cuando el servidor no puede responder.
- Se ejecutan las puertas release, lint, unitarias, instrumentadas relevantes y se sigue el checklist antes de generar el AAB.
