# Estado vivo — Cuentivo / CuentivoAI

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
**sustituyendo, no añadiendo**, y se mantiene por debajo de ~100 líneas. Lo que deja de estar
vigente baja a `docs/historia-del-proyecto.md`.

No es un source of truth. Manda, en este orden:

1. [`docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md) — arquitectura, seguridad, fases, GO/NO-GO y changelog de CuentivoAI.
2. [`PROJECT.md`](PROJECT.md) — dirección de producto y checkpoint.
3. El código y sus pruebas para comportamiento real.
4. `CLAUDE.md` para arquitectura del repo y trampas del entorno — **verifícalo contra el código**; ha estado desactualizado.

**Última actualización:** 2026-09-17. **v41 (`1.21.0`) subida a prueba INTERNA; Diego la está probando.**

**2026-09-20 — estado de cuenta por imagen.** En `diegodevxd/mensaje-al-cliente`: el mensaje al
cliente ya no invierte los signos, y al compartir el resumen va **imagen + texto**. La imagen lleva
los últimos 10 movimientos, `y N movimientos anteriores`, el saldo real y la fecha del estado de
cuenta; el sello de Cuentivo sólo en plan Gratis. **Falta lo único que no se puede automatizar:**
que Diego confirme en su teléfono si WhatsApp respeta el texto junto a la imagen o lo convierte en
pie de foto. Muestras en `dist/muestras-estado-cuenta/`.

**Dónde está cada cosa ahora mismo:**

- **Play.** `v36` en **producción** (desde 2026-08-31). `v37` / `1.19.0` en **cerrada**. `v40` /
  `1.20.2` y ahora **`v41` / `1.21.0`** en **interna**. `versionCode` 38 a 41 gastados: el próximo es
  **42**. El AAB está en `dist/Cuentivo-v41-1.21.0.aab`, llave SHA1 `25:05:8A:…:1D:BF`.
- **Functions:** `extractLedgerPage(us-south1)` **desplegado desde `81194a7`** el 2026-09-17, con
  contrato **v5** (y v3/v4 idénticos), `gemini-3.5-flash`, `thinkingBudget: 0` y
  `maxOutputTokens: 32768`. **Probado contra la API real** con el v40 de Play: tres libretas leídas,
  sin fallos del proveedor en los registros. Versión anterior, para volver atrás: `6629485`.
- **Ruta de salida de la v41 (decidida):** interna → unos días de uso real → **cerrada** →
  producción, **promoviendo el mismo AAB**. No directo a producción: la v41 cambió el núcleo del
  registro y su parte propia (v5, página entera, objeciones) **nunca ha corrido en un teléfono real**.
- La política de privacidad de la web está actualizada.

**Reparto de agentes, cambiado el 2026-09-16:** Claude orquesta y verifica. **OpenCode con DeepSeek
V4.1 Flash implementa** (en modo Orchestrator su subagente `fixer` entregó a medias tres rondas
seguidas; pedirle que **no delegue**). **AGY con Gemini 3.8 Flash (High) es la segunda opinión**, sólo
lectura. **Codex/Sol quedó fuera** por sus límites de tokens. A los dos se les pide el reporte en un
archivo de `C:/tmp/`, porque sus pantallas no guardan historial.

## 2026-09-08 — v39, fases 1 y 2: el borrador sobrevive y ya se puede medir

**Fase 1 (`0bba4dc`, `82df2c0`).** El estado de la revisión vivía en `remember`: al recrearse la
Activity se perdía la lectura entera y las correcciones, y como la foto sobrevive en disco la única
salida era volver a leerla — **otra llamada a Gemini, que se paga**. Ahora va en `rememberSaveable`
con un `Saver` propio, y **al restaurar no se llama al callable ni una vez**. Se guarda sólo lo
irrepetible; los candidatos, los 552 productos y los saldos se recargan de Room porque un `Bundle`
no los aguanta.

Además, la fecha de hoja que el servidor detecta ya no se tira: **se ofrece, nunca se aplica sola**,
ni con confianza alta.

**Dos correcciones que importan más que el arreglo:**

- **El diagnóstico de Claude estaba mal.** Dijo dos veces que esto pasaba al *girar el teléfono*.
  `MainActivity` declara `configChanges="orientation|screenSize"`, así que **rotar no la recrea**. Lo
  encontró OpenCode al necesitar un host de depuración sin ese atributo para poder probar el camino.
  Lo que sí recrea: que el sistema mate el proceso, y los cambios de configuración que ese atributo
  no absorbe (idioma, tema, densidad, tamaño de fuente, pantalla partida).
- **Sol encontró que el espejo del borrador estaba en un `LaunchedEffect`**, que es una corrutina y se
  despacha *después* de la composición: una recreación en esa ventana guardaba la instantánea
  anterior. Ahora es `SideEffect`. **Ninguna prueba que espere a que Compose quede idle ve esa
  ventana** — hizo falta que alguien leyera el código.

**Fase 2 (`4fd46ae`).** Tabla `ai_ledger_import_metrics`, **una fila por importación** (nunca por
renglón: eso sería la libreta reconstruible). 23 columnas, de las que **sólo `importId` y `status`
son TEXT** — verificado contra el `createSql` del esquema exportado, no contra la clase. No aparece
en `sync`: no sale del teléfono. Retención de 90 días.

**El fallo de privacidad que Sol encontró en el plan, y que Claude no vio:** una marca de tiempo
exacta **es una clave de unión**. Guardar el instante al milisegundo permite cruzar la fila contra
las ventas creadas en ese momento —que están en la misma base— y sacar de ahí la persona y el monto.
El diseño no guardaba datos de nadie: **los señalaba**. La hora se guarda redondeada, y la confianza
va como histograma agregado, nunca por renglón ni con `lineIndex`, porque un par por renglón en orden
es una huella de la página aunque no lleve un nombre.

La métrica se escribe **en dos tiempos** con id estable (SHA-256 de la sesión): una fila *en curso* al
llegar la extracción y una actualización idempotente al terminar. Así una importación que muere a
medias deja rastro de que existió, y ninguna se cuenta dos veces. La escritura final va en
`GlobalScope` a propósito: lanzarla desde el scope de Compose y cerrar la pantalla la cancelaría.

## 2026-09-16/17 — v41: la CuentivoAI que no se engaña sola

**El problema, medido con libretas reales de dos personas (la mamá y la abuela de Diego):** la
confianza del modelo salía **al revés**. Marcaba dudosos los renglones que estaban bien (casi siempre
por "sin fecha") y dejaba **listos** los que tenían el dinero mal: un precio 10 veces mayor, otro 10
veces menor, y $385.90 cargados a una clienta que ya existía, todos sin marca.

**Lo que se hizo, en cuatro bloques** (encargos en `docs/superpowers/encargos/2026-09-16-v41-*`):

1. **Comprobaciones deterministas en Android que sólo añaden duda** (`7015d65`): cliente existente
   cuyo nombre no sale del propio renglón (por `rawText`, sin paréntesis ni palabras del producto),
   precio contra el catálogo por `sku` o nombre, centavos fuera de patrón. Confirmación con huella.
   Y **una página se registra entera o no se registra** (opción B de Diego): la clave de
   idempotencia sale sólo de `(businessId, importSessionId)`. Ese cambio cerró un cargo duplicado
   posible que **ya existía antes de v41**.
2. **La instrucción aprendió cómo se lleva una libreta** (`f929b1d`): comillas, paréntesis como nota,
   fecha que baja, palomitas que no son dígitos, nombres al margen, `rawText` obligatorio.
3. **Presupuesto de salida y contrato v5** (`59af9b8`, `040fd16`, `fbf43c4`, `09697ab`): el tope de
   8192 era un corte garantizado (el peor caso real son ~27.800 tokens); ahora se detecta un corte
   (`TRUNCATED_BY_MAX_TOKENS`). v5 trae `pageTotals`, `pageType` y `marginNote`. Se cazó una **fuga de
   llaves en la rama v4** que habría tumbado al v37 y al v40. Descartes del parser, hoja que no es de
   cuentas y "puede faltar texto" obligan a confirmar la página, en las tres capas.
4. **La foto de lado** (`81194a7`): la orientación EXIF **ya se aplicaba**; sólo faltaban pruebas.

**Gate al cerrar:** JVM **1246**, 0 fallos; instrumentadas de las pantallas y lotes en el emulador en
verde (leídas del XML); `release:check` con **491** unitarias y **9** del emulador.

**Primera prueba real con el servidor nuevo** (v40 de Play, 2026-09-17): en *Semana 9* desaparecieron
todos los errores de dinero (el $999, los $385.90 de Charly, los centavos imposibles). En *Semana 5*
ya salen la fecha de la hoja y los nombres limpios, y el "agotado" va dudoso.

### Qué verificar en la prueba interna antes de promoverla

- Las fotos se leen con v5 sin error.
- Con una objeción pendiente, "Registrar" queda desactivado hasta confirmarla.
- Una página registrada deja los saldos bien, y **reintentar no duplica nada**.
- En *Toño Gasca* aparece el aviso de subtotales ("la hoja dice $200…").
- Con un cliente que ya existe, pide confirmar.

### Pendiente para parches (ninguno bloquea la v41)

- **Bibi $499.90** donde el papel dice $44.90 o $49.90, sin marca. Lo caza la v41 **sólo si** el
  producto 22710 tiene el código en `sku`. **Pregunta abierta a Diego: ¿sus productos tienen el
  código de catálogo en `sku`?**
- *Toño*: falta el abono de $500 del 14 de febrero; tres renglones siguen "sin fecha" aunque la fecha
  de arriba aplicaba; 13 de 14 renglones salen dudosos porque el nombre sólo está arriba.
- *Semana 5*: la hoja no trae año y el modelo puso 2026 por su cuenta.
- "Lore." y "Lore" salen como dos clientes distintos: el normalizador no quita el punto final.
- Los arreglos de instrucción son **parches de servidor**: llegan a todos sin actualizar la app.

## 2026-09-07 — Fase B cerrada: la libreta de papel se lee, se revisa y se registra

Lo que faltaba no era el planificador ni la transacción, que ya estaban. Era todo lo que rodea a la
foto, y cada defecto tenía la misma forma: **algo que fallaba sin decirlo**.

- **Gemini rechazaba la petición entera con `INVALID_ARGUMENT`** por `responseJsonSchema`. Se probó
  el esquema completo y el reducido al subconjunto documentado: los dos, 400. Ya **no se envía
  ninguno**. La forma se pide en la instrucción con un ejemplo literal y la garantía sigue siendo
  `parseLedgerExtraction`. **No lo reintroduzcas sin probarlo contra la API real**: el gate local no
  lo caza, porque el rechazo es del proveedor.
- **La importación de libreta no existía en release.** Su botón estaba anidado dentro de
  `if (ocrEngine != null)` y `debugAiOcrEngine` devuelve `null` en la variante release. Lo encontró
  Codex mirando el código, no una prueba. De paso se quitó el botón de OCR viejo: eran dos botones
  pegados y la gente se confundía.
- **El monto se preguntaba en centavos crudos.** Un `$999` mal leído como 99.90 le llegaba a la
  persona como `9990`, casi idéntico al `999` del papel, en el diálogo cuya única razón de existir
  es comparar los dos. Diego lo confirmó sin verlo. Ahora se pregunta en **pesos**
  (`LedgerAmountInput`), y la instrucción prohíbe partir un entero en pesos y centavos.
- **La clave del lote se podía falsificar con texto libre.** Pegaba renglones con `|` y campos con
  `:`. Un renglón cuya descripción cargue el resto de la cadena produce los mismos bytes que dos
  renglones, y el segundo cobro no se registra nunca, en silencio. Ahora va con prefijo de longitud.
- **El saldo de apertura se sumaba con `sumOf`**, que envuelve. Ahora `Math.addExact` y rechazo.
- **117 filas del outbox estaban aparcadas con `PERMISSION_DENIED`** y no había datos perdidos:
  eran documentos **ya publicados**. `set()` sobre algo que existe es un *update*, y lo financiero
  es create-only. Ahora se pregunta al servidor y se compara el contenido: idéntico se da por
  cumplido, distinto se aparca **diciendo que difiere**, y no poder preguntar es transitorio.
- **Con la instrucción endurecida, Gemini marca casi todo dudoso** — correcto: un valor heredado de
  unas comillas no es un valor leído. Pero un renglón dudoso no es registrable hasta que una persona
  lo confirme, así que la pantalla se quedaba con el botón muerto y "registrar 0 movimientos"
  escrito encima. Ahora el aviso dice cuántos hay, un botón abre el primero y guardar **avanza solo**
  al siguiente.

**La lección que más vale de esta sesión:** el 400 de Gemini se diagnosticó deduciendo del código
fuente durante dos rondas equivocadas, y sólo se resolvió al **imprimir el objeto que de verdad
viaja** y bisecar quitando el schema. Inspecciona lo que se envía, no lo que crees que se envía.

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

## Escaneo dirigido del 2026-09-04: dos defectos más, cerrados en `97ccd62`

Ese mismo día se buscaron a propósito los caminos que sólo se ejercitan en producción, con
revisión cruzada de Codex. Aparecieron dos, y ninguno lo veía la suite.

**El consumidor de RTDN llevaba once horas fallando cada cinco minutos.** El deploy de la
madrugada excluyó `handlePlayRtdnNotification` para esquivar el `--force` que exige su política de
reintentos, y quedó apuntando a la versión 1 del secret. Consecuencia: **nada revocaba
suscripciones**. Se corrigió desplegando las cinco funciones, tras comprobar que las cinco
desplegadas son las cinco del código y que por tanto `--force` no borraba nada.

**`aiEntitlements` lo escribían dos productores que compiten —verificación y RTDN— sin leer el
estado previo, así que la última escritura ganaba siempre.** Falla en las dos direcciones: una
verificación lenta reactivaba una suscripción ya cancelada, y como la app verifica **todas** las
compras owned al arrancar, una expirada apagaba a la vigente. El entitlement guarda ahora
`sourceTokenHash` y toda escritura pasa por `entitlementWriteDecision`:

| Situación | Regla |
|---|---|
| Mismo token | Gana la observación más reciente; el empate nunca concede acceso |
| Otro token, por RTDN | Nunca adopta: una notificación tardía no revoca una compra nueva |
| Otro token, verificación **activa** | Adopta si es más nueva o empata |
| Otro token, verificación **inactiva** | No adopta: un token histórico no revoca al vigente |
| Documento legado sin `sourceTokenHash` | Sólo lo adopta una verificación, y sujeta a la regla temporal |

`updateEntitlement` pasó de `set(merge)` suelto a `runTransaction`, y el RTDN toma el timestamp
**después** de consultar Play. `TOKEN_NOT_BOUND` y `LEGACY_SOURCE_UNRESOLVED` salen por
`logger.warn PLAY_RTDN_UNRESOLVED` con sólo el motivo: no se reintentan —ningún replay crea un
binding— pero dejan de ser invisibles.

**Dos lecciones de método que conviene conservar, porque se contradicen y las dos son ciertas:**

- **Codex rechazó el diff cuatro veces y las cuatro tenía razón**: el legado que aceptaba cualquier
  escritura, el caso del gate que se quería dejar como deuda, la verificación obsoleta que seguía
  siendo obsoleta, y la verificación inactiva de otro token. Ninguna la detecta una prueba: son
  huecos de razonamiento sobre orden de eventos.
- **El gate del emulador cazó lo que ninguna revisión de diseño habría visto**: el `tx.get` del
  entitlement estaba después del `tx.set` del token, y Firestore exige que todas las lecturas
  precedan a las escrituras. Pasaba las 291 unitarias en verde porque el bridge en memoria no
  impone esa regla, y habría roto **todas** las verificaciones de compra en producción.

También quedó verificado que un primer intento de comprobar la honestidad de las pruebas dio un
**falso verde**: el script decía haber neutralizado las guardas y no había reemplazado nada. Contar
las ocurrencias antes y después es parte del procedimiento, no un extra.

**Lo que sigue abierto tras el escaneo:**

- **`verifyAiSubscription.ts` todavía se traga el error real.** El `catch` alrededor de
  `playApi.getSubscription` colapsa cinco causas en `PLAY_API_UNAVAILABLE` sin registrar ninguna.
  Codex encontró el mismo patrón en `firestoreControlPlane.ts:225`, `:293` y `:317`, donde un
  documento ausente y una caída de Firestore lucen igual que el apagador. **Es el pendiente de mayor
  retorno**: diagnosticar el secret vacío costó horas que un `logger.warn` habría ahorrado.
- **Nada vigila que las callables respondan.** Un 401 permanente vivió cinco días sin alerta, y el
  RTDN falló once horas sin que nadie se enterara.
- **Nada vigila que un entitlement se acerque a expirar sin renovarse.**
- **La retención de imágenes en Artifact Registry sigue sin revisar.** Fue la causa de que no se
  pudiera crear ninguna revisión, y nada impide que vuelva a borrarse.
- **La UI concede acceso visual sin confirmación del servidor**: `MainActivity.kt:391` ignora el
  resultado de la verificación y `AiEntryPolicy.kt:26` abre el asistente con el tier local. No es
  brecha —el gateway niega— pero produce la pantalla muerta que originó el reporte.
- Codex **descartó** problemas en `FinancialDao`: las rutas de dinero reservan clave dentro de
  `@Transaction` y las guardas de cancelación son coherentes.

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

**Cuentivo base.** `versionCode 37` / `versionName 1.19.0` **subido a la pista cerrada** el
2026-09-07, firmado con la misma llave que el v36. Antes: `36 / 1.18.0` y, en producción con
lanzamiento gradual, `32 / 1.17.0` desde el 2026-08-26. Room, sync, Billing, Auth y roles operan en
datos reales.

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

Cierre de la Fase B (`caf6e71`, `5dd42e2`, `4bdea0d`, `4d9ee13`, `eb65916`, `8688351`, `79b40c9`,
`608b57c`). Todo reejecutado por Claude, nunca tomado del reporte de un implementador:

- **Functions:** `release:check` **completo** — `npm ci`, lint, **436 pruebas**, **gate del emulador
  de Firestore 9/9**, y compilar desde `lib/` limpio.
- **Android JVM 1063/1063**, contadas desde los XML de `app/build/test-results/`.
- **Instrumentadas en el A059P físico:** `SyncOperationReopenTest` 5/5,
  `LedgerGuidedReviewTest` 5/5, `LedgerImagePreparerTest` 6/6.
- **Verificación por mutación**, porque en este proyecto una prueba en verde ya mintió cinco veces:
  se rompió producción a propósito en cada arreglo y se comprobó que la prueba cae. Una de ellas
  **pasaba con el código roto** — el ejemplo de colisión de claves no colisionaba de verdad — y se
  reconstruyó hasta que falló.
- Paridad de claves **611 = 611** y puerta del idioma limpia (sólo las etiquetas de animación).
- `assembleDebug`, `assembleRelease` y `bundleRelease` en verde.

**Deuda de proceso, anotada porque pasó:** `extractLedgerPage` se desplegó dos veces el 2026-09-07
corriendo sólo `lint` y `npm test`, **sin** `release:check`. El gate se corrió después y pasó, así
que lo desplegado es correcto, pero el orden fue el inverso al que manda `CLAUDE.md`.

**Cómo se corre ahora:** el A059P de Diego está conectado en depuración y **sustituye al emulador**.
`installDebug` actualiza encima **sin desinstalar**. Para correr instrumentadas hay que pasar
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`: sin eso Gradle desinstala al
terminar y **se borra la base local de Diego**. Ya pasó una vez.

**Avisos sobre esta evidencia.** La corrida instrumentada *completa* aborta en
`ProductPhotoSourceTest`: eso **no cuenta como verde**, por eso cada clase se corre por su nombre.
Y `adb devices` puede decir `device` con el sistema a medias; el chequeo bueno es
`dumpsys user | grep RUNNING_UNLOCKED`.

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

**0. Fase B — pasar la libreta de papel con una foto.** Pista activa. Plan en
`docs/superpowers/plans/2026-09-05-cuentivoai-foto-libreta.md`; los encargos y las rondas de
revisión de Codex, en `docs/superpowers/encargos/` (sin versionar).

| Paso | Estado |
|---|---|
| 1. Contrato v3 y schema de lista | **Hecho**, `b30a408` |
| 2. Extracción: imagen a Gemini | **Hecho**, `b3f21f2` |
| 3. Ejecutores: fecha de libreta y cargo sin producto | **Hecho**, `aadbb5c` |
| 4a. Lógica pura de la revisión | **Hecho**, `903d12e` |
| 4b. Pantalla, cámara, ciclo de vida de la foto y fecha | **Hecho**, `90593ed` |
| 5. Registro atómico del lote | **Hecho**, `d8a5891` |
| 6. Prueba física con una libreta real | **Pendiente, lo hace Diego** |

**Lo único que Codex llamó bloqueante para publicar:** la **fecha de página** la fija hoy sin que el
usuario la elija (`LedgerBatchCoordinator`, `pageDateEpochDay = today`). Importar una libreta de
hace meses la registraría entera como de hoy, que es justo la historia que esta función promete
migrar.

**Deuda aceptable que Codex dio por no bloqueante, tareas concretas para AGY:**

- El callback de confirmación no aplica los límites de plan que sí aplica el camino normal de venta.
- `AiLedgerBatchPlanner` duplica la validación financiera del 4a y suma la deuda inicial con
  `sumOf`, sin `Math.addExact`.
- El digest de la clave del lote concatena texto libre con separadores sin escape.
- El DAO, al encontrar una reserva, devuelve el conteo recibido en vez de un resultado almacenado.
- La máquina de estados no sobrevive la recreación de la Activity durante extracción o revisión.

**Decisión de costo pendiente de Diego:** una foto consume la **misma cuota de acciones** que una
frase de texto, y no cuesta lo mismo. Compartir cuota no es equivalencia de gasto.

## Blockers y pendientes vigentes

**Play, al 2026-09-07:** la declaracion de Seguridad de los datos con Audio y Fotos esta enviada a
revision, la web de politica de privacidad **ya esta actualizada**, y el AAB `v37` / `1.19.0` esta
**subido a la pista cerrada**. La frase correcta sobre la foto sigue siendo **"sin archivo
permanente de fotos en Cuentivo"**, nunca "no se guarda en ningun lado": existe el temporal local y
el proveedor retiene registros para supervision de abuso.

**Pendientes de la Fase B, ninguno bloqueante para la prueba cerrada:**

- **La rotacion EXIF no cubre orientaciones espejadas** (`FLIP_HORIZONTAL`, `TRANSPOSE`). Un
  telefono que las escriba manda la pagina al reves.
- **`LedgerImagePreparer.prepareInPlace` sobrescribe el original**, asi que no se puede reintentar
  la lectura con mas detalle: ampliar una imagen ya reducida no recupera trazos.
- **La prueba de rotacion usa una imagen uniforme**, asi que 270 grados pasaria como 90.
- **`OwnerMembershipBootstrap.ensure` llama a `reopenParkedOperations()` a secas**, que desaparca
  *todas* las filas permanentes y no solo las que la membresia explica. Se queda mas ancho de lo
  debido, aunque solo dispare una vez. Lo acoto Codex el 2026-09-07.
- **La maquina de estados de la revision no sobrevive a recrear la Activity.** Girar el telefono a
  media revision pierde el borrador; la foto si sobrevive (`rememberSaveable`).
- **Paso 6 de la Fase B: la prueba fisica de Diego con libretas reales.** Lo que se mide es
  *cliente equivocado sin marca*, *monto equivocado sin marca* y *renglones inventados u omitidos*
  — no el porcentaje de JSON aceptado.

**Decision de producto escrita en una prueba, por si alguien la cuestiona luego:** dejar la revision
a medias **no bloquea** la pagina. Se registra lo confirmado y lo dudoso no se importa. Bloquear
significaria que un renglon que nadie puede leer deja toda la hoja sin registrar. Lo que no puede
pasar es que sea callado: el boton cuenta solo lo que va a escribir y el aviso dice cuantos quedaron.


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
