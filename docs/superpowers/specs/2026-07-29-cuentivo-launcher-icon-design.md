# Icono de Cuentivo para Android

Fecha: 2026-07-29

## Objetivo

Reemplazar el icono de plantilla de Android por una identidad propia, legible en tamaños pequeños y coherente con la interfaz actual de Cuentivo.

## Dirección visual aprobada

- Fondo azul marino oscuro, alineado con el fondo principal de la aplicación.
- Símbolo central verde menta.
- Una letra **C** construida visualmente como una libreta o estado de cuenta.
- Una marca de verificación pequeña integrada en el símbolo.
- Geometría sencilla, bordes definidos y contraste alto.
- Sin texto adicional, cifras, monedas, símbolos de dólar, degradados complejos ni detalles finos.
- Sin referencias visuales a criptomonedas, bancos o préstamos.

## Recursos Android

La ilustración generada servirá como fuente visual. A partir de ella se prepararán:

1. Fondo del icono adaptativo.
2. Primer plano con zona segura suficiente para las máscaras de Android.
3. Icono monocromático compatible con iconos temáticos.
4. Variantes heredadas `mdpi`, `hdpi`, `xhdpi`, `xxhdpi` y `xxxhdpi`.
5. Variantes normales y redondas.

El símbolo principal debe permanecer dentro de la zona segura central para no perder elementos con máscaras circulares, redondeadas o de lágrima.

## Publicación

- Incrementar `versionCode` de 5 a 6, manteniendo `versionName` 1.2.
- No modificar Billing, Firebase, sincronización ni otras fases del producto.
- Generar APK/AAB de release con la firma existente.
- Verificar visualmente el icono instalado en el emulador.
- Ejecutar pruebas unitarias, `lintDebug`, `assembleRelease` y `bundleRelease`.
- Confirmar firma, versión y fecha del AAB final.

## Criterios de aceptación

- El lanzador ya no muestra el robot de Android.
- El icono se reconoce como Cuentivo en fondo claro y oscuro.
- El símbolo no queda recortado en iconos redondos ni adaptativos.
- El icono temático conserva una silueta comprensible.
- El AAB final usa `versionCode 6` y `versionName 1.2`.
- No se incluyen secretos ni artefactos temporales en Git.
