# Estado vivo — Cuentivo

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
sustituyendo, no añadiendo. Lo que deja de estar vigente baja a `docs/historia-del-proyecto.md`.
La arquitectura y las reglas del proyecto viven en `CLAUDE.md`.

**Última actualización:** 2026-08-08

---

## Dónde estamos

- Rama `codex/neo-fintech`, comiteada y subida a `origin`.
- **1.14 / versionCode 20 subida a prueba cerrada** el 2026-08-07, con los 12 testers. Es la
  primera que lleva el semáforo, la tipografía grande y el borrado múltiple. Se les mandó un
  mensaje pidiendo tres cosas concretas: si se lee cómodo, si algún texto se corta, y cómo se ve
  la barra de abajo en un Android viejo.
- **1.15.2 / versionCode 24 SUBIDA el 2026-08-08.** Es la vigente. Cierra tres bugs de la misma
  familia, todos alrededor de `BusinessClaimPolicy`, y el mensaje al tester que los destapó ya se
  envió. Los dos primeros están **verificados en el teléfono de Diego**, no sólo por pruebas:
  1. **`reloadUser` no refrescaba el ID token.** Hacía sólo `user.reload()`, que actualiza el objeto
     local — `isEmailVerified` pasa a true y el cliente deja entrar — pero **no el token**, y
     `firestore.rules` lee `request.auth.token.email_verified`. Un **usuario nuevo** verificaba su
     correo, entraba, y la primera consulta se denegaba: pantalla `Blocked` con un "Reintentar" que
     reemitía la misma consulta con el mismo token viejo. Ahora fuerza `getIdToken(true)`.
  2. **`findBusinessOwnedBy` leía del caché.** Con `.get()` a secas, un teléfono sin red recibe un
     snapshot vacío **sin error**, así que "no pude preguntar" llegaba disfrazado de "esta cuenta no
     tiene nada" y el candado nunca se cerraba — era casi inalcanzable por la única causa para la que
     se escribió. Ahora `.get(Source.SERVER)`.
  3. Cuentas legacy sin verificar ya no consultan la nube (`canQueryCloud`), y un registro hecho en
     **este mismo proceso** puede crear aunque falle el lookup (`accountIsBrandNew`) — sin esa
     exención, exigir servidor dejaba a un usuario nuevo sin señal fuera de una app offline-first.
     La exención se resuelve en memoria y **nunca por reloj**: un reloj mal puesto haría que una
     reinstalación pareciera reciente y mintiera el duplicado que todo esto existe para impedir.
  La pantalla bloqueada ya tiene salida: **"Usar otra cuenta"** además de "Reintentar".
  294 pruebas JVM en verde, `jar verified`, paridad de idiomas 320 = 320.
- **1.15 / versionCode 22 y 1.15.1 / vc23 quedaron superadas.** La 22 estuvo en prueba interna; la 23
  se subió y quedó reemplazada por la 24 el mismo día.
- ✅ **RESUELTO Y VERIFICADO EN DISPOSITIVO: el backfill sube todo.** Con la **1.15 / versionCode 22**
  por el canal de **prueba interna**, actualizada encima del estado roto (sin desinstalar, que era
  la única forma de probar la segunda corrección). Firestore bajo `1668fc61`: **1 miembro** (id = uid,
  OWNER/ACTIVE), **3 clientes**, **3 ventas**, **3 movimientos**. Orden respetado: miembro a las
  `19:54:46.153`, el resto detrás. Las 3 ventas con `dueDateEpochDay = 20687` — el arreglo del alta
  rápida también confirmado en producción.
  **Eran dos bugs, no uno**: (1) el `entityId` del miembro usaba el id local compuesto
  `"businessId:userId"` en vez del uid que exige `match /members/{memberUserId}`; (2) sin
  `reopenParkedOperations()` el arreglo no habría servido a nadie que ya hubiera fallado, porque
  reencolar no revive una fila `PERMANENT` — el id del outbox es hash de los mismos cuatro campos y
  `INSERT IGNORE` la deja estacionada. Ver commit `c05b895`.
  **Lección**: la prueba de exhaustividad cubre que no falte un *tipo*, no que el *id* de un tipo
  cubierto sea correcto. La ruta remota sólo se verifica en dispositivo.
- 📌 **Canal correcto para probar: PRUEBA INTERNA**, no "Uso compartido interno de apps". Prueba
  interna tiene lista de probadores propia, **separada de la prueba cerrada** (los 12 testers de la
  1.14 no reciben nada), y **sí aparece como actualización en Play Store**. Uso compartido interno
  es el de links y no notifica.
- ⚠️ **Dos builds distintos comparten `versionName` 1.15** (versionCode 21 con el bug, 22 con el
  arreglo). Play distingue por `versionCode`, así que funciona, pero en la Play Store se ven igual.
  Para saber cuál hay instalada: `adb shell dumpsys package com.cuentivo.app | grep versionCode`.
  **Regla para adelante**: si el cambio importa lo bastante como para querer distinguirlo, subir
  también el `versionName`.
- 🔴 **HISTÓRICO (ya resuelto): el backfill sólo subía el negocio.**
  Probado en teléfono el 2026-08-07 con la 1.15 instalada por **Uso compartido interno**
  (`versionCode=21`, `installerPackageName=com.android.vending`, así que la prueba es válida).
  Secuencia: crear negocio + 3 clientes en Gratis → suscribirse a Pro. Resultado en Firestore:
  **sólo `businesses/1668fc61-c23c-43cc-8481-2cd2b3840a49`**, con **cero** `members`, `customers`,
  `sales` y `movements`.
  Que el negocio sí subiera demuestra que `OutboxBackfill` **corrió** y que el worker publicó al
  menos una fila; se rompe **después de la primera**.
  **Hipótesis sin confirmar**: la escritura de `MEMBER` se deniega y, como `firestore.rules` gatea
  cada colección tras el documento de miembro (`isMember`), todo lo demás cae detrás — y una
  denegación se **estaciona como permanente**, no se reintenta. Revisar primero `validMember` y el
  bloque `match /businesses/{businessId}/members/{uid}`, y `SyncOperationDao.pending` por si el
  orden real no es el que se asumió.
  **No se pudo obtener ni una línea de log de la app en release** (`adb logcat --pid`): sin eso no
  hay diagnóstico, sólo sospecha. Primer paso de la próxima sesión: conseguir esos logs.
- ✅ **Caso 1 del plan SÍ pasa**: en Gratis no se encola nada. Negocio + 2 clientes creados en
  Gratis, desinstalar/reinstalar → Firestore siguió en **cero** negocios. El gate de `SyncQueue`
  funciona. (Que los clientes no vuelvan en Gratis es lo correcto, no un fallo.)
- ✅ **Cerrada la otra mitad del ciclo de cobro**: el reembolso/revocación desde Play Console quitó
  el derecho y **la app volvió a Gratis sola**. Era el pendiente que llevaba desde el 2026-08-06.
- ✅ **Caso 3 (adopción) VERIFICADO**: desinstalar, reinstalar e iniciar sesión devolvió los datos y
  dejó **un solo** negocio — el **mismo** `1668fc61`, con `createdAtEpochMillis` idéntico. Que el id
  y el timestamp no cambien es la prueba de que lo **adoptó** en vez de recrearlo. Los tres casos
  probables del plan quedan verificados en dispositivo.
- ⚠️ Sigue sin probar sólo el caso `Blocked`, que **no se puede provocar a mano** (necesita sesión válida + Room vacío + lookup fallido
  a la vez, y sin red no se puede ni iniciar sesión): queda cubierto sólo por `BusinessClaimPolicyTest`.
- **✅ Los 9 negocios duplicados fueron borrados** el 2026-08-07 con
  `firebase firestore:delete --recursive` (CLI vía `npx`, sesión `capihwlabs@gmail.com`). Verificado:
  la consulta por `ownerUserId` devuelve vacío y las subcolecciones también.
- **✅ Arreglada la causa de los duplicados.** Consultar la nube dejó de ser parte de *restaurar*
  (Pro) y pasó a ser parte de **reclamar** (todos los planes): preguntar si ya tienes un negocio es
  integridad de datos, no función de pago; bajar el libro mayor sigue siendo Pro. `domain/
  BusinessClaimPolicy.kt` decide entre `UseLocal` / `AdoptRemote` / `CreateNew` / **`Blocked`**.
  `Blocked` es lo importante: **si el lookup falla, no se crea nada**. Antes "no sé" se trataba como
  "no tienes", y bastaba mala conexión —sin plan Gratis ni reinstalación— para acuñar un duplicado.
  En Gratis se adopta sólo la fila del negocio, con los timestamps del remoto para que no pueda
  ganarle una fusión y renombrarlo.
- **`findBusinessOwnedBy` ahora elige el MÁS ANTIGUO**, no el más nuevo. Elegir el más nuevo era al
  revés: un duplicado es por definición el creado después, así que devolvía el error del usuario y
  dejaba inalcanzable el libro con la historia real. Desempate determinista por id, o dos teléfonos
  adoptarían ids distintos.
- **En Gratis ya no se encola nada a la nube.** El gate vive **dentro de `SyncQueue`**, no en los 26
  call sites — repartir el `if` es garantía de olvidar uno. Antes el outbox se llenaba igual y
  `SyncEntitlement` sólo frenaba al *Worker*: todo lo acumulado se publicaba de golpe al confirmarse
  Pro, incluidos negocios abandonados hacía reinstalaciones. Al pasar a Pro, `sync/OutboxBackfill.kt`
  siembra la cola desde Room en un solo paso; es idempotente porque el id es
  `SHA-256(businessId|entityType|entityId|revision)` con `INSERT IGNORE`.
- ⚠️ **El backfill duplica los payloads de las rutas de escritura de `MainActivity`.** Si se agrega
  una columna a una entidad **hay que añadirla en los dos sitios**: la prueba de exhaustividad
  detecta un **tipo** faltante, no un **campo** faltante.
- Consultas nuevas `allForBackfill` / `*ForBackfill` en 4 DAOs, **sin migración**: Room sigue en 4.
- **✅ El alta rápida ya pide fecha pactada.** Era el último hueco del semáforo: `MainActivity`
  guardaba `dueDateEpochDay = null` a fuerza, así que toda venta por la ruta rápida quedaba fuera
  del score para siempre. `QuickSale` ahora lleva `dueInDays` (días, no fecha — el dominio sigue
  sin reloj) y el host lo resuelve contra `LocalDate.now()`. `DueOption` pasó de `private` a
  `internal` y se comparte con `NewSaleScreen` en vez de duplicar la lista de plazos. **Iba también
  un bug de nube**: el `syncQueue.enqueue` de esa ruta mandaba `"dueDateEpochDay" to null` fijo, así
  que aunque se hubiera guardado bien en local, la copia remota habría perdido la fecha.
- **El texto del respaldo ya distingue el plan.** `backup_detail` decía "tus datos viven solo en
  este teléfono" a un suscriptor Pro, justo encima de la tarjeta que le vende sincronización en la
  nube. Nueva clave `backup_detail_cloud`, elegida con el **mismo `tier`** del que se dibuja la
  tarjeta del plan, para que las dos mitades de la pantalla no puedan volver a contradecirse.
- **La compra de Pro funciona de punta a punta**, y **actualizar de versión no borra datos**
  (ambos verificados al pasar de 1.5 a 1.6). Una compra pertenece a la cuenta de Google, no a la
  versión.
- **Fase 1 cerrada y entregada** (tandas 4–7). Importar Excel verificado contra el archivo real de
  Diego: 31 filas → 30 productos, 0 rechazados.
- **✅ La recuperación desde la nube FUNCIONA** (verificado 2026-08-06): desinstalar → reinstalar
  → iniciar sesión devuelve clientes y productos solos. Es la evidencia de que lo que cobra Pro
  sirve de punta a punta. **`restore()` corre en `Dispatchers.IO` y ahí se queda**: `Tasks.await`
  es bloqueante y Play Services lo rechaza en el hilo principal.
- Suites en verde en la última corrida: **288 pruebas JVM**, 0 fallos, con `assembleDebug`,
  `assembleDebugAndroidTest`, `assembleRelease` y `bundleRelease` exitosos (2026-08-07). Las
  instrumentadas compilan pero **no se han corrido**: no hay emulador levantado y no se pueden
  correr contra el teléfono de Diego.
- Room sigue en `version = 4`. Firebase: proyecto `organitazionlabs` (600636825165), plan Spark.
- Paridad de textos es/en: **320 `<string>` + 3 `<plurals>` en cada idioma**.
- ⚠️ **La puerta del idioma de `CLAUDE.md` no ve texto dentro de un `if`.** El apartado de plan
  llevaba todo su copy en español duro (`text = if (free) "Plan Gratis" else ...`) y el grep nunca
  lo marcó, porque sólo detecta `text = "` seguido de letras. Con la app en inglés seguía saliendo
  en español. Corregido el 2026-08-07, pero **el grep solo no basta para auditar idiomas**.
- **✅ 🟢🟡🔴 El semáforo de clientes FUNCIONA en teléfono real.** Verificado por Diego el 2026-08-07
  con la **1.11 / versionCode 17** instalada de lado. `domain/CustomerScoreCalculator.kt` deriva la
  puntualidad del libro mayor: puntual ≤ 3 días de retraso promedio, tarda ≤ 20, moroso arriba; y
  una venta abierta con más de 60 días vencida fuerza moroso pese al promedio. Menos de 3 ventas
  **con fecha pactada** no pinta color: sin evidencia no hay veredicto. Sale de
  `FinancialDao.saleHistoryForScores`, consulta nueva sin migración — Room sigue en `version = 4`.
- **El semáforo nacía apagado para todos, y no era culpa del cálculo.** Las dos rutas de venta
  guardaban la fecha pactada vacía, así que ninguna venta podía puntuar nunca.
  `NewSaleScreen` arrancaba en `DueOption.NONE`; ahora arranca en **15 días** (2026-08-07). Corregir
  una venta **sigue** arrancando sin fecha: cambiar un producto equivocado no puede inventarle al
  cliente un plazo que jamás acordó. Lección que se repite: una función puede estar correcta,
  probada y aun así ser invisible porque nadie alimenta el dato del que vive.
- **✅ Bloque de diseño para adultos mayores** (2026-08-07, aprobado por Diego en teléfono real).
  El usuario objetivo son sus papás y gente con lentes, y eso decidió todo:
  - **Tipografía un escalón por encima del estándar de Material**, no sólo hasta él: cuerpo
    12 → 16sp, navbar 10 → 13sp. Costo aceptado: ~4 filas por pantalla en vez de ~6.
  - **17 alturas fijas pasaron a `heightIn(min = ...)`**, o las letras grandes se cortan. Regalo:
    la app crece con el ajuste de fuente del sistema. ⚠️ Trampa que dejó: un `fillMaxSize()` dentro
    de una tarjeta con altura mínima se queda sin qué llenar y pega el contenido arriba — se
    resuelve con `height(IntrinsicSize.Min)`. Corregido en `CustomersScreen`; **puede quedar el
    mismo patrón en filas que no se revisaron a fondo**.
  - **El botón de la cuenta dice "Tu cuenta"** con ícono de persona; antes era la insignia "C" más
    la palabra "Cuentivo" y se leía como logotipo, no como control.
  - **Navbar claymórfico hecho sólo con degradados y sombras, sin `Modifier.blur`**: blur es
    `RenderEffect`, no dibuja nada por debajo de API 31, y la app arranca en `minSdk 24`.
  - **Las pantallas entran animadas**, sólo entrada: `content` se dibuja del estado actual del
    host, así que animar la salida cruzaría la pantalla nueva consigo misma.
- **El apartado de plan ya se compara y trae precio real.** Antes: una sola tarjeta —la del plan que
  ya tenías— sin precio, así que no se veía lo que *no* tienes. Ahora los dos planes en pantalla,
  el tuyo con insignia. El precio sale de `ProductDetails.formattedPrice`, en la moneda de cada
  país; si Play no contesta se apunta a Play en vez de inventar cifra. Nunca escrito a mano.
- **Los productos se borran por selección** (mantener presionado). El lote llama `onArchiveProduct`
  una vez por producto: archivar es idempotente y no hizo falta camino nuevo en el host.

## Qué sigue, en orden de valor

1. **Esperar la retro de la 1.15.2 / vc24**, ya subida y anunciada. Si alguien reporta algo raro
   **sin señal**, mirar primero el restore de Pro (ver Pendientes de fondo): es el único hueco de esa
   familia que queda abierto. Aviso que sigue vigente: "gana el más antiguo" puede hacer que un
   tester con duplicados previos aterrice en un negocio distinto del que venía usando — es lo
   correcto, pero si reporta "me cambiaron los datos", es esto.
2. **Escuchar lo que reporten los testers de la 1.14**, sobre todo si algún texto se corta con la
   tipografía nueva y cómo se ve el navbar en un Android 11 o menor — eso nunca se ha visto, aquí
   sólo hay imagen API 37.
3. **Revisar el panel de App Check** (Firebase → App Check → Métricas), ~2–3 días después del
   2026-08-06, cuando los testers hayan actualizado. Si casi todas las peticiones salen
   *verificadas*, **activar el enforcement**. Si salen *no verificadas*, ver el primer blocker.
4. **YA HECHO — ciclo de cobro cerrado**: ver que al **caducar** la suscripción la app vuelve
   a FREE. Es lo más valioso que queda sin evidencia, y ya se sabe **por qué no se había podido
   probar**: el derecho de la compra de prueba sigue otorgado del lado de Google. Requiere
   **revocar** desde Play Console → Suscripciones → Pedidos (cancelar sólo detiene renovaciones, no
   quita el acceso). Diego tiene 3 clientes y datos en la nube, así que es buen caso real para ver
   también que `CloudRestorePolicy` devolviendo `NotEntitled` se comunique con claridad y no
   parezca pérdida de datos.
5. **Las tres mejoras acordadas para después**, en este orden de valor/esfuerzo:
   - **📷 Código de barras** con ML Kit (gratis, on-device, funciona sin internet). Encaja en
     `domain/QuickSale.kt` y en el alta de productos.
   - **📈 Gráficas** en el panel. Baratas, pero decorativas: `BusinessInsights.compare()` ya dice en
     palabras lo que la gráfica diría en pixeles.
6. **Cerrar el hueco de la tanda 6**: productos más vendidos y ranking de deudores. Requiere una
   consulta nueva sobre `sale_items` unido a `sales` no canceladas por periodo. Alimenta tanto el
   panel como el score de clientes.

## Blockers abiertos

- **El enforcement de App Check está APAGADO a propósito.** Los testers en versiones viejas no
  llevan App Check: activarlo antes de que actualicen les daría `PERMISSION_DENIED`.
- **No se pueden correr las pruebas instrumentadas contra el teléfono de Diego.** No hay
  `applicationIdSuffix` en debug, así que el APK de prueba comparte `applicationId` con el de Play
  pero lleva otra firma: instalarlo exige desinstalar la versión de Play y **borra justo el estado
  que se quiere probar**, además de dejar a Play Billing sin ver la suscripción.
- ✅ **RESUELTO (2026-08-08): el arreglo de duplicados ya está probado en dispositivo.** Se creía
  imposible de montar a mano — exige sesión válida + Room vacío + lookup fallido a la vez. Sí se
  puede, en un build debug: `adb shell run-as com.cuentivo.app rm ./databases/cuentivo.db{,-wal,-shm}`
  borra Room y **deja viva** la sesión de Firebase (vive en `shared_prefs`), que es exactamente el
  estado de una reinstalación. Con modo avión encima, el log dio `UNAVAILABLE` y la app bloqueó en
  vez de crear — **con el caché de Firestore todavía lleno en el teléfono**, que es la prueba de que
  `Source.SERVER` hace lo que dice. Respaldar `databases/` antes y restaurar después; un comando por
  llamada, sin `sh -c`. El único caso que **sigue** sin poderse montar es "usuario nuevo sin señal":
  registrarse y verificar exigen internet.
- **La caducidad de Pro nunca se ha visto ocurrir**, y el 2026-08-07 se averiguó por qué. Una serie
  de compras de prueba cancelada la noche anterior (último pedido 7:25 p.m., renovaciones cada 5
  min) **seguía otorgando Pro 13 horas después**, incluso tras desinstalar la app y limpiar la
  caché de Play Store — o sea que viene del servidor de Google, no de estado viejo en el teléfono.
  La app está bien: el tier no se guarda en Firestore ni en Room, `EntitlementStore.currentTier` ni
  siquiera se usa, y `tierState` arranca en FREE, así que un Pro persistente sólo puede venir de la
  respuesta viva de Play. **Ante un "se quedó en Pro", descartar el entorno de prueba antes de
  buscar el bug en el código.**
- **Play Billing no registra nada en logcat en builds release.** El `acknowledge` se lee del
  código (`BillingGateway.kt`) o de Play Console. Las compras de prueba **no aparecen en los
  informes de ingresos**, sólo en la vista de suscripciones — eso no es un fallo.
- **Instalar de lado deja `installerPackageName=null`**, y entonces Play Billing puede no ver la
  suscripción ni entregar el precio de Pro. Para probar cobros hay que instalar desde Play.

## Pendientes de fondo

- **El restore de Pro todavía lee del caché.** `FirestoreSyncGateway.download` usa `.get()` sin
  `Source`, así que si la señal se cae a media descarga Firestore contesta desde un caché vacío
  **sin error**: `NothingInCloud`, silencio total, y `restoreAttempted = true` impide reintentar.
  El usuario se queda con el negocio adoptado y el ledger vacío, creyendo que perdió sus datos. Es
  el mismo error que ya se corrigió en `findBusinessOwnedBy` (2026-08-08, `Source.SERVER`), en la
  ruta que más duele. Arreglo: `Source.SERVER` ahí también, o no marcar `restoreAttempted` cuando
  la respuesta venga del caché.
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

- **El OCR de libretas y la IA conversacional quedan archivados** (evaluados el 2026-08-06). El OCR
  choca con la regla 2: adivinar mal un cargo deja cicatriz permanente, porque nada financiero se
  edita ni se borra. Si algún día se hace, va como **borrador que el dueño confirma línea por
  línea**, reusando el patrón de `LegacyMigrationPlanner` ("planifica pero nunca escribe"). La IA de
  preguntas se descarta por costo por uso y porque mandar saldos a un modelo externo viola la regla
  4 — y porque "¿quién me debe más?" es SQL, no IA. También descartados: **LibreTranslate** (hay 304
  claves traducidas a mano), **Drive API** (ya hay Firestore + respaldo cifrado), **Calendar API**
  (`AppAlerts` ya lo hace sin pedir permisos) y **ExchangeRate** (contradice `Money.format` es-MX).
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
