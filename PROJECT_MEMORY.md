# Estado vivo — Cuentivo

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
sustituyendo, no añadiendo. Lo que deja de estar vigente baja a `docs/historia-del-proyecto.md`.
La arquitectura y las reglas del proyecto viven en `CLAUDE.md`.

**Última actualización:** 2026-08-06

---

## Dónde estamos

- Rama `codex/neo-fintech`, comiteada y subida a `origin`.
- **1.5 / versionCode 9 publicada en prueba cerrada** con 12 testers. Lo que se rompa, lo ven ellos.
- **1.6 / versionCode 10 construida y firmada** en `dist/Cuentivo-v10-1.6.aab`, **sin subir**. Lleva
  la reconexión de Play Billing (`domain/BillingReconnectPolicy.kt`): antes, una sola desconexión
  del servicio dejaba el botón de comprar inservible hasta reiniciar la app.
- **Fase 1 cerrada y entregada.** La 1.5 llevó a los testers las tandas 4–7: alta rápida de venta,
  respaldo cifrado, cobros del día y resumen del mes, App Check y ficha de Play.
- Suites en verde en la última corrida: **253 pruebas JVM**, 0 fallos, con `assembleDebug` y
  `bundleRelease` exitosos. Instrumentadas: 216, 0 fallos (corrida del 2026-08-06, antes del cambio
  de Billing).
- Room sigue en `version = 4`. Firebase: proyecto `organitazionlabs` (600636825165), plan Spark.
- Paridad de textos es/en: **297 claves en cada idioma**.

## Qué sigue, en orden de valor

1. **Revisar el panel de App Check** (Firebase → App Check → Métricas), ~2–3 días después del
   2026-08-06, cuando los testers hayan actualizado. Si casi todas las peticiones salen
   *verificadas*, **activar el enforcement**. Si salen *no verificadas*, ver el primer blocker.
2. **Fase 1.5 — "Cobrar de verdad" (en curso)**: verificar una compra de Pro de punta a punta.
   El código ya está listo; falta el trabajo de consola y teléfono, con el checklist paso a paso en
   **`docs/verificar-compra-pro.md`**. Lo primero a revisar es que exista en Play Console el
   producto con id exacto `cuentivo_pro_mensual`, activo y con plan base: si no, el botón de
   comprar dice "no disponibles" sin explicar por qué. Después hay que subir la 1.6 al track e
   instalar **desde Play** — con APK cargado a mano el diálogo de pago no abre.
3. **Cerrar el hueco de la tanda 6**: productos más vendidos y ranking de deudores. Requiere una
   consulta nueva sobre `sale_items` unido a `sales` no canceladas por periodo.

## Blockers abiertos

- **La SHA-256 registrada en Firebase puede ser la de la clave de *carga* y no la de *firma de la
  app*.** Es el primer sospechoso si Play Integrity falla en producción. Se arregla registrando
  también la otra; tener las dos no rompe nada.
- **El enforcement de App Check está APAGADO a propósito.** La versionCode 8 en producción no
  lleva App Check: activarlo antes de que los testers actualicen les daría `PERMISSION_DENIED`.
- **Sin confirmar** que el vínculo Firebase ↔ Play Console aparezca como *Vinculado*. El MCP de
  Firebase **no expone App Check ni ese vínculo**, así que sólo se comprueba en la consola web.
- **Play Integrity no se puede verificar en local**: sólo vale un binario firmado por Play e
  instalado desde Play.

## Pendientes de fondo

- **Textos en español fuera de `ui/`**: snackbars de `MainActivity` y literales de
  `domain/AppAlerts.kt`, `BusinessInsights.kt`, `CustomerStatementCsv.kt`, `DebtorShareText.kt`,
  `SaleDraft.kt`, `SubscriptionTier.kt`, `auth/AuthenticationGateway.kt`, `billing/BillingGateway.kt`.
  `CustomerStatementCsv` y `DebtorShareText` producen contenido que **sale** de la app (CSV,
  WhatsApp): deben usar el idioma activo.
- **Verificar el idioma en API 26–30.** Sólo hay imagen API 37 instalada y el riesgo del selector
  de idioma vive por debajo de API 33.
- **Promociones (Tarea D, nunca empezada).** Es lo más riesgoso que queda: exige migración de Room
  **4 → 5** (`promoPriceCents`, `promoEndsAtEpochMillis` nullable en `products`) y tocar
  `firestore.rules`, cuyo `hasExactKeys()` rechaza llaves de más — hay que hacerlas opcionales o
  desplegar las reglas *después* de que los testers actualicen.
- **Activar *Email enumeration protection*** en consola → Authentication → Settings → User actions.
- **Incoherencia aritmética en las capturas de `StitchCaptureTest`** ("vendiste 1 500 − abonó 250 =
  debe 1 500"). Se dejó a propósito por ser una referencia interna; `PlayStoreCaptureTest` sí usa
  cifras que cuadran.
- **Las 16 casillas de `docs/superpowers/plans/2026-07-24-phase-3-sync.md`** siguen sin marcar.

## Decisiones que no se reabren

- **La nube es exclusiva de Pro.** Gratis: 5 clientes / 30 ventas. Pro: $199 MXN/mes.
- **El proveedor de App Check se elige por *source set*, no con `if (BuildConfig.DEBUG)`.** Una
  rama en un solo archivo arrastraría al APK de release un proveedor que entrega tokens a quien los
  pida, anulando la protección entera.
- **`Money.format` es es-MX fijo**, en ambos idiomas. Un negocio mexicano cobra en pesos, y
  enhebrar el locale por sus call sites daría dos formatos en la misma pantalla.
- **El aviso de cupo de crédito no bloquea la venta.** El dueño está frente al cliente y necesita
  poder decidir.
- **`CreditLimitAdvisor` devuelve `null` si el cliente no tiene cupo.** Proponerle un número a
  quien tiene crédito ilimitado sería imponerle un techo, no premiarlo.
- **No se usa `AppCompatActivity`**: `Theme.Cuentivo` extiende un tema del framework
  (`android:Theme.Material.Light.NoActionBar`) y reventaría al arrancar.

## Reglas de continuidad

- **Compilar no es verificar.** No se declara nada terminado sin ejecutar de verdad y reportar la
  salida real del comando. El error más caro del proyecto fue exactamente ése: un total que decía
  $7,000 donde debía decir $5,000 y un selector de idioma que no aplicaba nada por debajo de API
  33 — ambos compilaban perfecto.
- Al cerrar trabajo relevante: **sustituir** en este archivo lo que cambió y bajar lo desplazado a
  `docs/historia-del-proyecto.md`. Si este archivo pasa de ~100 líneas, hay que podarlo.
