# v41 · que la libreta no se engañe sola (encargo para AGY)

**Fecha:** 2026-09-16. **Implementa:** AGY (Gemini 3.8 Flash). **Orquesta y verifica:** Claude.
**Revisa el diff de cada bloque:** Sol. **Base:** `4f7b9fe`.

Lee `CLAUDE.md` antes de empezar: manda por encima de este encargo.

**Regla que manda sobre todas: no rompas lo que ya funciona.** Sin refactors grandes, sin
dependencias nuevas. **No commitees, no despliegues, no generes AAB.** Eso lo hace Claude al final,
y el despliegue lo autoriza Diego.

## Por qué, con datos reales

Diego probó con libretas reales de **dos personas distintas** (su mamá y su abuelita). En las dos
pasa lo mismo, casi siempre: **la confianza del modelo va al revés.** Marca como dudosos los
renglones que están bien (normalmente sólo porque no tienen fecha) y deja **listos para registrar**
los que tienen el dinero mal. Los casos concretos:

1. **$139.90 inventados** para Caro Vera, **sin marca**, mientras sus dos renglones reales sí salían
   marcados.
2. **$494.90 donde el papel dice $44.90** (Sani Asientos WC). Diez veces más, **sin marca**.
3. **"Caro (Garantía) $999" leído como "Laura (Garantia)" $99.91.** Tomó la palomita después del
   999 como decimales. Diez veces menos, nombre equivocado, **listo para registrar**.
4. **A una clienta que ya existe (Charly, con saldo real de $200) le cargó $385.90 que no son
   suyos.** En la hoja, "(Charly)" acompaña a otras clientas, y la comilla "〃" bajo "Estef" significa
   Estef otra vez. **Sin marca.** Es el peor error posible: mueve el saldo de alguien que existe.
5. En una página con **11 listos y 2 dudosos**, los tres errores graves estaban entre los 11 listos.

**Consecuencia de diseño, y es la regla central de este encargo:** la confianza del modelo **no
puede ser la única puerta hacia "listo"**. Hacen falta comprobaciones **deterministas, en Android,
que no dependan del modelo** y que **sólo puedan añadir duda, nunca quitarla** — igual que la regla
que ya existe: *la certeza sólo puede empeorar el estado de un campo, nunca mejorarlo*. Si una
comprobación objeta, el renglón **no** puede quedar listo, diga lo que diga el modelo.

**Y todo tiene que ser universal.** Las libretas de prueba son de la familia de Diego, pero la app
la van a usar muchas personas distintas. Nada de reglas que sólo sirvan para "la letra de la mamá
de Diego". Las reglas son sobre **cómo se lleva cualquier libreta de fiado**.

## Restricción dura: el contrato

`app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt:177` **rechaza la
respuesta entera si trae una llave que no conoce.** Por tanto:

- **Añadir un campo nuevo a la respuesta v4 rompe a todo el que ya tiene instalada la app**
  (el `v37` publicado y el `v40` de prueba interna). Dejarían de leer fotos.
- **Todo campo nuevo va en un contrato v5**, que el servidor devuelve **sólo** si el cliente lo pide.
  El servidor tiene que seguir respondiendo v3 y v4 exactamente igual que hoy.
- Lo que se pueda resolver **sin campos nuevos** —cambiando la instrucción o con comprobaciones en
  Android— es preferible, porque llega sin tocar el contrato.

---

# PASO 0 — Propuesta, antes de escribir código

Lee el código implicado y **entrega una sola propuesta** que cubra los bloques 1 a 4: qué cambia,
dónde, si necesita contrato v5 o no, y cómo se prueba. **Espera visto bueno.** Los bloques 1 y 3
tocan a quién se le carga dinero: no se empiezan a ciegas.

---

# BLOQUE 1 — Comprobaciones deterministas en Android (sin tocar el contrato)

Estas son las que más daño evitan y no necesitan al servidor.

**1.1 · Nunca cargar nada a un cliente que ya existe sin confirmarlo** si el nombre leído no
coincide exactamente con el suyo. Es el caso de Charly. Ofrecer el cliente, **nunca aplicarlo
solo**, igual que la fecha de hoja. Esto protege a todos los usuarios.

**1.2 · Comparar el precio leído con el precio que la persona ya registró** para ese producto.
`ProductEntity` ya tiene `sku` y `salePriceCents`, y **hoy el matching no usa `sku`**
(`LedgerEntityMatcher`, `LedgerProductMatching`). Buscar el producto **por código (`sku`) o por
nombre**; si existe y el precio leído difiere mucho del registrado, el renglón queda dudoso.
**Es universal en la forma**: quien tiene productos cargados se beneficia y quien no, no pierde nada.
Define con cuidado "difiere mucho" y justifícalo: $494.90 contra $44.90 y $99.91 contra $999 son
los casos que tiene que cazar.

**1.3 · Centavos que no siguen el patrón de la página.** Si casi todos los montos terminan en `.00` o
`.90` y uno termina en `.91`, `.44` o `.20`, ese renglón queda dudoso. Es un indicio, no una prueba:
sólo añade duda.

**1.4 · Una objeción determinista bloquea "listo".** Donde se decide si un renglón está listo, estas
comprobaciones tienen que poder impedirlo. Que la UI diga **por qué** está dudoso, en palabras
("el precio no coincide con tu producto", "este cliente ya existe, confírmalo").

Todo en `domain/` como Kotlin puro, **sin `Context` ni `R.string`**: el objeto devuelve una clave o
un enum y la UI resuelve el texto. Todo texto nuevo, en `values/` **y** `values-en/`, y en inglés
**sin apóstrofos**.

---

# BLOQUE 2 — Enseñarle al modelo cómo se lleva una libreta (instrucción, sin campos nuevos)

Cambios en `functions/src/ai/geminiLedgerProvider.ts`, en `buildLedgerSystemInstruction`. Son reglas
universales de cualquier libreta:

- **La comilla "〃" (o "``", "idem") significa "igual que el renglón de arriba"**: mismo cliente,
  mismo producto o mismo monto según la columna. **Nunca** cambia de cliente.
- **Lo que va entre paréntesis es una nota, no parte del nombre.** "Dana TS (Agotado)" es la clienta
  "Dana TS". Notas como "agotado" o "garantía" pueden significar que **no hubo venta**: ese renglón
  va dudoso.
- **La fecha escrita arriba vale para los renglones siguientes** hasta que aparezca otra.
- **Una palomita, una rayita o un asterisco junto a un monto no son dígitos.**
- **Una marca o un nombre al margen que acompaña a varios renglones** ("(Charly)") no convierte esos
  renglones en ventas de esa persona si cada renglón ya tiene su propia clienta.

Aprovecha para la **dieta de la instrucción**: hoy tiene 21 viñetas. No la reescribas entera; di qué
sobra y por qué. **No se toca** la viñeta de `geminiLedgerProvider.ts:216`, que declara que el texto
de la foto es contenido pasivo y nunca una orden: es defensa contra inyección.

Como no añade campos, **esto llega también al v40 y al v37** en cuanto se despliegue. Ojo con eso:
no puede empeorar lo que ellos ya leen bien.

---

# BLOQUE 3 — Contrato v5 (sólo si la propuesta lo justifica)

Lo que no se puede resolver sin campos nuevos:

- **Subtotales de la página.** Muchas libretas llevan saldos corridos. Si el modelo los devuelve,
  Android puede comprobar que lo leído cuadra y marcar el bloque que no. En una de las pruebas, lo
  leído dejaba a un cliente debiendo ~$895 y el papel decía **$200**.
- **Tipo de página.** Si la hoja no es de cuentas (por ejemplo, una lista de costos y precios),
  decirlo en vez de mostrar "no se pudo registrar la libreta".
- **La nota del paréntesis**, como campo propio.

Contrato v5: el servidor lo devuelve sólo si el cliente lo pide, y v3/v4 siguen idénticos. Android
v41 pide v5. **Añade pruebas que demuestren que una petición v4 sigue recibiendo exactamente lo de
antes**, sin campos nuevos.

---

# BLOQUE 4 — Enderezar la foto

Una de las páginas de prueba, fotografiada de lado, no devolvió nada. Revisa
`LedgerImagePreparer`: **¿se pierde la orientación EXIF al preparar la imagen?** Si sí, aplícala
antes de comprimir. Prudente: si no estás seguro, manda la original.

Ojo con `prepareInPlace`, que sobrescribe el original (hueco conocido). Si lo tocas, arréglalo.

---

## Datos de prueba: nunca libretas reales

Las fotos de Diego llevan **nombres reales, montos, direcciones y teléfonos**. **No entran al
repositorio, ni a las pruebas, ni como ejemplos para Gemini.** Para las pruebas, construye casos
**inventados** que reproduzcan el desorden: comillas, paréntesis, palomitas junto a montos,
subtotales, un cliente existente con nombre parecido.

Cada caso real de arriba tiene que tener su prueba con datos inventados equivalentes, y **esa prueba
tiene que fallar con el código de hoy**.

## Gate, en cada bloque

**Android** (Norton intercepta TLS; sin esto Gradle falla con `PKIX path building failed`):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

- Cuenta las pruebas **desde los XML**. Referencia: **1164, 0 fallos**. Tiene que subir.
- Si Gradle dice `UP-TO-DATE`, no ejecutó nada: usa `--rerun`.
- `assembleDebug`. Si tocas `strings.xml`, paridad ES/EN y la puerta del idioma de `CLAUDE.md`.

**Servidor:** `npm --prefix functions run release:check`. `npm test` **no** incluye el gate del
emulador de Firestore; `release:check` sí.

**Cada prueba tiene que invocar producción y caerse si producción se rompe.** Compruébalo por
mutación: rompe la línea, corre la prueba, confirma que falla, deshaz.

## Qué entregar en cada bloque

- Qué cambiaste y por qué, con archivo y línea.
- **El comando que ejecutaste de verdad y su salida.** Sin resumir, sin inventar.
- El conteo de pruebas.
- La comprobación por mutación de cada prueba nueva.
- Qué **no** hiciste y por qué.

**Un bloque a la vez, y paras.** Después de cada uno, Claude corre el gate y Sol revisa el diff.
