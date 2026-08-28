# Brief para AGY — N5 / T5: apagador remoto visible en el cliente

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `3556c56` · **Plan:**
`docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md` §T5

T1, T2, T3, T4, T6 y T7 están cerradas. El apagador **autoritativo ya existe y funciona**: si
`aiControl/global.enabled` se apaga, `authorizeInTransaction` corta antes del entitlement, la cuota y
el proveedor, así que ni una llamada llega a Gemini. Eso está verificado.

Lo que falta es lo que ve la persona. Hoy, con la IA apagada, el usuario que pagó $299 entra al
laboratorio, escribe su frase, espera, y recibe el mensaje de mantenimiento que agregaste en T4.
Funciona, pero es un callejón: la app le ofrece algo que ya sabe que no va a poder darle.

## La decisión de diseño, ya tomada — no la cambies

El plan admite dos caminos: una callable autenticada o Remote Config. **Se usa la callable.**

**Por qué no Remote Config:** crea un segundo interruptor. Tendrías que apagar `aiControl/global`
**y** el flag de Remote Config, y el día que alguien apague uno solo, el sistema queda inconsistente
justo en una emergencia. Este proyecto ya pagó esa factura: el 2026-08-25 se editó `usedActions` en
vez de `monthlyActionLimit` y el sistema quedó igual de bloqueado con el operador creyendo que lo
había arreglado. **Un solo interruptor, un solo documento.** Además Remote Config mete una
dependencia nueva al APK publicado sin necesidad.

**Lo que sí se hace:** una callable nueva que lee el mismo `aiControl/global` que ya gobierna la
autorización, y devuelve **una sola cosa sanitizada**.

**Y lo que no cambia bajo ninguna circunstancia:** `firestore.rules` no se toca. `aiControl` sigue
cerrado al cliente. El APK **nunca** lee ese documento — se lo pregunta al servidor.

## Alcance

### 1. La callable

Nueva, en `functions/src/index.ts`, junto a las otras tres. Mismo `AI_BOUNDARY`: región
`us-south1`, App Check y Auth obligatorios, `concurrency: 1`. **Sin secretos ligados** — no necesita
ni el de Gemini ni el de Play, igual que `authorizeAiAction`.

Reglas duras:

- **Devuelve exactamente un booleano público**, del estilo `{ available: boolean }`. Nada de cuotas,
  topes, conteos, costos, nombres de configuración ni motivos internos. Si mañana alguien quiere
  saber por qué está apagado, esa es otra decisión; hoy no se expone.
- **Es de sólo lectura.** No consume cuota, no escribe ledger, ni `aiUsage`, ni ventana de rate, ni
  nada. Un usuario que abra la app cien veces no debe gastar ni una acción de su plan. Esto es lo más
  fácil de romper si la construyes reutilizando el camino de `authorizeAiAction`, que **sí** consume.
- Lee el **mismo** `aiControl/global` que usa la autorización. No dupliques la lógica de "está
  encendido": si ya existe una función que lo decide, invócala; si no, extráela para que ambos
  caminos usen una sola.
- Un usuario anónimo o sin Auth no la puede llamar, igual que las demás.

### 2. El cliente

- **Sólo se pregunta cuando el tier es AI.** Para FREE y PRO la entrada lleva al muro de suscripción
  de todos modos, así que preguntar por disponibilidad es una invocación desperdiciada. No la hagas.
- La respuesta se guarda **en memoria, por sesión**. **No la persistas en Room.** Un "apagado" que
  sobreviva al reinicio dejaría la función muerta en el teléfono después de que el problema ya se
  resolvió, y nadie sabría por qué.
- **Falla abierto, y esto es deliberado.** Si la callable no responde —red caída, timeout, error—
  **la entrada se pinta**. El razonamiento: el apagador autoritativo es el servidor, que ya falla
  cerrado; lo peor que pasa si el cliente se equivoca hacia abierto es que la persona toque, y el
  servidor le conteste `AI_DISABLED`, que T4 ya traduce a "en mantenimiento". Un toque desperdiciado.
  Si fallara cerrado, en cambio, **un parpadeo de red le esconde a alguien la función que paga**.
  Escríbelo como comentario en el código, no sólo en el reporte: es la clase de decisión que el
  siguiente que lea el archivo va a querer "arreglar" al revés.

### 3. La entrada

`AiEntryPolicy` es una decisión pura y así se queda: recibe la disponibilidad **como entrada**, no la
consulta. Nada de meterle red ni `Context`.

**La trampa que hay que evitar:** cuando el tier es AI y la IA está apagada, el usuario **no puede
terminar en el muro de compra**. Ya pagó. Mandarlo a comprar de nuevo lo que ya tiene es peor que no
decirle nada. Ese caso va a un estado de mantenimiento propio, con su texto, reusando el que T4 ya
introdujo si encaja.

Y quien no tiene el plan sigue viendo el muro **exactamente igual que hoy**, esté la IA encendida o
apagada. La disponibilidad no cambia el camino de compra: si mañana se enciende, la persona ya tendrá
su suscripción.

### 4. Textos

En `values/` y `values-en/`, con paridad de claves. **Línea base 551 = 551; cuenta después.** Nada de
literales en Composables — la puerta del idioma de `CLAUDE.md` debe salir vacía. Inglés sin
apóstrofos ("do not", no "don't").

## Invariantes que no se tocan

- **Registrar un pago o abono nunca se bloquea**, ni con la IA apagada, ni sin red, ni por plan.
- **`firestore.rules` no cambia.** `aiControl`, `aiUsage`, `aiRateWindows` y `aiRequestLedger` siguen
  cerrados al cliente.
- **Nada de PII, montos, saldos, ids ni tokens** en la respuesta, los logs, Analytics o Crashlytics.
- El apagador **autoritativo sigue siendo el servidor**. Esta tarea agrega cortesía, no seguridad: un
  APK modificado que ignore el booleano debe seguir chocando contra la autorización. Si tu diseño
  hace que el cliente sea quien "decide", está mal.
- Cuentivo Gratis y Pro funcionan igual aunque esta callable no exista o falle.

## Lo que NO entra

T8 (observabilidad y rollback ensayado) y T9 (gate final y AAB). Tampoco la función que consume las
notificaciones de Play, que ya tiene su tópico creado y verificado pero es su propio incremento. Ni
exponer motivos, cuotas o conteos: eso es superficie nueva y es decisión de Diego.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

En `functions/`, y **son dos comandos distintos, no uno**:

```
npm test                          # 258 pruebas — NO cubre el gate del emulador
npm run test:firestore-emulator   # 4 pruebas — el gate real, con el emulador levantado
```

`vitest.config.ts` sólo incluye `test/**/*.test.ts`, así que `firestoreEmulator.gate.ts` **no corre
con `npm test`**. Reportar "258/258" como si cubriera el gate es reportar evidencia que no ocurrió.
Corre los dos y reporta los dos por separado. Si no tienes el emulador levantado, dilo; no lo
supongas verde.

Cobertura mínima de pruebas para esta tarea: encendido, apagado, y **fallo de la callable con la
entrada pintándose igual** — esa última es la que demuestra que falla abierto a propósito.

Conteo real del XML de `app/build/test-results/testDebugUnitTest/`. **Línea base 916 pruebas, 0
fallos, 0 errores** — debe subir. Paridad de claves ejecutada y reportada. `git diff --check` limpio
y árbol final con **sólo** `?? docs/superpowers/handoffs/`, que es de Diego y no se toca.

**Comitea al terminar, en un solo commit de cierre**, e incluye este brief. **No despliegues nada:**
el deploy de functions lo autoriza Diego aparte.
