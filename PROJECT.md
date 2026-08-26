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

## Checkpoint — 2026-08-25 (núcleo 3 de 5)

- Rama: `diegodevxd/feature-cuentivo-ai`. HEAD: `cc0df03`.
- **Núcleo en 4 de 5.** Cerrados con prueba física: creación compuesta, consultas naturales, voz a texto editable y endurecimiento. Falta sólo la salida del laboratorio a beta con feature flags.
- **Endurecimiento (N4).** Ningún estado del laboratorio termina en pared. Encontró lo que la suite no veía: buscar un nombre inexistente devolvía **todos** los clientes, se mostraban nombres de enum crudos al usuario, y **dos bugs de dinero** —montos leídos como centavos, y ventas a crédito registradas como pagadas— ambos corregidos en el servidor, desplegados y verificados en la base real del A059P.
- **Fase 2, mitad técnica cerrada.** `authorizeAiAction` desplegada en `us-south1`; cuota, rate limit, idempotencia y kill switch probados contra Firebase real con cero escrituras en cada denegación. Falta la mitad comercial: que Play conceda y revoque el tier AI. Diseñada y sin implementar en `specs/2026-08-25-cuentivoai-play-entitlement-design.md`, bloqueada porque no existe el producto de suscripción y el precio no debe fijarse antes de medir la beta.
- **Venta de contado.** Una frase como "Demian me pagó 92 de un boleto" se clasificaba como abono y moría en un callejón. El camino de abonos no crea clientes a propósito y exige deuda previa: la frase era una venta pagada de contado. Regla discriminante agregada al servidor y desplegada; la base del A059P confirma saldo 0.
- **Voz.** `RECORD_AUDIO` se declara sólo en el manifiesto de `src/debug`, verificado con `aapt2` sobre las APK empaquetadas: la app publicada **no** gana permiso de micrófono. La prueba física encontró que el motor en el dispositivo existía sin paquete de es-MX y fallaba antes de escuchar; corregido con reintento único al reconocedor del sistema.
- Gate más reciente reejecutado de forma independiente: JVM **802/802** en 93 clases con `--rerun-tasks`, Functions **226/226**, `assembleDebug`, `assembleRelease`, `lintDebug`, paridad 471 = 471, puerta del idioma limpia y `git diff --check`.
- Configuración vigente del control plane: `monthlyActionLimit = 500`, `perMinuteLimit = 20`. Los registros del gate de Fase 2 citan `50 / 3`, que fue el valor restaurado ese día.
- Pendiente conocido: los seis caminos de rechazo de `interpretAiRequest` colapsan en un solo `AI_UNAVAILABLE`, así que la app no puede decir si se acabó la cuota, si hay que esperar o si la IA está apagada.
- El contador `aiUsage` de agosto 2026 se sobrescribió a mano durante un diagnóstico: no sirve para medir consumo ni para validar el precio.
- `docs/superpowers/handoffs/` continúa no rastreado y pertenece al usuario; no agregar, borrar ni modificar.
- Estado de release: Cuentivo base publicada en Play. **CuentivoAI sigue NO-GO**: laboratorio debug solamente.
