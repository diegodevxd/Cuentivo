# CuentivoAI: chat operativo temporal

## Propósito

CuentivoAI debe permitir que una persona describa su trabajo como habla normalmente, sin
convertirse en un chatbot general. Cada entrada de texto, foto OCR o voz produce una propuesta
de negocio estructurada; Cuentivo calcula y el usuario confirma explícitamente antes de cualquier
escritura.

Este documento sustituye el laboratorio visual de texto como dirección de UX, pero no autoriza
por sí solo una mutación financiera, un proveedor real, un secreto, un deploy ni un AAB.

## Experiencia aprobada

- El acceso **AI** vive junto a idioma en el encabezado, no como botón flotante sobre el contenido.
- Abrir AI muestra una pantalla opaca, aislada y a pantalla completa con historial temporal de la
  sesión. El historial se descarta al cerrar la pantalla; no es una conversación persistente ni se
  sincroniza.
- El compositor inferior ofrece texto, cámara, galería y voz. Cámara y galería terminan en OCR
  local primero; voz termina en una transcripción editable.
- El chat acepta exclusivamente tareas de negocio permitidas: propuesta de venta, abono, consulta
  de negocio, cliente, producto, cobranza o aclaración. Una entrada personal/fuera de alcance se
  rechaza brevemente y sin enviar una respuesta conversacional libre.
- Cada respuesta se muestra como mensaje de sistema o tarjeta de propuesta. Las propuestas
  ambiguas solicitan la aclaración concreta en el chat.
- Una propuesta financieramente resoluble muestra una tarjeta con los datos reales calculados por
  Cuentivo y el botón **Confirmar operación** dentro del chat. La tarjeta no puede mostrar ese
  botón hasta que la validación determinista y la resolución local sean exitosas.
- Tras confirmar, la operación usa los flujos/DAOs transaccionales existentes, `OperationEntity`,
  `AuditLog` y outbox. El chat muestra el resultado, nunca ejecuta código producido por Gemini.

## Invariantes

1. Gemini sólo entrega `AiProposal` tipada por contrato estricto; nunca escribe Room, Firestore,
   ventas, pagos, saldos, clientes, productos, inventario ni outbox.
2. El dominio conserva centavos enteros, cálculo de totales/saldos, autorización, auditoría e
   idempotencia. El chat no replica esas reglas.
3. La confirmación dentro del chat no evita preview, validación, resolución de entidades reales ni
   idempotency key. Doble toque, retry y resultado tardío no pueden duplicar dinero.
4. La IA se apaga/falla cerrada sin afectar Cuentivo Free o Pro. Ningún control de chat es requisito
   para registrar manualmente una venta o abono.
5. El APK no contiene key Gemini. La comunicación futura conserva Auth + App Check + gateway +
   entitlement/cuota/rate-limit/abuse-control antes de `AIProvider` Gemini 2.5 Flash-Lite.
6. Foto y voz son entrada no confiable: límites de tamaño/duración, consentimiento cuando aplique,
   texto editable y no persistir archivos temporales ni conversaciones por defecto.

## Incrementos propuestos

### I. Sustituir el laboratorio por shell de chat debug no ejecutable

Cambiar sólo UI local: entrada junto al idioma, historial temporal, burbujas de usuario/sistema y
compositor inferior. Reutilizar el modelo y transport debug actual. La tarjeta continúa siendo una
propuesta no ejecutable; no hay Room, gateway desplegado ni confirmación real.

Pruebas: estado puro de historial, guard de que no existen callbacks/DAO financieros, compose/UI
tests de apertura/cierre y gate JVM + assembleDebug/Release + lintDebug + diff-check.

### II. Captura operativa local

Mantener galería y añadir cámara, ambas hacia el mismo OCR local y editor/burbuja de texto.
Implementar voz sólo como transcripción editable con permisos solicitados al tocar micrófono.
No hay fallback multimedia remoto sin un diseño de privacidad y autorización independiente.

Pruebas: cancelación, contenido inválido, límites de captura, OCR vacío, permiso denegado,
transcripción cancelada y limpieza de temporales.

### III. Resolver y previsualizar propuestas reales

Después de cerrar gateway Gemini, control plane y contrato de propuestas: resolver nombres contra
Room, usar cálculo existente y presentar una tarjeta de preview editable. Ambigüedad o datos
incompletos permanecen como aclaración, nunca adivinanza.

### IV. Confirmación financiera dentro del chat

Incremento de alto riesgo separado. Reutilizar un caso de uso determinista existente o extraer uno
con transacción única; asociar idempotency key, `OperationEntity`, auditoría y outbox. Requiere
tests de doble toque, retry, offline/timeout, dos dispositivos y migración/compatibilidad.

## Fuera de alcance ahora

- Chat personal/general, consejos financieros inventados o memoria conversacional persistente.
- Ejecución directa por Gemini, provider SDK/API key en Android, deploy o cambio de cuota/plan.
- Imagen/audio enviados a Gemini como fallback, Storage cloud, AAB o activación pública.
- Cambiar límites Free/Pro, Billing o esquemas Room/Firestore.

## Criterios de aceptación de I

1. La pantalla es visualmente un chat operativo y no deja ver/interactuar el dashboard debajo.
2. AI se abre desde el encabezado junto a idioma; no hay botón flotante independiente.
3. El historial sólo existe mientras el panel está abierto; cerrar invalida resultados en vuelo y
   borra contenido temporal de captura.
4. Las propuestas actuales permanecen no ejecutables y el guard prueba ausencia de DAOs, Room y
   callbacks de creación/abono.
5. Cuentivo normal continúa funcionando si AI no está disponible.

