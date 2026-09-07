# Fase B — Paso 5: registro atómico del lote (encargo para AGY)

**Fecha:** 2026-09-06
**Base:** pasos 1 a 4 commiteados (`b30a408`, `b3f21f2`, `aadbb5c`, `903d12e`, `90593ed`). El
servidor lee la foto, la pantalla de revisión existe y está enganchada, y los ejecutores ya saben
registrar con la fecha de la libreta y cargos sin producto.
**Alcance:** SOLO el paso 5 del plan `docs/superpowers/plans/2026-09-05-cuentivoai-foto-libreta.md`.
No la prueba física con libreta real, que es el paso 6 y la hace Diego. Sin commits, sin deploys.

## Este es el paso que escribe dinero

Todo lo anterior propone; esto registra. Las reglas del proyecto aplican sin excepción y sin
interpretación creativa:

1. **Dinero en `Long` de centavos.** Ni un `Double` ni un `Float`, ni siquiera intermedio.
2. **Nada financiero se borra ni se edita.** Un error se corrige con un movimiento compensatorio.
3. **Toda consulta filtra por `businessId`.**
4. **Nada de PII ni saldos en logs.**
6. **No declares esto terminado sin pruebas reales ejecutadas.** Contra Room, no contra dobles.

## Todo o nada

Para un máximo de 25 operaciones: **validar, resolver los clientes compartidos, calcular los saldos
intermedios y escribir, en una sola transacción**. La llamada a Gemini y la red quedan fuera. El
plan explica por qué esto elimina de raíz problemas que el éxito parcial sí tiene:

- **Dos abonos del mismo cliente.** `FinancialDao.kt:585` compara contra el saldo esperado, así que
  el segundo sería rechazado después de aplicarse el primero. Los saldos intermedios se calculan
  **antes** de escribir.
- **Un cliente repetido en varias filas se crea una sola vez**, no una por renglón.
- **Resultado desconocido** deja de ser un caso que distinguir.

**Ojo al integrar:** algunos ejecutores devuelven `StalePreview` en vez de lanzar. El ejecutor del
lote debe convertir **cualquier** rechazo en rollback. Un lote a medias es exactamente lo que este
paso existe para impedir.

## Usa `confirmLedger`, no el ejecutor estándar

`AiLedgerSaleConfirmationRequest` existe para esto: exige `saleDateEpochDay` y el compilador lo
impone. Entrar por el camino normal pierde esa frontera y vuelve a fechar la libreta con el día de
hoy.

## La clave de idempotencia: pendiente que se cierra aquí

Hoy depende sólo de `confirmationKey`. Reusarla con otra fecha u otro importe devuelve el resultado
anterior; generar otra permite escribir otra vez lo mismo. **Lígala al contenido aprobado** —negocio
más la lista confirmada— y **generada por código confiable, nunca por Gemini**. Sigue el patrón de
`SaleOperationIds`, no inventes otro.

Queda un caso que la idempotencia técnica no resuelve y **no debes intentar resolver escribiendo**:
volver a fotografiar la misma página produce propuestas nuevas con claves nuevas. Eso se detecta en
la revisión, no en la escritura.

## Qué entra y qué no

- Sólo entran las operaciones **registrables y ejecutables**: `isRegisterableLedgerOperation` más la
  validación financiera de la revisión. Reusa las dos, **no escribas una tercera versión**.
- Un renglón con duda sin resolver o campo ilegible **no entra**, y el lote debe decir cuántos
  quedaron fuera.
- **La fecha de página**: hay renglones que la libreta no fecha. Hoy `date` es opcional y
  `AiLedgerSaleConfirmationRequest` exige fecha, así que **alguien tiene que elegirla**. Es la
  decisión de producto del plan: si el renglón no trae fecha, el usuario elige una para la página.
  Impleméntala aquí, incluida su UI mínima, y que **no se pueda confirmar** un lote con renglones
  sin fecha y sin fecha de página.

## La auditoría

Se escribe **dentro** de la transacción financiera, como ya hacen los cinco `*Idempotently`. El id
sale de `AuditTrail.eventId(idempotencyKey)`, determinista, así que reintentar no duplica el evento.
`AuditAction.summary` es una frase fija: **no la interpoles** con nombre, teléfono ni monto, y no la
traduzcas al escribir.

## Pruebas

**Contra Room, en `androidTest/`**, siguiendo `AiSaleExecutionIsolationTest`, que ya hace justo esto
para una venta. Como mínimo:

- Un lote de varias operaciones deja los saldos correctos, **consultados por el mismo camino que usa
  producción**, no por un cálculo del test.
- **Dos abonos del mismo cliente** en un lote se aplican los dos.
- Un cliente repetido en tres renglones se crea **una vez**.
- **Un fallo a mitad no deja nada escrito**: cero ventas, cero movimientos, cero clientes, cero
  auditoría.
- Reconfirmar el mismo lote **no duplica** ni filas ni eventos de auditoría.
- Un lote con un renglón no registrable **no lo escribe** y lo reporta.
- La fecha de la libreta llega a `saleDateEpochDay`, y la de página se usa sólo donde faltaba.

En JVM va la planificación pura: saldos intermedios, deduplicación de clientes, qué entra.
**Una prueba no puede reimplementar la regla que verifica.**

El emulador se degrada: si la suite aborta con `INSTRUMENTATION_ABORTED`, arranca en frío con
`adb emu kill` y `emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim`, y espera a que
`adb shell dumpsys user` dé `0=RUNNING_UNLOCKED`. `adb devices` puede decir `device` con el sistema
a medias. Si no logras correrlas, **dilo**; no las des por cubiertas con JVM.

## Dos pendientes que arrastra el 4b

No son de este paso, pero te los vas a encontrar:

- **La máquina de estados no sobrevive la recreación de la Activity** durante extracción o revisión:
  se conserva la ruta de la foto pero se pierde el modelo, y no hay recuperación ni descarte
  explícito. Si lo tocas, dilo aparte.
- **Compartimos cuota de acciones con el camino de texto, no presupuesto de dinero.** Una foto
  cuesta mucho más que una frase. Es una decisión de Diego, no la tomes tú.

## Gate

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Más `assembleDebug`, las instrumentadas, la paridad de claves y la puerta del idioma si tocas
textos. Cuenta las pruebas desde los XML de `app/build/test-results/`, no del texto de Gradle.
Reporta números reales y comandos exactos. No inventes resultados de build.
