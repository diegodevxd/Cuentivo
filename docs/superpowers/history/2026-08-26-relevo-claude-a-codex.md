# Relevo de orquestación — Claude → Codex (Sol)

**Fecha:** 2026-08-26 · **Rama:** `diegodevxd/feature-cuentivo-ai`

**Sobre el HEAD:** la primera versión de este archivo se redactó sobre `0e41cfa` y quedó commiteada
en `77de324`, que es su propio commit. La sección 7 la actualiza a estado posterior. Si dudas, manda
`git log`, no este encabezado.

Este documento existe para que Sol pueda **tomar la orquestación** de Cuentivo cuando a Claude se le
acaben los tokens, sin volver a derivar nada. Es el estado de ayer y hoy en un solo lugar.

Tablero visual equivalente (misma información, mejor presentada):
<https://claude.ai/code/artifact/747ced16-9797-4f19-ac90-7512df8ff350>
Si no puedes abrirlo, **este archivo es la fuente completa**: no te falta nada por no verlo.

---

## 1. El reparto de agentes

- **Claude:** orquestador. Planea, revisa, escribe los briefs, verifica **ejecutando**.
- **AGY:** el implementador. Tarea "AGY CUENTIVOAI NUCLEO TASK", terminal de Orca
  `term_1b45e54b-44d5-488a-a364-9043361d7e1a` (aparece titulada `powershell.exe`, no por su nombre).
  Se le habla con `orca terminal read/send/wait --terminal <handle> --json`.
- **Sol (tú):** segunda opinión. Has auditado T1 y T2, y las dos veces encontraste cosas reales.

**Si tomas la orquestación, heredas el papel de Claude, no el tuyo.** Es decir: escribes briefs para
AGY y verificas lo que devuelve. Regla que costó aprender: **`read` antes de `send`** — si AGY ya
está trabajando, no se duplica el prompt; se espera y se corrige al quedar idle. Y ojo:
`orca terminal wait --for tui-idle` **dispara en falso**; verifica contra `git log` / `git status`,
que es la fuente real.

---

## 2. Qué pasó ayer (2026-08-25)

Se publicó **`versionCode 32` / `1.17.0`**, la actualización de retención: deja entrar sin cuenta,
conserva los datos al registrarse y pide confirmación antes de cambiar de cuenta. **No lleva
CuentivoAI** — son pistas separadas a propósito, para que la medición de retención salga limpia.
Está en producción con **lanzamiento gradual al 20%**, esperando cohorte. AAB firmado con la misma
llave que el v28, verificado con `jarsigner` y comparación de SHA1.

---

## 3. Qué pasó hoy (2026-08-26)

### Dos decisiones de producto de Diego

1. **CuentivoAI sale ya como plan de pago**, no como beta gratuita. Quien esté en Gratis o Pro y
   toque CuentivoAI **ve el muro de suscripción**. Eso mete la UI del plan y el entitlement de Play
   **dentro** de este release.
2. **El AAB de N5 va primero a prueba cerrada de Play**, y sólo después a producción. No se repite
   el camino directo de la v32.

Costo asumido a sabiendas: el precio de **$299 MXN/mes** queda fijado **antes** de medir la beta.
Registrado como "precio inicial para prueba cerrada, sujeto a revisión antes de producción general".

### N5 en marcha: T1 y T2 cerradas con evidencia

- **T1** — tercer plan en el dominio. `SubscriptionTier` declara `rank` e `includesProBenefits` en
  vez de inferirlos del `ordinal` o de "no ser FREE"; `tierFrom` resuelve con `maxByOrNull { it.rank }`.
  Corrigió un bug latente: `allowsCloudSync` comparaba `== PRO`, así que el cliente de $299 habría
  perdido el respaldo en la nube que sí tiene el de $199, **compilando limpio**.
- **T2** — billing de dos productos, cambio de plan como reemplazo con el token anterior, política de
  prorrateo decidida (`CHARGE_PRORATED_PRICE` al subir, `DEFERRED` al bajar), `obfuscatedAccountId`
  SHA-256 del uid, y captura del `purchaseToken` lista para T3.

**Hicieron falta dos auditorías tuyas.** La segunda encontró lo importante: `launchPurchase`
construía el flujo desde `activePurchases`, un campo cacheado que sólo se llenaba cuando respondía
`refresh()`. Si el usuario tocaba comprar antes, se abría una compra limpia de AI **encima de un Pro
activo** — dos suscripciones cobrando. Resuelto con `PurchaseLaunchPolicy`, decisión pura que exige
consulta fresca de Play atada al uid y bloquea por ocho motivos explícitos (`QUERY_PENDING`,
`QUERY_FAILED`, `USER_MISMATCH`, `MISSING_USER_ID`, `ALREADY_ACTIVE`, `INCONSISTENT_STATE`,
`UNKNOWN_PRODUCT`, `INVALID_REPLACEMENT`).

**Lección que vale registrar:** las 888 pruebas pasaban con ese bug dentro, porque era una carrera
asíncrona y no lógica pura. Verificar pruebas verdes no es verificar.

**Evidencia actual, ejecutada:** **899 pruebas JVM en 101 clases, 0 fallos, 0 errores.**
`git diff --check` limpio. Árbol con sólo `?? docs/superpowers/handoffs/`.

### Investigación de competidores (nueva, no existía)

El spec de posicionamiento nombraba competidores sin un solo precio ni fecha. Consultado en la web
hoy y anotado en `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md` §2.1:

| Competidor | Precio | ¿IA? |
|---|---|---|
| **Kashi** (mexicano, Rioverde S.L.P.) | Gratis 1 usuario/30 productos · Starter $166/mes · Pro $333/mes | **Sí, "Kashi AI"** |
| **Treinta** (20+ países) | Esencial USD $9.99 · Pro USD $19.99/mes | No visible |
| Libretas de deudas offline | Gratis o compra única | No |

**El hallazgo que define el diferenciador:** Kashi tiene IA, pero **sólo responde**. Su sitio la
describe como *"Responde tus preguntas sobre ventas, inventario o fiado"*. CuentivoAI **ejecuta**:
una frase crea el cliente, crea los productos faltantes y registra la venta en una transacción, con
preview, confirmación e idempotencia.

Consecuencias: **$299 no está fuera de mercado** (entre Kashi Starter y Kashi Pro, por debajo de
Treinta Pro), pero **Kashi incluye su IA en el plan en vez de cobrarla aparte**, así que el muro de
T7 debe vender *ejecutar frente a responder*, no la palabra "inteligencia artificial". Kashi es muy
nueva (5.0 de **4 calificaciones**, publicada en marzo de 2026): valida la categoría, no la domina.

---

## 4. Dónde está todo

| Qué | Dónde |
|---|---|
| Estado vivo | `PROJECT_MEMORY.md` |
| Plan de N5 (T0–T9) | `docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md` |
| Diseño del entitlement | `docs/superpowers/specs/2026-08-25-cuentivoai-play-entitlement-design.md` |
| Posicionamiento + competidores | `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md` |
| Briefs para AGY | `docs/superpowers/briefs/` |
| Arquitectura y reglas | `CLAUDE.md` |

`docs/superpowers/handoffs/` es de Diego: **no agregar, borrar ni modificar**.

---

## 5. Qué sigue

**T3 está en marcha con AGY** ahora mismo, con el brief
`docs/superpowers/briefs/2026-08-26-T3-entitlement-server-side.md`. Sólo el **camino 1**, el que
concede: callable en `us-south1` con Auth y App Check, `purchases.subscriptionsv2.get`, y escritura
de `aiEntitlements/{uid}` con Admin SDK en transacción. Las Real-time Developer Notifications —el
camino que **revoca**— quedan fuera, porque necesitan un tópico de Pub/Sub que no existe.

**Cuando AGY entregue T3, hay que auditarla.** Toca dinero, backend y secretos: la auditoría no es
opcional. Los cuatro puntos a mirar sin piedad son las invariantes del diseño: un `purchaseToken`
pertenece a **un solo uid** (ledger de primer escritor gana), **idempotencia** por estado absoluto y
nunca sumando tiempo, **falla cerrado** si Play no responde, y vigencia contra el **reloj del
servidor**.

Después: **T4** (razones de rechazo — hoy seis caminos colapsan en `AI_UNAVAILABLE`, y con gente
pagando eso es un reembolso), **T5** (flag remoto, sin abrir `aiControl` al APK), **T6** (transporte
de release), **T7** (UI del plan y muro, con regresión que pruebe que el muro **nunca** bloquea
registrar un abono), **T8** y **T9**.

### Bloqueado en manos de Diego, no de un agente

1. Crear `cuentivo_ai_mensual` a $299 MXN en Play Console (el id debe ser ése literal).
2. Habilitar la Play Developer API y crear la cuenta de servicio. **Suele tardar.**
3. Crear el tópico de Pub/Sub para las notificaciones (bloquea el camino que revoca, no T3).
4. Autorizar la prueba de compra real en el A059P.

Sin 1 y 2, T3 se escribe pero no se verifica de punta a punta. AGY tiene instrucción de decir
explícitamente qué quedó sin verificar y **no simular** una verificación que no ocurrió.

---

## 6. Reglas que no se negocian

Están en `CLAUDE.md`, pero éstas son las que más se rozan en este tramo:

- Dinero siempre `Long` en centavos. Nada financiero se borra ni se edita.
- **Registrar un pago o abono nunca se bloquea** por plan, cuota ni entitlement.
- **Nada de PII, saldos ni tokens** en logs, Analytics ni Crashlytics. La evidencia se redacta:
  nunca el `purchaseToken`.
- Los `object` de `domain/` son **Kotlin puro sin Android**. Única excepción documentada:
  `LegacyMigrationRepository.kt`.
- **No declarar nada completo sin build y pruebas realmente ejecutadas.** No inventar resultados.

---

## 7. Actualización del relevo — final del 2026-08-26

Sol leyó la versión anterior y confirmó que puede asumir la orquestación. **Desde este punto Sol es
la segunda mente y el relevo.** Lo que sigue es lo que cambió después de escribir las secciones 1-6.

### Los prerrequisitos manuales de Diego: dos cerrados

- **T0 cerrado.** El producto `cuentivo_ai_mensual` está **ACTIVO** en Play Console: plan base
  `mensual`, renovación automática, **$299 MXN** confirmado, período de gracia y disponibilidad
  configurados. Quedó en 174 regiones en vez de sólo México — recortable después, sólo afecta
  compras nuevas.
- **Cuenta de servicio invitada.** Play Developer API habilitada, cuenta de servicio creada, clave
  JSON descargada y guardada **fuera del repositorio**, e invitada en Play Console → Usuarios y
  permisos con acceso a información financiera y a administrar pedidos y suscripciones. Nota
  práctica: la página "Acceso a API" vive a nivel de cuenta, no de app, y **vincular el proyecto de
  Cloud ya no es obligatorio** — invitar el correo de la cuenta de servicio directamente basta.

**Sigue pendiente de Diego:** el tópico de Pub/Sub para las Real-time Developer Notifications. No
bloquea T3; bloquea el camino que **revoca**. Detalle que se olvida: hay que dar permiso de
**Publicador de Pub/Sub** sobre el tema a `google-play-developer-notifications@system.gserviceaccount.com`,
o el tema se queda mudo.

### T3 entregada por AGY: `001466e`

Verificado ejecutando: **252 pruebas de Functions en 13 archivos, todas verdes.** El ledger token→uid
está bien: `subscriptionEntitlementRepository.ts:54-55` compara el uid ya ligado al token y **rechaza
en vez de reasignar**, que es la invariante que impide compartir una suscripción entre cuentas.

**Lo que falta y está en curso con AGY:** el enlace con la credencial.
`playSubscriptionVerifier.ts:114` **falla cerrado con un error explícito** —
`"Play Developer API service account not configured in this environment"` — en vez de simular una
verificación. Correcto, pero significa que no hay `defineSecret` para la cuenta de servicio ni
minado de token OAuth. Se le pidió a AGY: declarar el secreto siguiendo el patrón de
`CUENTIVOAI_GEMINI_API_KEY` (`functions/src/index.ts:27`), ligarlo con `secrets: [...]` **sólo** a la
callable de verificación, implementar el OAuth con scope `androidpublisher`, y mantener el fallo
cerrado. **AGY debe reportar el nombre exacto del secreto**, porque Diego lo necesita para
`firebase functions:secrets:set`.

### Las tres precisiones que pidió Sol, y que quedan como autoridad

1. **T3 puede quedar "implementada y validada localmente", nunca "verificada end-to-end"** hasta que
   el secreto esté cargado y exista una compra real contra Play.
2. **Sin RTDN el camino de revocación sigue abierto.** T3 **no cierra por sí sola** el entitlement
   completo ni autoriza producción.
3. **El handle de terminal de AGY es efímero.** Si Orca reinicia, se recupera con
   `orca terminal list --worktree active --json`. Nunca enviar al viejo y al nuevo a la vez.

### Estado que Sol toma como autoridad

- v32 / 1.17.0 en producción gradual al 20%, sin CuentivoAI.
- N5: **T1 y T2 cerradas**; T3 implementada y pendiente de credencial y de auditoría estricta.
- Evidencia base: **899 pruebas JVM en 101 clases** y **252 de Functions**.
- Orden posterior: T4 → T5 → T6 → T7 → T8 → T9.
- `docs/superpowers/handoffs/` intocable.
- **CuentivoAI público: sigue NO-GO.**

### Lo primero que debe hacer el relevo

Cuando AGY entregue el enlace de la credencial: **auditar T3 completa**, sin piedad, contra las
cuatro invariantes del diseño — un `purchaseToken` para un solo uid, idempotencia por estado
absoluto, falla cerrado si Play no responde, y vigencia contra el reloj del servidor. Toca dinero,
backend y secretos: la auditoría no es opcional. Y verificar **ejecutando**, no leyendo el reporte:
las 888 pruebas de T2 pasaban con un bug de cobro doble dentro.

---

## 8. Actualización — 2026-08-27

**La sesión de Codex se perdió** (Diego cambió de cuenta). Esta sección existe para que la sesión
nueva arranque sin reconstruir nada. La anterior era `gpt-5.6-sol`; la nueva es `gpt-5.6-terra` y no
comparte memoria.

**Aviso importante: los handles de terminal de Orca cambiaron con el reinicio.** Los de las
secciones anteriores ya no sirven. Al día de hoy, **AGY es `term_a7febf7f-937a-4d7d-b5ab-4eae6a833eb1`**
(titulada `powershell.exe`), identificado porque su preview reporta el commit `795ce24`. Si vuelven
a cambiar: `orca terminal list --worktree active --json` y reconocer a AGY por lo que dice su
preview, nunca por el título.

### Lo que se cerró desde la sección 7

- **T3 desplegada.** `verifyAiSubscription` está viva en `us-south1`, Node 22, 2ª gen, con el
  secreto `CUENTIVOAI_PLAY_SERVICE_ACCOUNT_KEY` cargado en Secret Manager y con `secretAccessor`
  concedido a la cuenta de cómputo. Deploy confirmado: *Successful update operation*.
- **El deploy falló la primera vez** por una incoherencia real: `functions/package.json` declaraba
  `google-auth-library@^9.15.1` y el lock resolvía `10.9.1`, así que `npm ci` de Cloud Build se
  negaba. Arreglado en `795ce24` alineando a `^10.9.1`, que es la versión contra la que está escrito
  el `GoogleAuth` de `playSubscriptionVerifier.ts`. **Lección: `npm test` no valida el lock; hay que
  añadir `npm ci` limpio al gate de Functions**, o el fallo sólo aparece al desplegar.
- **Prerrequisitos manuales de Diego, cerrados:** producto `cuentivo_ai_mensual` **ACTIVO** a
  $299 MXN, plan base `mensual` de renovación automática; cuenta de servicio
  `play-entitlement-verifier@organitazionlabs.iam.gserviceaccount.com` invitada en Play Console.

### El hallazgo del 27 de agosto

Diego creía que N5 estaba terminada y quiso probar comprando desde su teléfono. **No se podía, y no
por el token de App Check.** Verificado en el código:

- `MainActivity.kt:2347` es el **único** botón de compra y llama a `launchPurchase` **sin indicar
  plan**, así que cae en `PRO_PRODUCT_ID` por omisión.
- La carpeta `app/src/main/java/com/cuentivo/app/ui/` **no menciona `SubscriptionTier.AI` ni
  `AI_PRODUCT_ID` en ninguna línea**.
- `CuentivoApp.kt:362` esconde la entrada de CuentivoAI cuando el transporte es `null`.

Es decir: **el producto se vende en Play y el backend sabe verificarlo, pero la app no tiene dónde
venderlo.** Eso es T7.

### Estado real de N5: 3 de 9

Hechas **T1, T2, T3**. Pendientes **T4** (razones de rechazo), **T5** (flag remoto), **T6**
(transporte de release), **T7** (UI del plan y muro, **en curso con AGY** desde hoy con el brief
`docs/superpowers/briefs/2026-08-27-T7-ui-del-plan-y-muro.md`), **T8** y **T9**.

**T3 sigue desplegada pero NO verificada de punta a punta.** Sin una compra real que la ejerza, la
precisión que pidió la sesión anterior sigue vigente palabra por palabra.

### El camino que desbloquea todo

Build con T7 → subirlo a **prueba interna** de Play → instalarlo **desde Play Store** → comprar como
license tester. Instalando desde Play, App Check atestigua por **Play Integrity** y desaparece el
problema del token de depuración que se regenera en cada reinstalación. Esa misma compra verifica T3
de punta a punta **y** genera el tráfico que hace falta para encender el enforcement de App Check
(bloqueo B2). Tres cosas con una.

### Sigue pendiente de Diego

El **tópico de Pub/Sub** para las Real-time Developer Notifications. Sin él nada revoca cuando
alguien cancela o pide reembolso. No bloquea la prueba de compra; sí la prueba cerrada. Recordar el
permiso de **Publicador** sobre el tema para
`google-play-developer-notifications@system.gserviceaccount.com`, o el tema se queda mudo.
