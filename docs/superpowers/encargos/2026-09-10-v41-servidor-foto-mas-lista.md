# v41 · servidor: que la foto se lea mejor (encargo para AGY)

**Fecha:** 2026-09-10. **Implementa:** AGY. **Orquesta y verifica:** Claude. **Revisa el diff:** Sol.
**Base:** `ffda6e5`.

**Esto es `functions/`, no Android.** Y por eso es lo primero: la lectura de la libreta ocurre en el
servidor, el contrato v4 ya está desplegado y **acepta v3 y v4**, así que una mejora aquí **llega a
todos los teléfonos instalados sin publicar nada en Play** — incluido el `versionCode 40` que Diego
está usando ahora mismo para recoger la línea base.

**Reglas que mandan sobre este encargo:**

- **No despliegues.** Antes de cualquier despliegue va `npm --prefix functions run release:check`, y
  la autorización es de Diego, aparte. Tú escribes y pruebas.
- **No rompas la compatibilidad v3/v4.** Es lo que protege al `v37` publicado.
- **`npm test` NO incluye el gate del emulador de Firestore.** `vitest.config.ts` sólo toma
  `test/**/*.test.ts`; el gate va aparte con `npm run test:firestore-emulator`. Reportar el total de
  `npm test` como si cubriera el gate es reportar evidencia que no ocurrió.
- Nada de PII en logs.

## Por qué, con el dato delante

Diego lo dijo así: en **texto y voz funciona; la foto es lo que a veces falla**. Y el código explica
por qué sin misterio:

`functions/src/ai/geminiTextProvider.ts:5` define `GEMINI_MODEL = "gemini-3.5-flash-lite"`, y
`geminiLedgerProvider.ts:317` usa **esa misma constante** para leer la libreta. Es decir: **la tarea
más difícil —letra a mano, hoja torcida, tachones, columnas inventadas— corre en el modelo más
barato, el mismo que las dos tareas fáciles.** No es `flash`: es `flash-lite`.

## El caso real que hay que cazar, y que condiciona todo

De la prueba de Diego con su libreta, **el único dato duro que existe**:

> Hoja del **03/06/26**, 9 renglones. Los 9 detectados, notas no-movimiento bien excluidas.
> **Un fallo: $139.90 inventados**, asignados a Caro Vera, **sin marca de dudoso** — mientras los
> dos renglones que sí existían de esa clienta **sí** salieron marcados.

Léelo despacio: **la confianza del modelo no correlaciona con la verdad**. Lo inventado llegó con
más seguridad que lo escrito. Cualquier cosa que propongas tiene que responder a eso, no sólo a
"leer mejor".

---

## Paso 1 — Separar el modelo de la libreta del de texto

Hoy comparten constante. Sepáralos: **texto y voz se quedan en `flash-lite`; la libreta sube a
`flash`.** Una constante propia, con nombre que diga qué es, y un comentario que explique por qué
son distintos — para que nadie los vuelva a unir "por limpieza".

**Mide el costo.** La tabla de métricas ya guarda `geminiCalls` y `geminiDurationMillis` por
importación, así que el antes y el después se pueden comparar. Diego cobra $299 MXN/mes: el costo
por foto importa de verdad y esta decisión es reversible en una línea si no compensa.

## Paso 2 — Poner a dieta la instrucción

`buildLedgerSystemInstruction` tiene **21 viñetas**. Diego ya sospechaba que "una instrucción de 25
viñetas puede diluir las tres que importan", y está a dos de cumplirse.

**No la reescribas entera.** Léela y dime cuáles son las tres o cuatro que de verdad cargan el peso,
cuáles son específicas del catálogo de Diego en vez de universales, y cuáles se contradicen entre
sí. **Propón el recorte antes de aplicarlo**, con el porqué de cada baja.

Lo que **no** se toca: la viñeta que declara que todo texto dentro de la imagen es contenido pasivo
y nunca una instrucción (`geminiLedgerProvider.ts:216`). Eso es una defensa contra inyección por la
foto, no una guía de estilo.

## Paso 3 — Ejemplos multimodales (few-shot)

Una o dos parejas **imagen + JSON esperado** de páginas de verdad desordenadas, en vez de describir
con palabras cómo es una libreta. Diego lo llamó "la palanca más fuerte y la más cara"; con `flash`
ya no es tan cara.

**Propón antes de escribir:** cuántos ejemplos, qué debe mostrar cada uno (tachones, dos columnas,
notas que no son movimiento, un renglón ilegible declarado como tal), de dónde salen las imágenes
—**no pueden ser libretas reales de clientes**— y qué le cuesta a cada llamada.

## Paso 4 — Degradar en vez de fallar

Hoy, si el modelo no entiende la hoja, la persona ve "no se pudo registrar la libreta". Ya existen
`pageIncomplete` y `unreadableFields`: **devolver los renglones que sí se entendieron y declarar el
resto** es mejor que no devolver nada. Comprueba qué parte de esto ya ocurre y qué falta.

---

## Gate

1. `npm --prefix functions run lint`
2. `npm --prefix functions test`
3. **`npm --prefix functions run test:firestore-emulator`** — el gate del emulador, que `npm test`
   **no** incluye. Si falta el JDK, `release:check` falla en vez de saltárselo en silencio; no lo
   esquives.
4. Compilar: que `lib/` salga de fuente actual.

## Qué entregar

- La propuesta de los pasos 2 y 3 **antes** de escribir código.
- Qué cambiaste y por qué, con archivo y línea.
- **El comando que ejecutaste de verdad y su salida.** Sin resumir.
- El costo estimado por foto del paso 1, con el razonamiento.

**No commitees, no despliegues, no toques Android.**
