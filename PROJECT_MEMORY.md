# Estado vivo — Cuentivo / CuentivoAI

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
**sustituyendo, no añadiendo**, y se mantiene por debajo de ~100 líneas. Lo que deja de estar
vigente baja a `docs/historia-del-proyecto.md`.

No es un source of truth. Manda, en este orden:

1. [`docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md) — arquitectura, seguridad, fases, GO/NO-GO y changelog de CuentivoAI.
2. [`PROJECT.md`](PROJECT.md) — dirección de producto y checkpoint.
3. El código y sus pruebas para comportamiento real.
4. `CLAUDE.md` para arquitectura del repo y trampas del entorno — **verifícalo contra el código**; ha estado desactualizado.

**Última actualización:** 2026-08-25, después del gate live de Fase 2 (`a6ffb7b`).

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
- rotación horizontal/vertical conserva el laboratorio y sus respuestas en memoria.

**Creación compuesta cerrada.** Plan y evidencia completa:
`docs/superpowers/plans/2026-08-24-cuentivoai-composite-sale-creation.md`. Las cuatro tasks están
auditadas (`20f4f1e`, `febd8c3`, `32bc03c`, `24014de`); la base real del A059P confirmó la transacción
cliente/producto/venta/abono, campos desconocidos en NULL y cero duplicados.

**Incremento cerrado — consultas naturales deterministas.** Plan:
`docs/superpowers/plans/2026-08-24-cuentivoai-natural-queries.md`. Las cuatro tasks están completas
(`d308cfe`…`b747314`) y las regresiones físicas quedaron corregidas en `5a10b76`, `c1e1486` y
`a4f992d`: el gateway entrega fecha obligatoria en `America/Mexico_City` e instrucciones de dominio
a Gemini, y `MainActivity` conserva la sesión durante rotación sin persistir nuevas respuestas
financieras. `interpretAiRequest(us-south1)` quedó desplegada. APK instalada SHA-256
`AEF6D27E034151E485DFB34DAC33CA75D12868F986DB6F03E3A74E5723548F7F`. Diego confirmó en A059P
que aparece el resumen por período y que al girar ya no sale del chat.

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

Gate live de Fase 2: Functions **225/225**, TypeScript build/lint, Firestore Emulator **2/2**,
Android JVM **767/767** con `--rerun-tasks`, `assembleDebug`, `lintDebug`, prueba física directa
**2/2** y `git diff --check`. A059P probó autorización/replay, 401 sin App Check, cuota, rate limit
y kill switch; cada denegación escribió cero y la configuración volvió a `true / true / 50 / 3`.
Evidencia: `docs/superpowers/operations/2026-08-25-cuentivoai-phase-2-live-validation.md`.

## Orquestamiento

- **Sol/Codex** es la mente principal actual de arquitectura, auditoría, continuidad y gate. Claude
  puede retomar ese rol leyendo este archivo, el source of truth y el plan vigente; no debe asumir
  que un reporte de implementador equivale a evidencia.
- **AGY** implementa. Su reporte **no** cierra una tarea; sólo la cierra evidencia reejecutada de
  forma independiente: pruebas, build/lint cuando aplique, diff e invariantes.
- Dinero, Room, idempotencia o sync exigen TDD, transacción atómica, rollback probado y pruebas
  conectadas en el A059P antes de aprobar.
- Workspace único: `C:/Users/pibed/orca/workspaces/OrganizationLabs-neo-fintech/feature-cuentivo-ai`.
  El workspace estable original no se toca.
- `docs/superpowers/handoffs/` es del usuario, permanece **sin rastrear e intocable**: no agregar,
  borrar ni modificar.

## Qué sigue

1. **Cerrar la parte comercial de Fase 2:** diseñar y probar que Play conceda/revoque el tier AI;
   el entitlement del A059P todavía fue creado manualmente.
2. Encender enforcement de App Check únicamente después de revisar tráfico válido del día.
3. Voz a texto editable y endurecimiento/aceptación física del núcleo. OCR local/cámara ya existe
   parcialmente en el laboratorio.
4. Después del núcleo: cobranza/PDF/WhatsApp/barcode, catálogo y beta limitada, cada uno con plan propio.

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
- **No hay fila en `sync_state`** para el negocio del A059P, aunque el outbox se drenó tras la
  prueba física. No está confirmado que la mitad de *pull* complete. Se verifica mirando si el
  cliente creado aparece en `businesses/{id}/customers` de Firestore.
- `BackupCrypto.ITERATIONS` en 120 000 PBKDF2, por debajo de lo recomendado hoy. Subirlas no rompe
  respaldos viejos porque viajan en la cabecera; medir antes en un teléfono lento.

## Reglas de continuidad

Compilar no es verificar: exigir tests reales, build/lint, diff e invariantes. Si memoria,
documentación y código se contradicen, gana el código y la contradicción se documenta. Sustituir
estado viejo aquí; el histórico vive en `docs/historia-del-proyecto.md`.
