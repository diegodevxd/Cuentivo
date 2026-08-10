# Borrado de cuenta: qué hace la app y qué te toca hacer a ti

## El problema que esto resuelve

"Eliminar mi cuenta" borraba la base local y la cuenta de Firebase Auth, **y nada más**. Si el usuario
era Pro y había sincronizado, todo su negocio se quedaba en Firestore para siempre: clientes con
**nombre y teléfono**, ventas, abonos y movimientos.

Lo que más pesa no es que fueran los datos del dueño. Son datos de **sus clientes** — personas que
nunca instalaron la app ni aceptaron nada.

Y no se podía arreglar desde el teléfono: `firestore.rules` tiene `allow delete: if false` en todas
las colecciones, a propósito, porque nada financiero se borra. Un cliente que pudiera borrar su
negocio podría borrar el libro mayor de alguien más.

## Lo que hace la app hoy

Al confirmar "Eliminar mi cuenta", **antes** de tocar nada más:

1. Escribe `deletionRequestedAtEpochMillis` en `businesses/{businessId}`, y **espera al servidor**.
2. Luego borra la base local, la sesión, el derecho de Pro y la cuenta de Auth.

El orden no es un detalle: una vez borrada la cuenta de Auth ya no hay credencial con la que escribir,
así que ésta es la única ventana en la que se puede marcar la copia de la nube.

Si el teléfono no tiene señal, **el borrado local sigue adelante** —nadie debe quedarse atrapado con
sus datos por no tener internet— y la app se lo dice: *"Tu teléfono quedó limpio, pero no pudimos
avisar al servidor."*

## Lo que te toca a ti, a mano

⚠️ **Esto no es automático.** Mientras no lo hagas, los datos siguen ahí.

Busca los negocios marcados:

```bash
npx firebase firestore:query businesses --where "deletionRequestedAtEpochMillis,!=,null" --project organitazionlabs
```

Y borra cada uno con todas sus subcolecciones:

```bash
npx firebase firestore:delete "businesses/<businessId>" --recursive --project organitazionlabs
```

Es el mismo comando con el que se limpiaron los 9 negocios duplicados el 2026-08-07.

**Verifica después**: la consulta de arriba debe dejar de devolver ese negocio, y
`businesses/<id>/customers` debe quedar vacío.

## Con qué frecuencia

Al menos **una vez al mes**, y siempre que un usuario escriba pidiéndolo. Si la app crece, esto deja
de ser sostenible a mano — ver la deuda de abajo.

## Lo que esto NO resuelve

Sé honesto sobre el alcance:

- **No cumple del todo con Google Play.** Play exige que borrar la cuenta borre también los datos del
  servidor, y desde 2023 exige además una **URL de solicitud de borrado accesible desde la web** para
  cualquier app que permita crear cuenta. Esto es un puente: deja evidencia de la petición y la hace
  cumplible, pero el borrado sigue dependiendo de que una persona corra un comando.
- **La solución real es Blaze + Cloud Function** que borre `businesses/{id}` recursivamente al
  eliminarse la cuenta. Hoy el proyecto está en plan **Spark**, donde no se despliegan funciones.
  Cuando se pase a Blaze, esta marca ya deja el disparador listo.

## Si cambias el documento de negocio

`deletionRequestedAtEpochMillis` es **la única llave opcional** de todo el esquema. `validBusiness` la
admite con `hasOnly` pero no la exige con `hasAll`, para que los negocios escritos antes de que
existiera sigan validando.

Si agregas otra llave al negocio, agrégala en los dos sitios o el update se deniega **en silencio** —
que es exactamente el modo de fallo contra el que existe este archivo.
