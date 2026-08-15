# Borrado de cuenta: cómo funciona y cómo se mantiene

## Qué resuelve

"Eliminar mi cuenta" borra la base local, la cuenta de Firebase Auth **y la copia en la nube**:
clientes con nombre y teléfono, ventas, abonos, movimientos, miembros y auditoría — todo el negocio,
de forma permanente.

Antes esto no se podía hacer desde el teléfono: `firestore.rules` tiene `allow delete: if false` en
todas las colecciones, a propósito, porque nada financiero se borra. La app pedía el borrado y el
resto se hacía a mano con un comando de la CLI. Eso ya no es así: **el borrado efectivo ahora lo hace
el cliente**, sin Cloud Functions ni plan Blaze — sigue siendo Spark.

## El candado de seguridad (una sola puerta, de un solo sentido)

La regla que hace esto seguro es una **puerta de un solo sentido** en `firestore.rules`:

1. El borrado remoto solo lo puede ejecutar **el mismo dueño verificado** del negocio
   (`canDeleteForDeletion` exige `ownerUserId == request.auth.uid` y `email_verified`).
2. Solo se abre **después** de que ese dueño pidió el borrado: la llave opcional
   `deletionRequestedAtEpochMillis` en `businesses/{businessId}`. Sin la llave, toda escritura de
   borrado sigue siendo `delete: if false`.
3. La llave es **inmutable**: `deletionFlagImmutable` hace que un update no pueda quitarla ni
   cambiarle el valor. Nadie puede "des-solicitar" el borrado para volver a la normalidad; una vez
   pedido, el único camino es que se borre.
4. Ningún otro `delete` existe en las reglas. La puerta no se reutiliza para otra cosa.

Nada de esto es solo ocultar botones: el servidor decide. El cliente (la app) solo replica lo que
las reglas ya imponen.

## Cómo borra el cliente (cascada)

El orden no es un detalle: Firestore **no borra en cascada** por sí solo. Borrar la venta no borra
sus ítems, y borrar el negocio no borra nada más. El borrado es explícito, en este orden:

1. **Ítems de venta** (`sales/{saleId}/items/*`) antes que la venta que los contiene.
2. **Ventas** (`sales/*`).
3. **Asignaciones de abono** (`payments/{paymentId}/allocations/*`) antes que el abono.
4. **Abonos** (`payments/*`).
5. **Miembros, clientes, productos, movimientos y auditoría** (`members/*`, `customers/*`,
   `products/*`, `movements/*`, `auditLogs/*`).
6. **El documento del negocio, al final, solo.**

Todo en lotes de **a lo más 500 escrituras** (límite de `WriteBatch`), esperando cada lote antes de
pasar al siguiente. Si un lote falla, la cascada se detiene en un límite limpio y un reintento
re-lista solo lo que quedó — los borrados son idempotentes.

El planificador es **puro**: `domain/BusinessDeletionPlanner.kt` decide orden y tamaño de lotes y lo
fija con pruebas JVM (`BusinessDeletionPlannerTest`). `sync/FirestoreDeletionGateway.kt` solo lista
lo que existe (siempre con `Source.SERVER`, para ver la nube real y no un caché viejo) y ejecuta los
lotes. Si el documento del negocio ya no existe, la cascada devuelve éxito: es un reintento después
de un borrado a medias.

## Qué hace la app (Android)

Al confirmar "Eliminar mi cuenta", en este orden:

1. Pide el borrado a la nube y **espera al servidor**. La solicitud lee `businesses/{businessId}` del
   **servidor** (`Source.SERVER`, nunca del caché) y solo escribe `deletionRequestedAtEpochMillis`
   si la llave no existe todavía. Si el negocio ya no está, lo da por borrado; si la llave ya está
   puesta, no la reescribe y pasa directo a la cascada. Es idempotente a propósito: la llave es
   **inmutable**, y un reintento que la cambiara por un timestamp nuevo sería denegado por
   `deletionFlagImmutable`, atorando el borrado para siempre tras un fallo parcial. La decisión vive
   en `domain/DeletionRequestPolicy.kt` (puro, con pruebas).
2. Borra toda la cascada y **espera al servidor** (lotes de ≤ 500, hijos primero, negocio al final).
3. **Solo si la nube quedó limpia**: borra la base local, la sesión, el derecho de Pro y la cuenta
   de Auth.

**Si falla la nube, no se borra nada local.** Sin señal o con un error, la app lo dice
("No pudimos borrar tu información en la nube, así que no se borró nada de tu teléfono") y todo
sigue intacto para reintentar. Antes se borraba el teléfono igual; ya no: el objetivo es que el
dueño nunca se quede sin su copia local mientras la nube sigue teniendo datos de terceros.

**Sesión demasiado antigua.** Firebase exige una sesión reciente para borrar la cuenta de Auth
(`requires-recent-login`). La app lo distingue y dice claro: "Cierra sesión, vuelve a entrar y repite
el borrado". En ese caso la nube ya está limpia; lo único que falta es la cuenta en sí.

## Página pública de solicitud de borrado (Vercel)

La URL pública de solicitud de borrado que exige Google Play es
**https://eliminacion-cuentivo.vercel.app/** (desplegada en Vercel, no en Firebase Hosting). Es una
página de **solicitud por correo**: quien ya no puede entrar a la app escribe el correo de su
cuenta y Capilabs recibe la solicitud para procesarla (la página lo dice: hasta 30 días máximo,
con contacto directo en `diegomancera.dev@gmail.com`). La página **no** ejecuta la cascada de
borrado: el borrado efectivo lo hace la app (sección anterior). En este repositorio **no hay
Hosting**: no se despliega ninguna página web desde aquí.

El sitio vive en su propio proyecto de Vercel, fuera de este repositorio; los cambios de contenido
se publican desde allá.

La URL de solicitud es la que se reporta a Google Play desde 2023.

## Despliegue de reglas (manual, solo Firestore)

Las reglas **no se despliegan solas**: no hay CI ni paso automático. Cada cambio en
`firestore.rules` (por ejemplo, la puerta `canDeleteForDeletion`) llega a producción con un comando
manual. Este es el único archivo que se despliega y el único comando:

- **Archivo:** `C:\Users\pibed\AndroidStudioProjects\OrganizationLabs-neo-fintech\firestore.rules`
- **Comando:**

```bash
npx -y firebase-tools@latest deploy --only firestore:rules --project organitazionlabs
```

No se despliega hosting, ni funciones, ni nada más desde este repositorio.

## Qué hace falta de vez en cuando (mantenimiento)

**Ya no hay que borrar a mano.** La consulta y el `firestore:delete` manual de abajo quedan solo
como herramienta de emergencia (por ejemplo, un negocio cuya cascada quedó a medias por un bug):

```bash
npx firebase firestore:query businesses --where "deletionRequestedAtEpochMillis,!=,null" --project organitazionlabs
npx firebase firestore:delete "businesses/<businessId>" --recursive --project organitazionlabs
```

Un negocio con la llave puesta y datos que aún existen es un borrado que no terminó; conviene
revisarlos cuando aparezcan. **Un negocio con la llave puesta y cero datos es el estado final
correcto.**

## Si cambias el documento de negocio

`deletionRequestedAtEpochMillis` es **la única llave opcional** de todo el esquema. `validBusiness` la
admite con `hasOnly` pero no la exige con `hasAll`, para que los negocios escritos antes de que
existiera sigan validando.

Si agregas otra llave al negocio, agrégala en los dos sitios o el update se deniega **en silencio** —
que es exactamente el modo de fallo contra el que existe este archivo. Y si tocas las reglas de
borrado, recuerda el contrato: la puerta solo se abre con dueño verificado + llave puesta, y la llave
es inmutable. `DeletionRulesContractTest` lee el archivo real y verifica que cada colección tenga su
`allow delete` detrás de `canDeleteForDeletion` — correlo con `testDebugUnitTest`.
