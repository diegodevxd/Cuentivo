# Estado vivo — Cuentivo / CuentivoAI

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
**sustituyendo, no añadiendo**, y se mantiene por debajo de ~100 líneas. Lo que deja de estar
vigente baja a `docs/historia-del-proyecto.md`.

No es un source of truth. Manda, en este orden:

1. [`docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md) — arquitectura, seguridad, fases, GO/NO-GO y changelog de CuentivoAI.
2. [`PROJECT.md`](PROJECT.md) — dirección de producto y checkpoint.
3. El código y sus pruebas para comportamiento real.
4. `CLAUDE.md` para arquitectura del repo y trampas del entorno — **verifícalo contra el código**; ha estado desactualizado.

**Última actualización:** 2026-09-04. **El núcleo está cerrado y el código pendiente, terminado.**
Lo que queda para producción ya no es código: cohorte limpia de métricas y la observabilidad que el
incidente del 4 de septiembre dejó al descubierto.

## 2026-09-04 — CuentivoAI se cayó en producción: tres bugs apilados, resueltos

Diego reportó que CuentivoAI dejó de funcionar: la compra pasaba, la app lo dejaba entrar, y al
escribir el servidor respondía que debía pagar. **No fue una regresión de código: eran tres
defectos de infraestructura apilados, cada uno tapando al siguiente**, dormidos desde el 26 de agosto.

1. **`verifyAiSubscription` sin permiso de invocación.** Faltaba `allUsers` →
   `roles/cloudfunctions.invoker`. Cloud Run devolvía 401 *"access token could not be verified"* y
   la función **nunca ejecutaba**. La pista estuvo en `run.googleapis.com/client-name`:
   `cli-firebase` en las tres callables sanas, `cloudfunctions` en la rota. Se desplegó por otra
   ruta y no recibió el binding que el CLI de Firebase aplica.
2. **El secret de la cuenta de servicio de Play contenía 1 byte: una llave de apertura.** El JSON se
   pegó por terminal al crearlo y el shell se comió el resto. Superado el 401, la función moría en
   `PLAY_API_UNAVAILABLE`. La cuenta `play-entitlement-verifier@` y sus permisos en Play Console
   siempre estuvieron correctos.
3. **La imagen del contenedor ya no existía** en Artifact Registry, así que **no se podía crear
   ninguna revisión** para arreglar lo anterior — ni por consola ni por gcloud.

**Por qué nadie lo vio:** el entitlement manual del 24 de agosto (vencimiento
`2026-09-01T00:00:00Z`) mantuvo CuentivoAI funcionando todo ese tiempo. Venció el 31 a las 18:00
hora local y los tres defectos salieron juntos. `aiEntitlements` tenía **un solo documento**, el de
Diego: ningún tercero se quedó sin servicio.

**Arreglo:** los dos bindings de IAM restaurados —Cloud Run y Cloud Functions son capas distintas
con IAM propio, y arreglar sólo la primera no basta—, una versión nueva del secret con la llave
completa (2392 bytes, subida con `--data-file`, nunca por pipe) y deploy de las cuatro callables,
que reconstruyó la imagen. Verificado en logs: `AI_INTERPRET_RESULT · AI_PROPOSAL`, tres respuestas
de Gemini entre 808 y 877 ms.

**Mecanizado para que no se repita:** `AI_BOUNDARY` declara ahora `invoker: "public"`, así que cada
deploy restaura el binding en vez de depender del estado de la consola.

**Lo que este incidente deja abierto.** Ninguna de las tres fallas era detectable por las pruebas:
`release:check` verifica el código, y estas eran de configuración de producción.

- **`verifyAiSubscription.ts` se traga el error real.** El `catch` alrededor de
  `playApi.getSubscription` colapsa cinco causas distintas en `PLAY_API_UNAVAILABLE` sin registrar
  ninguna. Diagnosticar el secret vacío costó horas que un `logger.warn` con el tipo de error habría
  ahorrado. **Es el pendiente de mayor retorno.**
- **Nada vigila que las callables respondan.** Un 401 permanente vivió cinco días sin alerta.
- **Nada vigila que un entitlement se acerque a expirar sin renovarse**, que es justo la señal que
  habría delatado los tres bugs antes de que el usuario los sintiera.

## CuentivoAI se subió a PRODUCCIÓN el 2026-08-31, con el AAB `v36` (`88fb072`)

**El NO-GO público terminó.** Diego lo autorizó y lo subió tras cerrar las cinco condiciones. A
partir de aquí, cualquier persona puede comprar el plan de $299 — no sólo él.

**Lo que eso cambia, y hay que tenerlo presente:**

- **Ya hay dinero de terceros de por medio.** El consumidor de RTDN está vivo, así que cancelaciones
  y reembolsos se procesan; pero cualquier defecto que aparezca ahora le pasa a alguien que pagó.
- **El apagador es real y está ensayado.** Si los costos se disparan o la IA empieza a equivocarse,
  se apaga `aiControl/global.enabled` y el gasto se detiene en cero. El runbook tiene el
  procedimiento y el acta del simulacro del 31 de agosto.
- **Empieza la medición limpia.** El contador `aiUsage` quedó contaminado el 2026-08-25, así que la
  primera cohorte útil para validar los $299 arranca ahora.
- **Vigilar los primeros días:** Crashlytics, el panel diario de App Check, el gasto de Gemini y las
  reseñas. Un fallo en el arranque se corrige mucho mejor con lanzamiento gradual que después.

## Cierre del 31 de agosto: las cinco condiciones de producción, cerradas

**Sólo queda una cosa abierta, y no es de teclado: medir los $299 con una cohorte limpia.**

| Condición | Estado |
|---|---|
| Núcleo CuentivoAI | ✅ 5 de 5 |
| Revocación por notificaciones de Play | ✅ desplegada y confirmada en logs |
| Privacidad y Play Data Safety | ✅ política, declaración enviada y enlace en la app |
| **Simulacro del apagador** | ✅ **ejecutado en producción**, acta en el runbook |
| **App Check en Firestore** | ✅ **enforcement encendido y verificado** |
| Validar el precio de $299 | ⬜ requiere cohorte, no trabajo |

**El simulacro dejó la prueba que faltaba:** con la IA apagada, una petición real entró, se
autenticó, pasó App Check y **murió en 216 ms sin tocar Gemini** — cero `AI_PROVIDER_INVOCATION`,
frente a los 1113 ms de latencia del modelo con la IA encendida. **Apagar no cuesta dinero**, y eso
ya no es una afirmación de diseño. Corte total: 5 min 10 s, del 19:25:29 al 19:30:39.

**Y una lección operativa que quedó escrita:** el TTL de 5 minutos es el **techo**, no el tiempo
típico — el camino del servidor avisa al instante y reabrir la app fuerza una consulta nueva.

**Último defecto encontrado y cerrado (v36):** la app **dejaba comprar con la sesión anónima**.
Diego lo vivió con dinero real: pagó como anónimo, inició sesión, y no pudo usar lo que había
pagado. Ningún guarda falló —la compra se selló con el uid anónimo, la titularidad estricta la
rechazó bien, y el servidor rechaza anónimos por diseño— pero **la app vendió algo imposible de
usar**. `PurchaseLaunchPolicy` gana un noveno motivo, `ANONYMOUS_SESSION`, y ahora lleva a iniciar
sesión en vez de a la pantalla de pago. **Deuda menor anotada:** ese parámetro tiene default `false`,
o sea que falla hacia el lado inseguro si un futuro llamador lo omite; hoy los dos call sites lo
pasan y hay pruebas, pero conviene volverlo obligatorio junto con los otros defaults.

## Lo que pasó el 30 y 31 de agosto

**Diego probó en Play de verdad, y esa prueba destapó cuatro defectos que ninguna suite veía.**

1. **El plan se acreditaba al teléfono, no a la cuenta.** `PlayPurchase` no tenía campo de
   titularidad y `applyPurchases` nunca leía `accountIdentifiers`, así que el tier salía de la
   cuenta de **Google Play del dispositivo**. Una segunda cuenta entraba al asistente. El servidor
   negó correctamente; el cliente mintió. Corregido y verificado por Diego en el `v34`.
2. **El primer arreglo traía una regresión que habría quitado Pro a TODOS los clientes de
   producción.** Play no devuelve `null` para una compra sin identificador: devuelve **cadena
   vacía**, y la regla sólo toleraba `null`. Las 940 pruebas JVM pasaban con el defecto dentro
   porque **la única que lo veía es instrumentada**. Normalizado en la frontera.
3. **Cancelar revocaba al instante.** `translatePlaySubscription` concedía sólo con `ACTIVE` e
   `IN_GRACE_PERIOD`, y `CANCELED` caía en el mismo cubo que `EXPIRED` — pero en Play una
   suscripción cancelada **sigue vigente hasta que termina el periodo pagado**. Quien cancelaba el
   día 3 perdía 27 días ya pagados. Corregido y **desplegado**.
4. **`firebase deploy` no compilaba.** No había hook de `predeploy`, así que subía lo que hubiera en
   `lib/`. Funcionó antes por casualidad. Un deploy dijo *"Successful update"* cuatro veces
   subiendo código de tres días atrás, y por eso **T4, T5 y T8 estuvieron escritas sin llegar a
   producción**. Hook agregado; `lib/` ahora se borra antes de compilar.

**El consumidor de notificaciones de Play está vivo y confirmado en producción**: dos
`PLAY_RTDN_IGNORED` con `TEST_NOTIFICATION` en los logs, desde el tópico. Diseño de Codex que hay que
conservar: **la notificación es una señal, no un veredicto** — se consulta Play y se decide por el
estado real, con reintentos activos y clasificación explícita de errores permanentes.

**El Pro histórico se simplificó por un dato de Diego:** nunca nadie compró Pro. Cero suscriptores.
Así que la tolerancia no protegía a nadie y la regla quedó **estricta y simétrica** con AI.
**Condición que la hace segura, y deja de valer en cuanto alguien compre:** la app de producción es
la `versionCode 32`, que **no lleva el sello**, así que cualquier compra de Pro desde la app
publicada hoy nace sin identificador. Está escrito en el código.

**Privacidad quedó completa**: la política ganó su sección de CuentivoAI y luego cuatro correcciones
—Crashlytics como proveedor, ubicación aproximada inferida por IP, notas de ventas y abonos, y la
fecha—; la declaración de **Seguridad de los datos se envió**; y el `v35` lleva el **enlace al aviso
dentro de la app**. Los tres coherentes entre sí, que es lo que se revisa.

**Mecanizado para que no se repita:** `npm --prefix functions run release:check` encadena `npm ci`
limpio, lint, pruebas, gate del emulador y compilación limpia, y **falla si falta el JDK** en vez de
saltarse el gate.

---

**(2026-08-30) El núcleo de CuentivoAI está cerrado: 5 de 5.** N5 cerró sus
nueve tareas, el AAB `versionCode 33` salió a prueba cerrada y **la compra real desde Play verificó
el entitlement de punta a punta** (T3 y T4 verificadas en vivo, no sólo desplegadas). El AAB `v34`
corrige lo que esa prueba destapó.

**Precisión que hay que conservar, de Codex:** núcleo terminado significa **código y beta técnica**.
**No** significa readiness comercial ni operación segura. **Producción pública sigue en NO-GO** y sus
condiciones están abajo.

---

## Dónde estamos

**Cuentivo base.** `versionCode 32` / `versionName 1.17.0` **subido a Play y en producción con
lanzamiento gradual al 20%** desde el 2026-08-26. La baseline anterior era `31 / 1.16.6`. Room, sync,
Billing, Auth y roles operan en datos reales. El siguiente AAB es el que **cierra el núcleo de
CuentivoAI**, y esa es la pista de trabajo activa mientras la v32 acumula cohorte.

**CuentivoAI.** Rama aislada `diegodevxd/feature-cuentivo-ai`. El gateway server-side existe: la
key de Gemini vive en Secret Manager, la facturación Blaze está configurada y únicamente
`interpretAiRequest` y `authorizeAiAction` quedaron desplegadas en `us-south1` para la cohorte
debug personal. El APK debug habla con el gateway vía Firebase Auth y App Check. Ninguna key entra
al APK y la callable de autorización no tiene ligado el secreto Gemini.

Aceptado físicamente en el A059P, no sólo por pruebas:

- abono natural: $500 → $150 → $350 sobre la deuda real de una clienta;
- venta con cliente y producto **existentes**, con abono inicial;
- creación compuesta de cliente, producto, venta y abono sin duplicados;
- consultas deterministas de saldo, deudores y ventas por período; las cifras salen de Room;
- rotación horizontal/vertical conserva el laboratorio y sus respuestas en memoria;
- **venta de contado por IA**: cliente y producto nuevos creados y pagados al momento, saldo cero;
- **dictado por voz**: texto reconocido, editado a mano y enviado, con Gemini devolviendo propuesta.

**Incrementos cerrados con plan y evidencia propios** — el detalle vive en cada plan, no aquí:
creación compuesta de cliente/producto/venta/abono sin duplicados
(`2026-08-24-cuentivoai-composite-sale-creation.md`, `20f4f1e`…`24014de`) y consultas naturales
deterministas cuyas cifras salen sólo de Room
(`2026-08-24-cuentivoai-natural-queries.md`, `d308cfe`…`a4f992d`).

**Incremento cerrado — venta de contado.** Salió de uso real, no de una prueba: "Demian me pagó 92
de un boleto" se clasificaba como abono y moría en un callejón, porque el camino de abonos no crea
clientes **a propósito** y exige deuda previa. La frase era una venta pagada de contado. `buildSystemInstruction`
no tenía regla que la distinguiera; se agregó en `7f391da`/`4f7ee34` y se desplegó. La base del A059P
confirma cliente `Demian`, producto a 9200 centavos, venta 9200, abono 9200 y **saldo 0**.

**Incremento cerrado — voz a texto editable (N3).** Plan:
`docs/superpowers/plans/2026-08-25-cuentivoai-voz-a-texto.md` (`0c46d55`…`cc0df03`). **La voz es un
método de entrada para el cuadro de texto que ya existe**, no un pipeline nuevo. `RECORD_AUDIO` se
declara **sólo** en `app/src/debug/AndroidManifest.xml`, verificado con `aapt2` sobre las APK
empaquetadas: release `RECORD_AUDIO=0`, debug `=1` — la app publicada no gana permiso de micrófono.
La prueba física encontró lo que 800 pruebas no podían: el motor en el dispositivo existía pero sin
paquete de es-MX y fallaba con `ERROR_LANGUAGE_UNAVAILABLE` antes de escuchar; se trataba la
disponibilidad del **motor** como si fuera la del **idioma**. Corregido con reintento único al
reconocedor del sistema.

**Incremento cerrado — endurecimiento (N4).** Plan:
`docs/superpowers/plans/2026-08-25-cuentivoai-endurecimiento.md`. Ningún estado del laboratorio termina
en pared: los "no" dicen qué hacer y con qué cifras. **Ninguna guarda se debilitó**: cambió lo que se
dice, no lo que se permite. Encontró tres cosas que la suite no veía — buscar un nombre inexistente
devolvía **todos** los clientes (`COALESCE(phone,'') LIKE '%%'`), se mostraban enums crudos
(`UNKNOWN_INPUT`, `MISSING_PRODUCT`) al usuario, y **dos bugs de dinero**: montos interpretados como
centavos, y ventas a crédito registradas como pagadas. Los dos desplegados y verificados físicamente.

**Núcleo: 5 de 5, cerrado el 2026-08-30.** La salida del laboratorio a beta (N5) terminó sus nueve
tareas y el segundo AAB ya está en prueba cerrada.

**Condiciones para el GO público, ninguna cumplida todavía** (criterios de Codex, 2026-08-30):

1. **Consumidor de las notificaciones de Play operativo.** Es lo único que queda de código nuevo.
   **Ojo con el diseño:** no debe revocar al recibir cualquier evento. La notificación es la señal;
   el consumidor **consulta Play y decide el estado real** — renovación extiende la vigencia,
   **cancelación conserva el acceso hasta el fin del periodo pagado**, y sólo reembolso, revocación,
   *on hold* o expiración retiran el entitlement. Idempotente ante reintentos, duplicados y mensajes
   fuera de orden, con alerta o DLQ para lo no procesado. **Revocar por una simple cancelación sería
   también un bug comercial.**
2. **App Check con enforcement encendido y probado.** No basta con que ya haya tráfico: hay que
   revisar tráfico válido **por cada callable usada en release**, encender, y después ejecutar una
   petición real desde una instalación de Play Store, con monitoreo y rollback listos. Es condición
   de GO, no validación retrospectiva.
3. **Simulacro del apagador ejecutado y firmado.** T8 cerró código, pruebas y runbook; el ensayo real
   sigue pendiente y **no es una tarea administrativa**.
4. **Validación del `v34`** desde Play Store con la segunda cuenta.
5. **Cohorte limpia de métricas** y **privacidad/Data Safety cerradas** — ver los blockers.

**Actualización de retención — `versionCode 32` / `versionName 1.17.0`, subida a Play el 2026-08-26.**
Primera versión desde la baseline congelada 31 / 1.16.6, y **no lleva CuentivoAI**: son pistas
separadas a propósito. Plan: `docs/superpowers/plans/2026-08-25-cuentivo-retencion-puerta-abierta.md`;
decisiones de producto en `specs/2026-08-25-cuentivo-invitado-decisiones.md`. El AAB firmado quedó en
`dist/Cuentivo-v32-1.17.0.aab`, con SHA1 idéntico al de la llave de subida existente (verificado con
`keytool -printcert -jarfile` antes de subirlo).

**Por qué:** Firebase mostró que la retención a la semana 1 se desplomó de 20-29% a **0.7%** el 5 de
agosto, el día que entró la verificación de correo obligatoria, y que **219 de 385 instalaciones
terminan en desinstalación**. El argumento decisivo salió del propio código: la nube es beneficio de
Pro y Room es la fuente de verdad, así que a un usuario Gratis se le pedía cuenta y verificación **a
cambio de cero beneficio**, antes de que viera si la app le servía.

**Qué cambió:** sesión anónima de Firebase al primer arranque; `linkWithCredential` que **conserva el
uid**, con guarda en tiempo de ejecución que rechaza cualquier resultado con uid distinto; rechazo
explícito de anónimos en el gateway antes de tocar cuota o ledger; primera pantalla "¿Quién te debe?";
y confirmación explícita —nombrando la cuenta destino— antes de cambiar de cuenta con datos locales.
La verificación de correo **no se eliminó, se movió** al momento en que hay respaldo que proteger.

**La task 2 se auditó dos veces**, por Claude y por Sol, por ser la más peligrosa del proyecto. Sol
encontró que la prueba conectada **simulaba** el enlace —el mismo error que Claude había bloqueado esa
mañana en otro trabajo— y de ahí salió la guarda en tiempo de ejecución.

**Estado de release.** Cuentivo base está **publicada en Play**, con la v32 al 20% y **esperando
cohorte**: la retención a la semana 1 no se lee en días sueltos, así que hasta principios de
septiembre no hay señal que interpretar. Nada de tocar el rollout con métricas de dos días.
**CuentivoAI sigue NO-GO**: sólo vive en el laboratorio debug, y su AAB, beta pública y activación
general continúan cerradas.

## Invariantes que ningún incremento rompe

- Dinero siempre `Long` en centavos. Nada financiero se borra ni se edita: se corrige con un
  movimiento compensatorio.
- Toda consulta filtra por `businessId`, en repositorio **y** en `firestore.rules`.
- **Registrar un pago o abono nunca se bloquea** por límite de plan ni por cuota de IA.
- Gemini sólo propone Structured Outputs. Nunca elige IDs locales, ni declara saldos, ni escribe
  Room, Firestore, outbox ni auditoría. Cuentivo resuelve, calcula, previsualiza y ejecuta.
- Preview editable + confirmación explícita + idempotencia son el centro de confianza: doble toque
  y reintento producen como máximo una operación.
- Cuentivo Gratis y Pro funcionan aunque IA, gateway, Gemini o Internet fallen.
- Nada de PII, saldos ni tokens en logs, Analytics o Crashlytics.
- Prioridad fija: estabilidad > integridad financiera > seguridad > compatibilidad > pruebas > UX > velocidad.

## Último gate verificado

Cierre de voz (`cc0df03`), reejecutado por Claude con XML posterior al commit: Android JVM
**802/802** en 93 clases con `--rerun-tasks`, focal `VoiceRecognizerTest` 7/7, `assembleDebug`,
`assembleRelease`, `lintDebug`, paridad de claves **471 = 471**, puerta del idioma limpia y
`git diff --check`. Functions **226/226** con build/lint tras la regla de venta de contado.

Gate live de Fase 2 (previo): Functions 225/225, Firestore Emulator 2/2, JVM 767/767, prueba física
2/2. Evidencia: `docs/superpowers/operations/2026-08-25-cuentivoai-phase-2-live-validation.md`.
**Ojo:** ese registro cita la configuración restaurada en `true / true / 50 / 3`. Los valores
**vigentes** son `monthlyActionLimit = 500` y `perMinuteLimit = 20`, subidos a mano el 2026-08-25.

## Orquestamiento

- **Claude** es la mente principal actual de arquitectura, auditoría, continuidad y gate; Sol/Codex
  le devolvió el rol el 2026-08-25. Cualquiera de los dos puede retomarlo leyendo este archivo, el
  source of truth y el plan vigente; nadie debe asumir que un reporte de implementador equivale a
  evidencia.
- **AGY** implementa. Su reporte **no** cierra una tarea; sólo la cierra evidencia reejecutada de
  forma independiente: pruebas, build/lint cuando aplique, diff e invariantes.
- Dinero, Room, idempotencia o sync exigen TDD, transacción atómica, rollback probado y pruebas
  conectadas en el A059P antes de aprobar.
- Workspace único: `C:/Users/pibed/orca/workspaces/OrganizationLabs-neo-fintech/feature-cuentivo-ai`.
  El workspace estable original no se toca.
- `docs/superpowers/handoffs/` es del usuario, permanece **sin rastrear e intocable**: no agregar,
  borrar ni modificar.

## Qué sigue

1. **Cerrar el núcleo: salida del laboratorio a beta (N5)** con feature flags y observabilidad. Es la
   pista activa y lo único que queda del 4-de-5. Exige su propio plan antes de tocar código: qué
   bandera enciende la IA, quién la puede encender, qué se observa y cómo se apaga sin publicar.
   **El AAB que salga de N5 va primero a prueba cerrada de Play, y sólo después a producción**
   (decisión de Diego, 2026-08-26). No se repite el camino directo a producción de la v32.
   **CuentivoAI sale ya como plan de pago:** el usuario en Gratis o Pro que lo toque ve el muro de
   suscripción. Eso mete la UI del plan y el entitlement de Play **dentro** de este release, no
   después — ver el punto 2.
2. **Producto de suscripción en Play + UI del plan de $299.** Antes estaba al final; la decisión de
   sacar CuentivoAI como plan de pago lo vuelve **prerrequisito de la prueba cerrada**. Diseño escrito
   en `docs/superpowers/specs/2026-08-25-cuentivoai-play-entitlement-design.md`. Implica fijar el
   precio antes de medir la beta, que es exactamente lo que la fuente de verdad pedía evitar: la
   decisión de producto gana, pero queda escrito que el número entró sin medición previa.
3. **Decir por qué se rechazó.** `interpretAiRequest` colapsa seis caminos en un mismo `AI_UNAVAILABLE`
   y ahí se pierden cuota agotada, rate limit, IA apagada y falta de entitlement. Propuesta escrita en
   `.superpowers/sdd/propuesta-motivo-ai-unavailable.md`. Va **antes** de la prueba cerrada: ahora que
   alguien va a **pagar** $299, leer "aún no disponible" al agotar cuota es un reembolso, no un bug.
4. **Esperar la cohorte de la v32** — trabajo de calendario, no de teclado. Si la retención a la semana
   vuelve a 20-29%, el muro era el problema; si sigue en el suelo, era otra cosa. Publicada al 20% para
   atrapar fallas del build minificado; **subir a 100% en cuanto no aparezcan crashes**, porque en
   lanzamiento parcial la cohorte se mezcla y la medición se ensucia.
5. Encender enforcement de App Check tras revisar tráfico válido del día, generándolo desde una
   instalación de Play Store.
6. Después del núcleo: cobranza/PDF/WhatsApp/barcode y catálogo, cada uno con plan propio.

Cada punto exige su propio diseño, autorización y gates. Ninguno se adelanta dentro del commit
de otro. Fotos cloud/Storage es un proyecto **separado**, no dependencia de CuentivoAI.

## Blockers y pendientes vigentes

- **El control plane live personal está cerrado, pero no el entitlement comercial.** Cuota,
  hard cap, rate limit y kill switch ya están desplegados/probados; Play todavía no concede ni
  revoca el tier AI server-side, por lo que beta y AAB CuentivoAI continúan bloqueados.
- ~~El entitlement AI fue creado manualmente para el usuario de prueba.~~ **Resuelto el
  2026-09-04:** la verificación server-side funcionó de punta a punta por primera vez y
  `verifyAiSubscription` ya escribe el entitlement desde una compra real de Play. Ver el incidente
  del 4 de septiembre.
- **N5 cerrada, 9 de 9 (2026-08-30). El núcleo queda en 5 de 5.** T0 a T9, todas
  verificadas reejecutando, no por reporte del implementador. Estado del gate al cerrar T8: **930
  pruebas JVM en 104 clases, 0 fallos**; **functions 266** con lint de TypeScript limpio; **gate del
  emulador de Firestore 6/6**; `assembleDebug`, `assembleRelease` y `lintDebug` en verde; paridad de
  claves **551 = 551** sin claves huérfanas.

  Lo que cambió de fondo, y que no se lee del `git log`:

  - **T6 era más grande de lo planeado.** Además del transporte de IA, `SubscriptionVerification`
    de release era un `NoOp` que siempre fallaba, así que en el AAB alguien pagaba $299 y
    `aiEntitlements/{uid}` nunca se escribía: T3 estaba desplegada pero **inalcanzable desde el
    build publicado**. Causa raíz común: `firebase-functions` estaba como `debugImplementation`.
  - **T4 no podía llevar el motivo en un campo nuevo.** El parser exige que las llaves de la raíz
    sean exactamente cuatro, así que agregar `reason` habría roto **todo cliente ya instalado**. El
    motivo viaja en el `status` existente, y es compatible hacia atrás por construcción porque el
    parser ya mandaba lo desconocido a `Unavailable`.
  - **T5 usa callable, no Remote Config**, para no tener dos interruptores que se puedan
    desincronizar en una emergencia. El cliente **falla abierto** a propósito: el servidor es el
    apagador autoritativo y ya falla cerrado.
  - **El apagador remoto ya existía del lado del servidor** y quedó verificado: `authorizeInTransaction`
    lee `aiControl/global` primero y corta antes de entitlement, cuota y proveedor. T5 sólo agrega
    la cortesía en pantalla; T8 agregó la prueba de que apagar **no gasta dinero** (cero
    invocaciones al proveedor) y el runbook en
    `docs/superpowers/operations/2026-08-27-cuentivoai-killswitch-runbook.md`.
- **Competencia investigada el 2026-08-26** (precios fechados en
  `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md` §2.1): **Kashi** es el
  competidor mas cercano y el unico con IA, pero la suya solo **responde** preguntas; CuentivoAI
  **ejecuta** operaciones. Kashi Gratis 1 usuario/30 productos, Starter $166/mes, Pro $333/mes, con su
  IA incluida en el plan. Treinta: USD $9.99 y $19.99/mes, sin IA visible. **$299 queda dentro de
  mercado**, entre Kashi Starter y Kashi Pro. El muro del plan (T7) debe vender "ejecutar" frente a
  "responder", no la palabra IA.
- **La UI del plan de $299 ya existe** (T7, 2026-08-27): pantalla de tres planes con precios leídos
  de Play —nunca escritos a mano, y con las compras deshabilitadas mientras Play no responda— y muro
  de suscripción para quien esté en Gratis o Pro. El producto `cuentivo_ai_mensual` está **ACTIVO**
  en Play a $299 MXN. La beta sale **sin voz**: `RECORD_AUDIO` sigue declarado sólo en el manifiesto
  debug, verificado con `aapt2` sobre el APK de release empaquetado.
- **Las notificaciones en tiempo real de Play ya tienen tubería, pero nadie las escucha.** El tópico
  `projects/organitazionlabs/topics/play-rtdn-cuentivo` está creado, con `Publicador de Pub/Sub`
  otorgado a `google-play-developer-notifications@system.gserviceaccount.com`, conectado en Play
  Console y **verificado con notificación de prueba recibida en la suscripción** (2026-08-27). Falta
  la función que la consuma: hasta que exista, **nada revoca el tier cuando alguien cancela o pide
  reembolso**. La suscripción retiene los mensajes 7 días, así que esa función **debe existir antes
  de que entre a pagar cualquier persona distinta de Diego**.
- **Deuda comercial abierta: el Pro heredado sin identificador de cuenta.** Desde el fix de
  titularidad (2026-08-28), una compra que Play reporta con `obfuscatedAccountId` vacío concede
  **PRO a cualquier cuenta de Cuentivo del dispositivo**. Es el precio deliberado de no quitarle Pro
  a los clientes que compraron antes de T2, y **no hay forma de atribuir una compra histórica**. Pero
  como señaló Codex: *vacío no demuestra que la compra sea antigua*, y a diferencia de AI, **los
  beneficios de Pro se aplican en el cliente** (`SyncEntitlement.allowsCloudSync`), sin freno
  autoritativo del servidor. No es fuga de datos entre cuentas —Firestore sigue aislando por
  identidad— pero sí permite beneficios de Pro a quien no pagó. **Antes de ampliar la venta de Pro**
  hay que resolverlo: migrar/revincular el Pro histórico, o ponerle fecha de caducidad a esta
  tolerancia.
- **El enforcement de App Check sigue apagado.** Falta leer el panel con tráfico posterior al
  vínculo de Play Integrity, mirando la barra del día y no el acumulado. Lo ya descartado con
  evidencia está en el histórico; no volver a perseguirlo.
- **No hay CI** y `.superpowers/` está ignorado: la evidencia SDD local no sobrevive a otro clon.
- **Privacidad y Play Data Safety, sin cerrar — levantado por Codex el 2026-08-30 y no estaba en
  ninguna lista.** Que exista una política externa publicada **no demuestra** lo que la fuente de
  verdad exige: confirmar que la política, la declaración de **Data Safety**, los términos y el
  consentimiento cubren que **el texto del negocio viaja al gateway y al proveedor** — con sus
  categorías, su retención y cómo se desactiva la IA. Sigue faltando además el **enlace al aviso de
  privacidad dentro de la app**. Esto **no bloquea el desarrollo**, pero sí bloquea datos reales de
  terceros y el lanzamiento público.
- ~~Todos los rechazos de IA se ven igual.~~ **Resuelto en T4 (2026-08-27).** Cuota agotada, rate
  limit, IA apagada y falta de entitlement ahora viajan como `status` propio y tienen su texto; el
  de entitlement lleva al muro de compra en vez de pintarse como error. Todo lo demás —entrada
  inválida, fallo del proveedor, reclamo duplicado— sigue colapsando en `AI_UNAVAILABLE` a
  propósito, porque filtra estado interno o es "reintenta".
- **El contador `aiUsage` de agosto 2026 se sobrescribió a mano** durante ese diagnóstico: ya no sirve
  para medir acciones por usuario ni para validar el precio. La primera medición limpia empieza con un
  contador que corra sin intervención.
- Descartado el 2026-08-25, **no volver a perseguirlo**: que una reinstalación limpia no devuelva los
  datos **no es un defecto**. El respaldo en la nube es beneficio de Pro (`SyncEntitlement.allowsCloudSync`)
  y el dispositivo estaba en Free, así que nunca subió nada. `sync_state` vacío es lo correcto ahí.
- `BackupCrypto.ITERATIONS` en 120 000 PBKDF2, por debajo de lo recomendado hoy. Subirlas no rompe
  respaldos viejos porque viajan en la cabecera; medir antes en un teléfono lento.

## Reglas de continuidad

Compilar no es verificar: exigir tests reales, build/lint, diff e invariantes. Si memoria,
documentación y código se contradicen, gana el código y la contradicción se documenta. Sustituir
estado viejo aquí; el histórico vive en `docs/historia-del-proyecto.md`.
