# Relevo iOS: de F5-C en rojo a compilar y seguir con pantallas

**La meta, confirmada por Diego el 2026-11-06:** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar la cuenta de Apple Developer. No te detengas al cerrar una tanda: sigue con la siguiente fase, y deja lo manual de Diego para el final.

**Lee primero** `docs/superpowers/handoffs/2026-11-05-ios-relevo.md` (papel, OpenCode, cómo auditar, trampas de iOS, reglas), y de `2026-11-06-ios-relevo-f5c.md` las secciones "Puerta abierta", "Tandas que siguen", "Pendientes que siguen vivos" y "Lista manual de Diego". Sus secciones de estado y primer paso ya caducaron; manda este archivo. Lee también `CLAUDE.md` y la memoria `cuentivo-ios-sin-mac`, `cuentivo-reparto-de-agentes`, `relevo-de-sesiones-caras` y `working-agreements`.

## Estado al 2026-11-06

- **F5-C escrita y auditada, iOS en rojo:** `da0f465`, run 42178299819. **`android-check` verde; `ios-build` falló en `:shared:compileKotlinIosSimulatorArm64`.** Es la primera compilación de este código en iOS, así que es lo esperado, pero **F5-C no está cerrada**.
- Android local, según XML que leí yo: 877 + 649 = 1526, 0 fallos, en el último commit de OpenCode (`1e0f222`). Bajó de 883 a 877 porque las 6 de `CollectionReminderPreferencesTest` pasaron a `commonTest`. `assembleRelease`, `lintDebug` y `:app:compileDebugAndroidTestKotlin` pasaron según su reporte (`C:/tmp/oc-ios-f5c-reporte.md`). Mis dos commits posteriores solo tocan `iosMain` y un comentario en Android.
- `a1bf6f7` se subió roto por un error mío, y `da0f465` lo completa. Ver "Hallazgos".
- `ResLocaleTest` y `CuentivoAppTest` **siguen sin correr**: el teléfono no estaba conectado.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima, y las interfaces de F5-C ni siquiera compilan aún.** Dilo con esas palabras.

## Tu primer paso

Corregir estos errores (44 en total). Log completo en `C:/tmp/ios-relevo/log-f5c-1.txt`. **La terminal de OpenCode ya no existe: la PC se reinició.** Crea otra con `orca terminal create`, espera a que cargue el TUI y concede "Allow always" a `C:\tmp\*` al primer aviso (Flecha derecha + Enter + Enter), como dice el relevo del 2026-11-05. Cada envío lleva Monitor. Salen por capas: al corregir estos aparecerán `iosTest` y luego Swift.

| Archivo | Error | Pista |
|---|---|---|
| `BoundedImageCache.kt:15` | hereda de `LinkedHashMap`, final en Native | **El único de diseño.** Reescribe con composición y misma semántica LRU: `get` refresca recencia. Que sus pruebas de `app/src/test` pasen a `commonTest` para que corran en iOS. |
| `PlannedReminderScheduler.kt:58`, `IosProductPhotoStore.kt:38`, `IosSharer.kt:60` | `Clock.System` sin resolver y pide `ExperimentalTime` | Copia cómo se pide el reloj en lo que ya compila en común (`:iosKit`, `domain/`), no inventes. |
| `IosVoiceRecognizer.kt:182` | `error.domain` es `String?` | Ajusta la llamada a `mapIosSpeechError`. |
| `IosAppFiles.kt:44, 104` | sin `writeToFile`, falta opt-in | `NSData.writeToFile` está en `platform.Foundation`; revisa imports y `@OptIn`. |
| `IosDataBridge.kt:32` | sin `NSData.create` | Falta `import platform.Foundation.create`. |
| `IosImagePickerFactory.kt:50, 53, 75, 97` | `release`, tipo y etiqueta `onImage` | El método `remember` de la clase **tapa** al `remember` de Compose: importa este con alias. Etiqueta el lambda: `onImage@`. |
| `IosLocalReminderCenter.kt:48-54` | `val` sin reasignar y parámetro `dateMatching` | `NSDateComponents` se asigna con `setYear(...)`. El parámetro se llama `dateComponents`. |
| `IosSharer.kt:86-89` | `UIActivityViewController` | Revisa el constructor (`initWithActivityItems`). `popoverPresentationController` se resuelve al arreglar lo anterior. |
| `IosStatementImageRenderer.kt:14-17, 112-133` | sin `NSFontAttributeName`, `boundingRectWithSize`, `drawWithRect`, `NSStringDrawingUsesLineFragmentOrigin` | **Busca en qué paquete los expone Kotlin/Native.** Si no los encuentras, cambia a `NSAttributedString` de `platform.Foundation`. |
| `IosTopViewController.kt:21` | `UISceneActivationStateForegroundActive` | En Kotlin/Native es constante de nivel superior: `import platform.UIKit.UISceneActivationStateForegroundActive`. |

**Las pistas son mías y no están verificadas contra los bindings;** Windows no puede comprobarlas. Haz que OpenCode las contraste con la fuente o un ejemplo real antes de cada vuelta de CI, de 7 a 15 minutos cada una.

Verde significa: pruebas de iOS leídas del artefacto, `xcodebuild` y `android-check`. Hasta entonces, F5-C sigue abierta.

## Después de cerrar F5-C

1. **Selector de documentos e `XlsxReader`.** `BackupSection` y la importación de productos (`ProductsScreen`) usan `OpenDocument`/`CreateDocument`: `AppFiles` **no** cubre eso, aunque el relevo anterior lo listara ahí. `XlsxReader` usa `ZipInputStream`.
2. **Pantallas candidatas directas** (lista en el relevo F5-C). Al mover `ProductPhoto`, implementa `ImagePickerFactory` en Android, conservando `rememberSaveable`, `deleteStale` y las dos carpetas separadas. **Hasta correr `ProductPhotoSourceTest` en el teléfono no está probado.**
3. **`MainActivity.onCreate`:** plan propio, antes del encargo. Debe conectar en iOS `IosLocalReminderCenter.requestPermission`, tocar el aviso para abrir los clientes vencidos (`UNUserNotificationCenterDelegate`) y llamar a `reminders.sync()` al abrir, al volver a primer plano y tras escrituras financieras.
4. Pendientes del relevo F5-C: `deleteAccountWithApple`, `signOut()` que puede lanzar, idioma en iOS. `CFBundleLocalizations` **ya está** declarado en `Info.plist`: solo falta el selector y verificar `NSLocale.preferredLanguages`.
5. **F6:** los textos `subscription_*` (3 claves, ES y EN) dicen Google Play y hay que hacerlos por plataforma: Apple lo rechaza. `verifyAppleSubscription`, `handleAppStoreNotification`, `release:check`, **sin deploy**.
6. **Día de pago:** poner el enlace de App Store en `StoreListing.ios.kt`. Hoy iOS firma "Enviado con Cuentivo", sin enlace. Agrégalo a `docs/ios-dia-de-pago.md`.

## Decisiones de F5-C (no las re-decidas sin motivo)

- **Recordatorios en iOS:** 7 días programados con `OverdueReminderPolicy.summarize` real, reemplazados en cada `sync()`. Limitaciones: si alguien paga desde otro teléfono y esta app no se abre, llega un aviso viejo; y si no se abre en 7 días, dejan de llegar. **Dile esto a Diego al entregar.**
- **`PickedImage`:** en Android es `actual class PickedImage(val uri: Uri)`, no `typealias`; según el reporte, el compilador rechaza un `actual typealias` a una clase abstracta. `ProductImageStore` conserva el overload con `Uri`.
- **`ImagePickerFactory` no tiene implementación en Android todavía**, y `FileAppFiles` no se conecta a nada: solo lo ejercita su prueba de contrato.
- **Voz en iOS:** temporizador de 1.5 s tras el último parcial, 6 s sin ninguna palabra y 5 s de guardia esperando el final. Son valores elegidos por mí, sin probar con voz real: `SFSpeechRecognizer` no corta solo.

## Hallazgos de esta auditoría

- **Mi encargo olvidó el silencio:** con la voz como la pedí, nunca llegaría el resultado final. Lo cacé leyendo el código, no en una prueba: `IosVoiceRecognizer` no se puede probar en el simulador. Sigue sin probarse.
- **Defecto de Android anterior a esto, bajo riesgo, sin tocar:** `ProductImageStore.load` y `delete` hacen `File(directory, fileName)` sin validar, y ese nombre puede llegar por sync. Hoy `delete` solo recibe nombres creados en el propio diálogo. Al conectar `AppFiles` queda cubierto por `SafeFileName`.
- `commonMain` que compila en Android puede no compilar en iOS (`LinkedHashMap`, reloj). No lo des por portable hasta ver CI.

## Reglas que no cambian

Dinero en `Long`; nada financiero se borra ni se edita; **sin `firebase deploy` ni deploy de `functions` sin autorización aparte de Diego**; repo público, revisa secretos antes de cada push. Nunca abras ni imprimas `keystore.properties`, `*.jks`, `google-services.json`, llaves ni el plist. No toques la rama `feature-cuentivo-ai`. Esta sesión cerró con ~$77: repite el relevo cerca de $50-$80 o antes de un tramo grande.

**Este archivo está en un commit local sin subir:** súbelo junto con la corrección, porque un push solo de docs gasta un run de iOS.

## Lista manual de Diego (para el final, paso a paso)

1. Conectar el teléfono en depuración para correr `ResLocaleTest` y `CuentivoAppTest`.
2. Bajar una imagen API 31 en Android Studio antes de publicar Android con estas pantallas.
3. Pagar Apple cuando la app funcione: `docs/ios-dia-de-pago.md`.
