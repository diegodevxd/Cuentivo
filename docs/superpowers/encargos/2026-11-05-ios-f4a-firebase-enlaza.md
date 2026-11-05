# Encargo iOS — F4-A: Firebase enlaza en iOS (solo estructura, sin lógica de negocio)

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push: lo hace el auditor. Commits `ios(F4-A): ...`, uno por tarea.

**Objetivo:** que `ios-build` compile, enlace y genere `Cuentivo.app` con Firebase dentro, y que las 547
pruebas de `:shared` sigan pasando en iOS. Nada de auth, sync ni pantallas: eso es el siguiente encargo.
Windows no puede compilar iOS: lo verifica el runner macOS después del push. Por eso el `.pbxproj` se edita
copiando exactamente la estructura de las entradas que ya existen, y si dudas de algo lo anotas en el reporte.

**Por qué un módulo aparte:** GitLive hace cinterop contra los frameworks de Firebase. Dentro de `:shared`, el
binario de pruebas de iOS se quedaría con símbolos sin resolver, y perderíamos esas 547 pruebas. `:shared`
**no debe depender de GitLive ni de nada de Firebase.**

## Tareas

**A1. Versión de GitLive.** Consulta en Maven Central las versiones de `dev.gitlive:firebase-firestore` y elige
la más reciente compatible con Kotlin 2.2.10, con target `iosSimulatorArm64`. Anota en el reporte la versión,
cómo comprobaste la compatibilidad y la versión de `firebase-ios-sdk` que esa versión de GitLive documenta o usa.
Agrégala a `gradle/libs.versions.toml`: `firebase-auth`, `firebase-firestore`, `firebase-functions`.
Si no hay ninguna compatible con Kotlin 2.2.10, **no subas Kotlin: para y repórtalo.**

**A2. Módulo `:iosKit`** (`iosKit/build.gradle.kts`, incluido en `settings.gradle.kts`): Kotlin Multiplatform con
targets `iosArm64` e `iosSimulatorArm64`, sin target Android, con los plugins de Compose que hoy usa `:shared`.
- Framework `baseName = "Shared"`, `isStatic = true`, para no cambiar el `import Shared` de Swift.
- Depende de `project(":shared")` y de GitLive.
- Mueve con `git mv` `shared/src/iosMain/kotlin/com/cuentivo/app/MainViewController.kt` a
  `iosKit/src/iosMain/kotlin/com/cuentivo/app/`, y quita el framework de `shared/build.gradle.kts`. No toques los
  targets ni las pruebas de `:shared`. Si `Money` u otro símbolo usado es `internal`, repórtalo en vez de abrirlo.
- `iosKit/src/iosMain/kotlin/com/cuentivo/app/FirebaseLinkCheck.kt`: una función que referencie
  `Firebase.firestore`, `Firebase.auth` y `Firebase.functions`, llamada desde `MainViewController`, sin leer ni
  escribir datos ni tocar la red. Su único fin es obligar al enlazador a resolver esos símbolos; dilo en un comentario.

**A3. Proyecto Xcode** (`iosApp/iosApp.xcodeproj/project.pbxproj`):
- El script "Compile Kotlin Framework" pasa a `./gradlew :iosKit:embedAndSignAppleFrameworkForXcode`.
- Actualiza a `iosKit` toda ruta de búsqueda de frameworks que apunte a `shared/build/xcode-frameworks`.
- Agrega Swift Package `https://github.com/firebase/firebase-ios-sdk`, fijado a la versión exacta de A1
  (`XCRemoteSwiftPackageReference` + `XCSwiftPackageProductDependency`), con los productos `FirebaseCore`,
  `FirebaseAuth`, `FirebaseFirestore`, `FirebaseFunctions` y `FirebaseAppCheck` en la fase Frameworks del target.
- `OTHER_LDFLAGS` conserva lo que tenga y agrega `-ObjC`, que Firebase requiere.
- Referencia `iosApp/GoogleService-Info.plist` en la fase Resources, igual que `Assets.xcassets`. El archivo sigue
  ignorado por git: **nunca lo agregues ni leas el de `Downloads`.**

**A4. Arranque en Swift.** En `iosApp/iosApp/iOSApp.swift` agrega `import FirebaseCore`, una clase
`AppDelegate: NSObject, UIApplicationDelegate` cuyo `didFinishLaunchingWithOptions` llama a
`FirebaseApp.configure()` y devuelve `true`, y `@UIApplicationDelegateAdaptor(AppDelegate.self) var delegate` en
`iOSApp`. Conserva `ContentView()` y `import Shared`.

**A5. `.github/workflows/ios-build.yml`:** antes de "Compilar la app de iOS", un paso que falle con
`::error::Falta el secreto GOOGLE_SERVICE_INFO_PLIST` si el secreto está vacío, y si no lo decodifique de base64 a
`iosApp/iosApp/GoogleService-Info.plist`. Pasa el secreto por `env:`; nunca lo imprimas. El paso
`:shared:iosSimulatorArm64Test` no cambia, y `ARCHS=arm64` se queda. Revisa que `ios-release.yml` y `fastlane` no
nombren `:shared` ni rutas de framework; si lo hacen, corrígelos.

**A6. Puerta Android**, con el entorno de Norton:
`.\gradlew.bat testDebugUnitTest :shared:testAndroidHostTest assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain`.
Cuenta desde el XML: **927 en `app` y 550 en `:shared`, 0 fallos**, o más. Si `settings.gradle.kts` con `:iosKit`
rompe algo en Windows, arréglalo sin tocar `app/`. **`app/` no se toca en este encargo.**

## Reglas

- Si en un `.kt` nuevo usas algo de JVM, Native lo rechazará. Solo Kotlin común y GitLive.
- Si para terminar hace falta algo no previsto aquí, para y repórtalo en vez de improvisar.
- Antes de cada commit: `git status --short` sin plist, `google-services.json` ni `local.properties`.

## Reporte

`C:/tmp/oc-ios-f4a-reporte.md`: versiones elegidas y su evidencia, archivos tocados, conteo del XML, hashes de los
commits, y lo que no pudiste verificar en Windows. Al terminar: `ENCARGO-IOS-F4A-TERMINADO`.
