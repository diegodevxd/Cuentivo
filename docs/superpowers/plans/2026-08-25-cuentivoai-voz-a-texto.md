# CuentivoAI — voz a texto editable

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que el usuario hable en vez de escribir. Punto N3 del núcleo y Fase 7 de la fuente de verdad. Es el que más valor tiene para un comerciante con las manos ocupadas atendiendo.

**Architecture — la decisión que hace este incremento pequeño:** la voz es un **método de entrada para el cuadro de texto que ya existe**, no un pipeline nuevo. El reconocedor produce texto, lo deja en el mismo campo donde hoy se escribe, el usuario lo corrige si hace falta y presiona Enviar como siempre. No toca contratos, ni el gateway, ni dinero, ni Room. Si esto se diseña como "un camino de voz" en vez de "un teclado que oye", el incremento se vuelve diez veces más grande sin ganar nada.

**Tech Stack:** `android.speech.SpeechRecognizer` del sistema, preferentemente en el dispositivo. Kotlin puro para la máquina de estados, Compose para el botón.

## Global Constraints

- **El audio nunca sale del teléfono por nuestra cuenta y nunca se guarda.** Ni a Gemini, ni a Firestore, ni a un archivo temporal. Lo único que viaja es el texto final, y sólo si el usuario presiona Enviar, igual que si lo hubiera tecleado.
- **Permiso just-in-time.** `RECORD_AUDIO` se pide al tocar el micrófono por primera vez, jamás al abrir la app. Permiso denegado no rompe nada: el teclado sigue ahí.
- **El permiso se declara SÓLO en el manifiesto de `src/debug/`.** Cuentivo está publicada en Play; agregar micrófono al manifiesto principal le añadiría un permiso visible a todos los usuarios y obligaría a actualizar Data Safety por una función que en release ni existe. El proyecto ya usa este patrón de separación por source set en `AppCheckProvider`. Cuando CuentivoAI salga a beta, el permiso se mueve **deliberadamente** y con su declaración de privacidad.
- **Falla abierta hacia el teclado, no cerrada.** Sin servicio de reconocimiento, sin red, con error o con permiso denegado, el usuario debe poder escribir. La voz nunca bloquea la app: ese es el criterio DONE de la Fase 7.
- Idioma `es-MX`. Preferir reconocimiento en el dispositivo cuando esté disponible; si no, el del sistema.
- Nada de PII ni transcripciones en logs, Analytics o Crashlytics.
- No se toca el contrato de propuestas, ni `interpretAiRequest`, ni el control plane, ni una sola ruta financiera.

## Task 1 — Máquina de estados pura

Archivo nuevo `app/src/main/java/com/cuentivo/app/ai/VoiceCapturePolicy.kt`. Kotlin puro, sin Android, corre en `testDebugUnitTest`.

- [ ] `VoiceCaptureState` sellado: `Idle`, `PermissionRequired`, `Listening`, `Transcribed(text)`, `Unavailable(reason)`.
- [ ] `VoiceUnavailableReason` como enum: `NO_RECOGNIZER`, `PERMISSION_DENIED`, `NO_SPEECH`, `RECOGNIZER_ERROR`. Vocabulario fijo, sin texto libre — la UI resuelve la cadena, igual que hace el resto de `domain/`.
- [ ] Transiciones puras: tocar micrófono, permiso concedido, permiso denegado, resultado parcial, resultado final, error, cancelar.
- [ ] **Single-flight:** estando en `Listening` no se puede volver a arrancar.
- [ ] Un resultado que llega cuando el usuario ya canceló **se descarta**. Mismo patrón de generación que ya usan venta, abono y consultas.
- [ ] Una transcripción vacía o en blanco resuelve `Unavailable(NO_SPEECH)`, nunca `Transcribed("")`.
- [ ] Pruebas JVM de cada transición, del descarte del resultado rancio, del single-flight y del texto en blanco.

## Task 2 — Seam del reconocedor y permiso

- [ ] Envoltura del `SpeechRecognizer` detrás de una interfaz estrecha, para que la máquina de estados siga siendo pura y testeable.
- [ ] `RECORD_AUDIO` **sólo** en `app/src/debug/AndroidManifest.xml`. Verifica con `grep` que no aparezca en `src/main/`.
- [ ] Permiso pedido al tocar, con las tres rutas cubiertas: concedido, denegado y denegado permanentemente.
- [ ] Liberar el reconocedor al salir de la pantalla y al cancelar. No dejarlo escuchando.
- [ ] Sin dependencias nuevas de Gradle.

## Task 3 — Presentación

- [ ] Botón de micrófono junto al campo de texto de `AiDebugLabScreen`, sin mover lo que ya está.
- [ ] Estado de escucha visible y **cancelable**. Nunca un diálogo modal que secuestre el foco.
- [ ] El texto reconocido **entra al campo editable**, no se envía solo. El usuario confirma.
- [ ] Cada `VoiceUnavailableReason` con su mensaje propio y honesto, en `values/` y `values-en/`. Paridad de claves cuadrada y puerta del idioma limpia.

## Task 4 — Cableado y aceptación física

- [ ] Cablear en el host, sin tocar rutas financieras.
- [ ] Prueba física en el A059P: dictar "Demian me pagó 92 pesos de un boleto", verificar que el texto aparece editable, corregirlo a mano y enviarlo.
- [ ] Probar también negar el permiso y comprobar que el teclado sigue funcionando.

## Gate

No escribe dinero ni toca Room, así que no exige pruebas conectadas. Sí exige: focales, suite JVM completa, `assembleDebug`, `assembleRelease` — **importante, para confirmar que el permiso no se coló al manifiesto de release** — `lintDebug`, puerta del idioma, paridad de claves y `git diff --check`. La aceptación física es obligatoria.
