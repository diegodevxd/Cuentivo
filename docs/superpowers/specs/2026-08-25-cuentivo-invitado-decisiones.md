# Cuentivo — las dos decisiones que faltaban antes de tocar el arranque

**Diseño. 2026-08-25.** Escrito tras la revisión de Sol al plan de retención
(`2026-08-25-cuentivo-retencion-puerta-abierta.md`). Sol acertó en que estas dos decisiones son de
producto y no deben improvisarse dentro del código.

## Decisión 1 — Primer arranque sin conexión

**El problema que Sol encontró:** `signInAnonymously()` **necesita red**. La solución que iba a
dejar entrar a todos exige internet exactamente en el momento en que no lo habíamos previsto, y eso
choca con la identidad offline-first de Cuentivo.

**Alternativa descartada, y por qué.** Dejar entrar con una identidad local temporal y crear la
cuenta anónima cuando aparezca la red **reintroduce el problema que la sesión anónima venía a
evitar**: mover filas de un propietario a otro. Sería cambiar un riesgo conocido y acotado por uno
peor, ejecutado en el arranque de cada instalación sin señal.

**Decisión: se exige red una sola vez, en el primer arranque.**

- Pantalla honesta y breve, no un error: *"Sólo esta primera vez necesitamos conexión para preparar
  tu libreta. Después Cuentivo funciona sin internet."*
- Reintento visible. Sin formularios, sin cuenta, sin correo.
- Una vez creada, la sesión anónima **persiste en el teléfono**: no se vuelve a pedir red nunca para
  entrar.

**Por qué es aceptable:** quien acaba de descargar 67 MB de Play Store tiene señal en ese instante.
El costo es un caso borde raro; el beneficio es no inventar una migración de identidad.

**Si esto resulta ser un problema real** —se verá en la métrica— la salida correcta no es la
identidad temporal, sino permitir el uso local y **bloquear sólo lo que necesita uid**, con un
diseño propio.

## Decisión 2 — La cuenta ya existe al enlazar

**El caso:** el usuario trabajó como invitado, tiene datos, y al registrarse resulta que ese correo
o esa cuenta de Google **ya existen**. `linkWithCredential` falla.

**Lo que NO se hace, y es lo importante:** no se cierra sesión y se entra automáticamente con la
cuenta existente. Eso haría que `LocalSessionGuard` viera un propietario distinto y **borrara la
libreta local** sin que el usuario entendiera qué pasó. Es la peor falla posible de esta actualización.

**Decisión, adoptada de la revisión de Sol:**

1. **Se conserva la sesión anónima y todos sus datos.** El estado por defecto tras la colisión es
   "sigues trabajando como estabas".
2. Se informa con claridad que esa cuenta ya existe.
3. **No se fusiona ni se borra nada automáticamente.**
4. Entrar con la cuenta existente es posible, pero como **acción deliberada y aparte**, con una
   advertencia inequívoca: lo anotado en este teléfono **no se va a combinar** con esa cuenta.
5. **La fusión de datos queda fuera de alcance.** Si algún día se hace, es un diseño propio con sus
   propias pruebas: combinar dos libros mayores es un problema financiero, no de sesión.

**Regla de oro:** ante la duda, se conservan los datos locales y se le pregunta al usuario. Nunca se
borra para resolver un conflicto de identidad.

## Lo que estas decisiones NO cambian

- `LocalSessionGuard` sigue borrando cuando entra un propietario **realmente** distinto.
- `firestore.rules` no se toca. `isAuthenticated()` exige `email_verified == true`, así que los
  anónimos **ya** no pueden leer ni escribir la nube — y eso es exactamente lo que queremos.
- La nube sigue siendo Pro. Enlazar la cuenta no da sincronización: hace falta verificar el correo
  **y** tener Pro.
