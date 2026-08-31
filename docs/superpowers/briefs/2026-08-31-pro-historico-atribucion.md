# Brief para AGY — Atribuir el Pro histórico sin quitárselo a nadie

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `4452faa` · **Origen:** deuda levantada por
Codex el 2026-08-30, registrada en `PROJECT_MEMORY.md`

## La deuda

Cuando arreglamos la titularidad de la compra, tuvimos que tolerar un caso: **una compra que Play
reporta sin identificador de cuenta concede PRO a cualquier cuenta de Cuentivo del dispositivo.**

Esa tolerancia existe por una razón real y sigue siendo válida: **los clientes que compraron Pro
antes de T2 no tienen identificador**, y exigirlo les quitaría el plan que están pagando. No hay
forma de atribuir retroactivamente una compra histórica.

Pero como señaló Codex: **"vacío no demuestra que la compra sea antigua"**. Cualquier compra
iniciada desde un binario viejo, o una futura omisión al fijar el identificador, entra por la misma
puerta. Y a diferencia de CuentivoAI, **los beneficios de Pro se aplican en el cliente**
(`SyncEntitlement.allowsCloudSync`), sin el freno autoritativo del servidor.

No es fuga de datos entre cuentas —Firestore sigue aislando por identidad— pero **sí permite
beneficios de Pro a quien no pagó**. Bloquea ampliar la venta de Pro. No bloquea vender CuentivoAI.

## La decisión, ya tomada — no la cambies

Había dos caminos. **Se usa el primero.**

- **Revincular**, que es lo que haremos: **el primer uid que presenta una compra sin identificador la
  reclama**, y a partir de ahí es suya. Nadie pierde nada y el agujero se cierra solo, cuenta por
  cuenta, a medida que la gente abre la app.
- **Caducar la tolerancia** en una fecha: trivial de implementar y **descartado**, porque ese día
  cualquier cliente que no haya vuelto a comprar pierde Pro. Eso es peor que la deuda.

## El diseño

Reusa el patrón que ya existe y funciona: **el ledger token→uid de T3, primer escritor gana.**

### 1. Servidor

El reclamo se decide **en el servidor**, no en el teléfono, porque un reclamo local se pierde al
reinstalar y se lo llevaría el siguiente que inicie sesión.

Callable nueva, mismo `AI_BOUNDARY` —`us-south1`, Auth y App Check obligatorios— con el secreto de
la cuenta de servicio de Play ligado. Recibe el `purchaseToken` de una compra de **Pro** y:

1. Consulta Play y confirma que la compra **existe y está vigente**. Una compra inventada no reclama
   nada.
2. Ata el token al uid en el **ledger que ya existe** (`tokenBindingPath`, `hashPurchaseToken`),
   **primer escritor gana**. Un token ya atado a otro uid **se rechaza y se registra; nunca se
   reasigna** — es la misma invariante de T3 y no se relaja.
3. Devuelve únicamente si **este uid es el dueño**. Nada más: ni fechas, ni estados internos, ni
   datos de otra cuenta.

**No escribe `aiEntitlements/{uid}`.** Pro no tiene entitlement server-side y esto no se lo inventa:
sólo resuelve de quién es la compra.

### 2. Cliente

`MainActivity.kt:388` hoy filtra el token **sólo para AI**. Pasa a enviar también el de Pro cuando la
compra no trae identificador — que es exactamente el caso ambiguo.

`SubscriptionStatus.tierFrom` gana la respuesta del reclamo como **una entrada más**, igual que hizo
`AiEntryPolicy` con la disponibilidad. Sigue siendo una decisión pura: no consulta nada.

### 3. La regla, con los tres estados

| Situación | Concede Pro | Por qué |
|---|---|---|
| Identificador presente y **coincide** | **Sí** | Ya funciona así |
| Identificador presente y **no coincide** | **No** | Ya funciona así |
| Sin identificador, **reclamado por este uid** | **Sí** | Es suya |
| Sin identificador, **reclamado por otro uid** | **No** | Aquí se cierra el agujero |
| Sin identificador, **reclamo desconocido** | **Sí** | Ver abajo |

**El último caso es deliberado y es la parte delicada.** Si no hay red, si la callable falla, o si el
reclamo todavía no se ha resuelto, **se concede igual que hoy**. Cuentivo es offline-first y **jamás
puede quitarle Pro a alguien porque su teléfono no tenga señal**. El agujero se cierra cuando hay
conexión — que es justo cuando el beneficio de Pro, el respaldo en la nube, importa.

Es el mismo razonamiento del fallo abierto de T5: **falla hacia conservar, nunca hacia quitar.**
Escríbelo como comentario en el código, no sólo en el reporte.

## Invariantes que no se tocan

- **Nadie que esté pagando pierde su plan.** Si tu cambio puede quitarle Pro a un cliente real en
  algún camino, está mal.
- **Un `purchaseToken` pertenece a un solo uid** y no se reasigna.
- **Registrar un pago o abono nunca se bloquea.**
- **Nada de PII ni tokens en logs.** Ni el `purchaseToken`, ni truncado.
- `firestore.rules` no cambia.
- El comportamiento de **CuentivoAI no se toca**: su entitlement lo sigue decidiendo el servidor.

## Pruebas — y la primera es la que protege a los clientes actuales

- Compra sin identificador con **reclamo desconocido** → **PRO**. Es la regresión que no puede
  ocurrir; si esta prueba no existe, el arreglo puede romper clientes reales sin que nadie lo note.
- Compra sin identificador **reclamada por este uid** → PRO.
- Compra sin identificador **reclamada por otro uid** → FREE.
- Fallo de red al consultar el reclamo → **PRO**, y el estado anterior se conserva.
- En el servidor: primer reclamo gana; segundo uid sobre el mismo token **se rechaza**; reclamar dos
  veces con el mismo uid es idempotente; y una compra que Play no reconoce **no reclama nada**.

Recuerda la regla de `CLAUDE.md`: la prueba **invoca el código que producción ejecuta**. Armar el
objeto a mano prueba la regla, no el cableado.

## Lo que NO entra

El enforcement de App Check. El simulacro. La alerta de revocación sin procesar. Y **el despliegue,
que autoriza Diego**.

## Evidencia obligatoria

`/gate` para Android, y en `functions/` el comando nuevo:

```
npm --prefix functions run release:check
```

Encadena `npm ci`, lint, pruebas, gate del emulador y compilación limpia, y **falla si falta el
JDK** en vez de saltarse el gate. Está documentado en `CLAUDE.md`.

**Líneas base: 940 pruebas JVM en 104 clases; functions 278; gate del emulador 7.** Deben subir.
`git diff --check` limpio y árbol final con **sólo** `?? docs/superpowers/handoffs/`.

**Esto sí toca `app/`, así que pide AAB nuevo.** No lo construyas todavía: primero el código
verificado, y el `versionCode 35` cuando Diego lo autorice.

Comitea al cerrar incluyendo este brief. **No despliegues.**

---

### Adenda: Reducción de alcance por dato verificado de producción (2026-08-30)

Diego confirmó que el conteo histórico de suscriptores de Pro es **cero** (0 suscriptores reales).
En consecuencia:
1. No existen compras históricas de Pro que requieran tolerancia a identificadores vacíos.
2. Toda la maquinaria de callable/ledger/fail-open para Pro se descarta.
3. `SubscriptionStatus.grantedTier` aplica comprobación estricta y simétrica de `obfuscatedAccountId` tanto para Pro como para AI: un identificador ausente o no coincidente evalúa a `FREE`.
4. Condición de seguridad documentada: la app de producción publicada es versionCode 32 (sin sello T2). Mientras el conteo siga en cero, no hay riesgo para clientes reales.

