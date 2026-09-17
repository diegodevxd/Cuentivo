# v41 · bloque 3 — presupuesto de salida y contrato v5 (OpenCode)

**Base:** `f929b1d` (bloques 1 y 2 cerrados). **Implementa:** OpenCode. **Revisa:** AGY.
Lee `CLAUDE.md` y el bloque 3 de `docs/superpowers/encargos/2026-09-16-v41-libreta-que-no-se-engana.md`.

**No despliegues, no commitees.** Reporte completo en `C:/tmp/oc-bloque3-reporte.md`.

Este bloque tiene **dos partes y van en orden**. La parte A bloquea el despliegue de lo que ya está
hecho; la parte B es trabajo nuevo.

---

# PARTE A — Que la respuesta quepa (bloquea el despliegue)

Claude lo encontró al cerrar el bloque 2. **Nada de esto está desplegado**, pero no se despliega sin
resolverlo.

**A.1 · El cálculo del tope está desactualizado.** `functions/src/ai/geminiLedgerProvider.ts:159-173`
justifica `MAX_LEDGER_OUTPUT_TOKENS = 8192` con un peor caso de ~11.950 caracteres, pero
`buildWorstCaseLedgerDocument` **no incluye los campos de v4**: `rawText`, `items`, `warnings`,
`confidence`, `sources`. Y el bloque 2 acaba de hacer `rawText` **obligatorio** en la instrucción, así
que las respuestas normales crecen hacia ese peor caso. Actualiza el peor caso con **todos** los campos
v4 en su máximo (25 renglones, `rawText` de 200, el máximo de `items` y de `warnings` que acepta el
parser), recalcula, y ajusta el tope si hace falta. Con prueba que falle si el peor caso deja de caber.

**A.2 · Nadie detecta una respuesta cortada.** No hay ningún manejo de `finishReason` en el proveedor.
Si Gemini corta por `MAX_TOKENS`, el JSON llega truncado y acaba como un fallo genérico. Detéctalo y
devuélvelo como una **categoría de fallo propia**, para que se pueda distinguir y medir. **Sin
reintentar a ciegas**: cada llamada se paga.

**A.3 · El razonamiento del modelo.** `4f7b9fe` cambió la libreta de `gemini-3.5-flash-lite` a
`gemini-3.5-flash`, y el proveedor **no configura `thinkingConfig`**. Si ese modelo razona por defecto
y esos tokens cuentan dentro de `maxOutputTokens`, el riesgo de corte crece mucho.
**No lo supongas: verifícalo** en la documentación del SDK `@google/genai` que usa el proyecto y en
los tipos instalados en `functions/node_modules`. Di qué encontraste y con qué fuente. Si no se puede
determinar con certeza, **dilo así** y configúralo de forma explícita y conservadora, justificándolo.
No inventes campos de la API.

---

# PARTE B — Contrato v5

Lo que no se puede resolver sin campos nuevos. **Restricción dura:**
`app/src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt:177` rechaza la respuesta
**entera** si trae una llave desconocida. Por eso:

- El servidor devuelve v5 **sólo si el cliente lo pide**. **v3 y v4 siguen idénticos**, byte por byte
  en su forma: pruebas que lo demuestren (una petición v4 no recibe ninguna llave de v5).
- Android v41 pide v5.
- **Orden de despliegue:** el servidor con v5 tiene que estar desplegado **antes** de que el AAB v41
  llegue a nadie. Además, **v41 tiene que degradar limpio** si el servidor responde error a una
  petición v5 (por ejemplo, reintentando una vez con v4, o mostrando un fallo claro). Con prueba.

**Campos nuevos de v5**, propón la forma exacta **antes** de escribirla y espera visto bueno:

1. **Subtotales de la página.** Muchas libretas llevan saldos corridos. Si el modelo los devuelve,
   Android comprueba que lo leído cuadra y **marca el bloque que no cuadra** como dudoso, con las
   comprobaciones deterministas del bloque 1 (sólo añaden duda). Caso real: lo leído dejaba a un
   cliente debiendo ~$895 y el papel decía **$200**. Montos siempre como string de centavos y
   `Long` en Android; nunca `Double`.
2. **Tipo de página.** Si la hoja no es de cuentas (por ejemplo, una lista de costos y precios),
   decirlo en vez de mostrar "no se pudo registrar la libreta". Caso real: una hoja de costo/precio
   con margen del 55% sin ninguna clienta.
3. **La nota del paréntesis como campo propio** del renglón, para que la pantalla la muestre sin
   mezclarla con el nombre.

**Límites de cada campo nuevo en el parser**, igual que los de v4: longitud, cantidad, valores de
enum. Y el presupuesto de salida de la parte A tiene que contar también con ellos.

---

## Pruebas

- Parte A: peor caso v4 **y** v5 caben; `MAX_TOKENS` produce su categoría; configuración del
  razonamiento explícita y probada.
- Parte B: v3/v4 idénticos; v5 sólo si se pide; v41 degrada limpio ante error de v5; subtotal que no
  cuadra marca el bloque; página que no es de cuentas se dice como tal; la nota no entra en el nombre.
- Cada prueba nueva tiene que fallar si se rompe su regla (mutación). Datos inventados.

## Gate

- Servidor: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` y
  `npm --prefix functions run release:check` (referencia: **480** unitarias, **9** del emulador).
- Android: `testDebugUnitTest --rerun assembleDebug compileDebugAndroidTestKotlin` con el trust store
  (referencia: **1226**). Si tocas `strings.xml`, paridad ES/EN y en inglés sin apóstrofos.
- **Tienes el emulador** (`emulator-5554`, adb en
  `C:/Users/pibed/AppData/Local/Android/Sdk/platform-tools/adb.exe`): corre las instrumentadas que
  toques, citando el argumento `-Pandroid.testInstrumentationRunnerArguments.class=...` en PowerShell,
  y **lee el XML**, no el `BUILD SUCCESSFUL`.
- **No lances Gradle mientras otro corre en esta carpeta.**

## Orden de entrega

1. **Parte A completa** + la **propuesta** de la forma de v5. Paras.
2. Con el visto bueno, la parte B.
