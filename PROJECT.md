# Cuentivo / CuentivoAI

## Dirección del producto

Cuentivo evoluciona sobre la aplicación Android local-first existente. No es una app nueva ni un POS genérico. Su mercado principal son comerciantes mexicanos que venden a crédito, por fiado o por catálogo y necesitan capturar pedidos, entregar, cobrar abonos y explicar cada saldo sin depender de libretas o formularios largos.

La promesa aprobada es:

> El usuario habla o escribe como trabaja normalmente. Cuentivo entiende la operación, prepara todo y el usuario sólo confirma.

CuentivoAI es una capacidad adicional y desactivable. Gemini sólo propone intents y borradores tipados; nunca escribe dinero, Room, Firestore, inventario ni outbox. Cuentivo resuelve datos reales, calcula, muestra una preview editable y ejecuta únicamente tras confirmación explícita e idempotente.

## Fuentes de verdad

1. [Arquitectura, seguridad y estado maestro](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md)
2. [Diseño de posicionamiento y roadmap competitivo](docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md)
3. El código y las pruebas del repositorio para comportamiento real.

Este archivo es una puerta de entrada. No sustituye ni compite con el source of truth.

## Prioridad actual

Terminar el núcleo confiable de CuentivoAI en incrementos pequeños:

1. confirmación explícita y ejecución idempotente de abonos;
2. resolución, preview y ejecución idempotente de ventas completas;
3. consultas naturales deterministas;
4. voz a texto editable;
5. OCR por lotes con revisión.

Después del núcleo: PDF de estados de cuenta y recibos, WhatsApp asistido, promesas de pago, barcode local Pro, flujo de pedidos/campañas de catálogo y extracción revisable de documentos.

## Reglas que ningún incremento puede romper

- Prioridad: estabilidad > integridad financiera > seguridad > compatibilidad > pruebas > UX > velocidad.
- Pagos y abonos nunca se bloquean por límites Gratis/Pro/AI.
- Gemini está detrás del gateway con Auth, App Check, cuotas y kill switch; ninguna key entra al APK.
- Montos en centavos enteros y cálculos deterministas.
- Cuentivo Gratis y Pro funcionan aunque IA, Gemini, gateway o Internet fallen.
- Nada financiero queda DONE sin tests, build, lint, diff check y evidencia proporcional al riesgo.
- No generar ni subir AAB hasta completar APK debug, prueba física, beta y gates documentados.

## Estado resumido al 2026-08-24

Existe texto remoto Gemini → propuesta tipada y una preview de abono que resuelve cliente y saldo real sin mutar datos. La prueba física mostró $500 de deuda, $150 propuestos y $350 proyectados; el saldo real permaneció en $500 y no apareció ningún abono. Todavía no existe confirmación ni ejecución financiera desde CuentivoAI.
