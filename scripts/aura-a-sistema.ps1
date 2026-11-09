# Pone la última Aura en /system/priv-app/Aura de una caja ya preparada, sin tocar sus apps ni sus datos.
# Hace falta una vez en las cajas que quedaron con Aura 1.1 o anterior en /system: desde la 1.2, Aura instala sin la
# ventana de confirmación de Android, pero sólo si la copia de /system pide ese permiso. Después, las actualizaciones
# desde Aura (Sistema › Buscar actualizaciones) ya lo conservan.
# Lo que falte en la PC (adb, build-tools, Java portátil) se instala solo, sin Android Studio ni administrador.
#
# Uso:  .\aura-a-sistema.ps1 -Ip 192.168.X.Y [-Puerto 3222]    (o -Serial <serie>)
#       .\aura-a-sistema.ps1 -Buscar                            (lista las cajas con ADB abierto en la red)
param(
    [string] $Ip = '',
    [int] $Puerto = 3222,
    [string] $Serial = '',
    [switch] $Buscar
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

function Bajar-Zip($url, $nombre) {
    "Bajando $nombre..."  | Write-Host
    $tmp = Join-Path $env:TEMP "aura-$nombre"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue; New-Item -ItemType Directory $tmp | Out-Null
    $ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
    Invoke-WebRequest -UseBasicParsing $url -OutFile (Join-Path $tmp 'd.zip') -ErrorAction Stop
    Expand-Archive (Join-Path $tmp 'd.zip') $tmp
    return $tmp
}
function Requisitos-Adb {
    if (Get-Command adb -ErrorAction SilentlyContinue) { return }
    $pt = Join-Path $sdk 'platform-tools'
    if (-not (Test-Path (Join-Path $pt 'adb.exe'))) {
        $d = Bajar-Zip 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' 'platform-tools'
        New-Item -ItemType Directory -Force $sdk | Out-Null
        Move-Item (Join-Path $d 'platform-tools') $pt; Remove-Item -Recurse -Force $d
    }
    $env:Path = "$pt;$env:Path"
}
function Apksigner-Funciona { cmd /c "`"$(Join-Path $script:BT 'apksigner.bat')`" --version >nul 2>nul"; return ($LASTEXITCODE -eq 0) }
function Requisitos-BuildTools {
    $btDir = Join-Path $sdk 'build-tools'
    $buscarBt = { Get-ChildItem $btDir -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'aapt2.exe')) } |
        Sort-Object { [version]$_.Name } | Select-Object -Last 1 }
    $bt = & $buscarBt
    if (-not $bt) {
        $d = Bajar-Zip 'https://dl.google.com/android/repository/build-tools_r34-windows.zip' 'build-tools'
        $dentro = Get-ChildItem $d -Recurse -Filter aapt2.exe | Select-Object -First 1
        if (-not $dentro) { throw 'El zip de build-tools no trae aapt2.exe.' }
        New-Item -ItemType Directory -Force $btDir | Out-Null
        Move-Item $dentro.DirectoryName (Join-Path $btDir '34.0.0'); Remove-Item -Recurse -Force $d
        $bt = & $buscarBt
    }
    $script:BT = $bt.FullName
    $jre = Join-Path $sdk 'aura-jre'
    if (Test-Path (Join-Path $jre 'bin\java.exe')) { $env:JAVA_HOME = $jre; $env:Path = "$jre\bin;$env:Path" }
    if (-not (Apksigner-Funciona)) {
        $d = Bajar-Zip 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse' 'java'
        $dentro = Get-ChildItem $d -Recurse -Filter java.exe | Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
        if (-not $dentro) { throw 'El zip de Java no trae bin\java.exe.' }
        Remove-Item -Recurse -Force $jre -ErrorAction SilentlyContinue
        Move-Item $dentro.Directory.Parent.FullName $jre; Remove-Item -Recurse -Force $d
        $env:JAVA_HOME = $jre; $env:Path = "$jre\bin;$env:Path"
        if (-not (Apksigner-Funciona)) { throw 'apksigner no arranca ni con el Java portátil.' }
    }
}

Requisitos-Adb

# -Buscar: prueba el puerto de ADB en las redes /24 de esta PC y muestra qué caja y qué Aura hay en cada IP que responde.
if ($Buscar) {
    $bases = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' } |
        ForEach-Object { ($_.IPAddress -split '\.')[0..2] -join '.' } | Sort-Object -Unique)
    if (-not $bases) { Write-Host '  Esta PC no tiene red IPv4.'; exit 1 }
    Write-Host "  Buscando cajas con ADB (puerto $Puerto) en: $(($bases | ForEach-Object { "$_.x" }) -join ', ')..."
    $intentos = foreach ($b in $bases) {
        foreach ($n in 1..254) {
            $c = New-Object Net.Sockets.TcpClient
            [pscustomobject]@{ Ip = "$b.$n"; C = $c; T = $c.ConnectAsync("$b.$n", $Puerto) }
        }
    }
    $limite = (Get-Date).AddSeconds(4)
    while ((Get-Date) -lt $limite -and @($intentos | Where-Object { -not $_.T.IsCompleted }).Count) { Start-Sleep -Milliseconds 200 }
    $abiertas = @($intentos | Where-Object { $_.T.IsCompleted -and -not $_.T.IsFaulted -and $_.C.Connected } | ForEach-Object { $_.Ip })
    $intentos | ForEach-Object { $_.C.Close() }
    $hay = 0
    foreach ($a in $abiertas) {
        $s = "${a}:$Puerto"
        adb connect $s 2>&1 | Out-Null
        if ((adb -s $s get-state 2>&1 | Out-String).Trim() -ne 'device') { continue }
        $modelo = (adb -s $s shell getprop ro.product.model 2>&1 | Out-String).Trim()
        $chip = (adb -s $s shell getprop ro.board.platform 2>&1 | Out-String).Trim()
        $v = ((adb -s $s shell dumpsys package com.jajua.aura 2>&1 | Out-String) -split "`n" |
            Where-Object { $_ -match 'versionName=' } | Select-Object -First 1) -replace '.*versionName=', ''
        if (-not $v) { $v = 'sin Aura' } else { $v = "Aura $($v.Trim())" }
        Write-Host ("  {0,-15}  {1}  ({2}, {3})" -f $a, $v, $modelo, $chip) -ForegroundColor Green
        adb disconnect $s 2>&1 | Out-Null
        $hay++
    }
    if (-not $hay) {
        Write-Host '  No encontré ninguna caja. En la TV: Aura > Sistema > Abrir ADB, y que la caja esté en la misma red que la PC.'
    } else { Write-Host '  Escribe una de esas IP para actualizarla.' }
    exit 0
}

if (-not $Serial) { if (-not $Ip) { throw 'Falta -Ip (o -Serial, o -Buscar).' }; $Serial = "${Ip}:$Puerto" }
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
Requisitos-BuildTools
function Version-Apk($f) {
    $l = & (Join-Path $script:BT 'aapt2.exe') dump badging $f 2>$null | Select-Object -First 1
    if ("$l" -match "versionCode='(\d+)'") { return [int]$matches[1] }; return 0
}
$aura = Join-Path $env:TEMP 'Aura-ultima.apk'
$ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
Invoke-WebRequest -UseBasicParsing 'https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/latest/download/Aura.apk' -OutFile $aura
$local = Join-Path $root 'build\Aura.apk'
if ((Test-Path $local) -and (Version-Apk $local) -gt (Version-Apk $aura)) { $aura = $local }
$cert = (cmd /c "`"$(Join-Path $script:BT 'apksigner.bat')`" verify --print-certs `"$aura`" 2>nul" | Select-String 'SHA-256 digest: (\w+)' | Select-Object -First 1).Matches.Groups[1].Value
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
$conWifi = [bool](Sh 'ls /sys/class/net/wlan0 2>/dev/null')

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
$volvio = $false
while ((Get-Date) -lt $fin) {
    try { Conectar } catch { continue }
    if ((Sh 'getprop sys.boot_completed') -eq '1') { $volvio = $true; break }
    Start-Sleep 5
}
if (-not $volvio) {
    'AVISO: Aura ya quedó en /system, pero la caja no volvió en ' + $Serial + ' tras reiniciar. Si cambió de IP (por ejemplo,'
    'porque el Wi-Fi no arrancó), conéctala por cable o búscala con Enter, y revisa que Aura siga abriendo.'
    exit 1
}
if ((Sh 'dumpsys package com.jajua.aura') -match 'android\.permission\.INSTALL_PACKAGES: granted=true') {
    'Listo: Aura de sistema actualizada; instala apps y actualizaciones sin la ventana de Android.'
} else {
    'AVISO: Aura quedó en /system, pero Android no le dio INSTALL_PACKAGES: seguirá mostrando su ventana de confirmación.'
}
# Algunas cajas (HiSilicon con chip Unisoc) dejan el Wi-Fi encendido durante un reinicio y el chip ya no responde
# hasta quitarle la corriente.
if ($conWifi) {
    $hasta = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $hasta -and -not (Sh 'ls /sys/class/net/wlan0 2>/dev/null')) { Start-Sleep 5 }
    if (-not (Sh 'ls /sys/class/net/wlan0 2>/dev/null')) {
        'AVISO: el Wi-Fi de la caja no arrancó tras el reinicio. Desconéctala de la corriente 15 segundos y vuelve a conectarla.'
    }
}
