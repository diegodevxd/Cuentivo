# HANDOFF CUENTIVO7 — la verificación cruzada se manda SÓLO a la app que la pide

**Tarea:** `TASK_CUENTIVO7.md` · **Rama:** `mexalm/ledger-crosscheck` (base `ca93fd4`) ·
**Worktree:** `C:\Users\pibed\Downloads\CUENTIVO_mexalm_ledger` · **Fecha:** 2026-10-22

**Estado:** construido y probado. La bandera `MEXALM_LEDGER_CROSSCHECK` sigue **APAGADA** y no
hace falta tocarla para que este cambio viva en producción: con la bandera apagada la respuesta es
byte a byte la de siempre, y **con la bandera encendida y una app que no declara la capacidad,
también**.

No se hizo push, no se hizo deploy, **no se arrancó ningún emulador ni servidor**, no se instaló
nada en ningún dispositivo y no se tocó ningún otro worktree.

---

## 1. El defecto, en una línea

La app que hoy tienen los clientes compara el juego de llaves de la RAÍZ de la respuesta y, ante una
llave que no conoce, **rechaza la página entera**. El servidor agregaba `mexalmCrosscheck` a v3, v4 y
v5 con sólo encender la bandera. Encenderla, tal como estaba, habría dejado el escaneo mudo en todos
los teléfonos publicados a la vez — y el síntoma habría sido "la IA no sirve", no "llegó una llave de
más".

La regla exacta del parser publicado, en el commit `76242e5`
(`app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt`):

- **v3**: `rootKeys != baseKeys → Unavailable`. Juego exacto de siete llaves.
- **v4/v5**: `allowedKeys = baseKeys + pageDate (+ pageType, pageTotals en v5)`, y
  `!allowedKeys.containsAll(rootKeys) → Unavailable`. Lista cerrada: `mexalmCrosscheck` no está.

O sea: en ninguna versión la app vieja acepta el bloque.

---

## 2. La regla nueva: dos condiciones, no una

El bloque se agrega sólo si **las dos** se cumplen:

| `MEXALM_LEDGER_CROSSCHECK` | `acceptsMexalmCrosscheck` en la petición | Ledger | Bloque en la respuesta |
|---|---|---|---|
| apagada | ausente (app ≤ v48) | no se llama | no |
| apagada | `true` (app nueva) | no se llama | no |
| encendida | ausente o `false` (app ≤ v48) | **no se llama** | **no — bytes idénticos a hoy** |
| encendida | `true` (app nueva) | se llama una vez | sí |

Las dos condiciones existen por razones distintas y ninguna sustituye a la otra:

- **la bandera** dice si el servidor verifica;
- **la capacidad** dice si ESTA app sabe leer el bloque.

Y el "no se llama" de la tercera fila es una aserción sobre el **gasto**, no sobre el estilo: el
Ledger cuesta una ida y vuelta por página y no se toca si la app no puede leer el resultado.

### 2.1 La capacidad que no es booleana se RECHAZA

`acceptsMexalmCrosscheck` es opcional, pero si viene tiene que ser un booleano. Si no lo es, la
petición se rechaza con `invalid-argument` (que la app colapsa a `Unavailable`) **antes de gastar**:
ni Ledger ni modelo.

El "por qué" no es purismo: en JavaScript el texto `"false"` es **verdadero**. Un validador que se
tragara cualquier cosa y después preguntara por truthiness le mandaría el bloque —la llave que tumba
el escaneo— a un cliente que escribió `"false"`. "Ignorar lo que no entiendo" no existe aquí:
rechazar es lo único que no tiene que adivinar nada. Es también la política que ya tenía el resto del
validador.

El juego de llaves sigue siendo **cerrado**: cinco obligatorias, más opcionalmente ésta. Cualquier
otra llave sigue tumbando la petición (`knownCustomers`, `prompt`, `acceptsMexalmCrosscheckPlus`…
todas rechazadas, con prueba).

`warmMexalmLedger` no se tocó: es otra función y la app vieja no la llama.

---

## 3. Archivos exactos

### Servidor (`functions/`)

| Archivo | Qué cambió |
|---|---|
| `src/contract.ts` | `ExtractLedgerPageRequest.acceptsMexalmCrosscheck?: boolean`; `EXTRACT_LEDGER_OPTIONAL_KEYS`; el validador acepta el juego exacto "cinco + opcionalmente la capacidad", rechaza la capacidad no booleana y **omite la llave del valor validado cuando la petición no la trajo** (el valor de una petición vieja es exactamente el mismo objeto que antes). |
| `src/extractLedgerPage.ts` | `acceptsMexalmCrosscheck = validation.value.acceptsMexalmCrosscheck === true` y la verificación cruzada se corre **sólo** si eso es verdadero. Nada más: el bloque ya se agregaba al final y sólo si existe, así que la respuesta sin bloque es la de hoy. |

No se tocó `src/index.ts`: el composition root sigue armando el verificador cuando la bandera está
encendida y hay URL y token. Eso **no** llama al Ledger —construir el objeto no es una petición de
red—; quien decide si se usa es el handler, que es donde vive la petición ya validada.

### App (`app/`)

| Archivo | Qué cambió |
|---|---|
| `src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt` | `buildLedgerCallablePayload` manda `"acceptsMexalmCrosscheck" to true`. Siempre `true`: no depende de ninguna bandera local, porque quien decide si hay verificación es el servidor; este lado sólo declara lo que puede leer. |
| `src/test/java/com/cuentivo/app/ai/ledger/LedgerExtractionGatewayTest.kt` | La prueba del juego exacto de llaves incluye la nueva, y una prueba nueva afirma que viaja como booleano `true` (no como texto). |

### Pruebas del servidor

| Archivo | Qué se agregó |
|---|---|
| `test/extractLedgerPage.test.ts` | El bloque `verificación cruzada: la capacidad decide a quién se le cuenta`: 14 casos (ver §4). |
| `test/contract.test.ts` | 14 casos del validador de la capacidad: booleano aceptado, valor sin la llave cuando no viene, ocho formas no booleanas rechazadas, llaves de más rechazadas, capacidad que no sustituye una obligatoria, y aceptación en v3/v4/v5. |
| `test/mexalmCrosscheck.test.ts` | `NEW_APP_DATA` (la petición con la capacidad) para todos los casos que esperan bloque; `VALID_DATA` se queda para los que verifican que la app publicada recibe los mismos bytes. El caso "bandera apagada" ahora representa la bandera apagada como la representa el composition root (`crosscheck: null`) en vez de con un verificador de mentira, que probaba otra cosa. |
| `test/index.test.ts` | La prueba inestable (ver §5). |

---

## 4. La prueba de compatibilidad con la app vieja

Está en `test/extractLedgerPage.test.ts`, en el bloque
`compatibilidad con la app publicada (≤ v48)`, y tiene dos mitades que sólo sirven juntas.

**La regla copiada.** `oldAppAcceptsResponse()` es la transcripción de la regla de `allowedKeys` del
commit `76242e5`, con su comparación de juego exacto para v3 y su lista cerrada para v4/v5. Se copia
en vez de importarse porque es Kotlin y la prueba es TypeScript; se copia **tal cual**, porque una
versión "equivalente pero más amable" de esa regla no probaría nada: probaría que el servidor le
habla bien a un parser que no existe.

**Se le pasa la respuesta REAL del handler**, no una inventada: `createExtractLedgerPageHandler` con
un verificador cableado y la bandera encendida, y la petición de la app vieja (sin la capacidad).
Para v3, v4 y v5: `oldAppAcceptsResponse(resultado) === true`.

**La mitad que impide que la prueba sea teatro.** Los mismos tres casos, con la misma petición más
`acceptsMexalmCrosscheck: true`, afirman que el bloque **sí** llega y que **la misma regla copiada
rechaza esa respuesta** (`=== false`). Sin esta mitad, la prueba estaría en verde aunque la regla
copiada no se pareciera a la app vieja.

**Verificación por mutación.** Quitando la condición de capacidad del handler (llamando a
`runCrosscheck` incondicionalmente) se ponen rojas **7** de las pruebas de
`test/extractLedgerPage.test.ts`. La condición no es decorativa.

---

## 5. La prueba inestable: cuál era y por qué

**Era** `test/index.test.ts > regional callable gateway > exports interpretAiRequest, …`.

**Por qué.** Esa prueba hacía `await import("../src/index.js")` **dentro del cuerpo**, así que su
plazo de 5 s incluía cargar `firebase-functions` + `firebase-admin` + los siete handlers. Con la
caché de transformación de Vite fría —la primera corrida de una máquina limpia, un CI, o el primer
arranque del día— esa carga pasó de los 5 s y la prueba falló **por tiempo, no por lo que afirma**.
La corrida que falló lo dice sin ambigüedad: `import 10.32s` en el resumen, `Test timed out in
5000ms` en la prueba, **1 de 574**, y las cinco corridas siguientes en verde porque la caché ya
estaba tibia. Es el patrón exacto del reporte.

**El arreglo** es cargar el composition root al **importar el archivo** (import estático arriba), no
dentro de la prueba. La carga ya no cae dentro de ningún plazo.

**Por qué no la debilita:** se sigue importando el composition root real, las aserciones son las
mismas, y si el módulo no carga el archivo falla al recolectarse —ruidoso— en vez de esconderse
detrás de un `timeout` que parece un problema de la máquina. No se subió ningún `testTimeout` ni se
marcó nada como `skip`.

**Evidencia:** 15 corridas seguidas y 5 con `--sequence.shuffle` (semillas 7, 14, 21, 28, 35), 602 de
602 en las 20. En `MEXALM_TEST_OUTPUT_7.txt` está también el resumen de la corrida que falló, antes
del arreglo.

---

## 6. Cómo probarlo (números reales)

Todo en `MEXALM_TEST_OUTPUT_7.txt`.

| Comando | Resultado |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` (en `functions/`) | limpio |
| `npx vitest run` (en `functions/`) | **602 de 602**, 21 archivos |
| 15 corridas seguidas | 602 de 602 en cada una |
| 5 corridas `--sequence.shuffle` | 602 de 602 en cada una |
| `.\gradlew.bat testDebugUnitTest` | **1449 de 1449**, 155 clases, 0 fallos |
| `.\gradlew.bat assembleDebug -Pcuentivo.functionsEmulatorHost=127.0.0.1` | BUILD SUCCESSFUL · `app-debug.apk` 68 287 918 bytes · **no se instaló** |

Las 574 pruebas de antes siguen pasando; las 28 nuevas son 14 de la capacidad en el handler y 14 del
validador. En Android hay 1448 + 1.

**Lo que NO se corrió, y por qué:** `npm run test:firestore-emulator` y `npm run release:check`
levantan el emulador de Firestore, y esta tarea prohíbe arrancar servidores o emuladores. Por eso el
gate del emulador queda pendiente para quien pueda levantarlo (`/gate`), aunque este cambio no toca
Firestore ni las reglas.

---

## 7. El orden seguro para producción

**Tres pasos, y el primero no es el que el encargo sugiere.**

1. **Publicar las Functions primero**, con la bandera **apagada**. El validador nuevo acepta la sexta
   llave opcional; el de hoy la rechaza. Si se publicara la app antes que el servidor, **cada escaneo
   de la app nueva recibiría `invalid-argument`** y el reintento v5→v4 tampoco salvaría nada, porque
   el rechazo ocurre antes de mirar la versión. Con el servidor nuevo y la bandera apagada, la
   respuesta es idéntica a la de hoy para todo el mundo: nadie gana nada y nadie pierde nada.
2. **Publicar la app nueva** (la que manda la capacidad) y dejarla llegar a los teléfonos. Las apps
   viejas siguen igual: no mandan la capacidad y no reciben el bloque. La app nueva tampoco recibe el
   bloque todavía, porque la bandera está apagada.
3. **Encender `MEXALM_LEDGER_CROSSCHECK`.** A partir de ahí, sólo la app nueva recibe el bloque; la
   vieja sigue byte a byte igual. Encender es un parámetro, no un deploy: se apaga igual de rápido.

**Para revertir, al revés:** primero la bandera (instantáneo, sin deploy), después la app, y sólo
entonces el código de Functions. Bajar el código de Functions mientras circula una app que manda la
capacidad deja a esa app sin escaneo, porque el validador viejo rechaza la sexta llave.

**Antes de encender la bandera** tienen que existir `MEXALM_LEDGER_URL` y el secreto
`MEXALM_LEDGER_TOKEN`: sin ellos el servidor no arma el verificador y la bandera no hace nada (no
falla, simplemente no hay bloque). Eso ya estaba así y no cambia.

---

## 8. Riesgos y lo que queda abierto

- **El orden de despliegue es el riesgo real de este cambio**, no el código. Está en §7 y conviene
  tratarlo como parte del entregable: publicar la app antes que las Functions rompe el escaneo de esa
  app, con el mismo síntoma silencioso que este trabajo viene a evitar.
- **El bloque sigue siendo un contrato aditivo**: la app lo lee con tolerancia (bloque malformado →
  `null`, la página se lee igual) y sin él no cambia nada de lo que se registra.
- **La capacidad no es autenticación ni permiso.** No protege nada: sólo evita mandarle a una app una
  llave que su parser no conoce. Quien decide si hay verificación sigue siendo la bandera, y el
  Ledger sigue detrás del token de servidor.
- **La prueba que falta es de Diego**: encender la bandera en el emulador y escanear una página real
  con la app nueva. Eso no se puede hacer desde esta tarea (prohibido arrancar emuladores).
- No se tocó `warmMexalmLedger`, ni las reglas de Firestore, ni el camino de escritura, ni una sola
  línea de dinero.
