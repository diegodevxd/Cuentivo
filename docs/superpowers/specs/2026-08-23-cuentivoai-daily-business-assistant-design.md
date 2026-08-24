# CuentivoAI: asistente diario de negocio

## Objetivo

CuentivoAI se presenta como un asistente operacional, no como un chatbot
genérico. Cada cuenta conserva una conversación local por día durante 30 días
para preparar ventas, abonos, deudas y consultas del negocio con menos
formularios. La conversación nunca sustituye el dominio financiero ni ejecuta
una operación por sí misma.

## Experiencia

Al iniciar la conversación del día se muestra:

> Hola, soy CuentivoAI, tu asistente para registrar ventas más rápido.
> Cuéntame una venta, un abono, una deuda o una consulta de tu negocio;
> prepararé un borrador para que lo revises antes de registrarlo.

Cada día de calendario local del dispositivo tiene una conversación. Al abrir
un día anterior se visualiza su historial; al abrir un día sin mensajes se
muestra el saludo. El usuario puede borrar manualmente una conversación. Un
trabajo de limpieza local elimina mensajes y conversaciones con más de 30 días.
No hay sincronización de conversaciones entre dispositivos en este incremento.

Para texto claramente ajeno al negocio, la app o el servidor devuelve una
respuesta de límite de alcance:

> Soy CuentivoAI, un asistente para tu negocio. No puedo conversar sobre temas
> personales, pero sí ayudarte con ventas, abonos, clientes, productos, deudas
> y cobros.

## Arquitectura y privacidad

La persistencia es local, ligada al usuario autenticado actual y separada de
ventas, pagos, auditoría y outbox. Se almacena sólo lo necesario para renderizar
la conversación diaria: identificador local, fecha local, rol, estado del
mensaje, texto/propuesta tipada y marcas de tiempo. No se sube a Firestore,
Analytics, Crashlytics, logs ni gateway de control. Al cambiar de usuario se
aplica la misma frontera de sesión local existente; nunca se muestra un chat de
otro usuario.

La clasificación de alcance tiene dos defensas. Una regla local evita enviar
casos triviales fuera del negocio; el contrato Gemini permitido conserva una
rama tipada `OUT_OF_SCOPE` y el servidor valida que no existe una respuesta de
texto libre. Si cualquiera falla, la UI muestra el mensaje local de límite, no
una conversación personal.

Voz es un incremento posterior y acotado: micrófono dentro del chat,
`SpeechRecognizer` de Android, permiso justo a tiempo y transcripción editable.
No se guarda ni sincroniza audio. Cámara/OCR ya sigue una ruta local temporal
aislada y también desembocará en el editor de la conversación.

## Límites de seguridad

- Ningún mensaje, propuesta, OCR o transcripción escribe Room financiero,
  Firestore, saldos, ventas, pagos, inventario, auditoría u outbox sin el flujo
  futuro de resolución determinista, preview y confirmación idempotente.
- La retención de chat no es un historial financiero autoritativo. Las
  explicaciones de saldo futuras parten de movimientos reales del dominio.
- No se cambia Billing ni se habilita el plan CuentivoAI de $299 todavía; el
  precio sigue siendo una decisión comercial futura.
- Sin Gemini/gateway/red/permiso de voz, Cuentivo Free y Pro siguen funcionales.

## Descomposición propuesta

1. Modelo puro de conversación diaria, 30 días y mensajes `OUT_OF_SCOPE`, sin
   persistencia ni cambio de proveedor.
2. Persistencia Room local aislada, con migración desde 1.16.6 y pruebas de
   limpieza/aislamiento de usuario.
3. UI de historial diario y borrado local, conectada sólo al repositorio AI.
4. Voz Android a texto editable, en un plan propio con permisos y pruebas
   físicas.
5. Confirmación financiera, sync y plan comercial permanecen bloqueados y
   requieren planes/gates propios.

## Criterios de aceptación del programa

- El saludo y el límite de alcance son deterministas, localizables y no invitan
  a charla personal.
- Una conversación no aparece para otro usuario ni vive más de 30 días.
- Borrado manual y expiración no pueden borrar movimientos financieros.
- Toda migración Room tiene pruebas de upgrade desde la base estable.
- Sin conectividad o IA desactivada, el chat muestra estado seguro y Cuentivo
  normal continúa operativo.
