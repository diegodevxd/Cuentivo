# Encargo iOS — C1: primer error real de compilación en iOS

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commit con formato `fix(ios): ...`.

El primer `ios-build` que consiguió una Mac (run 42108539986, `macos-26`) falló en
`:shared:compileKotlinIosSimulatorArm64`:

```
Hashing.ios.kt:38:40 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch: fun MatchGroupCollection.get(name: String)
Hashing.ios.kt:38:51 None of the following candidates is applicable: fun String.toByte()
```

**C1.1.** En `shared/src/iosMain/kotlin/com/cuentivo/app/domain/Hashing.ios.kt` agrega
`import kotlinx.cinterop.get`, en su lugar alfabético. Es lo único que falta: `out[index]` sobre un
`CPointer<UByteVar>` necesita ese operador. No cambies nada más de ese archivo.

**C1.2.** En `.github/workflows/ios-build.yml` deja la matriz solo con `macos-26`:
`os: [macos-26]`. `macos-15` y `macos-14` no consiguen runner y solo ensucian el resultado.
Conserva la matriz y el nombre del artefacto.

**No corras Gradle:** Windows no puede compilar iOS, y esto no toca Android.

## Reporte

`C:/tmp/oc-ios-c1-reporte.md`: diff resumido y hash del commit. Al terminar: `ENCARGO-IOS-C1-TERMINADO`.
