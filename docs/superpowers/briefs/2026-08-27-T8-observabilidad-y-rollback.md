# Brief para AGY — N5 / T8: observabilidad y simulacro de rollback

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `5f87152` · **Plan:**
`docs/superpowers/plans/2026-08-26-cuentivoai-n5-salida-a-beta.md` §T8

**Lee antes de escribir código:**
`docs/superpowers/operations/2026-08-15-cuentivoai-observability-threat-model.md`. Ese documento
manda sobre qué se puede registrar y qué no. Esta tarea es precisamente donde se rompe la regla 4 del
proyecto si nadie la mira.

T1 a T7 están cerradas. CuentivoAI se puede comprar, existe en el build publicado, dice por qué
rechaza y se puede apagar en remoto. Falta poder **ver** qué está pasando y haber **ensayado** el
apagado antes de necesitarlo de verdad.

## Por qué ahora

Dos cosas hacen que éste sea el momento y no antes:

- **T4 acaba de hacer medible el rechazo.** Hasta ayer, `AI_INTERPRET_RESULT` sólo distinguía
  propuesta de "no disponible", así que un tablero de errores no medía nada: todo era el mismo
  cubo. Ahora el status trae cuota agotada, rate limit, apagado y falta de entitlement por separado.
  La instrumentación se vuelve útil justo ahora.
- **El apagador está escrito pero nunca se ha usado.** Un rollback descrito no es un rollback. La
  primera vez que se ejecuta no puede ser durante una emergencia con gente pagando.

## Alcance

### 1. Instrumentación mínima, en `functions/`

Hoy hay cuatro líneas de log (`AI_PROVIDER_FAILURE`, `AI_REJECTED`, `AI_INTERPRET_RESULT`,
`AI_AVAILABILITY_REJECTED`) y ninguna dice cuánto tardó ni cuántas veces se llamó a Gemini.

Agrega **sólo** lo que se puede convertir en una métrica y responde una pregunta operativa real:

- **Costo.** El momento en que se gasta dinero es la invocación efectiva del proveedor, después de
  que `claimProviderInvocation` devuelve `CLAIMED`. Registra ese hecho de forma contable. Sin eso,
  contar `AI_PROPOSAL` subestima: una llamada que falla en Gemini también costó.
- **Latencia.** Milisegundos de la llamada al proveedor, sólo cuando hubo llamada. No la latencia
  total de la callable: eso mezcla la espera de Firestore con la del modelo y no permite distinguir
  "Gemini está lento" de "Firestore está lento".
- **Error.** Ya existe `AI_PROVIDER_FAILURE` con categoría y status. Verifica que cubra los cuatro
  casos que `geminiTextProvider` distingue; si alguno se pierde, ciérralo.

**Lo que no se registra, y no es negociable:**

- Ni `uid`, ni `purchaseToken`, ni el texto del usuario, ni nombres, ni teléfonos, ni montos, ni
  saldos, ni ids de cliente, venta o producto. **Todo agregado, nada por persona.** Si para responder
  una pregunta hace falta identificar a alguien, esa pregunta no se responde en este incremento.
- Nada nuevo en Analytics ni Crashlytics del lado Android. Esta tarea es de servidor.

**No cambies comportamiento.** T8 observa; no altera decisiones, ni cuotas, ni rechazos. Si al
instrumentar te ves cambiando un `if`, te saliste del alcance.

### 2. El runbook del apagado

Documento nuevo en `docs/superpowers/operations/`, escrito para que alguien lo ejecute **a las tres
de la mañana sin leer código**:

- **Cómo se apaga**, exactamente: qué documento, qué campo, qué valor. Es `aiControl/global`; nombra
  el campo tal como está en el código, no de memoria.
- **Qué pasa y en cuánto tiempo.** Sé preciso y honesto: el servidor corta **de inmediato** —ninguna
  llamada llega a Gemini— y los clientes ya abiertos convergen a la pantalla de mantenimiento en
  **hasta 5 minutos**, por el TTL de T5, o al instante si reabren la app. Dos tiempos distintos, y
  quien opere tiene que saberlo o va a creer que el apagador no sirvió.
- **Cómo se comprueba que sí se apagó**, sin adivinar: qué se ve en los logs.
- **Cómo se vuelve a encender**, y qué se espera al encender.
- **La trampa que ya nos costó una hora el 2026-08-25:** `usedActions` y `monthlyActionLimit` viven
  en colecciones vecinas y se confunden. El runbook tiene que decir cuál es cuál y qué pasa si
  editas la equivocada, porque el síntoma es idéntico: todo bloqueado.

Deja una sección de **registro del simulacro vacía**, con los huecos que Diego llenará al ejecutarlo
en vivo. **No la llenes tú y no simules haberlo hecho.**

### 3. La prueba que hace del apagado algo verificado

Contra el emulador, en `firestoreEmulator.gate.ts`: con `aiControl/global` apagado, la callable
rechaza **y el proveedor nunca se invoca**. Esa segunda mitad es la que importa y es la que hoy no
está afirmada: no basta con que el usuario reciba `AI_DISABLED`; hay que probar que no se gastó
dinero. Un doble del proveedor que cuente invocaciones y afirme cero.

Y el camino de vuelta: al encender, la misma petición vuelve a funcionar.

### 4. Limpieza de una línea

`app/src/main/java/com/cuentivo/app/ui/CuentivoApp.kt:77` importa `mutableLongStateOf` y ya no se
usa desde que la orquestación se fue a `AiAvailabilityCoordinator`. Bórralo aquí y no le dediques un
commit propio.

## Lo que NO entra

T9 (gate final, prueba física y AAB). La función que consume las notificaciones de Play. Crear los
tableros en la consola de Google Cloud: eso lo hace Diego a mano, y tu trabajo es **decirle
exactamente qué filtro y qué campo usar** en el runbook, no dejarlo a que lo adivine. Tampoco cambiar
topes, precios ni límites.

## Invariantes que no se tocan

- **Nada de PII, montos, saldos, ids ni tokens en logs.** Es *la* regla de esta tarea.
- **Registrar un pago o abono nunca se bloquea.**
- Ninguna guarda se debilita: se observa, no se decide.
- `firestore.rules` no cambia.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

En `functions/`, **dos comandos distintos**, y ya está escrito en `CLAUDE.md`:

```
npm test                          # NO cubre el gate del emulador
npm run test:firestore-emulator   # el gate real, con el emulador levantado
```

Más `npm run lint`. Reporta los tres por separado; si no tienes el emulador levantado, dilo, no lo
supongas verde.

**Línea base: 930 pruebas JVM en 104 clases, 0 fallos; functions 262; gate del emulador 5.** Deben
subir, nunca bajar. `git diff --check` limpio y árbol final con **sólo**
`?? docs/superpowers/handoffs/`, que es de Diego y no se toca.

Y recuerda la regla que se acaba de agregar a `CLAUDE.md`: **una prueba no puede reimplementar la
lógica que verifica.** Si para probar el conteo de invocaciones te ves copiando el flujo dentro del
test, extrae el código en vez de copiarlo.

**Comitea al cerrar, en un solo commit**, incluyendo este brief y el runbook. **No despliegues nada.**
