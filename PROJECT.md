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

## Checkpoint — 2026-08-24 (creación compuesta aceptada)

- Rama: `diegodevxd/feature-cuentivo-ai`. HEAD: `24014de` (`fix(ai): enforce plan limits and offer upgrade on composite sale`).
- **Creación compuesta cerrada.** Las cuatro tasks del plan `2026-08-24-cuentivoai-composite-sale-creation.md` están completas y auditadas de forma independiente sobre el código, no sobre el reporte del implementador: `20f4f1e`, `febd8c3`, `32bc03c`, `24014de`.
- Gate automático verde reejecutado: JVM 729/729 con `--rerun-tasks`, `assembleDebug`, `assembleRelease`, `lintDebug`, paridad de idiomas 443 = 443, `git diff --check` limpio.
- **Prueba física aceptada en el A059P `00170157R000160`**, leída de la base real del dispositivo y no del reporte del usuario. "Ana se llevó dos cremas de $120 y pagó $100" creó cliente `Ana`, producto `crema` con precio habitual 12000 centavos, venta de 24000, abono de 10000 y saldo derivado de 14000. Una segunda frase con otra clienta produjo el mismo resultado. Conteos finales: 2 clientes, 2 productos, 2 ventas, 2 ítems, 2 abonos, 2 asignaciones, 4 movimientos, 4 operaciones. **Cero duplicados.**
- `costPriceCents`, `stock`, `category` y `sku` quedaron en NULL: se cumple la decisión de no inventar datos del producto nuevo.
- APK auditada e instalada: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `F649EEA8A9621487BE29C60B0867EFEC8B252BB86EB4B493524700B71F052E54`.
- Pendiente menor observado: los productos nuevos se guardan con el token hablado tal cual, en minúscula (`crema`, `camisa`). El cliente sí conserva capitalización porque `name` y `normalizedName` son columnas distintas.
- Pendiente abierto: no existe fila en `sync_state` para el negocio pese a que el outbox se drenó; falta confirmar que el *pull* complete.
- Próximo incremento: **consultas naturales deterministas** (punto 4 del Tramo A en `specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md`). Requiere su propio diseño y autorización.
- `docs/superpowers/handoffs/` continúa no rastreado y pertenece al usuario; no agregar, borrar ni modificar.
- Estado de release: Cuentivo base está publicada en Play. **CuentivoAI sigue NO-GO**: laboratorio debug solamente; su AAB y activación pública continúan cerradas.
