# Encargo iOS — F4-B: gateway de Firestore en iOS, sin dinero con decimales

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push: lo hace el auditor. Commits `ios(F4-B): ...`, uno por tarea.

Windows no compila iOS: lo verifica el runner macOS después del push. Escribe con cuidado y anota en el reporte
lo que no pudiste comprobar. **Código nuevo solo Kotlin común o Native: nada de JVM.** En nombres de prueba entre
backticks no uses `, . ; : / \ < > [ ]`, ni `@Test(expected=)`: usa `assertFailsWith`.

**Fuera de este encargo:** `RemoteMerger`, `CloudRestore`, `SyncWorker`, login, App Check, `BGTaskScheduler` y
pantallas. `app/` **no se toca**, salvo B2. `:shared` **no puede depender de Firebase ni de GitLive.**

## Por qué

`RemoteMerger` (`app/.../sync/RemoteMerger.kt`, línea ~384) acepta dinero solo si es `Long` y rechaza todo lo demás,
incluido `Double`. Es la frontera que impide que entren decimales a un libro en centavos. En iOS, Firestore entrega
números como `NSNumber`: ni `is Long` ni `is Double` funcionan directo. Convertir con `longValue` truncaría `10.5`
a `10` en silencio, **que es justo el defecto que esta frontera prohíbe.** Y al subir pasa igual: un entero no puede
llegar a Firestore como double.

## Tareas

**B1. Normalizador, con pruebas en iOS.** En `shared/src/iosMain/kotlin/com/cuentivo/app/sync/` crea
`normalizeFirestoreValue(value: Any?): Any?`, que convierte recursivamente lo que entrega el SDK nativo a tipos Kotlin:
- `NSNumber` según su tipo real con `objCType` / `CFNumberIsFloatType`: entero → `Long`, flotante → `Double`
  **sin redondear ni truncar**, y booleano (`CFBooleanGetTypeID`) → `Boolean`. Cuidado: un `NSNumber` booleano
  también se ve como entero, y un `Long` que llega como 1 no puede volverse `true`.
- `NSString` → `String`, `NSNull` → `null`, y `NSDictionary`/`NSArray` → `Map`/`List` recursivos. Cualquier otro tipo
  (por ejemplo `FIRTimestamp`) se conserva tal cual, para que el lector de dinero lo rechace.

Crea también `parseJsonToFirestoreMap(json: String): Map<Any?, Any?>`, equivalente a `asFirestoreMap` de
`FirestoreSyncGateway.kt` (línea ~331), con `NSJSONSerialization`. Mismo resultado que Android para los payloads
actuales. Los enteros grandes (`9007199254740993`) deben conservarse exactos: si `NSJSONSerialization` no lo garantiza,
escribe otro parser o **para y repórtalo. No aceptes pérdida de precisión.**

Pruebas en `shared/src/iosTest/kotlin/com/cuentivo/app/sync/`, que invocan estas funciones reales: `NSNumber` de
`longLong` → `Long` exacto; de `double` 10.5 → `Double` 10.5; de `double` 10.0 → `Double`, **no** `Long`; de `bool`
→ `Boolean`; `9007199254740993L` exacto; mapas y listas anidados; `NSNull`; y un JSON con enteros, decimales,
booleanos, nulos y anidados que queda con los tipos correctos.

**B2. Lo puro que hoy vive en `FirestoreSyncGateway.kt`** y necesita iOS: `FirestoreDocumentPath` (línea ~18),
`PullSpec`/`PULL_SPECS` (líneas ~36 y ~321) y `classify` (línea ~288), si se puede separar la decisión del tipo de
excepción. Muévelos a `commonMain`, mismo paquete, con `git mv` o extracción mínima, y usa desde Android esa misma
definición para que no haya dos tablas. Si alguno depende de `org.json` o de tipos de Firebase, déjalo y repórtalo.
Mueve sus pruebas a `commonTest` si es posible. El comportamiento de Android no cambia.

**B3. `iosKit/src/iosMain/kotlin/com/cuentivo/app/sync/IosFirestoreSyncGateway.kt`**: implementa
`SyncRemoteGateway`, `SyncRemotePullGateway` y `SyncRemoteBusinessLookup`, **traducción línea por línea** de
`FirestoreSyncGateway`. Lee esa clase completa y conserva:
- Las mismas rutas, las mismas colecciones, el orden de `download` y el traer hijos por padre recién descargado.
- Lectura desde **servidor, nunca caché**, igual que `Source.SERVER`, y la resolución contra la copia publicada de
  `resolveAgainstPublishedCopy`.
- `requestBusinessDeletion` y la misma clasificación de fallos transitorios y permanentes.
- Usa la API nativa por `.ios` de GitLive (`FIRFirestore`), para no pasar por la serialización de GitLive: los valores
  de lectura pasan siempre por `normalizeFirestoreValue` y los de escritura por `parseJsonToFirestoreMap`.
- Los contratos son **bloqueantes**. Hazlos esperar el callback con `runBlocking`/semáforo, con timeout y fallo
  `Transient`. **No se pueden llamar desde el hilo principal** porque se bloquearía: documéntalo en KDoc y agrega una
  comprobación con `NSThread.isMainThread` que devuelva fallo en vez de congelar la app. Los callbacks deben
  ejecutarse en una cola que no sea la principal.

**B4. Puerta Android**, con el entorno de Norton:
`.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain`.
Desde el XML: 927 o más en `app`, 550 o más en `:shared`, 0 fallos. La migración B2 no puede bajar el total de `app`
más de lo que suba en `:shared`.

## Reglas

- **Nada de `Double`/`Float` con dinero:** `Double` solo aparece para conservar y luego rechazar lo que llegó.
- Sin dependencias nuevas. Si hacen falta, para y repórtalo. Si algo no se puede traducir igual, para y repórtalo.
- Antes de cada commit: `git status --short` sin plist, `google-services.json` ni `local.properties`.

## Reporte

`C:/tmp/oc-ios-f4b-reporte.md`: archivos, tabla método Android → método iOS, diferencias inevitables, cuenta del XML,
hashes de commits y lo no verificado. Al terminar: `ENCARGO-IOS-F4B-TERMINADO`.
