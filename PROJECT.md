# Cuentivo / CuentivoAI

## Dirección del producto

Cuentivo evoluciona sobre la aplicación Android local-first existente. No es una app nueva ni un POS genérico. Su mercado principal son comerciantes mexicanos que venden a crédito, por fiado o por catálogo y necesitan capturar pedidos, entregar, cobrar abonos y explicar cada saldo sin depender de libretas o formularios largos.

La promesa aprobada es:

> El usuario habla o escribe como trabaja normalmente. Cuentivo entiende la operación, prepara todo y el usuario sólo confirma.

CuentivoAI es una capacidad adicional y desactivable. Gemini sólo propone intents y borradores tipados; nunca escribe dinero, Room, Firestore, inventario ni outbox. Cuentivo resuelve datos reales, calcula, muestra una preview editable y ejecuta únicamente tras confirmación explícita e idempotente.

## Fuentes de verdad

1. [Arquitectura, seguridad y estado maestro](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md)
2. [Diseño de posicionamiento y roadmap competitivo](docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md)
3. [Diseño aprobado de creación compuesta](docs/superpowers/specs/2026-08-24-cuentivoai-composite-sale-creation-design.md)
4. El código y las pruebas del repositorio para comportamiento real.

Este archivo es una puerta de entrada. No sustituye ni compite con el source of truth.

## Prioridad actual

Terminar el núcleo confiable de CuentivoAI en incrementos pequeños:

1. creación compuesta confirmable de clientes y productos nuevos dentro de una venta;
2. consultas naturales deterministas;
3. voz a texto editable;
4. aceptación física y endurecimiento de texto, cámara/OCR y voz;
5. promoción controlada desde laboratorio debug hacia una beta protegida por feature flags.

Después del núcleo: PDF de estados de cuenta y recibos, WhatsApp asistido, promesas de pago, barcode local Pro, flujo de pedidos/campañas de catálogo y extracción revisable de documentos.

## Reglas que ningún incremento puede romper

- Prioridad: estabilidad > integridad financiera > seguridad > compatibilidad > pruebas > UX > velocidad.
- Pagos y abonos nunca se bloquean por límites Gratis/Pro/AI.
- Gemini está detrás del gateway con Auth, App Check, cuotas y kill switch; ninguna key entra al APK.
- Montos en centavos enteros y cálculos deterministas.
- Cuentivo Gratis y Pro funcionan aunque IA, Gemini, gateway o Internet fallen.
- Nada financiero queda DONE sin tests, build, lint, diff check y evidencia proporcional al riesgo.
- No generar ni subir AAB hasta completar APK debug, prueba física, beta y gates documentados.

## Checkpoint de pausa — 2026-08-24

- Rama: `diegodevxd/feature-cuentivo-ai`.
- HEAD al pausar: `b2a889b` (`docs(ai): design composite sale creation`).
- El APK debug conecta con el gateway Gemini mediante Firebase Auth y App Check; la API key permanece server-side.
- Texto → propuesta tipada → preview → confirmación funciona para abonos y ventas con entidades existentes.
- Prueba física aceptada: un abono de $150 redujo la deuda real de María López de $500 a $350.
- Prueba física aceptada: una venta con abono inicial se creó correctamente desde CuentivoAI.
- El incremento de venta existente quedó en `335dbc1`; Codex verificó JVM, 19 pruebas Room conectadas, `assembleDebug`, `assembleRelease`, `lintDebug` y `git diff --check`.
- APK auditada: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256 de esa APK: `F80CD085031E1DB38C589ED147044B1FDE9C39FCBDD25335922DDD175A73E3B5`.
- Diseño siguiente aprobado y documentado en `docs/superpowers/specs/2026-08-24-cuentivoai-composite-sale-creation-design.md`.
- Decisión aprobada: si el producto es nuevo, el precio mencionado se guarda como precio habitual; costo, stock, categoría y barcode no se inventan.
- Decisión aprobada: nombres parecidos requieren aclaración para usar el existente, crear uno nuevo o cancelar.
- Próximo paso exacto: revisar la especificación escrita y preparar el plan de implementación para AGY. No se inició código de creación compuesta.
- `docs/superpowers/handoffs/` continúa no rastreado y pertenece al usuario; no agregar, borrar ni modificar.
- Estado de release: APK debug interna solamente. AAB, Play/beta pública y activación general continúan NO-GO.
