# Compila e instala el puente HDMI-CEC (cec-bridge/) para manejar la TV Box con el control de la TV.
# Requiere `adb root` y /system escribible. Ver cec-bridge/README.md.
# Uso: .\control-tv.ps1 -Serial <IP_DE_TU_TVBOX>:<PUERTO>
param([Parameter(Mandatory)] [string] $Serial)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$src = Join-Path $root 'cec-bridge'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$jar = Get-ChildItem "$sdk\platforms\android-*\android.jar" | Sort-Object FullName | Select-Object -First 1
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
function Sh($c) { (adb -s $Serial shell $c 2>&1 | Out-String).Trim() }

# 1. Compilar (javac + d8)
$out = Join-Path $src 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory "$out\cls" | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" -Xlint:-options --release 8 -cp $jar.FullName -d "$out\cls" (Get-ChildItem "$src\src" -Recurse -Filter *.java).FullName
& "$($bt.FullName)\d8.bat" --min-api 21 --lib $jar.FullName --output $out (Get-ChildItem "$out\cls" -Recurse -Filter *.class).FullName
Move-Item "$out\classes.dex" "$out\cecbridge.dex" -Force
$rc = [IO.File]::ReadAllText("$src\aura_cec.rc") -replace "`r`n", "`n"
[IO.File]::WriteAllText("$out\aura_cec.rc", $rc, (New-Object Text.UTF8Encoding $false))

# 2. Instalar en /system
adb -s $Serial root | Out-Null; Start-Sleep 4
if ($Serial -match ':') { adb connect $Serial | Out-Null; Start-Sleep 2 }
if ((Sh 'id -u') -ne '0') { throw 'adb root no está disponible en este firmware.' }
adb -s $Serial push "$out\cecbridge.dex" /data/local/tmp/cecbridge.dex | Out-Null
adb -s $Serial push "$out\aura_cec.rc" /data/local/tmp/aura_cec.rc | Out-Null
Sh ('dev=$(readlink -f /dev/block/by-name/system); blockdev --setrw $dev; mount -o remount,rw /; ' +
    'mkdir -p /system/etc/aura; ' +
    'cp /data/local/tmp/cecbridge.dex /system/etc/aura/cecbridge.dex.new && mv /system/etc/aura/cecbridge.dex.new /system/etc/aura/cecbridge.dex; ' +
    'cp /data/local/tmp/aura_cec.rc /system/etc/init/aura_cec.rc; ' +
    'chmod 644 /system/etc/aura/cecbridge.dex /system/etc/init/aura_cec.rc; ' +
    'chcon u:object_r:system_file:s0 /system/etc/aura/cecbridge.dex /system/etc/init/aura_cec.rc; ' +
    'rm /data/local/tmp/cecbridge.dex /data/local/tmp/aura_cec.rc; sync') | Out-Null

# 3. Arrancar: si el servicio ya existía basta reiniciarlo; si es nuevo, init lo lee al arrancar.
if ((Sh 'getprop init.svc.aura_cec')) {
    Sh 'setprop ctl.restart aura_cec' | Out-Null
    Start-Sleep 3
    "Servicio: $(Sh 'getprop init.svc.aura_cec')"
} else {
    'Instalado. Reinicia la caja para que arranque el servicio (adb reboot).'
}
adb -s $Serial unroot | Out-Null
