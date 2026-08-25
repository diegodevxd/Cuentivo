# Estado vivo — Cuentivo / CuentivoAI

Este archivo dice **dónde está el proyecto hoy y qué sigue**. No es un diario: se actualiza
**sustituyendo, no añadiendo**, y se mantiene por debajo de ~100 líneas. Lo que deja de estar
vigente baja a `docs/historia-del-proyecto.md`.

No es un source of truth. Manda, en este orden:

1. [`docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md`](docs/superpowers/plans/2026-08-14-cuentivoai-source-of-truth.md) — arquitectura, seguridad, fases, GO/NO-GO y changelog de CuentivoAI.
2. [`PROJECT.md`](PROJECT.md) — dirección de producto y checkpoint.
3. El código y sus pruebas para comportamiento real.
4. `CLAUDE.md` para arquitectura del repo y trampas del entorno — **verifícalo contra el código**; ha estado desactualizado.

**Última actualización:** 2026-08-24, sobre `24014de`.

---

## Dónde estamos

**Cuentivo base.** `versionCode 31` / `versionName 1.16.6`, congelado y en prueba cerrada de Play.
`32 / 1.17.0` es candidato futuro, **no autorizado**. Room, sync, Billing, Auth y roles operan en
datos reales.

**CuentivoAI.** Rama aislada `diegodevxd/feature-cuentivo-ai`. El gateway server-side existe: la
key de Gemini vive en Secret Manager, la facturación Blaze está configurada y únicamente
`interpretAiRequest` quedó desplegada en `us-south1`; `authorizeAiAction` **no** está desplegada.
El APK debug habla con ese gateway vía Firebase Auth y App Check. Ninguna key entra al APK.

Aceptado físicamente en el A059P, no sólo por pruebas:

- abono natural: $500 → $150 → $350 sobre la deuda real de una clienta;
- venta con cliente y producto **existentes**, con abono inicial.

**Incremento cerrado — creación compuesta.** Una sola frase crea el cliente y los productos que
faltan dentro de la misma preview y la misma transacción. Plan:
`docs/superpowers/plans/2026-08-24-cuentivoai-composite-sale-creation.md`. Las cuatro tasks están
completas y auditadas (`20f4f1e`, `febd8c3`, `32bc03c`, `24014de`), y la **aceptación física está
observada en la base real del A059P**, no reportada de palabra: dos frases produjeron 2 clientes,
2 productos, 2 ventas, 2 ítems, 2 abonos, 2 asignaciones, 4 movimientos y 4 operaciones — cero
duplicados. "Ana se llevó dos cremas de $120 y pagó $100" guardó `crema` a 12000 centavos, venta de
24000, abono de 10000 y saldo derivado de 14000. `costPriceCents`, `stock`, `category` y `sku`
quedaron en NULL: no se inventó nada.

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

## Hechos del código verificados el 2026-08-24

Estos estaban mal en la documentación y se corrigieron contra el código:

- Room: `version = 6`, 14 entidades, 9 DAOs. Tres migraciones — `MIGRATION_3_4`, `MIGRATION_4_5`,
  `MIGRATION_5_6` — en `CUENTIVO_MIGRATIONS`. Sin `fallbackToDestructiveMigration()`.
- **Tres** builders de `"cuentivo.db"`: `MainActivity.onCreate`, `SyncWorker.doWork` y
  `CollectionReminderWorker.doWork`. Los tres registran el arreglo hoy. Compruébalo con
  `grep -rn '"cuentivo.db"' app/src/main`, no con una lista escrita.
- El índice `customers (businessId, normalizedName)` **NO es único y no debe serlo**: el nombre es
  ayuda de búsqueda, nunca identidad. La identidad es `customer.id`.
- Suite JVM en `24014de`: **729 pruebas, 0 fallos**, 90 clases. Paridad de idiomas **443 = 443**.
- La clave de idempotencia de una venta IA nace en la **preview** (`newConfirmationKey()` en
  `AiSalePreview.kt`), no en el toque de confirmar: reconfirmar la misma preview reusa la clave y
  no duplica. Dos previews distintas son dos ventas distintas, y así debe ser.

## Orquestamiento

- **Claude** es mente de arquitectura, auditoría, continuidad y gate. No implementa código
  productivo: define paquetes acotados, audita diffs y decide si algo pasa.
- **AGY** implementa. Su reporte **no** cierra una tarea; sólo la cierra evidencia reejecutada de
  forma independiente: pruebas, build/lint cuando aplique, diff e invariantes.
- Dinero, Room, idempotencia o sync exigen TDD, transacción atómica, rollback probado y pruebas
  conectadas en el A059P antes de aprobar.
- Workspace único: `C:/Users/pibed/orca/workspaces/OrganizationLabs-neo-fintech/feature-cuentivo-ai`.
  El workspace estable original no se toca.
- `docs/superpowers/handoffs/` es del usuario, permanece **sin rastrear e intocable**: no agregar,
  borrar ni modificar.

## Qué sigue

1. Consultas naturales deterministas — punto 4 del Tramo A en
   `docs/superpowers/specs/2026-08-24-cuentivo-fiado-catalogo-product-design.md`. Allow-list de
   intents; las cifras las contesta el dominio, nunca Gemini.
2. Voz y OCR por lotes con texto editable, mismo pipeline.
3. Cobranza explicable, PDF, WhatsApp con aprobación humana y barcode local Pro.
4. Pedidos y campañas de catálogo — un pedido **no** crea deuda antes de la entrega.
5. Beta limitada con observabilidad, hard caps y rollback ensayado; después producción gradual.

Cada punto exige su propio diseño, autorización y gates. Ninguno se adelanta dentro del commit
de otro. Fotos cloud/Storage es un proyecto **separado**, no dependencia de CuentivoAI.

## Blockers y pendientes vigentes

- **El enforcement de App Check sigue apagado.** Falta leer el panel con tráfico posterior al
  vínculo de Play Integrity, mirando la barra del día y no el acumulado. Lo ya descartado con
  evidencia está en el histórico; no volver a perseguirlo.
- **No hay CI.** Nada valida el código salvo lo que se ejecute localmente.
- **`.superpowers/` está en `.gitignore`.** Los briefs, reportes y `progress.md` del trabajo SDD
  viven sólo en este disco: no sobreviven a un clon nuevo ni llegan a otra máquina.
- **No hay enlace al aviso de privacidad dentro de la app.** La política **sí** está publicada como
  sitio externo (repo `Cuentivo-politicasdeprivacidad`, Vercel) y Play la tiene; lo que falta es el
  enlace en pantalla. Es detalle de UX, ya no blocker de publicación.
- **No hay fila en `sync_state`** para el negocio del A059P, aunque el outbox se drenó tras la
  prueba física. No está confirmado que la mitad de *pull* complete. Se verifica mirando si el
  cliente creado aparece en `businesses/{id}/customers` de Firestore.
- `BackupCrypto.ITERATIONS` en 120 000 PBKDF2, por debajo de lo recomendado hoy. Subirlas no rompe
  respaldos viejos porque viajan en la cabecera; medir antes en un teléfono lento.

## Reglas de continuidad

- **Compilar no es verificar.** No se declara nada terminado sin ejecutar de verdad y reportar la
  salida real del comando.
- Si documentación, código y memoria se contradicen, gana el código: reporta la evidencia y
  corrige el documento. No lo resuelvas en silencio.
- Al cerrar trabajo relevante, **sustituye** aquí lo que cambió y baja lo desplazado a
  `docs/historia-del-proyecto.md`. La instantánea previa a CuentivoAI quedó en
  `docs/superpowers/history/2026-08-24-project-memory-pre-cuentivoai.md`.
