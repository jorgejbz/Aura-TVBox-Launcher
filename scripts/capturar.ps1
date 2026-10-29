# Envía teclas de control remoto a un dispositivo y guarda una captura de pantalla.
# Uso: .\capturar.ps1 -Serial emulator-5584 -Nombre 02-foco -Teclas DPAD_DOWN,DPAD_RIGHT
param(
    [Parameter(Mandatory)] [string] $Serial,
    [Parameter(Mandatory)] [string] $Nombre,
    [string[]] $Teclas = @(),
    [int] $Espera = 900
)
$out = Join-Path $PSScriptRoot "..\docs\capturas"
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($t in $Teclas) {
    adb -s $Serial shell input keyevent "KEYCODE_$t"
    Start-Sleep -Milliseconds 350
}
Start-Sleep -Milliseconds $Espera
# screencap se guarda en el dispositivo y se descarga: la redirección '>' de PowerShell 5.1 corrompe binarios.
adb -s $Serial shell screencap -p /sdcard/aura-cap.png
adb -s $Serial pull /sdcard/aura-cap.png (Join-Path $out "$Nombre.png") | Out-Null
adb -s $Serial shell rm /sdcard/aura-cap.png
Join-Path (Resolve-Path $out) "$Nombre.png"
