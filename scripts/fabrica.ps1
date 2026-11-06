# Convierte la configuración actual en "de fábrica": tras un formateo la caja arranca en Aura con tus apps.
#  - Aura -> /system/priv-app/Aura (launcher desde el primer arranque, puede reactivar los botones)
#  - Tus apps -> /system/preinstall (*.apk sueltos o carpetas con APK divididos)
#  - preinstall.sh propio (instala también apps divididas) + favoritas/botones en /system/etc/aura
# Sólo funciona si el firmware trae el mecanismo de preinstalación y permite `adb root`; si no, se detiene.
# Haz antes .\respaldar.ps1.
#
# Uso: .\fabrica.ps1 -Serial <IP_TVBOX>:3222 [-Apks ..\apks\fabrica] [-Probar]
#      [-Bind carpeta] [-Idioma es-MX] [-ZonaHoraria America/Mexico_City] [-Overscan 100]
#  -Apks: carpeta con las apps (puedes llenarla con -CopiarDeLaCaja paquete1,paquete2 ...)
# BORRA el contenido de /system/preinstall: para sólo actualizar Aura en una caja ya lista, ver la guía.
param(
    [Parameter(Mandatory)] [string] $Serial,
    [string] $Apks = (Join-Path $PSScriptRoot '..\apks\fabrica'),
    [string[]] $CopiarDeLaCaja = @(),
    # Carpeta opcional con APK parcheados, nombrados priv-app@Carpeta@App.apk (ver patch_settings.py).
    [string] $Bind = '',
    # Animación de arranque (''  para conservar la del firmware).
    [string] $Animacion = (Join-Path $PSScriptRoot 'bootanimation.zip'),
    # Se escriben en build.prop, así que sobreviven a un formateo. Vacío = no tocar.
    [string] $Idioma = '',           # ej. es-MX
    [string] $ZonaHoraria = '',      # ej. America/Mexico_City
    [ValidateRange(0, 100)] [int] $Overscan = 0,   # 100 = imagen completa; 0 = no tocar
    [switch] $Probar,
    [switch] $SinConfirmar
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
# PowerShell 5.1 turns anything adb writes to stderr into a terminating error under 'Stop'.
function Sh($c) { $ErrorActionPreference = 'Continue'; (adb -s $Serial shell $c 2>&1 | Out-String).Trim() }

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
# adb escribe su progreso en stderr: con 'Stop' y la salida redirigida, PowerShell 5.1 lo trataría como error fatal.
$ErrorActionPreference = 'Continue'
# system-as-root mounts it on /; older firmwares (e.g. Rockchip Android 7) mount it on /system with a rootfs on /.
$sysmnt = if (Sh "mount | grep ' /system '") { '/system' } else { '/' }
$sysdev = Sh "mount | grep ' $sysmnt ' | cut -d' ' -f1"
if ($sysdev -eq '/dev/root') { $sysdev = Sh 'readlink -f /dev/block/by-name/system 2>/dev/null || ls /dev/block/platform/*/by-name/system' }
Sh "blockdev --setrw $sysdev; mount -o remount,rw $sysmnt" | Out-Null
if (-not (Sh "mount | grep ' $sysmnt ' | grep '[(,]rw[,)]'")) { adb -s $Serial remount | Out-Null }
if (-not (Sh "mount | grep ' $sysmnt ' | grep '[(,]rw[,)]'")) { throw "No pude montar $sysmnt en escritura." }
Sh 'rm -rf /system/preinstall/*; mkdir -p /system/etc/aura /system/priv-app/Aura' | Out-Null
Get-ChildItem $Apks | ForEach-Object { adb -s $Serial push $_.FullName /system/preinstall/ | Select-Object -Last 1 }
# Overwrite in place: init runs the script under its own SELinux label (e.g. preinstall_exec), which must be kept.
adb -s $Serial push (Join-Path $PSScriptRoot 'preinstall.sh') /data/local/tmp/preinstall.sh | Out-Null
Sh 'cat /data/local/tmp/preinstall.sh > /system/bin/preinstall.sh' | Out-Null
adb -s $Serial push (Join-Path $PSScriptRoot 'aura-defaults.properties') /system/etc/aura/defaults.properties | Out-Null
# Patched firmware screens (see patch_settings.py); preinstall.sh mounts them after each boot.
if ($Bind -and (Test-Path $Bind)) {
    Sh 'mkdir -p /system/etc/aura/bind' | Out-Null
    Get-ChildItem $Bind -File | ForEach-Object { adb -s $Serial push $_.FullName /system/etc/aura/bind/ | Select-Object -Last 1 }
}
adb -s $Serial push $aura.FullName /data/local/tmp/Aura.apk | Out-Null
Sh 'cp /data/local/tmp/Aura.apk /system/priv-app/Aura/Aura.apk.new && mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk' | Out-Null
Sh ('chown -R root:root /system/preinstall /system/etc/aura /system/priv-app/Aura; ' +
    'find /system/preinstall /system/etc/aura /system/priv-app/Aura -type d -exec chmod 755 {} \; ; ' +
    'find /system/preinstall /system/etc/aura /system/priv-app/Aura -type f -exec chmod 644 {} \; ; ' +
    'chown root:shell /system/bin/preinstall.sh; chmod 755 /system/bin/preinstall.sh; ' +
    'chcon -R u:object_r:system_file:s0 /system/preinstall /system/etc/aura /system/priv-app/Aura; sync') | Out-Null
Sh "ls -lR /system/preinstall /system/priv-app/Aura; df -h $sysmnt"

# 3b. Arranque y ajustes de la caja
function Set-BuildProp($clave, $valor) {
    Sh "grep -q '^$clave=' /system/build.prop && sed -i 's#^$clave=.*#$clave=$valor#' /system/build.prop || echo '$clave=$valor' >> /system/build.prop" | Out-Null
    "build.prop: $clave=$valor"
}
if ($Animacion -and (Test-Path $Animacion)) {
    adb -s $Serial push $Animacion /data/local/tmp/bootanimation.zip | Out-Null
    Sh ('cp /data/local/tmp/bootanimation.zip /system/media/bootanimation.zip.new && chown root:root /system/media/bootanimation.zip.new && ' +
        'chmod 644 /system/media/bootanimation.zip.new && chcon u:object_r:system_file:s0 /system/media/bootanimation.zip.new && ' +
        'mv /system/media/bootanimation.zip.new /system/media/bootanimation.zip; rm -f /data/local/tmp/bootanimation.zip') | Out-Null
    'Animación de arranque instalada.'
}
if ($Idioma) { Set-BuildProp 'ro.product.locale' $Idioma }
if ($ZonaHoraria) { Set-BuildProp 'persist.sys.timezone' $ZonaHoraria }
if ($Overscan) { Set-BuildProp 'persist.sys.overscan.main' "overscan $Overscan,$Overscan,$Overscan,$Overscan" }
Sh 'sync' | Out-Null

# 4. Probar sin formatear (reinstala encima, conserva datos)
if ($Probar) {
    Sh 'logcat -c; rm -f /data/local/symbol_thirdpart_apks_installed; sh /system/bin/preinstall.sh; logcat -d -s preinstall'
}
"`nListo. Reinicia la caja para que Aura pase a ser app del sistema."
