# Convierte la configuración actual en "de fábrica": tras un formateo la caja arranca en Aura con tus apps.
#  - Aura -> /system/priv-app/Aura (launcher desde el primer arranque, puede reactivar los botones)
#  - Tus apps -> /system/preinstall (*.apk sueltos o carpetas con APK divididos)
#  - preinstall.sh propio (instala también apps divididas) + favoritas/botones en /system/etc/aura
# Sólo funciona si el firmware trae el mecanismo de preinstalación y permite `adb root`; si no, se detiene.
# Haz antes .\respaldar.ps1.
#
# Uso: .\fabrica.ps1 -Serial <IP_TVBOX>:3222 [-Apks ..\apks\fabrica] [-Probar]
#  -Apks: carpeta con las apps (puedes llenarla con -CopiarDeLaCaja paquete1,paquete2 ...)
param(
    [Parameter(Mandatory)] [string] $Serial,
    [string] $Apks = (Join-Path $PSScriptRoot '..\apks\fabrica'),
    [string[]] $CopiarDeLaCaja = @(),
    [switch] $Probar,
    [switch] $SinConfirmar
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
function Sh($c) { (adb -s $Serial shell $c 2>&1 | Out-String).Trim() }

adb -s $Serial root | Out-Null; Start-Sleep 4
if ($Serial -match ':') { adb connect $Serial | Out-Null; Start-Sleep 2 }
if ((Sh 'id -u') -ne '0') { throw 'adb root no está disponible: este firmware no permite modificar /system.' }
if ((Sh 'ls -d /system/preinstall 2>/dev/null') -ne '/system/preinstall' -or -not (Sh 'grep -rl "service preinstall" /init*.rc /vendor/etc/init /system/etc/init 2>/dev/null')) {
    throw 'Este firmware no tiene /system/preinstall ni el servicio "preinstall". Hay que adaptar el método (ver docs/GUIA-NUEVA-TVBOX.md).'
}

# 1. Reunir las apps
New-Item -ItemType Directory -Force $Apks | Out-Null
foreach ($pkg in $CopiarDeLaCaja) {
    $paths = @(adb -s $Serial shell pm path $pkg | ForEach-Object { ($_ -replace '^package:', '').Trim() })
    if (-not $paths) { Write-Warning "$pkg no está instalado"; continue }
    $label = ($pkg -split '\.')[-1]
    if ($paths.Count -eq 1) { adb -s $Serial pull $paths[0] (Join-Path $Apks "$label.apk") | Out-Null }
    else { $d = Join-Path $Apks $label; New-Item -ItemType Directory -Force $d | Out-Null; foreach ($p in $paths) { adb -s $Serial pull $p (Join-Path $d (Split-Path $p -Leaf)) | Out-Null } }
    "copiado $pkg ($($paths.Count) apk)"
}
$aura = Get-ChildItem (Join-Path $root 'build\Aura*.apk') | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $aura) { throw 'No hay build\Aura.apk: compila Aura primero (ver README).' }

# 2. Confirmar
"`nSe va a escribir en /system:"
"  Aura: $($aura.Name)"
Get-ChildItem $Apks | ForEach-Object { "  preinstall: $($_.Name)" }
"Contenido actual de /system/preinstall (se BORRA):"
Sh 'ls /system/preinstall' -split "`n" | ForEach-Object { "  - $_" }
if (-not $SinConfirmar -and (Read-Host '¿Continuar? (s/n)') -ne 's') { 'Cancelado.'; exit 0 }

# 3. Escribir (dispositivo de bloque en rw solo mientras dura)
$sysdev = Sh "mount | grep ' / ' | cut -d' ' -f1"
if ($sysdev -eq '/dev/root') { $sysdev = Sh 'readlink -f /dev/block/by-name/system 2>/dev/null || ls /dev/block/platform/*/by-name/system' }
Sh "blockdev --setrw $sysdev; mount -o remount,rw / || mount -o remount,rw /system" | Out-Null
Sh 'rm -rf /system/preinstall/*; mkdir -p /system/etc/aura /system/priv-app/Aura' | Out-Null
Get-ChildItem $Apks | ForEach-Object { adb -s $Serial push $_.FullName /system/preinstall/ | Select-Object -Last 1 }
adb -s $Serial push (Join-Path $PSScriptRoot 'preinstall.sh') /system/bin/preinstall.sh | Out-Null
adb -s $Serial push (Join-Path $PSScriptRoot 'aura-defaults.properties') /system/etc/aura/defaults.properties | Out-Null
adb -s $Serial push $aura.FullName /data/local/tmp/Aura.apk | Out-Null
Sh 'cp /data/local/tmp/Aura.apk /system/priv-app/Aura/Aura.apk.new && mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk' | Out-Null
Sh ('chown -R root:root /system/preinstall /system/etc/aura /system/priv-app/Aura; ' +
    'find /system/preinstall /system/etc/aura /system/priv-app/Aura -type d -exec chmod 755 {} \; ; ' +
    'find /system/preinstall /system/etc/aura /system/priv-app/Aura -type f -exec chmod 644 {} \; ; ' +
    'chown root:shell /system/bin/preinstall.sh; chmod 755 /system/bin/preinstall.sh; ' +
    'chcon -R u:object_r:system_file:s0 /system/preinstall /system/etc/aura /system/priv-app/Aura /system/bin/preinstall.sh; sync') | Out-Null
Sh 'ls -lR /system/preinstall /system/priv-app/Aura; df -h / | tail -1'

# 4. Probar sin formatear (reinstala encima, conserva datos)
if ($Probar) {
    Sh 'logcat -c; rm -f /data/local/symbol_thirdpart_apks_installed; sh /system/bin/preinstall.sh; logcat -d -s preinstall'
}
"`nListo. Reinicia la caja para que Aura pase a ser app del sistema."
