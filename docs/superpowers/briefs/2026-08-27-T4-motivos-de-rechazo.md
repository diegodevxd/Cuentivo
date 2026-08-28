# Brief para AGY — N5 / T4: decir por qué se rechazó

**Rama:** `diegodevxd/feature-cuentivo-ai` · **Base:** `53dbc90` · **Propuesta original:**
`.superpowers/sdd/propuesta-motivo-ai-unavailable.md` (leerla: tiene el diagnóstico real que originó
esta tarea)

T1, T2, T3, T6 y T7 están cerradas. CuentivoAI ya existe en el build publicado y ya se puede comprar.
Falta que, cuando diga que no, **diga por qué**.

## El hueco

`functions/src/interpretAiRequest.ts` tiene **nueve** `return UNAVAILABLE(requestId)`. Todos
producen exactamente la misma respuesta. Entre ellos se pierden cuatro cosas que el servidor **ya
sabe** y decide callar:

- `AI_QUOTA_EXHAUSTED` — se acabaron las acciones del mes
- `AI_RATE_LIMITED` — demasiado rápido, espera
- `AI_DISABLED` — la IA está apagada del lado del servidor
- `AI_ENTITLEMENT_REQUIRED` — no tiene el plan

Las cuatro mueren en la línea 93: `if (authorization.decision !== "AI_AUTHORIZED") return UNAVAILABLE(...)`.

**Por qué importa ahora y no antes:** el 2026-08-25 esto costó una hora de diagnóstico con logs para
descubrir que la cuota mensual estaba agotada. A partir de la prueba cerrada el que lo va a sufrir es
alguien que **pagó $299** y va a leer "aún no disponible" como si la app estuviera descompuesta. Eso
es un reembolso y una reseña de una estrella, no un detalle de presentación.

## La decisión de diseño, que ya está tomada — no la cambies

Hay dos formas de propagar el motivo y **una de ellas rompe a todos los clientes instalados**. Se
usa la segunda:

**Lo que NO se hace:** agregar un campo `reason` a la raíz de la respuesta.
`app/src/main/java/com/cuentivo/app/ai/CallableAiProposalResult.kt:39-43` exige que el conjunto de
llaves de la raíz sea **exactamente** `{contractVersion, requestId, status, proposal}`. Una llave de
más y **todo cliente ya instalado** cae en `Unavailable`. Tampoco se sube `contractVersion` a 3: eso
obliga a mantener dos contratos en el servidor.

**Lo que sí se hace:** el motivo viaja **en el campo `status` que ya existe**. En vez de
`status: "AI_UNAVAILABLE"`, la respuesta trae `status: "AI_QUOTA_EXHAUSTED"`, `"AI_RATE_LIMITED"`,
`"AI_DISABLED"` o `"AI_ENTITLEMENT_REQUIRED"`. `proposal` sigue en `null` y el conjunto de llaves no
cambia.

Esto es compatible hacia atrás **por construcción**: el parser actual ya tiene
`else -> CallableAiProposalResult.Unavailable` (línea 62), así que un cliente viejo que reciba un
status que no conoce se comporta **exactamente como hoy**. Ningún cliente se rompe, y no hace falta
versionar nada.

## Alcance

### 1. Servidor

`interpretAiRequest.ts` deja de colapsar los cuatro caminos. Reglas:

- Los cuatro status nuevos salen **únicamente** de `authorization.decision`. No los inventes en otro
  punto del flujo.
- **Todo lo demás sigue siendo `AI_UNAVAILABLE`**, sin excepción: entrada inválida, sesión anónima,
  `authorize` que lanza, proveedor nulo, reclamo ya tomado, fallo del proveedor y propuesta nula. Esos
  o filtran estado interno o son "reintenta", y no aportan nada al usuario.
- **Los dos rate limits mapean al mismo `AI_RATE_LIMITED`**: el guardia de borde
  (`guardDecision.kind === "RATE_LIMITED"`, línea 77) y la ventana por minuto de dentro de
  `authorize`. Para el usuario es la misma situación.
- **El camino de replay no cambia de comportamiento.** `deps.guard.remember(...)` sólo se llama tras
  una autorización exitosa, así que un replay siempre trae `AI_AUTHORIZED`. Un reintento con el mismo
  `requestId` debe devolver el mismo status que la primera vez.
- **Ningún número en la respuesta.** Nada de cuántas acciones quedan, cuándo se reinicia la cuota ni
  cuánto falta del rate limit. Es vocabulario fijo y nada más. Exponer conteos es superficie nueva y
  es su propia decisión, no ésta.

### 2. Cliente

`CallableAiProposalResult` gana variantes para los cuatro motivos, junto a `Proposal` y
`Unavailable`. El parser las reconoce **sin relajar nada más**: sigue exigiendo el mismo conjunto de
llaves, `contractVersion == 2`, el `requestId` que corresponde y `proposal == null` para todo status
que no sea `AI_PROPOSAL`.

`AiDebugLabModel.kt:292` mapea hoy `Unavailable -> AssistantUnavailable`. Ahora cada motivo lleva a
su propio mensaje. **Ojo con Room:** `AiDailyMessageKind` se persiste en `ai_daily_messages`, así que
si agregas variantes de mensaje tienen que sobrevivir a la relectura y **un valor desconocido en una
fila vieja debe caer en el mensaje genérico, nunca tronar**.

### 3. Qué ve el usuario

Cuatro textos, en `values/` y `values-en/`, con paridad de claves. **Línea base 547 = 547; cuenta
después, no confíes en ese número.** Nada de literales en Composables: la puerta del idioma de
`CLAUDE.md` debe salir vacía. Y en inglés redacta sin apóstrofos ("do not", no "don't").

| Motivo | Qué se le dice |
|---|---|
| `AI_QUOTA_EXHAUSTED` | se acabaron sus acciones de este mes, y que se reinician el mes que entra |
| `AI_RATE_LIMITED` | va muy rápido, que espere un momento y lo intente de nuevo |
| `AI_DISABLED` | CuentivoAI está en mantenimiento, que vuelva más tarde |
| `AI_ENTITLEMENT_REQUIRED` | **no es un error: es el camino de compra** |

Ese último es el importante. `AI_ENTITLEMENT_REQUIRED` significa que el servidor no le reconoce el
plan, y la respuesta correcta es llevarlo al muro de suscripción que T7 ya construyó — el mismo
patrón que ya usa `AiSaleExecutionResult.LimitReached`. **No lo pintes como falla.**

Y el texto genérico de `AI_UNAVAILABLE` se queda como está: sigue siendo el correcto para todo lo
demás.

## Invariantes que no se tocan

- **Registrar un pago o abono no se bloquea nunca**, por ningún motivo de IA, cuota ni plan.
  `FeatureAccessManager.canRegisterPayment()` devuelve `Allowed` siempre.
- **Nada de PII, montos, saldos, ids ni tokens** en la respuesta, en los logs ni en Analytics. Los
  cuatro status son vocabulario cerrado y por eso son seguros; no los acompañes de contexto.
- **Ningún motivo abre una ruta de escritura financiera nueva.**
- El motivo **no revela nada de otra cuenta**: cada usuario sólo sabe de sí mismo.
- Ninguna guarda se debilita. Esto cambia lo que se **dice**, no lo que se **permite** — igual que N4.

## Lo que NO entra

T5 (flag remoto en cliente), T8 (observabilidad y rollback), T9 (gate final y AAB). Tampoco la
función consumidora de las notificaciones de Play, que ya tiene su tópico creado y verificado pero es
su propio incremento. Ni cambiar topes, precios o límites: son decisiones de Diego.

## Evidencia obligatoria

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

En `functions/`: suite completa, build y lint de TypeScript, más pruebas contra el emulador que
cubran **los cuatro motivos por separado** y que confirmen que el resto sigue colapsando en
`AI_UNAVAILABLE`.

Y una prueba que vale por todas las demás: **un cliente que no conoce los status nuevos los trata
como `Unavailable`**. Es la que demuestra que no rompiste a nadie.

Conteo real del XML de `app/build/test-results/testDebugUnitTest/`. **Línea base 910 pruebas, 0
fallos, 0 errores** — debe subir. Paridad de claves ejecutada y reportada. `git diff --check` limpio
y árbol final con **sólo** `?? docs/superpowers/handoffs/`, que es de Diego y no se toca.

**No despliegues nada.** El deploy de functions lo autoriza Diego aparte.
