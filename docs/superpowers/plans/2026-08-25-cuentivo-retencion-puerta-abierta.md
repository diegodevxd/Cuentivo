# Cuentivo — retención: dejar entrar antes de pedir la cuenta

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que una persona que instala Cuentivo pueda anotar su primer fiado **sin crear cuenta y sin salir de la app** (requiere conexión únicamente la primera vez para preparar la libreta, después Cuentivo funciona sin internet). Es la actualización 1 de tres, e iría como `versionCode 32`.

## Por qué, con datos

Firebase, cohortes semanales de retención a la semana 1:

| Cohorte | Semana 0 → 1 | Retención |
|---|---|---|
| 19–25 jul | 10 → 2 | 20% |
| 26 jul – 1 ago | 35 → 10 | 29% |
| 2–8 ago | 180 → 8 | **4.4%** |
| 9–15 ago | 145 → 1 | **0.7%** |

`EmailVerificationPolicy.LEGACY_ACCOUNT_CUTOFF = 2026-08-05`: desde ese instante toda cuenta nueva
**debe verificar su correo antes de entrar**. La caída coincide con esa fecha. Antes, Cuentivo
retenía por encima del promedio de fintech en LATAM (13% al día 1). `app_remove` = 219 contra
`first_open` = 385: el 57% desinstala.

**El argumento que lo cierra:** la nube es beneficio de Pro
(`SyncEntitlement.allowsCloudSync(tier) = tier == PRO`) y Room es la fuente de verdad. Para un
usuario Gratis los datos **nunca salen del teléfono**, así que la cuenta y la verificación se le
piden **a cambio de cero beneficio**, y antes de que vea si la app le sirve.

## La decisión de arquitectura

**Sesión anónima de Firebase al primer arranque**, no "modo sin cuenta".

- Con anónima **hay `uid` desde el segundo cero**, así que `ownerUserId` y todas las consultas por
  propietario siguen igual. No se inventa un dueño falso ni se toca el esquema.
- Al registrarse después se usa `linkWithCredential`: **el `uid` no cambia**. `LocalSessionGuard` no
  ve un dueño distinto, así que **no borra nada** y no hace falta migrar datos.
- Firestore no se toca: un anónimo nunca sincroniza, porque la nube es Pro.

Es la diferencia entre *cambiar de identidad* (peligroso) y *ponerle nombre a la que ya tenía* (seguro).

**La verificación de correo no se elimina, se mueve.** Su razón está escrita en el código: sin correo
probado nadie puede recuperar su contraseña y la libreta vive en un solo teléfono. Eso es cierto —
pero sólo importa cuando hay algo que recuperar. Se mueve al momento en que el respaldo existe.

## Global Constraints

- **Quien ya tiene cuenta y datos no debe notar absolutamente nada.** Entra igual, su libreta intacta.
  Esta actualización llega a ~280 personas con datos reales: una regresión aquí les vacía la libreta.
- **`LocalSessionGuard` no se debilita.** Sigue borrando cuando entra un propietario distinto. Lo que
  cambia es que enlazar una cuenta anónima **no es** un propietario distinto.
- Ningún cambio de esquema de Room ni migración. Ningún cambio en `firestore.rules`.
- La nube sigue siendo Pro. Un usuario anónimo no sincroniza.
- Sin dependencias nuevas.
- Cadenas en `values/` y `values-en/`, paridad cuadrada y puerta del idioma limpia.

## Task 1 — Política pura de acceso

- [ ] Extender `EmailVerificationPolicy` (o un objeto hermano puro) para el caso **cuenta sin correo**:
      una sesión anónima resuelve `ALLOWED`, nunca `MUST_VERIFY`. Hoy razona sobre cuentas con correo.
- [ ] Regla explícita: la verificación se exige **sólo** cuando la cuenta tiene correo **y** el
      respaldo en la nube está en juego.
- [ ] Pruebas JVM: anónimo entra; cuenta con correo sin verificar creada después del corte sigue
      bloqueada del respaldo; cuenta heredada anterior al corte sigue exenta; sesión cerrada sigue fuera.

## Task 2 — Sesión anónima y enlace de cuenta

- [ ] Al primer arranque, si no hay sesión, iniciar **sesión anónima** sin mostrar pantalla alguna.
- [ ] Al registrarse o entrar con Google desde una sesión anónima, usar `linkWithCredential` para
      **conservar el `uid`**. Nunca cerrar sesión y volver a entrar: eso cambiaría el propietario.
- [ ] Manejar el choque real: si el correo o la cuenta de Google **ya existen**, el enlace falla. Hay
      que decirlo con claridad y **no perder los datos locales** — decidir y documentar qué pasa ahí
      antes de escribir el código, no después.
- [ ] Verificar que `LocalSessionGuard` no dispare borrado en el enlace.
- [ ] Habilitar el proveedor anónimo en Firebase Auth es acción manual de Diego, fuera del repo.

## Task 3 — Primer momento de valor

- [ ] Primera pantalla al entrar sin datos: **"¿Quién te debe?"** → nombre y monto → libreta creada.
      Una pregunta, no un carrusel ni un tutorial.
- [ ] Se puede saltar. Nunca bloquea.
- [ ] Pedir la cuenta en los momentos de valor, con la razón visible: guardar el respaldo, comprar Pro,
      cambiar de teléfono. El texto deja de ser "regístrate para usar" y pasa a "crea tu cuenta para no
      perder tus N clientes".
- [ ] Cadenas en ambos idiomas, paridad y puerta del idioma.

## Task 4 — Compatibilidad y aceptación física

- [ ] **Prueba conectada de Room**: enlazar una cuenta a una sesión anónima **conserva** todas las filas.
      Es el caso que, si falla, borra la libreta de un usuario real.
- [ ] Prueba física en el A059P, en este orden:
      1. Instalación limpia → anotar un fiado **sin registrarse**.
      2. Registrarse después → los datos siguen ahí.
      3. Sesión con cuenta ya existente y datos → entra igual, nada cambió.
      4. Cerrar sesión y entrar con **otra** cuenta → sigue borrando, como debe.

## Gate

Toca sesión y borrado local: **gate completo**. Focales, suite JVM con `--rerun-tasks`, pruebas
conectadas acotadas
(`-Pandroid.testInstrumentationRunnerArguments.class=<clase>`, nunca la suite completa: borra la base
del dispositivo), `assembleDebug`, `assembleRelease`, `lintDebug`, puerta del idioma, paridad y
`git diff --check`. Aceptación física obligatoria en los cuatro escenarios.

## Cómo se mide si funcionó

Una sola métrica: **la retención a la semana 1 de la cohorte siguiente**. Si vuelve a los niveles de
julio (20–29%), era el muro. Si sigue en el suelo, era otra cosa y se sabrá con datos limpios.

Por eso esta actualización va **sola**, sin CuentivoAI: un paquete grande confunde la medición, y
CuentivoAI además sigue en NO-GO público hasta la Fase 2 comercial y el enforcement de App Check.

---

# Revisión de Sol y plan actualizado — 2026-08-25

Sol revisó este plan y **encontró un hueco que Claude no vio**: `signInAnonymously()` necesita red, y
el plan no definía qué pasa en una instalación sin conexión. Las dos decisiones de producto que
faltaban quedaron escritas en
`docs/superpowers/specs/2026-08-25-cuentivo-invitado-decisiones.md` — arranque sin red y colisión de
cuenta — antes de tocar código, que es lo que Sol pedía.

Sol también aportó, y se adopta: **el gateway de IA debe rechazar sesiones anónimas explícitamente**,
antes del proveedor y antes de consumir cuota, sin confiar únicamente en el entitlement. Y verificó
en las reglas que `isAuthenticated()` exige `email_verified == true`, así que **los anónimos ya no
pueden tocar Firestore** — coherente con el plan, pero hay que **probarlo**, no asumirlo.

Matiz de Claude aceptado por ambos: las cohortes de retención de Firebase se calculan sobre
identificadores de instancia de app, **no sobre uid de Auth**, así que la métrica del experimento
sigue siendo comparable. Lo que se infla es el conteo de usuarios en Firebase Auth; anotarlo para no
malinterpretarlo.

## Tasks actualizadas

- **Task 1** — Política pura de acceso (abajo, sin cambios).
- **Task 2** — Sesión anónima y enlace, ahora con el arranque sin red y la colisión **según la spec**,
  y con las pruebas de identidad que pidió Sol: mismo uid antes y después del enlace, cero filas
  borradas, ningún `businessId` ni `ownerUserId` cambiado.
- **Task 3 (nueva)** — **Rechazo explícito de anónimos en el gateway de IA.** Decisión estable propia,
  antes del proveedor y antes de tocar cuota. Probar que no llega a Gemini, no consume cuota, no crea
  ledger y no puede aprovechar un entitlement asignado por error.
- **Task 4** — Primer momento de valor.
- **Task 5** — Compatibilidad y aceptación física, más la prueba de que un anónimo **no** puede leer ni
  escribir Firestore, y que tras enlazar sigue sin sincronizar hasta verificar correo y tener Pro.
