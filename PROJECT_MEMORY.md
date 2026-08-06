# Estado vivo — Cuentivo

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
sustituyendo, no añadiendo. Lo que deja de estar vigente baja a `docs/historia-del-proyecto.md`.
La arquitectura y las reglas del proyecto viven en `CLAUDE.md`.

**Última actualización:** 2026-08-06

---

## Dónde estamos

- Rama `codex/neo-fintech`, comiteada y subida a `origin`.
- **1.5 / versionCode 9 publicada en prueba cerrada** con 12 testers. Lo que se rompa, lo ven ellos.
- **1.6 / versionCode 10 publicada en prueba interna** e instalada desde Play en el teléfono de
  Diego (`installerPackageName=com.android.vending`). Lleva la reconexión de Play Billing
  (`domain/BillingReconnectPolicy.kt`): antes, una sola desconexión del servicio dejaba el botón de
  comprar inservible hasta reiniciar la app.
- **La compra de Pro funciona de punta a punta.** Primera vez que hay evidencia. La suscripción
  comprada en la 1.5 la reconoció la 1.6 sin reinstalar nada: una compra pertenece a la cuenta de
  Google, no a la versión.
- **Actualizar de versión no borra datos.** Verificado por Diego al pasar de 1.5 a 1.6: clientes,
  ventas e importación de Excel intactos. Room migró sin incidentes.
- **Fase 1 cerrada y entregada.** La 1.5 llevó a los testers las tandas 4–7: alta rápida de venta,
  respaldo cifrado, cobros del día y resumen del mes, App Check y ficha de Play.
- **Importar Excel estaba roto para archivos con prefijo de namespace** (`<x:row>` en vez de
  `<row>`, ambos válidos en OOXML): `XlsxReader` no encontraba ninguna fila y reportaba 0 productos
  sin un solo error, que es como se ve un archivo vacío. Corregido el 2026-08-06 y **verificado
  contra el archivo real de Diego: 31 filas → 30 productos aceptados, 0 rechazados**, con acentos y
  precios correctos.
- Suites en verde en la última corrida: **260 pruebas JVM**, 0 fallos, con `assembleDebug` y
  `assembleDebugAndroidTest` exitosos (2026-08-06). Instrumentadas: 216 + `CloudRestoreTest`, sin
  correr todavía en emulador.
- Room sigue en `version = 4`. Firebase: proyecto `organitazionlabs` (600636825165), plan Spark.
- Paridad de textos es/en: **297 claves en cada idioma**.

## Qué sigue, en orden de valor

1. **Probar la restauración en el teléfono** (es el blocker de mayor valor): `firebase deploy --only
   firestore:rules`, luego desinstalar / reinstalar / iniciar sesión y confirmar que los clientes
   vuelven solos sin tocar "Crear negocio". Hasta que eso se vea, la nube de Pro no está probada.
2. **Revisar el panel de App Check** (Firebase → App Check → Métricas), ~2–3 días después del
   2026-08-06, cuando los testers hayan actualizado. Si casi todas las peticiones salen
   *verificadas*, **activar el enforcement**. Si salen *no verificadas*, ver el primer blocker.
3. **Cerrar la otra mitad del ciclo de cobro**: ver que al **caducar** la suscripción la app vuelve
   a FREE. La compra ya está probada; la caducidad no. Es rápido de verificar porque una
   suscripción de prueba renueva cada ~5 min y caduca tras unas 6 renovaciones: dejarla correr
   media hora y comprobar que vuelve a frenar en 5 clientes.
4. **Las tres mejoras acordadas para después**, en este orden de valor/esfuerzo:
   - **🟢 Semáforo y score de clientes** — puntualidad de pago por cliente. SQL sobre datos que ya
     existen, cero dependencias nuevas, y responde la pregunta que importa en un negocio de fiado:
     *¿a este le fío?*. Medio camino hecho en `domain/CreditLimitAdvisor.kt`.
   - **📷 Código de barras** con ML Kit (gratis, on-device, funciona sin internet). Encaja en
     `domain/QuickSale.kt` y en el alta de productos.
   - **📈 Gráficas** en el panel. Baratas, pero decorativas: `BusinessInsights.compare()` ya dice en
     palabras lo que la gráfica diría en pixeles.
5. **Cerrar el hueco de la tanda 6**: productos más vendidos y ranking de deudores. Requiere una
   consulta nueva sobre `sale_items` unido a `sales` no canceladas por periodo. Alimenta tanto el
   panel como el score de clientes.

## Blockers abiertos

- **El enforcement de App Check está APAGADO a propósito.** Los testers en versiones viejas no
  llevan App Check: activarlo antes de que actualicen les daría `PERMISSION_DENIED`.
- **⚠️ La restauración desde la nube sigue sin probarse en el teléfono.** La 1.7 no restauró, y la
  inspección de Firestore del 2026-08-06 dio evidencia dura de por qué. **La subida funciona
  perfecto**: el uid `6A4cRF7cIkbzCBBfnoWZMzt2yYE2` posee **cuatro** negocios (19:59, 20:14, 20:21 y
  21:00) y los 7 clientes de prueba están íntegros en el más reciente, `5b0d730b…`. El problema es
  sólo la recuperación, y eran dos fallos encadenados, corregidos en la 1.8:
  1. `findBusinessOwnedBy` usaba `limit(1)` **sin ordenar**. Firestore responde por id de documento,
     que es un UUID aleatorio, así que elegía un negocio arbitrario — en este caso el vacío de las
     19:59. Aun restaurando bien, la app habría abierto vacía. Ahora trae los del dueño y se queda
     con el de `createdAtEpochMillis` mayor, comparado en memoria para **no exigir un índice
     compuesto** (`whereEqualTo` + `orderBy` sí lo exigiría, y sin desplegarlo la consulta falla
     entera con FAILED_PRECONDITION).
  2. **La carrera que realmente ocurrió a las 21:00**: `tierState` arranca en FREE y "plan gratis" era
     indistinguible de "Play todavía no contesta", así que "Crear negocio" quedaba alcanzable a
     segundos de abrir. Diego lo tocó antes de que Play confirmara Pro y acuñó el cuarto negocio.
     Ahora `entitlementResolvedState` separa ambos casos y un dispositivo vacío espera a Play hasta
     `ENTITLEMENT_WAIT_MILLIS` (8 s) antes de ofrecer nada.
  **Sigue faltando la evidencia en dispositivo**: instalar la 1.8 desde Play, desinstalar,
  reinstalar, iniciar sesión y ver volver los clientes. Hasta verlo, esto no está cerrado.
- **No se pueden correr las pruebas instrumentadas contra el teléfono de Diego.** No hay
  `applicationIdSuffix` en debug, así que el APK de prueba comparte `applicationId` con el de Play
  pero lleva otra firma: instalarlo exige desinstalar la versión de Play y **borra justo el estado
  que se quiere probar**, además de dejar a Play Billing sin ver la suscripción.
- **`firestore.rules` cambió y hay que desplegarlo antes de publicar la app.** `/businesses/{id}`
  ahora también permite leer al dueño (`isOwner(resource.data.ownerUserId)`), sin lo cual un
  teléfono reinstalado no puede encontrar su negocio: `canReadBusiness` exige el documento de
  miembro, cuyo id es justo lo que se perdió. Sólo amplía acceso del dueño a lo suyo, así que es
  seguro desplegarlo antes de que los testers actualicen.
- **La caducidad de Pro nunca se ha visto ocurrir.** Que quien deja de pagar deje de tener nube es
  la mitad del ciclo de cobro que sigue sin evidencia.
- ~~La SHA-256 podría ser la de la clave de carga~~ **RESUELTO el 2026-08-06**: el logcat del
  teléfono mostró `Finsky: requestIntegrityToken() finished for com.cuentivo.app`. Play Integrity
  emite token en producción, así que la SHA registrada es la correcta.
- **La librería de Play Billing no registra nada en logcat en builds release.** No busques ahí la
  confirmación del `acknowledge`: se lee del código (`BillingGateway.kt:238-261`, que reconoce toda
  compra pendiente en cada arranque) o de Play Console. Las compras de prueba **no aparecen en los
  informes de ingresos**, sólo en la vista de suscripciones — no confundir eso con un fallo.

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
