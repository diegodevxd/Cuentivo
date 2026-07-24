# Fase 2a: navegación y dashboard

## Objetivo

Reemplazar la pantalla de plantilla por el inicio de Cuentivo: una experiencia Material 3 que prioriza la acción diaria y presenta el estado financiero local de un negocio.

## Alcance

- Barra de navegación inferior: Inicio, Clientes, Nueva venta, Movimientos y Más.
- Dashboard de acción primero: saludo, acceso destacado a Nueva venta, alertas de cobro, métricas de vendido/cobrado y estados vacíos útiles.
- Lectura de métricas desde Room mediante una fuente de negocio activo local, sin datos de demostración.
- Tema propio claro/oscuro, formato monetario de México y semántica accesible para los controles principales.

## Fuera de alcance

- Formularios completos de clientes, productos, ventas o abonos.
- Firebase, autenticación, sincronización, roles, notificaciones y facturación.
- Datos de prueba persistentes o identificación real de usuarios.

## Arquitectura

`MainActivity` alojará la app Compose. Un `DashboardViewModel` expondrá el estado de pantalla desde consultas Room, con una implementación local de negocio activo que devuelve estado vacío hasta que las fases de configuración creen un negocio. Los destinos que aún no tengan flujo mostrarán una pantalla de preparación explicativa; el destino Nueva venta mantendrá el mismo contrato de navegación que usará el formulario de la Fase 2c.

## Comportamiento

- Inicio muestra una acción principal "Nueva venta" y dos alertas: pagos vencidos y clientes por cobrar.
- Las métricas representan vendido y cobrado para el período seleccionado; el primer corte será "Este mes".
- Si no hay negocio activo, se explica cómo empezar y se deshabilitan las acciones que dependen de datos.
- Si hay negocio pero no movimientos, se muestran ceros y una invitación a crear la primera venta.
- Verde profundo comunica acciones y cobros; ámbar comunica vencimientos; el rojo queda reservado para errores.

## Pruebas

- Pruebas unitarias del ViewModel: negocio ausente, negocio vacío y métricas con movimientos.
- Pruebas Compose: navegación inferior, acción Nueva venta, contenido vacío y contenido de métricas.
- Prueba instrumentada Room para las consultas del dashboard, siempre filtradas por `businessId`.
