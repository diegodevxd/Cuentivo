# Relevo iOS: F5-D cerrada, siguen las pantallas candidatas

**La meta, confirmada por Diego el 2026-11-06:** Cuentivo en iOS sin Mac, de modo que lo único pendiente sea pagar la cuenta de Apple Developer. No te detengas al cerrar una tanda: sigue con la siguiente fase, y deja lo manual de Diego para el final.

**Lee primero** `docs/superpowers/handoffs/2026-11-06-ios-relevo-f5e.md` completo: sus secciones "Después", "Hallazgos", "OpenCode y permisos", "CI", "Decirle a Diego", "Lista manual" y "Reglas" siguen vigentes. **Sus secciones de estado y primer paso ya caducaron; manda este archivo.** También lo que ese relevo indica leer. Modelo de encargo: `docs/superpowers/encargos/2026-11-06-ios-f5d-documentos-xlsx.md`.

## Estado al 2026-11-06

- **F5-D cerrada en `afa4837`:** `ios-build` run 42298092935 verde al primer intento, con **720 pruebas iOS en 100 suites y 0 fallos** (leídas por mí del artefacto; `AppleInflateRawTest`, `ZipCentralDirectoryTest`, `ProductSheetImportTest` e `IosDocumentPickerTest` aparecen), `xcodebuild` correcto y `.app` subido. `android-check` verde en el mismo commit (run 42298092887).
- **Android local:** 861 + 697 = 1558, 0 fallos, leído por mí de los XML, en `c7a1cbf`. Lo posterior solo toca `iosMain`, `iosTest` y docs. `assembleRelease` y `lintDebug` pasaron **según el reporte de OpenCode, sobre `e574289`**; no los repetí.
- `ResLocaleTest`, `CuentivoAppTest` y `ProductPhotoSourceTest` **siguen sin correr**.
- **`compression_decode_buffer` sí corrió en el simulador** con `.xlsx` reales (DEFLATE, streaming y STORED). **El selector de documentos nunca se ha presentado:** solo sus temporales tienen prueba. **Ningún `.xlsx` hecho por Excel real se ha probado:** los fixtures son de Python.

**Nada se ha probado contra Firestore, Auth o Apple reales, ni en iPhone. La UI de iOS sigue siendo la pantalla mínima, y la `.app` nunca se ha abierto en un simulador en CI.** Dilo con esas palabras.

**Este archivo está en un commit local sin subir:** súbelo con la siguiente tanda, porque un push solo de docs gasta un run de iOS.

## Decisiones de F5-D (no las re-decidas sin motivo)

- **ZIP:** Android conserva `ZipInputStream`, tras `expect`/`actual` (`readZipEntries`). iOS usa `ZipCentralDirectory` (`commonMain`, inflador inyectado) y `compression_decode_buffer`. `ZipCentralDirectoryHostTest` compara el lector contra `ZipInputStream` en Windows.
- **Diferencia consciente:** iOS rechaza ZIP64 y, en cualquier entrada, cifrado o métodos raros. Android rechaza menos. Límites en iOS: 64 MiB por entrada y 128 MiB en total, solo sobre lo que pasa `wanted`.
- **Selector:** `rememberDocumentOpener` y `rememberDocumentSaver` con `expect`/`actual`. El destino es `DocumentSaveTarget.write(bytes): Boolean` porque iOS necesita el archivo hecho antes de preguntar dónde guardarlo. `PickedDocument.readBytes` en iOS se lee una sola vez y borra la copia. Si la persona cierra el diálogo de contraseña sin restaurar, la copia queda en `tmp` hasta que el sistema la purgue.
- **`BackupSection` ya está en `commonMain`; `ProductsScreen` sigue en `app` usando el selector común.**

## Pendiente nuevo que los relevos anteriores no listaban

**El respaldo no funciona en iOS todavía.** `DatabaseBackup` y `BackupCrypto` siguen en `app`: copian el archivo de la base y usan `javax.crypto` (AES-GCM con PBKDF2-SHA256 de 120,000 iteraciones). **Mi entendimiento, sin verificar:** CommonCrypto no expone GCM públicamente y CryptoKit es solo de Swift, así que parece hacer falta un puente desde `iosApp`, como con los proveedores de login. Contrástalo y decide antes del encargo, junto con la raíz común, porque restaurar reinicia el proceso en Android (`restartAfterRestore`) y en iOS no se puede. **Compatibilidad entre plataformas:** decide si un respaldo de Android debe abrirse en iOS.

Otro menor: la primera puerta de i18n de `CLAUDE.md` no cubre `shared/.../backup/`. Hoy da 0 coincidencias. Tampoco cubría `app/.../backup/`, así que no es regresión.

## Tu primer paso

**Pantallas candidatas directas, en tandas.** Lista en "Después" del relevo F5-E, punto 1: `AuthenticationScreen`, `EmailVerificationScreen`, `PaymentScreen`, `FirstDebtScreen`, `SubscriptionSheet`, `AiPaywallSheet`, `OpeningBalanceImportDialog` y los `*Presentation`/`*Policy` de `customer`, `dashboard`, `product`, `account`, `onboarding`, `ai`. Mide primero con `grep` qué importa cada una (`billing/`, `ai/`, `java.time`, `UUID`, `BigDecimal`, `R.`, `android.`): algunas dependen de lo que no está en `:shared`. **Haz la primera tanda con las que no toquen dinero ni plataforma**, y deja `PaymentScreen` y `ProductPhoto` (con `ImagePickerFactory` en Android) para tandas propias.

Pide en el encargo **`:app:compileDebugAndroidTestKotlin`**: al mover pantallas se rompen imports de `androidTest` que `assembleDebug` no ve.

Luego sigue el orden de "Después" del relevo F5-E, añadiendo el respaldo a la raíz común.

## Hallazgos de esta auditoría (úsalos)

- **Lee `iosMain` buscando lo que Windows no ve, antes de subir.** En F5-D cacé dos errores de compilación que el reporte no podía ver: `src_buffer` sin `reinterpret<UByteVar>()` (`CPointer<ByteVar>` no entra donde se pide `UByteVar`) y un `private var` asignado desde fuera de la clase. Con eso, **verde al primer intento**.
- **`withContext` en `finally` no corre si la corrutina está cancelada:** lanza antes de ejecutar el bloque. El temporal se borra sin él.
- **El control negativo sirvió de verdad:** quitar el límite de tamaño no hizo fallar nada al principio, porque las pruebas usaban `assertFails` con un payload vacío que lanzaba por otra razón. OpenCode lo detectó y las reescribió. **Pide siempre "dime qué prueba falló, con su nombre".** Una prueba con `assertFails` puede estar fallando por el motivo equivocado.
- **El encabezado del reporte de OpenCode tenía la rama y la base equivocadas** (`feature-cuentivo-ai`, `e1a9407`). La rama real es `diegodevxd/cuentivo-ios`, comprobado con `git branch --show-current`. No confíes en esos datos del reporte.
- `SafeFileName.isValid` acepta `..algo`, espacios y otros caracteres de control salvo NUL: sirve para rutas, no para nombres bonitos.

## OpenCode

Terminal activa `term_6951abe3-0d6d-482a-9c04-a5d81dd06cfa`, al ~60% de contexto. **Si pasa de ~80%, crea otra** con `orca terminal create` y concede "Allow always" a `C:\tmp\ios-relevo\*` al primer aviso. La otra terminal `OC | ...` (`term_d8e7884b-...`) es vieja: no la uses. Los símbolos nuevos los contrastas tú en `C:/tmp/ios-relevo/simbolos-<tanda>.txt`.

**Esta sesión cerró con ~$60:** repite el relevo cerca de $50-$80 o antes de un tramo grande. **Abre cada sesión nueva con Remote Control** (`claude --model opus --remote-control "Cuentivo iOS <tanda>"`), comprueba que conectó y dile a Diego el nombre: la sigue desde su celular. Repite esta regla en tu relevo.
