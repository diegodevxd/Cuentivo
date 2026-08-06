# Verificar la compra de Pro de punta a punta

Play Billing sólo funciona si la app está **instalada desde Play**. Con un APK cargado a mano el
diálogo de pago no abre, así que esta prueba no se puede hacer desde el emulador ni con el APK de
`dist/`: hay que instalar desde el enlace de prueba cerrada.

Objetivo: ver una compra completada **y reconocida**. Sin el reconocimiento, Play reembolsa a los
tres días automáticamente.

---

## 1. Play Console — antes de tocar el teléfono

### 1.1 El producto tiene que existir con el id exacto

**Monetización → Productos → Suscripciones.** Debe haber una suscripción con id

```
cuentivo_pro_mensual
```

Ese string está en `SubscriptionTier.PRO_PRODUCT_ID` y es lo que la app pide. Si no coincide —una
letra, un guion— `queryProductDetailsAsync` devuelve una lista vacía, `proProduct` se queda en
`null` y el botón de comprar responde *"Las suscripciones no están disponibles en este momento"*,
sin decir por qué. **Es el fallo más probable de toda esta prueba.**

Comprobar además:

- La suscripción está **activa**, no en borrador.
- Tiene un **plan base activo** con precio **$199 MXN** y México entre los países. La app compra a
  través del `offerToken` del primer plan base (`BillingGateway.launchPurchase`); sin plan base
  activo el mensaje es *"El plan no está disponible todavía"*.

### 1.2 Cuenta de prueba de licencia

**Configuración → Pruebas de licencia** (a nivel cuenta de desarrollador, no de la app): agregar el
correo de Google del teléfono. Eso hace la compra **gratuita** y con renovación acelerada, así que
se puede repetir sin cobros reales.

Es distinto de ser tester de la prueba cerrada: hacen falta **las dos cosas**.

### 1.3 Instalar desde Play

Abrir el **enlace de participación** de la prueba cerrada en el teléfono, aceptar y **instalar desde
Play Store**. Si ya tienes la 1.5 instalada por USB, desinstálala primero: una app cargada a mano no
tiene la firma de Play y Billing la rechaza.

---

## 2. La prueba, con el teléfono conectado por USB

```bash
adb logcat -c
adb logcat -v time BillingClient:V Cuentivo:V "*:S" > purchase-run.log
```

Recorrido: abrir la app → iniciar sesión → Cuenta → Suscripción → comprar Pro.

Lo que tiene que aparecer, en orden:

| # | Qué buscar | Si no sale |
|---|---|---|
| 1 | La hoja de pago abre con el precio de $199 | Punto 1.1: el producto o su plan base |
| 2 | `onPurchasesUpdated` con `responseCode: 0` | La compra no se completó; mira el código que sí salió |
| 3 | La UI deja de mostrar los límites del plan gratis | `applyPurchases` no atribuyó la compra a tu uid |
| 4 | **`acknowledgePurchase` con `responseCode: 0`** | **Play reembolsa a los 3 días.** Es el paso crítico |

Después, dos comprobaciones que valen tanto como la compra:

- **Cerrar la app por completo** (Ajustes → forzar detención) y reabrir. Debe seguir en Pro sin
  volver a pagar: eso prueba `EntitlementStore` y el `refresh` de arranque.
- **Probar el respaldo en la nube** con la cuenta ya en Pro. Es la función que el plan desbloquea, y
  hasta ahora nadie la ha visto encendida por una compra real.

---

## 3. Si algo falla

Guarda `purchase-run.log` completo. No lleva PII: el filtro sólo deja pasar las etiquetas de la app
y de Billing, y la app nunca registra nombres, saldos ni tokens.

Dos cosas que ya sabemos y no hay que confundir con un fallo:

- **La verificación es sólo en el dispositivo.** La app cree lo que Play le dice a ese teléfono.
  Endurecerlo exige un servidor que valide el `purchaseToken`, o sea el plan Blaze. Es una decisión
  tomada, no un descuido: protege ingresos, no datos de terceros.
- **Una desconexión de Play a mitad del recorrido ya no rompe nada.** Desde la 1.6 el cliente se
  reconecta solo con retroceso exponencial (`BillingReconnectPolicy`). Antes, un solo corte dejaba
  el botón de comprar inservible hasta reiniciar la app.

---

## 4. Decisión que queda pendiente para después de esta prueba

`SyncEntitlement.MAX_CONFIRMATION_AGE_MILLIS` son **24 horas**: si alguien que paga no abre la app
en un día, el `SyncWorker` deja de sincronizar hasta que la vuelva a abrir. Evita creer para siempre
un Pro caducado, pero es agresivo para un negocio que usa la app unos días por semana.

**No se cambia antes de la prueba**: mover esa ventana mientras se verifica el cobro es mover una
variable en mitad del experimento.
