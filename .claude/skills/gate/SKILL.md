---
name: gate
description: Ejecuta el gate de evidencia completo de Cuentivo — pruebas JVM, builds, lint, los dos comandos de functions, el gate del emulador de Firestore, paridad de claves, puerta del idioma y diff limpio — y reporta los números reales, no un resumen.
disable-model-invocation: true
---

# Gate de evidencia de Cuentivo

Este proyecto **no tiene CI**. Nada valida el código salvo lo que se ejecute aquí. Esta skill es
esa validación.

**Regla que gobierna todo lo demás:** reporta el comando que realmente ejecutaste y su resultado
real. Si algo no corrió, dilo. Un hueco declarado vale; uno tapado, no. No inventes resultados de
build.

## 1. Entorno — sin esto nada corre

Norton intercepta TLS en esta máquina, así que todo Gradle necesita el trust store temporal o falla
con `PKIX path building failed`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
```

Si falla la validación del certificado al bajar una dependencia nueva, Norton rotó su raíz y hay que
regenerar el trust store — el procedimiento está en `CLAUDE.md`. **No se desactiva TLS ni se modifica
el JDK instalado.**

## 2. Android

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

`--no-watch-fs` no es opcional: el file watcher de Gradle se vuelve extremadamente lento aquí.

**En un gate de release, agrega `--rerun-tasks` a `testDebugUnitTest`.** Sin eso, un `UP-TO-DATE`
puede hacerte reportar como verde algo que no corrió en este árbol.

**El conteo se saca del XML, no del resumen de Gradle:**

```powershell
$t=0;$f=0;$e=0;$n=0
Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object {
  $x=[xml](Get-Content $_.FullName); $n++
  $t+=[int]$x.testsuite.tests; $f+=[int]$x.testsuite.failures; $e+=[int]$x.testsuite.errors
}
"clases $n / tests $t / failures $f / errors $e"
```

## 3. Functions — son TRES comandos, no uno

```bash
cd functions
npm test                          # NO cubre el gate del emulador
npm run lint
```

`vitest.config.ts` sólo incluye `test/**/*.test.ts`, y `firestoreEmulator.gate.ts` **queda fuera**.
Reportar el total de `npm test` como si cubriera el gate es reportar evidencia que no ocurrió.

El gate del emulador va aparte, y en esta máquina **no hay `firebase` ni `java` en el PATH**. La
receta que funciona, desde la raíz del repo:

```bash
export PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH"
npx -y firebase-tools@latest emulators:exec --only firestore --project organitazionlabs \
  "cd functions && npm run test:firestore-emulator"
```

Si el emulador no está levantado, `npm run test:firestore-emulator` se niega a correr con
`FIRESTORE_EMULATOR_HOST is not set`. **Eso no es un pase: es un no-corrió.** Dilo así.

## 4. Idioma

Paridad de claves — el número envejece, así que cuenta, no confíes en el último valor escrito:

```bash
grep -c "<string name=" shared/src/commonMain/composeResources/values/strings.xml shared/src/commonMain/composeResources/values-en/strings.xml
```

**Contar no basta.** Dos archivos pueden empatar en total y tener claves distintas. Compara nombres:

```bash
diff <(grep -oP '(?<=<string name=")[^"]+' shared/src/commonMain/composeResources/values/strings.xml | sort) \
     <(grep -oP '(?<=<string name=")[^"]+' shared/src/commonMain/composeResources/values-en/strings.xml | sort)
```

Puerta del idioma — debe salir vacía salvo etiquetas de animación de Compose:

```bash
grep -rnE '(Text\(|text = |label = |title = |detail = |placeholder = |contentDescription = |emptyText = |actionLabel = |eyebrow = |note = |backDescription = )"[^"]*[a-zA-ZáéíóúñÁÉÍÓÚÑ¿¡][^"]*"' \
  app/src/main/java/com/cuentivo/app/ui --include=*.kt \
  | grep -vE '"\$ "|" · "|testTag|neo-action-press|"Cuentivo"'
```

## 5. Árbol

```bash
git diff --check
git status --short
```

El árbol final debe quedar con **sólo** `?? docs/superpowers/handoffs/`, que es de Diego y **no se
toca**: no agregar, no borrar, no modificar.

## 6. Prueba física, cuando aplique

Si hay dispositivo conectado (`adb devices`):

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo real del XML de `app/build/outputs/androidTest-results/`, **y la lista de pruebas saltadas con
su motivo**. Un total sin los skips oculta cobertura ausente.

Si aborta con `INSTRUMENTATION_ABORTED` o `RootViewWithoutFocusException`, suele ser el diálogo ANR
del sistema robándose el foco, no un fallo del código. **No lo reportes verde ni lo reintentes en
bucle**; el procedimiento de arranque en frío está en `CLAUDE.md`.

## Qué reportar

Una tabla con: pruebas JVM (clases/total/fallos/errores), functions, gate del emulador, resultado de
cada build y del lint, paridad de claves **con el diff de nombres**, puerta del idioma, y estado del
árbol. Compara contra la línea base anterior: **puede subir, nunca bajar.**

## Lo que este gate NO hace

No despliega functions, no sube nada a Play, no firma ni comitea. Todo eso se autoriza aparte.
