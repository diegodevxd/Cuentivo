# Estado vivo — Cuentivo / CuentivoAI

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
**sustituyendo, no añadiendo**, y se mantiene por debajo de ~100 líneas. Lo que deja de estar
vigente baja a `docs/historia-del-proyecto.md`.

No es un source of truth. Manda, en este orden:

1. [`docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md) — arquitectura, seguridad, fases, GO/NO-GO y changelog de CuentivoAI.
2. [`PROJECT.md`](PROJECT.md) — dirección de producto y checkpoint.
3. El código y sus pruebas para comportamiento real.
4. `CLAUDE.md` para arquitectura del repo y trampas del entorno — **verifícalo contra el código**; ha estado desactualizado.

**Última actualización:** 2026-08-25, después de cerrar el endurecimiento (`d451b08`).

---

## Dónde estamos

**Cuentivo base.** `versionCode 31` / `versionName 1.16.6`, congelado y en prueba cerrada de Play.
`32 / 1.17.0` es candidato futuro, **no autorizado**. Room, sync, Billing, Auth y roles operan en
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

**Núcleo: 4 de 5.** Falta sólo la salida del laboratorio a beta con feature flags.

**Estado de release.** Cuentivo base está **publicada en Play**. **CuentivoAI sigue NO-GO**: sólo
vive en el laboratorio debug, y su AAB, beta pública y activación general continúan cerradas.

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

1. **Endurecimiento y aceptación física del núcleo (N4).** Ahí entra el callejón sin salida del camino
   de abonos: cuando el modelo clasifique mal, debe decir "no tiene deuda registrada, ¿era una venta?"
   y ofrecer la ruta, en vez de mostrar clientes que no son.
2. **Decir por qué se rechazó.** `interpretAiRequest` colapsa seis caminos en un mismo `AI_UNAVAILABLE`
   y ahí se pierden cuota agotada, rate limit, IA apagada y falta de entitlement. Propuesta escrita en
   `.superpowers/sdd/propuesta-motivo-ai-unavailable.md`.
3. **Salida del laboratorio a beta (N5)** con feature flags y observabilidad.
4. Encender enforcement de App Check tras revisar tráfico válido del día, generándolo desde una
   instalación de Play Store.
5. **Parte comercial de Fase 2:** que Play conceda y revoque el tier AI. Diseño escrito y sin
   implementar en `docs/superpowers/specs/2026-08-25-cuentivoai-play-entitlement-design.md`; bloqueado
   porque no existe el producto de suscripción en Play y el precio no debe fijarse antes de la beta.
6. Después del núcleo: cobranza/PDF/WhatsApp/barcode y catálogo, cada uno con plan propio.

Cada punto exige su propio diseño, autorización y gates. Ninguno se adelanta dentro del commit
de otro. Fotos cloud/Storage es un proyecto **separado**, no dependencia de CuentivoAI.

## Blockers y pendientes vigentes

- **El control plane live personal está cerrado, pero no el entitlement comercial.** Cuota,
  hard cap, rate limit y kill switch ya están desplegados/probados; Play todavía no concede ni
  revoca el tier AI server-side, por lo que beta y AAB CuentivoAI continúan bloqueados.
- **El entitlement AI actual fue creado manualmente para el usuario de prueba.** Billing/Play aún
  no concede ni revoca el tier CuentivoAI de forma server-side.
- **El enforcement de App Check sigue apagado.** Falta leer el panel con tráfico posterior al
  vínculo de Play Integrity, mirando la barra del día y no el acumulado. Lo ya descartado con
  evidencia está en el histórico; no volver a perseguirlo.
- **No hay CI** y `.superpowers/` está ignorado: la evidencia SDD local no sobrevive a otro clon.
- Falta enlace al aviso de privacidad dentro de la app; la política externa sí está publicada.
- **Todos los rechazos de IA se ven igual.** Seis caminos devuelven el mismo `AI_UNAVAILABLE`. El
  2026-08-25 costó una hora de diagnóstico descubrir que la cuota mensual estaba agotada. Un usuario
  que pague $299 y agote su cuota leería "aún no disponible", como si la app estuviera descompuesta.
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
