#Requires -Version 5.1
<#
.SYNOPSIS
    Graba el recorrido de la libreta para la ficha de Play.

.DESCRIPTION
    Pone el MODO DEMOSTRACIÓN de Android (barra de estado limpia: hora fija, batería llena, sin
    iconos de notificación), graba la pantalla con `screenrecord` mientras corre el guion
    instrumentado `LedgerDemoRecordingTest`, y trae el `.mp4` a `dist/`.

    El modo demostración se apaga SIEMPRE, pase lo que pase: si el script muriera a medias y lo
    dejara puesto, el teléfono se quedaría con la hora falsa.

    Va contra el EMULADOR. El serial se pasa por parámetro y **no se adivina nunca**: con el teléfono
    de Diego conectado, elegir mal sería grabar su app real, con sus datos. Por eso, además, el
    script se niega a grabar un dispositivo que no sea un emulador.

    Nota de implementación: se llama a `adb` DIRECTAMENTE y no a través de una función envoltorio.
    Un envoltorio en PowerShell intenta casar `-a`, `-e` y demás opciones de `am broadcast` con sus
    propios parámetros y revienta con "el nombre de parámetro 'e' es ambiguo". Un comando nativo pasa
    los argumentos tal cual.

.EXAMPLE
    .\scripts\grabar-demo.ps1
    .\scripts\grabar-demo.ps1 -Serial emulator-5554
#>
[CmdletBinding()]
param(
    # Serial EXPLÍCITO. No hay autodetección a propósito: elegir mal graba otro dispositivo.
    [string]$Serial = 'emulator-5554',

    # Clase del guion instrumentado.
    [string]$TestClass = 'com.cuentivo.app.ui.LedgerDemoRecordingTest',

    # Segundos de grabación como red de seguridad, por si algo se cuelga.
    [int]$TimeLimitSeconds = 180,

    [string]$OutputDir = 'dist'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$remoteVideo = '/sdcard/demo-libreta-v41.mp4'
$localVideo = Join-Path $repoRoot (Join-Path $OutputDir 'demo-libreta-v41.mp4')

function Set-DemoMode {
    param([switch]$Off)
    if ($Off) {
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command exit | Out-Null
        & $adb -s $Serial shell settings put global sysui_demo_allowed 0 | Out-Null
        Write-Host 'modo demostracion: APAGADO'
    }
    else {
        & $adb -s $Serial shell settings put global sysui_demo_allowed 1 | Out-Null
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command enter | Out-Null
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000 | Out-Null
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false | Out-Null
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 | Out-Null
        & $adb -s $Serial shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false | Out-Null
        Write-Host 'modo demostracion: ENCENDIDO'
    }
}

Write-Host "=== grabando el demo contra $Serial ==="

# --- El dispositivo se comprueba ANTES de tocar nada -------------------------------------------
if (-not (Test-Path -LiteralPath $adb)) { throw "no encuentro adb en $adb" }

$devices = & $adb devices
if (-not ($devices | Select-String -Pattern "^$([regex]::Escape($Serial))\s+device")) {
    throw "el serial '$Serial' no esta conectado como device. Conectados:`n$($devices -join "`n")"
}

# La red de seguridad que importa: esto graba PANTALLA, y un telefono real tiene los datos de Diego.
if (-not $Serial.StartsWith('emulator-')) {
    throw "el serial '$Serial' no es un emulador. Este script graba la pantalla de un dispositivo, y un telefono real tiene datos reales: si de verdad quieres grabarlo, edita el script a proposito."
}

New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot $OutputDir) | Out-Null

$recorder = $null
try {
    Set-DemoMode

    # --- Grabar en segundo plano -----------------------------------------------------------------
    & $adb -s $Serial shell rm -f $remoteVideo | Out-Null
    $recorder = Start-Process -FilePath $adb -PassThru -NoNewWindow -ArgumentList @(
        '-s', $Serial, 'shell', 'screenrecord',
        '--time-limit', "$TimeLimitSeconds",
        '--bit-rate', '8000000',
        $remoteVideo
    )
    Write-Host 'screenrecord: grabando'
    Start-Sleep -Seconds 2

    # --- El guion -------------------------------------------------------------------------------
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
    $env:ANDROID_SERIAL = $Serial

    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') :app:connectedDebugAndroidTest `
            "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" `
            --no-daemon --no-configuration-cache --no-watch-fs --console=plain
        if ($LASTEXITCODE -ne 0) { throw "el guion fallo (exit $LASTEXITCODE)" }
    }
    finally {
        Pop-Location
    }
}
finally {
    # --- Parar la grabación y apagar el modo demostración, PASE LO QUE PASE ----------------------
    if ($null -ne $recorder -and -not $recorder.HasExited) {
        # SIGINT hace que screenrecord cierre el archivo por las buenas.
        & $adb -s $Serial shell pkill -INT -f screenrecord 2>$null | Out-Null
        if (-not $recorder.WaitForExit(15000)) { $recorder.Kill() }
    }
    # Un respiro para que el contenedor se cierre antes de copiarlo.
    Start-Sleep -Seconds 2

    Set-DemoMode -Off
}

# --- Traer el video ----------------------------------------------------------------------------
& $adb -s $Serial pull $remoteVideo $localVideo | Out-Null
if (-not (Test-Path -LiteralPath $localVideo)) { throw "no se trajo el video a $localVideo" }

$size = (Get-Item -LiteralPath $localVideo).Length
Write-Host "video: $localVideo ($([math]::Round($size / 1MB, 1)) MB)"
if ($size -lt 100KB) { Write-Host 'AVISO: el video pesa muy poco; revisa que screenrecord haya grabado de verdad.' }
