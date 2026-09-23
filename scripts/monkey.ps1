<#
.SYNOPSIS
  Toca la app al azar miles de veces y junta los cierres y congelamientos que encuentre.

.DESCRIPTION
  Usa `monkey`, que ya viene en Android. No gasta tokens. Existe para cazar crashes y ANR que las
  pruebas no cubren, no para juzgar si algo se entiende.

  SEGURIDAD — lee esto antes de correrlo en un teléfono real:
  - `-p com.cuentivo.app` encierra a monkey en la app: bloquea abrir WhatsApp, la hoja de compartir o
    la compra de Play, porque viven en otros paquetes.
  - Pero SÍ puede, dentro de la app, registrar ventas y hasta confirmar "Eliminar cuenta". Por eso:
    córrelo con la app de DEBUG y una cuenta de INVITADO (plan Gratis: no sincroniza a Firestore).
    Nunca con la cuenta Pro de Diego.
  - El serial es obligatorio. Nunca adivina el dispositivo.

.EXAMPLE
  .\scripts\monkey.ps1 -Serial 00170157R000160 -Events 5000
#>
param(
    [Parameter(Mandatory = $true)] [string] $Serial,
    [int] $Events = 5000,
    [int] $Seed = 42,
    [int] $ThrottleMs = 150
)

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$package = 'com.cuentivo.app'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $PSScriptRoot "..\dist\monkey\$stamp"
New-Item -ItemType Directory -Force $outDir | Out-Null

$flags = & $adb -s $Serial shell dumpsys package $package | Select-String 'pkgFlags'
if ($flags -notmatch 'DEBUGGABLE') {
    throw "La app instalada en $Serial no es de debug. Instala la de debug con una cuenta de invitado antes de correr monkey."
}

& $adb -s $Serial logcat -c
Write-Host "monkey: $Events eventos, semilla $Seed, en $Serial. Salida en $outDir"

# Sin cambios de sistema (--pct-syskeys 0): ni volumen ni botón de encendido. Pocos cambios de
# orientación, que ya tienen su propia prueba. Sigue aunque encuentre un error, para juntar varios.
& $adb -s $Serial shell monkey -p $package -s $Seed --throttle $ThrottleMs `
    --pct-syskeys 0 --pct-rotation 2 --pct-appswitch 0 `
    --ignore-crashes --ignore-timeouts --monitor-native-crashes -v -v $Events 2>&1 |
    Out-File -Encoding utf8 (Join-Path $outDir 'monkey.txt')

& $adb -s $Serial logcat -d -b crash | Out-File -Encoding utf8 (Join-Path $outDir 'crash.txt')
& $adb -s $Serial logcat -d '*:E' | Select-String $package | Out-File -Encoding utf8 (Join-Path $outDir 'errores.txt')
& $adb -s $Serial exec-out screencap -p > (Join-Path $outDir 'final.png')

$crashes = (Select-String -Path (Join-Path $outDir 'monkey.txt') -Pattern '// CRASH|// NOT RESPONDING' -AllMatches).Count
Write-Host "Terminó. Cierres/congelamientos detectados por monkey: $crashes. Revisa $outDir"
Write-Host "Para repetir exactamente la misma secuencia: -Seed $Seed"
