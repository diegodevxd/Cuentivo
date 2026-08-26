# N5 — Salida del laboratorio a beta de pago

**Fecha:** 2026-08-26 · **Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `c7e20c7`

Último punto del núcleo de CuentivoAI. Cierra el 5 de 5 y produce el **segundo AAB**, que va a
**prueba cerrada de Play** antes que a producción.

## Decisión de producto que gobierna este plan

Del 2026-08-26: **CuentivoAI se publica ya como plan de pago.** El usuario en Gratis o en Pro que
toque CuentivoAI **ve el muro de suscripción**, no una función escondida. La entrada es visible para
todos; lo que cambia según el plan es a dónde lleva.

Consecuencia directa: la UI del plan y el entitlement de Play dejan de ser trabajo posterior y son
requisitos de este mismo release.

## Lo que ya existe (verificado en el código, no supuesto)

- **Seam de transporte limpio.** `debugAiTransport()` vive en dos source sets: debug devuelve
  `AiDebugFirebaseTransport`, release devuelve `null`, y `CuentivoApp.kt:363` esconde la entrada
  entera cuando es `null`. No hay Firebase en `main/`.
- **Control plane server-side cerrado.** `functions/src/ai/controlPlane.ts` ya distingue config
  apagada, entitlement inválido o expirado, cuota mensual y rate limit, y ya emite
  `AI_ENTITLEMENT_REQUIRED` como razón propia. El servidor **ya sabe** por qué rechaza.
- **Billing real funcionando.** `BillingGateway`, `EntitlementStore`, `EntitlementReconciler`,
  `SubscriptionStatus` y `SubscriptionSheet` operan hoy con la suscripción Pro
  (`cuentivo_pro_mensual`), con acuse a Play y caducidad de 24 h del entitlement recordado.
- **`AiExperiment.isEnabled(localOverride)`** es un gate puramente local y **no lo usa nadie**. El
  gate real hoy es el source set.

## Dos hallazgos que hay que decidir antes de escribir código

### 1. `allowsCloudSync` se rompe en silencio al agregar el tercer plan

`SyncEntitlement.allowsCloudSync(tier)` es hoy `tier == SubscriptionTier.PRO`. Si se agrega un tier
`AI` y no se toca esa línea, **el que pague $299 pierde el respaldo en la nube** que sí tiene el de
$199. Compila, pasa las pruebas actuales y falla en manos del cliente que más paga.

La regla correcta es preguntar por un rasgo declarado del plan, `tier.includesProBenefits`, y va con
prueba propia que la fije.

### 2. La voz no existe en release

`RECORD_AUDIO` se declara **sólo** en `app/src/debug/AndroidManifest.xml`. Eso fue deliberado en N3.
Si CuentivoAI sale a producción con dictado por voz, el permiso tiene que moverse al manifiesto
principal, y eso obliga a **declarar el micrófono en la ficha de Play y en el aviso de privacidad**.

**Decidido el 2026-08-26: sin voz.** CuentivoAI 1.0 sale como chat escrito. `RECORD_AUDIO` se queda
fuera del build publicado, no se declara micrófono en Play ni se toca el aviso de privacidad, y la
voz entra en una versión posterior con su propio plan. La regla de N3 sigue en pie tal cual.

## Decisión de planes

**CuentivoAI incluye todo lo de Pro.** Decidido el 2026-08-26: no es un complemento que se sume a
$199, es un plan superior que trae la IA **más** respaldo en la nube, fotos, Excel, equipo y
auditoría. El usuario elige entre tres: Gratis, Pro y CuentivoAI.

**Precio inicial para prueba cerrada: $299 MXN/mes.** Sigue **sujeto a revisión** antes de
producción general, según los costos y la conversión que se observen. La fuente de verdad anterior
lo llamaba experimental y no se declara validado aquí: el contador de `aiUsage` de agosto de 2026
está contaminado por una escritura manual, así que ese mes se **excluye del análisis** — la
colección está particionada por uid y `YYYYMM`, de modo que basta descartar ese uid y arrancar
ventana limpia. Eso **no bloquea** el AAB ni la prueba cerrada; lo único prohibido es afirmar que
$299 quedó validado con esos datos.

Eso hace que el hallazgo 1 no sea un detalle sino la regla central del tier: **cualquier plan
pagado da los beneficios de Pro**, y por eso `allowsCloudSync` pregunta por `includesProBenefits`.

## Tareas

Cada una cierra con evidencia reejecutada. Ninguna se adelanta dentro del commit de otra.

### T0 — Producto de suscripción en Play *(manual, Diego)*

Crear `cuentivo_ai_mensual`, plan base mensual, $299 MXN. Sin esto no hay nada que probar: el resto
puede escribirse, pero no verificarse de punta a punta.

### T1 — Tercer plan en el dominio *(Kotlin puro, TDD)*

`SubscriptionTier.AI` + `AI_PRODUCT_ID`. `SubscriptionStatus.tierFrom` con precedencia
**AI > PRO > FREE**, para que una cuenta con ambas compras resuelva al mayor. `allowsCloudSync`
deja de comparar `== PRO` y pregunta por `includesProBenefits`, con la prueba del hallazgo 1.
`FeatureAccessManager` igual.

**Endurecimiento pedido en la revisión de Sol.** El enum declara los dos hechos en vez de
inferirlos:

```kotlin
enum class SubscriptionTier(val rank: Int, val includesProBenefits: Boolean) {
    FREE(0, false), PRO(10, true), AI(20, true),
}
```

y `tierFrom` resuelve con `maxByOrNull { it.rank }`. Así reordenar el enum por legibilidad no
cambia entitlements en silencio, y un futuro `TRIAL`, `INTERNAL` o `LEGACY` **no** hereda los
beneficios de Pro por el mero hecho de no ser `FREE`.

### T2 — Billing consulta los dos productos, y sabe cambiar de plan

`BillingGateway` consulta ambos ids, acusa recibo de ambos y `EntitlementStore` guarda el tier
resultante. La caducidad de 24 h y la comparación por uid no se tocan.

**Dos cosas que T1 no cubre y que se descubrieron en la revisión de Sol (2026-08-26):**

1. **Cambio de plan, no segunda compra.** `tierFrom` resuelve bien el *acceso* cuando Play reporta
   ambas compras, pero eso no impide **dos suscripciones activas cobrando a la vez**.
   `BillingGateway.kt:196` `launchPurchase` abre hoy una compra normal. T2 tiene que:
   - lanzar Pro → AI como **reemplazo**, usando el `purchaseToken` de la suscripción anterior;
   - fijar una política de prorrateo explícita y escrita, no la que Play traiga por defecto;
   - cubrir la baja AI → Pro si se ofrece;
   - **impedir** que alguien acabe pagando $199 + $299;
   - probar el estado transitorio en que Play todavía reporta ambas.

   La frase "nadie paga dos a la vez" es hoy una afirmación de este documento; T2 es donde pasa a
   estar garantizada por código.

2. **T2 prepara los datos que T3 necesita, o T3 queda desconectada.** `BillingGateway.kt:209` no
   configura identidad ofuscada. Antes de T3 hacen falta: un `obfuscatedAccountId` estable y **no
   reversible** a partir del uid, captura segura del `purchaseToken`, y el envío de ese token a la
   callable de verificación. **El token nunca se registra en logs, Analytics ni Crashlytics**, y el
   tier calculado en Android **nunca** concede la IA por sí solo — sólo la respuesta del servidor.

### T3 — Entitlement server-side desde Play *(el trabajo más largo)*

Sustituir el entitlement creado a mano por uno derivado de la compra: función que valide el token
contra la Play Developer API y escriba **`aiEntitlements/{uid}`** — plural, es la colección que ya
usa el backend; crear la singular daría dos fuentes de verdad — con su `expiresAtEpochMillis`.
Cierra el criterio 4 de Fase 2 y el bloqueo B3. Requiere cuenta de servicio con acceso a la API de
Play.

### T4 — Decir por qué se rechazó *(B6)*

El servidor ya distingue las razones; el cliente las colapsa. Propagar el motivo con vocabulario
fijo y sin PII. **Va antes de la prueba cerrada**: con gente pagando, "aún no disponible" al agotar
cuota es un reembolso, no un bug.

### T5 — Flag remoto y apagador en el cliente

Apagar CuentivoAI de forma remota tiene que quitarlo de la app **sin publicar nada**.

**Lo que esto no puede significar:** que el APK lea `aiControl/global`. Esa ruta está cerrada al
cliente a propósito y no se relaja. Las dos formas aceptables son:

- una **callable autenticada** que devuelva sólo una capacidad pública sanitizada
  (`aiAvailable: true/false`), sin exponer cuotas, costos ni configuración interna; o
- **Remote Config** para la visibilidad de la entrada.

En ambos casos el apagador **autoritativo** se queda en Functions: el cliente sólo decide si pinta
la entrada, el servidor decide si atiende. Uno no sustituye al otro.

### T6 — Transporte de release

`AiDebugTransportRelease` deja de devolver `null` y entrega el gateway real. Renombrar el seam:
deja de ser "debug". Aquí aplica la decisión de la voz.

### T7 — UI del plan y muro

Pantalla de CuentivoAI: qué es, qué incluye, precio, botón de suscripción. Muro cuando el tier no
es AI, siguiendo el patrón de `SubscriptionSheet`. Textos en `values/` y `values-en/` con **paridad
de claves**; nada de literales en Composables.

**Regresión de UI obligatoria** (pedida por Sol): en Gratis y en Pro, tocar "Registrar abono" entra
al abono y **nunca** al muro. El muro sólo aparece en la entrada de CuentivoAI. Es la invariante de
abonos hecha prueba, no comentario.

### T8 — Observabilidad y simulacro de rollback

Tableros de costo, latencia y error, y un rollback ensayado de verdad, no descrito.

### T9 — Gate final y AAB

JVM completo con `--rerun-tasks`, `assembleDebug`, `assembleRelease`, `lintDebug`, paridad de
claves contada, puerta del idioma limpia, `git diff --check`, Functions con build/lint, y **prueba
física en el A059P**. Después: AAB firmado con la llave existente → **prueba cerrada**.

## Invariantes que este incremento no rompe

- Dinero en `Long` centavos; nada financiero se borra ni se edita.
- **Registrar un pago o abono nunca se bloquea** por plan ni por cuota de IA. El muro de CuentivoAI
  no puede tocar ese camino.
- Gemini sólo propone; Cuentivo resuelve, calcula, previsualiza y ejecuta.
- Cuentivo Gratis y Pro siguen funcionando con IA, gateway o Internet caídos.
- Nada de PII, saldos ni tokens en logs, Analytics o Crashlytics.

## Orden de ejecución

T0 y T1 en paralelo (T0 es de Diego, T1 es código puro). Luego T2 → T3 → T4 → T5 → T6 → T7 → T8 →
T9. T7 puede adelantarse a T3 si conviene ver la pantalla antes, pero no puede **verificarse** sin
producto en Play.
