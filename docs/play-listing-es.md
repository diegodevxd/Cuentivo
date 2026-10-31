# Ficha de Google Play — Cuentivo (es-MX)

Todo lo que se copia y se pega en Play Console. Los límites y las funciones de pago salen de
`domain/SubscriptionTier.kt` (`FREE_CUSTOMER_LIMIT`, `FREE_SALE_LIMIT`, `PaidFeature`); si esos
cambian, este archivo cambia con ellos o la ficha promete algo que la app no hace.

Regla 5 del proyecto: **cero marcas de terceros** en cualquier texto o captura.

---

## Nombre de la app (≤30 caracteres)

```
Cuentivo: fiado y cobranza
```

*26 caracteres.*

## Descripción corta (≤80 caracteres)

```
Lleva el fiado de tu negocio: clientes, ventas, abonos y saldos. Sin internet.
```

*77 caracteres.*

## Descripción completa (≤4000 caracteres)

```
¿Todavía llevas el fiado en un cuaderno?

Cuentivo es la libreta de fiado de tu negocio, en tu teléfono. Anotas quién te compró, cuánto
debe y qué te ha abonado, y la cuenta se saca sola. Sin fórmulas, sin hojas de cálculo y sin
sumar de memoria.

FUNCIONA SIN INTERNET
Todo se guarda en tu teléfono. Puedes anotar una venta en el puesto, en la ruta o en casa de la
clienta aunque no tengas señal. Nada se pierde por quedarte sin datos.

TUS CUENTAS, SIEMPRE CUADRADAS
• El saldo de cada cliente se calcula solo con lo que anotaste
• Los abonos se aplican primero a lo más viejo
• Si te equivocaste, corriges la venta sin borrar nada: queda el registro de la corrección
• Ninguna venta ni abono se borra jamás, así que tu libreta nunca se descuadra

SABER A QUIÉN COBRAR HOY
• Aviso de las ventas que se están venciendo
• Cobras desde el mismo aviso, sin buscar al cliente
• Resumen del mes: cuánto vendiste, cuánto cobraste y cuánto te deben
• Cupo de crédito por cliente, con aviso cuando alguien se pasa

MANDAR EL ESTADO DE CUENTA
Envía por WhatsApp el detalle de lo que un cliente compró, lo que ha abonado y lo que debe. Ya
redactado, sin que tengas que escribirlo.

LO QUE ES GRATIS
El plan gratis incluye 5 clientes activos y 30 ventas registradas, con todo lo de arriba. Los
abonos nunca se bloquean: si alguien te llega a pagar, siempre lo puedes anotar, tengas el plan
que tengas.

CUENTIVO PRO
Cuando tu negocio ya no cabe en el plan gratis:
• Clientes y ventas sin límite
• Respaldo y sincronización en la nube, para no depender de un solo teléfono
• Fotos de productos y comprobantes de pago
• Importar y exportar tus productos en Excel
• Compartir la lista completa de deudores
• Varios usuarios con permisos por rol
• Historial de auditoría de todos los movimientos

El cobro se hace por Google Play y lo puedes cancelar cuando quieras desde ahí.

PARA QUIÉN ES
Tienditas, papelerías, tortillerías, estéticas, talleres, puestos de mercado, venta por catálogo,
rutas de reparto y cualquier negocio que venda fiado y quiera dejar el cuaderno.

TUS DATOS SON TUYOS
La información de tu negocio vive en tu teléfono. Sólo sale de ahí si activas la sincronización
en la nube con Cuentivo Pro. No vendemos ni compartimos tus datos, y puedes borrar tu cuenta y
todo lo guardado desde la propia app.

Disponible en español e inglés.
```

*≈2 400 caracteres. Las palabras que Play indexa (fiado, cobranza, clientes, abonos, saldo,
deudores, crédito, libreta, cuaderno, negocio) van repartidas en el texto: Play no tiene campo de
keywords, indexa el título y las dos descripciones.*

---

## Notas de la versión 1.5 (≤500 caracteres)

```
• Respaldo y restauración de tus datos, protegidos con contraseña
• Alta rápida: cliente, producto y su primera venta en un solo paso
• Cobra desde el mismo aviso de vencimiento, sin buscar al cliente
• Resumen del mes en el panel: cuánto vendiste y cuánto cobraste
• Más protección para tu cuenta
```

⚠️ *Corregir venta, estado de cuenta y verificación de correo salieron en la **1.4** (versionCode 8).
No van aquí: repetirlas hace que la nota mienta sobre lo que el usuario acaba de recibir.*

---

## Formulario de Seguridad de los datos

Lo que hay que declarar, con el porqué de cada respuesta:

| Dato | ¿Se recopila? | Para qué | ¿Obligatorio? |
|---|---|---|---|
| Correo electrónico | Sí | Autenticación de la cuenta (Firebase Auth) | Sí |
| Clientes, ventas, abonos | Sólo con Pro | Sincronización entre dispositivos del mismo negocio | No — es opción del usuario |
| Fotos de productos y comprobantes | Sólo con Pro | Sincronización | No |
| Identificadores de dispositivo | No | — | — |
| Ubicación, contactos, salud, SMS | No | — | — |

Puntos que Play pregunta explícitamente:

- **Los datos van cifrados en tránsito**: sí (HTTPS/Firestore).
- **El usuario puede pedir que se borren sus datos**: sí — *Cuenta → Eliminar mi cuenta* borra la
  cuenta, toda la información del teléfono, y **marca la copia en la nube para su eliminación**.
  ⚠️ **La eliminación en la nube todavía se procesa a mano** (ver `docs/borrado-de-cuenta.md`), porque
  las reglas prohíben que un cliente borre documentos y el proyecto está en plan Spark, sin Cloud
  Functions. No declares aquí un borrado automático mientras siga siendo así: Play pide además una
  **URL de solicitud de borrado accesible desde la web**, que hoy **no existe** y hay que crear antes
  de salir de prueba cerrada a producción.
- **Se comparten con terceros**: no.
- **La app recopila datos de forma opcional**: sí — sin Pro, nada del negocio sale del teléfono.

Coherente con la regla 4 del proyecto: no hay PII, saldos ni tokens en logs, Analytics ni
Crashlytics.

---

## Categoría y clasificación

- **Categoría**: Negocios (alternativa: Finanzas — Negocios evita el cuestionario de app financiera
  regulada, y Cuentivo no mueve dinero: sólo lo anota).
- **Etiquetas**: contabilidad, pequeña empresa, punto de venta.
- **Clasificación de contenido**: para todos. No hay contenido generado por usuarios que se
  comparta públicamente, ni compras dentro de la app aparte de la suscripción.

---

## Capturas

Las genera `PlayStoreCaptureTest` (androidTest) a 1080×1920. Play pide mínimo 2; se suben 6, en
este orden — la primera es la que se ve en los resultados de búsqueda:

1. **Panel del día** — lo primero que ve al abrir: por cobrar, actividad, resumen del mes
2. **Clientes** — el directorio con saldos
3. **Detalle de cliente** — historial de ventas y abonos con el saldo derivado
4. **Nueva venta** — lo rápido que es anotar
5. **Avisos** — a quién cobrar hoy, con el botón de cobrar
6. **Cuentivo Pro** — qué incluye el plan de pago

Los datos de las capturas son de un negocio ficticio y genérico: nunca una persona real, nunca una
marca de terceros.

**Texto superpuesto** (opcional, si se decoran las capturas): frases cortas, en español, del mismo
tono que la ficha — "Sabe quién te debe", "Anota una venta en 10 segundos", "Funciona sin internet".
