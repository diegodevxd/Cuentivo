# Encargo iOS — Fase 7: todo listo para subir (sin tocar Android)

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
Misma carpeta y formato de commit. **Hora límite dura: reporte antes de las 03:15.**

Lee la "Fase 7" y "Día de pago" del plan. Este encargo **solo crea archivos de iOS, fastlane,
workflows y documentación**: no toques `app/`, `shared/src/` ni Gradle. Si necesitas tocarlos, para
y repórtalo. La puerta Android (`testDebugUnitTest assembleDebug`) se corre una vez al final para
confirmar que nada cambió.

**P1. `iosApp/iosApp/Info.plist`**: `NSCameraUsageDescription` y `NSPhotoLibraryUsageDescription`
en español ("Cuentivo usa la cámara para leer la foto de tu libreta de fiado." / "...para elegir la
foto de tu libreta."), `ITSAppUsesNonExemptEncryption = false`, `CFBundleDevelopmentRegion = es`,
`CFBundleLocalizations` = es, en. No borres claves existentes.

**P2. `iosApp/iosApp/PrivacyInfo.xcprivacy`** (plist válido) con `NSPrivacyTracking = false`,
`NSPrivacyCollectedDataTypes` coherente con `docs/superpowers/plans/2026-08-30-cuentivoai-privacy.md`
(lee ese archivo; no inventes recolección que no exista) y `NSPrivacyAccessedAPITypes` con
`NSPrivacyAccessedAPICategoryUserDefaults` (CA92.1) y `NSPrivacyAccessedAPICategoryFileTimestamp`
(C617.1). Agrégalo a los recursos del target en el `.pbxproj` **solo si** puedes hacerlo copiando la
estructura exacta de cómo está referenciado `Assets.xcassets` (PBXFileReference + PBXBuildFile +
grupo + Resources phase, con IDs nuevos de 24 hex únicos). Si no estás seguro, déjalo fuera del
proyecto y anótalo como paso manual.

**P3. `fastlane/Appfile` y `fastlane/Fastfile`**: `app_identifier "com.cuentivo.app"`; lane `beta`
que usa `app_store_connect_api_key` con `ENV["ASC_KEY_ID"]`, `ENV["ASC_ISSUER_ID"]`,
`ENV["ASC_KEY_P8"]` (base64, `is_key_content_base64: true`), luego `build_app` del proyecto
`iosApp/iosApp.xcodeproj`, scheme `iosApp`, `export_method "app-store"`, firma automática con
`xcargs "-allowProvisioningUpdates DEVELOPMENT_TEAM=#{ENV['APPLE_TEAM_ID']}"`, y
`upload_to_testflight(skip_waiting_for_build_processing: true)`. `Gemfile` con `fastlane`.

**P4. `.github/workflows/ios-release.yml`**: `workflow_dispatch` solamente, `macos-latest`. Primer
paso: si falta cualquiera de `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `APPLE_TEAM_ID`,
`GOOGLE_SERVICE_INFO_PLIST`, imprime `::error::Falta la cuenta de Apple o un secreto: ver
docs/ios-dia-de-pago.md` y termina con `exit 1`. Luego: decodifica `GOOGLE_SERVICE_INFO_PLIST`
(base64) a `iosApp/iosApp/GoogleService-Info.plist`, JDK 21, Ruby + `bundle install`,
`bundle exec fastlane beta`. Agrega `iosApp/iosApp/GoogleService-Info.plist` al `.gitignore`.

**P5. `docs/ios-dia-de-pago.md`**: la sección "Día de pago" del plan, ampliada paso a paso con las
rutas exactas de App Store Connect y de GitHub (Settings → Secrets and variables → Actions), cómo
generar el base64 en Windows (`[Convert]::ToBase64String([IO.File]::ReadAllBytes("ruta"))`), y
cómo correr `ios-release`. Incluye también lo que se puede hacer **antes** de pagar: registrar la
app iOS en Firebase con bundle id `com.cuentivo.app`, bajar `GoogleService-Info.plist` y guardarlo
como secreto; hacer push de la rama y correr `ios-build`.

## Reporte

`C:/tmp/oc-ios-p-reporte.md`. Al terminar: `ENCARGO-IOS-P-TERMINADO`.
