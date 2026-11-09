# Pone la última Aura en /system/priv-app/Aura de una caja ya preparada, sin tocar sus apps ni sus datos.
# Hace falta una vez en las cajas que quedaron con Aura 1.1 o anterior en /system: desde la 1.2, Aura instala sin la
# ventana de confirmación de Android, pero sólo si la copia de /system pide ese permiso. Después, las actualizaciones
# desde Aura (Sistema › Buscar actualizaciones) ya lo conservan.
#
# Uso:  .\aura-a-sistema.ps1 -Ip 192.168.X.Y [-Puerto 3222]    (o -Serial <serie>)
param(
    [string] $Ip = '',
    [int] $Puerto = 3222,
    [string] $Serial = ''
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { $env:Path = "$(Join-Path $sdk 'platform-tools');$env:Path" }
if (-not $Serial) { if (-not $Ip) { throw 'Falta -Ip (o -Serial).' }; $Serial = "${Ip}:$Puerto" }
function Sh($c) { (adb -s $Serial shell $c 2>&1 | Out-String).Trim() }
function Conectar {
    for ($i = 0; $i -lt 20; $i++) {
        if ($Serial -match ':') { adb disconnect $Serial 2>&1 | Out-Null; adb connect $Serial 2>&1 | Out-Null }
        Start-Sleep 3
        if ((adb -s $Serial get-state 2>&1 | Out-String).Trim() -eq 'device') { return }
    }
    throw "No conecto con $Serial. Si ADB quedó 'offline', apaga y enciende la Depuración USB en la TV."
}

# 1. La Aura más nueva: la publicada, o build\Aura.apk si es más nueva (recién compilada). Sólo con la firma de Aura.
$bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $bt) { throw 'No hay build-tools: ejecuta Preparar-TVBox.bat una vez para instalarlas.' }
function Version-Apk($f) {
    $l = & (Join-Path $bt.FullName 'aapt2.exe') dump badging $f 2>$null | Select-Object -First 1
    if ("$l" -match "versionCode='(\d+)'") { return [int]$matches[1] }; return 0
}
$aura = Join-Path $env:TEMP 'Aura-ultima.apk'
Invoke-WebRequest -UseBasicParsing 'https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/latest/download/Aura.apk' -OutFile $aura
$local = Join-Path $root 'build\Aura.apk'
if ((Test-Path $local) -and (Version-Apk $local) -gt (Version-Apk $aura)) { $aura = $local }
$cert = (cmd /c "`"$(Join-Path $bt.FullName 'apksigner.bat')`" verify --print-certs `"$aura`" 2>nul" | Select-String 'SHA-256 digest: (\w+)' | Select-Object -First 1).Matches.Groups[1].Value
if ($cert -ne '1bf3f8f944dac8496f0f13be7a40304ef6622c51e778f4d9e160b99ebc12ff21') { throw "$aura no tiene la firma de Aura." }
$version = Version-Apk $aura
"Aura a instalar: versionCode $version ($aura)"

# 2. Root y Aura ya en /system.
Conectar
adb -s $Serial root 2>&1 | Out-Null; Start-Sleep 4; Conectar
if ((Sh 'id -u') -ne '0') { throw 'adb root no funciona en esta caja.' }
if ((Sh 'ls /system/priv-app/Aura/Aura.apk 2>/dev/null') -ne '/system/priv-app/Aura/Aura.apk') {
    throw 'Esta caja no tiene Aura en /system/priv-app: prepárala con Preparar-TVBox.bat.'
}

# 3. Primero como actualización (conserva favoritas y botones), luego la copia de /system.
$r = (adb -s $Serial install -r $aura 2>&1 | Out-String).Trim()
if ($r -notmatch 'Success' -and $r -notmatch 'DOWNGRADE') { throw "No se pudo instalar Aura: $r" }
# /system en escritura: en HiSilicon (system-as-root) adb remount falla con "Permission denied" y hay que poner el
# dispositivo de bloque en rw y remontar a mano, como fabrica.ps1; en Rockchip sólo funciona adb remount.
$sysmnt = if (Sh "grep ' /system ' /proc/mounts") { '/system' } else { '/' }
function SystemRw { [bool](Sh "grep ' $sysmnt ' /proc/mounts | grep '[ ,]rw[ ,]'") }
$sysdev = Sh "grep ' $sysmnt ' /proc/mounts | cut -d' ' -f1"
if ($sysdev -eq '/dev/root' -or -not (Sh "ls $sysdev 2>/dev/null")) {
    $sysdev = Sh 'readlink -f /dev/block/by-name/system 2>/dev/null || ls /dev/block/platform/*/by-name/system 2>/dev/null'
    if (-not $sysdev) { $sysdev = Sh "tr ' ' '\n' < /proc/cmdline | grep '^root=/dev/' | cut -d= -f2" }
}
Sh "blockdev --setrw $sysdev; mount -o remount,rw $sysmnt" | Out-Null
if (-not (SystemRw)) { adb -s $Serial remount 2>&1 | Out-Null }
if (-not (SystemRw)) { throw "No pude montar $sysmnt en escritura ($sysdev): no toqué /system." }
adb -s $Serial push $aura /data/local/tmp/Aura.apk 2>&1 | Out-Null
$copia = Sh ('cp /data/local/tmp/Aura.apk /system/priv-app/Aura/Aura.apk.new && chown root:root /system/priv-app/Aura/Aura.apk.new && ' +
    'chmod 644 /system/priv-app/Aura/Aura.apk.new && chcon u:object_r:system_file:s0 /system/priv-app/Aura/Aura.apk.new && ' +
    'mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk; rm -f /data/local/tmp/Aura.apk; sync')
$h1 = ((Sh 'md5sum /system/priv-app/Aura/Aura.apk') -split '\s+')[0]
$h2 = (Get-FileHash -Algorithm MD5 $aura).Hash.ToLower()
if ($h1 -ne $h2) { throw "La copia de /system no coincide con el APK: revisa antes de reiniciar. $copia" }
'Copiada a /system/priv-app/Aura. Reiniciando...'

# 4. Tras reiniciar, Android debe dar a Aura el permiso para instalar sin la ventana de confirmación.
adb -s $Serial reboot 2>&1 | Out-Null
Start-Sleep 40
$fin = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $fin) {
    try { Conectar } catch { continue }
    if ((Sh 'getprop sys.boot_completed') -eq '1') { break }
}
if ((Sh 'dumpsys package com.jajua.aura') -match 'android\.permission\.INSTALL_PACKAGES: granted=true') {
    'Listo: Aura de sistema actualizada; instala apps y actualizaciones sin la ventana de Android.'
} else {
    'AVISO: Aura quedó en /system, pero Android no le dio INSTALL_PACKAGES: seguirá mostrando su ventana de confirmación.'
}
