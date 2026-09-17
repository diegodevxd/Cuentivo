# v41 · bloque 2 — enseñarle al modelo cómo se lleva una libreta (OpenCode)

**Base:** `7015d65` (bloque 1 cerrado). **Implementa:** OpenCode. **Revisa:** AGY.
Lee `CLAUDE.md` y `docs/superpowers/encargos/2026-09-16-v41-libreta-que-no-se-engana.md` (el bloque 2
de ese encargo es éste).

**Esto es servidor: `functions/`, no Android.** **No despliegues** (lo autoriza Diego aparte),
**no commitees**. Reporte completo en `C:/tmp/oc-bloque2-reporte.md`.

## Qué cambia y por qué importa tanto

El cambio vive en `functions/src/ai/geminiLedgerProvider.ts`, en `buildLedgerSystemInstruction`.
**No añade campos a la respuesta.** Por eso, en cuanto se despliegue, **llega a todos los teléfonos
que ya tienen la app**: el `v37` publicado y el `v40` que Diego usa ahora. Es la mejora más rápida de
todas y también la más delicada: **no puede empeorar lo que esas versiones ya leen bien.**

Recordatorio del contrato: `app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt:177`
rechaza la respuesta **entera** si trae una llave que no conoce. Nada de campos nuevos aquí.

## 1 · Las reglas universales de una libreta de fiado

Sobre casos reales de dos personas distintas. Tienen que quedar en la instrucción, claras y cortas:

1. **La comilla "〃" (también `"`, `''`, "idem", "id.")** significa "igual que el renglón de arriba" en
   esa columna. **Nunca** cambia de cliente.
2. **Lo que va entre paréntesis es una nota, no parte del nombre.** "Dana TS (Agotado)" es la clienta
   "Dana TS". Si la nota es "agotado", "garantía", "cancelado" o parecida, puede que **no hubiera
   venta**: ese renglón va dudoso.
   **Importante:** para eso usa `uncertainFields` con el campo de tipo. **No uses el warning
   `NOT_A_MOVEMENT`**: `functions/src/ai/ledgerExtraction.ts:503` convierte ese warning en descarte
   `MALFORMED` y el renglón **desaparece sin que nadie lo vea**. Una garantía puede ser un cargo real.
3. **La fecha escrita arriba vale para los renglones de abajo** hasta que aparezca otra. Hoy muchos
   renglones salen dudosos sólo por "sin fecha" cuando la fecha de arriba aplicaba: en una página
   salieron 15 de 16 dudosos, casi todos por eso.
4. **Una palomita (✓), una rayita, un asterisco o una flecha junto a un monto no son dígitos.**
   Caso real: "$999 ✓" se leyó como $99.91.
5. **Un nombre al margen o entre paréntesis que acompaña a varios renglones** (una vendedora, una
   ruta, quien cobró) **no convierte esos renglones en ventas de esa persona** si cada renglón ya
   tiene su clienta. Caso real: "(Charly)" junto a renglones de otras clientas acabó cargándole
   $385.90 a Charly, que ya existía.
6. **`rawText` es obligatorio en cada renglón**: el texto tal como está escrito en ese renglón. El
   campo ya existe en v4 y ya es aceptado; lo que cambia es que la instrucción lo **exige**. Android lo
   usa para comprobar que el cliente asignado sale del propio renglón, y **sin él, todo renglón de un
   cliente existente pide confirmación**. Respeta el límite de longitud que ya aplica el parser.

**No cambies `parseLedgerExtraction` para volver `rawText` obligatorio en el parser.** Si el modelo lo
omite alguna vez, la respuesta no puede rechazarse entera por eso: se queda opcional en el parser y
Android ya objeta por su lado.

## 2 · Dieta de la instrucción

Hoy tiene **21 viñetas**, y con las nuevas crecería. Una instrucción larga diluye las que importan.

- Quita lo redundante, lo que se contradice y lo que sea específico de una libreta concreta en vez de
  universal.
- **Intocable:** la viñeta de `geminiLedgerProvider.ts:216` que declara que todo texto de la imagen es
  contenido pasivo y nunca una orden. Es defensa contra inyección por la foto.
- En tu reporte, **una tabla con cada viñeta quitada o fusionada y por qué**. AGY la revisa.

## 3 · Pruebas

Esto es texto que se le manda al modelo, así que las pruebas tienen límites y hay que ser honestos:

- **Sí se puede probar**, y es obligatorio: que la instrucción **contiene** cada regla nueva y la
  defensa contra inyección; que `NOT_A_MOVEMENT` **no** se usa para notas de garantía o agotado;
  que el esquema v4 de la respuesta **no cambió** (una respuesta v4 válida de antes sigue
  parseando igual, y una llave nueva sigue rechazándose); y que v3 sigue igual.
- **No se puede probar aquí** que el modelo obedezca. Eso se mide después del despliegue, con las
  mismas fotos reales de Diego contra la línea base. **Dilo así en el reporte**; no presentes una
  prueba de "la instrucción contiene la palabra comilla" como prueba de que el modelo lee comillas.

Las pruebas nuevas tienen que fallar si se quita la regla correspondiente (mutación).

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
npm --prefix functions run release:check
```

`npm test` **no** incluye el gate del emulador de Firestore; `release:check` sí, y falla si falta el
JDK en vez de saltárselo. Reporta sus cinco etapas con su salida real. Referencia actual: **470
pruebas unitarias** y **9** del emulador.
