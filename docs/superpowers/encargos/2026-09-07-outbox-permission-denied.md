# Outbox: 117 filas con PERMISSION_DENIED que nunca podrán subir

**Solo lectura y opinión. No edites, no comitees, no despliegues.** Responde corto y concreto.

## El diagnóstico, con la evidencia

Saqué la base del teléfono de Diego (`adb run-as ... cat databases/cuentivo.db`). `sync_operations`
tiene **157 filas**, de las cuales **117 están aparcadas** como
`PERMANENT:PERMISSION_DENIED: Missing or insufficient permissions.`:

| entityType | filas aparcadas |
|---|---|
| PAYMENT_ALLOCATION | 30 |
| MOVEMENT | 29 |
| SALE_ITEM | 27 |
| PAYMENT | 15 |
| SALE | 15 |
| MEMBER | 1 |

Comprobé contra Firestore de producción, documento por documento:

- El negocio `3e95d1ee-…` **existe**, con `ownerUserId` = el uid actual de Diego.
- Su documento `members/{uid}` **existe**, `role: OWNER`, `status: ACTIVE`. O sea `isMember()` pasa.
- El uid tiene `emailVerified: true`, así que `isAuthenticated()` pasa.
- Tomé una venta aparcada al azar (`41bcdec5-…`) y **también existe ya en Firestore**, completa y
  correcta.

Todos esos documentos tienen `createTime` idéntico: `2026-08-13T03:35:37`. Se publicaron de una vez,
por otra vía, y las filas del outbox de aquí sobrevivieron.

**Entonces la causa es esta:** `FirestoreSyncGateway` sube con `set()`. Sobre un documento que ya
existe, `set()` es un **update**, no un create. Y en `firestore.rules` ventas, ítems, pagos,
asignaciones y movimientos son **create-only** (`allow update: if false`). El documento de miembro
OWNER es el mismo caso por otra puerta: `allow update` exige `resource.data.role != 'OWNER'`, así que
la membresía del dueño se puede crear pero **nunca** actualizar.

Es decir: **no hay datos perdidos.** Todo está ya en la nube. Lo que hay es un outbox que no se puede
vaciar nunca y un error que asusta al mirarlo.

## Lo que quiero proponer, y donde quiero que me digas si me equivoco

Para una entidad cuyo documento remoto es **inmutable por regla**, existir en el id correcto **es**
el estado final. Este cliente no podría escribir nada legítimo ahí aunque quisiera. Así que:

> Al recibir `PERMISSION_DENIED` en un UPSERT de una entidad create-only, leer el documento remoto.
> Si existe → la fila está cumplida: **borrarla** del outbox, no aparcarla.
> Si no existe → es un rechazo real de reglas: aparcarla como permanente, como ahora.

Alcance deliberadamente estrecho: `SALE`, `SALE_ITEM`, `PAYMENT`, `PAYMENT_ALLOCATION`, `MOVEMENT`, y
el documento `MEMBER` de rol OWNER. **NO** `CUSTOMER`, `PRODUCT` ni `BUSINESS`: esos sí se pueden
actualizar, así que un `PERMISSION_DENIED` ahí puede ser un `validCustomer` que falló sobre un
documento que existe con contenido viejo, y borrar la fila perdería la actualización.

Preguntas:

1. ¿Es correcto el razonamiento de "inmutable por regla ⇒ existir es el estado final"? ¿Ves un caso
   donde exista el documento pero la fila **sí** tuviera algo pendiente que hacer?
2. La lectura remota extra sólo ocurre al fallar. ¿Aceptable, o prefieres otra forma de distinguir
   "ya estaba" de "rechazado de verdad"? ¿Serviría mirar `getAfter`/`createTime`?
3. **`OwnerMembershipBootstrap.ensure`** llama a `syncOperationDao.reopenParkedOperations()` cuando
   la membresía se inserta por primera vez, y eso **desaparca TODAS** las filas permanentes, incluidas
   estas 117 que no pueden triunfar jamás. Vuelven a intentarse, vuelven a fallar, vuelven a
   aparcarse. ¿Hay que acotar ese reopen a lo aparcado **antes** de que existiera la membresía, o
   basta con el arreglo de arriba?
4. ¿Borrar filas del outbox es seguro aquí? El outbox no es un registro financiero — la verdad está
   en Room y en Firestore, y ambos ya coinciden. Pero quiero que lo confirmes o lo discutas.
5. ¿Cómo se prueba esto **sin** un dispositivo y sin inventar una copia de la regla dentro del test?
   `SyncDrainer` ya está escrito contra lambdas; ¿la costura correcta está ahí, en
   `FirestoreSyncGateway.classify`, o en el coordinador?

## Contexto que puede que necesites

Lee `app/src/main/java/com/cuentivo/app/sync/FirestoreSyncGateway.kt` (sobre todo `classify`),
`SyncDrainer.kt`, `SyncWorker.kt`, `OwnerMembershipBootstrap.kt`, `data/local/SyncOperationDao.kt` y
las reglas de `firestore.rules`.
