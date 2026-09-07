# Fase B — Paso 3: ejecutores, fecha de la libreta y cargo sin producto (encargo para AGY)

**Fecha:** 2026-09-05
**Base:** pasos 1 y 2 commiteados en `b30a408` y `b3f21f2`. El servidor ya devuelve la lista de
operaciones; falta que la app sepa **registrar** dos formas que hoy no puede.
**Alcance:** SOLO el paso 3 del plan `docs/superpowers/plans/2026-09-05-cuentivoai-foto-libreta.md`.
**No** hagas la pantalla de revisión (paso 4) ni el registro atómico del lote (paso 5). Sin commits,
sin deploys.

## Los dos huecos

Mira `app/src/main/java/com/cuentivo/app/ai/AiSaleExecution.kt`:

1. **La fecha está clavada en hoy.** `saleDateEpochDay = today`. Una libreta trae la fecha en la que
   se hizo la venta, y registrar todo con la de hoy destruye justamente la historia que el usuario
   quiere migrar.
2. **Toda línea exige producto.** `AiSaleConfirmationLine` pide `productId` y
   `productNameSnapshot`. Una libreta dice "Juan, $300" sin decir de qué. El plan permite ese cargo
   **sin inventar un producto**.

## Antes de escribir código: averigua qué permite el esquema

**No supongas.** Ve a `data/local/FinancialDao.kt` y a `firestore.rules` y responde con evidencia:

- ¿Se puede registrar una venta **sin ítems**? Las reglas validan
  `subtotalCents == quantity * unitPriceCents - discountCents` por ítem y
  `totalCents == subtotalCents - discountCents` en la venta. ¿Qué exige además el `require(...)` de
  la transacción?
- Un **cargo a la cuenta** es un `AccountMovementEntity` de tipo `CHARGE`. ¿Puede existir sin venta
  que lo respalde, o hay una invariante que lo ata?
- La **deuda anterior** (`PRIOR_DEBT`) es un saldo que viene de antes. ¿Se representa con ese mismo
  cargo sin producto, o hace falta otra cosa?

**Si resulta que algo de esto no es representable con el esquema actual, para y dilo.** No inventes
un producto fantasma tipo "Varios", no aflojes una invariante y no agregues una columna: cualquiera
de esas tres cosas es una decisión de arquitectura que no te toca tomar sola. Reporta qué encontraste
y qué opciones ves.

## Reglas del proyecto que aplican aquí, sin excepción

- **Regla 1**: dinero en `Long` de centavos. Ni un `Double` ni un `Float`, ni siquiera intermedio.
- **Regla 2**: nada financiero se borra ni se edita. Un error se corrige con un movimiento
  compensatorio.
- **Regla 3**: toda consulta filtra por `businessId`.
- **Regla 4**: nada de PII ni saldos en logs.
- La **idempotencia real** es la tabla `operations`, con la clave generada por **código confiable**,
  nunca por Gemini, y ligada al negocio y al contenido ya confirmado. Sigue el patrón de
  `SaleOperationIds` que ya existe; no inventes otro.
- La auditoría se escribe **dentro** de la transacción financiera, como ya hacen los `*Idempotently`.

## Qué construir

1. **Fecha explícita.** Que el ejecutor reciba la fecha del renglón en lugar de clavar `today`.
   Sigue siendo `...EpochDay` — no mezcles las dos precisiones de fecha del proyecto. Si el renglón
   no trae fecha, quien llama decide cuál usar; el ejecutor **no** vuelve a poner hoy por su cuenta.
   Una fecha **en el futuro** no es una fecha de libreta: recházala.
2. **Cargo sin producto.** Un camino que registre el cargo a la cuenta del cliente con su
   descripción, sin producto y sin inventarlo. Decide, con lo que averiguaste arriba, si va como
   venta sin ítems o como movimiento de cargo, y **justifica la elección** contra lo que el esquema
   permite de verdad.
3. Deja el **camino de hoy intacto**: una venta normal con productos y fecha de hoy tiene que
   comportarse exactamente igual que antes, con sus pruebas sin editar.

## Pruebas

En `app/src/test/` (JVM), que es donde corre lo puro. Recuerda la regla más importante del
proyecto: **una prueba no puede reimplementar la lógica que verifica**; tiene que invocar el código
que producción ejecuta y **fallar si ese código se rompe**. Si lo que importa quedó dentro de un
Composable o de una Activity, la salida correcta es **extraerlo**, no reescribirlo en el test.

Como mínimo: la fecha de la libreta llega a `saleDateEpochDay`; una fecha futura se rechaza; el
cargo sin producto queda registrado y **mueve el saldo del cliente en la dirección correcta**
(consultado por el mismo camino que usa producción, no por un cálculo del test); el saldo se sigue
derivando y no se guarda; reintentar la misma confirmación **no duplica** el cargo; y el camino de
venta normal no cambió.

Si algo sólo se puede probar contra Room, va en `androidTest/` y **hay que decirlo**: esas pruebas
necesitan emulador y no corren en el gate de JVM.

## Gate

Gradle necesita el trust store de Norton y `--no-watch-fs`:

    $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
    .\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain

Usa `--rerun-tasks`: sin él Gradle devuelve "BUILD SUCCESSFUL" con todo cacheado y ese verde no
prueba nada. Corre también `assembleDebug`. Reporta el número real de pruebas antes y después y el
comando exacto. No inventes resultados de build.

---

# Ronda 2 — revisión de Codex (2026-09-06)

Codex **no cierra el paso 3**. Confirmó las tres sospechas y encontró tres cosas más, en orden de
gravedad.

## 1. Confundir un olvido del programador con una decisión financiera — defecto

Con los valores por defecto se puede construir una línea indicando **sólo los dos importes**: queda
sin producto, cantidad 1 y descripción vacía, y el ejecutor la convierte **en silencio** en "Saldo
anterior". Un olvido de quien programa termina escrito en el libro mayor como una deuda anterior
que nadie decidió.

**Quita los defaults.** Distingue los dos casos con un tipo sellado —línea de catálogo y cargo sin
producto— para que el compilador obligue a cada punto de llamada a decir cuál es. "Saldo anterior"
tiene que ser una **deuda anterior elegida**, nunca el relleno de una descripción ausente.

## 2. El DAO acepta más de lo que nadie autorizó — defecto

En `FinancialDao.kt:787`, `productId == null` omite **toda** la validación de producto para
cualquier llamada al ejecutor compuesto. Las condiciones aritméticas que sí validas —cantidad 1,
importe positivo, subtotal igual, fingerprint vacío— viven en el servicio, y el DAO **no las exige**.

El DAO es donde viven las invariantes de este proyecto; que la garantía dependa de que el llamador
se porte bien es justo lo que ese archivo existe para evitar. Que el caso nulo **exija esas
condiciones ahí**.

## 3. La fecha histórica está aplicada a medias — defecto

- **El abono inicial sigue fechado hoy.** Una venta antigua ya pagada queda como cobro de hoy y como
  pago tardío frente a su vencimiento. La fecha tiene que llegar también al pago.
- **Falta validar `promisedPaymentEpochDay >= effectiveSaleDate`**, que las reglas de Firestore sí
  exigen. Hoy se puede escribir una fila local que **nunca sincroniza**: el usuario la ve en su
  teléfono y no existe en ningún otro lado.
- Omitir la fecha sigue poniendo hoy. Para el camino normal está bien; para el de libreta, quien
  llama debe decidirla. Sepáralos.

## 4. OpeningBalance quedó duplicado, y ya divergen

El ejecutor sólo toma `LINE_NAME` y arma sus propias entidades. La divergencia ya existe:
`OpeningBalance` rechaza descripciones vacías y recorta espacios; el ejecutor las sustituye.

Extrae la construcción común y úsala en los dos lados, **sin** convertir todo cargo sin producto en
deuda anterior: son cosas distintas que comparten forma. Y acota la descripción a los **160
caracteres** de Firestore, o es otra fila que se escribe local y nunca sube.

## 5. La clave de idempotencia no incorpora el contenido confirmado

Sigue dependiendo sólo de `confirmationKey`. Reusarla con otra fecha u otro importe devuelve el
resultado anterior; generar otra permite otro cargo. Resuélvelo, o **escríbelo como pendiente
explícito** en el código si pertenece al paso 5.

## 6. Los 972 verdes no cubren el cambio financiero — el más importante

`registerSale` es **una función falsa que devuelve `Recorded`**. Las pruebas demuestran qué
entidades se construyen; **no** que el saldo suba, que la auditoría se escriba, ni que un reintento
deje un solo cargo. Es el defecto que este proyecto ya tuvo cinco veces.

Dos pruebas además no prueban lo que dicen: la de "mismatch total" no incluye ese caso, y la de
importe cero usa un total cero que se rechaza **antes** de llegar a la guarda nueva.

Hace falta una prueba contra Room en `androidTest/`: cargo sin producto, **consulta real del saldo**
por el mismo camino que usa producción, misma confirmación repetida, un solo cargo y una sola fila
de auditoría, más el rollback ante un fallo posterior.

Si el emulador aborta con `INSTRUMENTATION_ABORTED` o `RootViewWithoutFocusException`, arranca en
frío con `adb emu kill` y `emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim`. Si no logras
correrlas, **dilo**; no las des por cubiertas con JVM.

## 7. Riesgo preexistente: confírmalo, no lo arregles

El DAO inserta cliente y productos **antes** de algunas salidas `StaleData`, y devolver ese
resultado **no es rollback** de lo insertado. Es anterior a este trabajo: confírmalo y repórtalo,
pero no lo toques en este paso.

## Gate

`testDebugUnitTest --rerun-tasks` y `assembleDebug`, más las instrumentadas si logras correrlas.
Números reales y comandos exactos. Sin commits ni deploys.

---

# Ronda 3 — lo último del paso 3 (2026-09-06)

Codex reconoce el avance: el DAO ya exige cantidad 1, importe positivo, descuento cero y subtotal
igual al precio para el caso sin producto; la descripción quedó acotada a 160; desapareció el
reemplazo silencioso de la descripción; y la prueba instrumentada **sí** recorre servicio →
repositorio → Room y consulta el saldo real. Quedan dos bloqueos y dos afinaciones.

## 1. El tipo sellado quedó debilitado por el `invoke` del companion — **defecto, y peor que el anterior**

Los constructores explícitos distinguen bien `CatalogProduct` de `UncatalogedCharge`, pero **sigue
permitido** llamar `AiSaleConfirmationLine(productId = null, …)`. Y esa fábrica **descarta en
silencio** `quantity` y `sourceProductFingerprint` cuando el id es nulo: una entrada con **cantidad
2** o con un fingerprint indebido se convierte en un cargo de cantidad 1 sin fingerprint, **esquivando
el rechazo que acabas de escribir**.

Eso es peor que los defaults que reemplazó: antes el olvido producía una línea rara, ahora una
entrada **inválida** se normaliza sola hasta parecer válida. **Quita la ruta nullable.** Si hace
falta compatibilidad para el camino de catálogo, que sea una fábrica que no acepte id nulo.

## 2. La fecha del camino de libreta sigue siendo opcional

Omitir `saleDateEpochDay` sigue poniendo hoy. Para el camino normal está bien; el de libreta **debe
exigirla**. Sepáralo de forma que el compilador lo imponga, en vez de dejar el mismo default para
los dos.

## 3. Dos aserciones que pueden pasar por casualidad

- **Auditoría**: la prueba sólo exige que **aumente**. Con abono inicial, pasaría aunque faltara
  específicamente el evento de la venta. Afirma el evento que esperas, no un contador.
- **Precio cero (JVM)**: aunque se quite su guarda, el total calculado (cero) difiere del total
  solicitado (1000) y se rechaza igual, así que la prueba no aísla lo que dice aislar. Necesita otra
  línea válida y un total que cuadre.

## Gate

`testDebugUnitTest --rerun-tasks`, `assembleDebug` y las instrumentadas. El emulador ya está
levantado y desbloqueado. Números reales. Sin commits.
