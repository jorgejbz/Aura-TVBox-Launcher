# Diagnóstico de una TV Box Android: encuentra la caja, distingue ADB real de falso,
# compara los datos anunciados con los reales y marca apps, puertos y conexiones sospechosas.
# Sólo lectura: no instala, borra ni cambia nada.
#
# Uso:  .\diagnostico.ps1                 (busca la caja en la red local)
#       .\diagnostico.ps1 -Ip <IP_TVBOX>
#       .\diagnostico.ps1 -Serial ABC123  (conectada por USB)
param(
    [string] $Ip,
    [string] $Serial,
    [int[]] $Puertos = @(5555, 3222, 5556, 4444, 6666)
)
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$report = Join-Path $logs ("diagnostico-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.md')
$alertas = New-Object System.Collections.Generic.List[string]
function Out-Line($m) { $m | Tee-Object -FilePath $report -Append }
function Alerta($m) { $alertas.Add($m); Out-Line "  ⚠️ $m" }

# ---------------------------------------------------------------- 1. encontrar la caja
function Find-Candidates {
    if ($Serial) { return @($Serial) }
    $hosts = if ($Ip) { @($Ip) } else {
        $mine = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127|169\.254|172\.)' } | Select-Object -First 1
        if (-not $mine) { throw 'No encontré una red local.' }
        $base = ($mine.IPAddress -split '\.')[0..2] -join '.'
        Write-Host "Buscando en $base.0/24 los puertos $($Puertos -join ', ')..."
        1..254 | ForEach-Object { "$base.$_" }
    }
    $found = @()
    foreach ($p in $Puertos) {
        $tasks = foreach ($h in $hosts) { $c = New-Object System.Net.Sockets.TcpClient; [pscustomobject]@{ h = $h; c = $c; t = $c.ConnectAsync($h, $p) } }
        Start-Sleep -Milliseconds 2500
        foreach ($x in $tasks) { if ($x.t.Status -eq 'RanToCompletion' -and $x.c.Connected) { $found += "$($x.h):$p" }; $x.c.Close() }
    }
    return $found
}

# Un ADB falso responde con texto fijo: no tiene echo, lista lo mismo para cualquier ruta y casi no tiene paquetes.
function Test-RealAdb($s) {
    if ($s -match ':') { adb connect $s 2>&1 | Out-Null; Start-Sleep 2 }
    if ((adb -s $s get-state 2>&1 | Out-String).Trim() -ne 'device') { return 'NO AUTORIZADO / SIN CONEXIÓN' }
    $echo = (adb -s $s shell echo AURA_OK 2>&1 | Out-String).Trim()
    $ls = (adb -s $s shell ls /zz_no_existe_aura 2>&1 | Out-String)
    $pkgs = @(adb -s $s shell pm list packages 2>$null | Where-Object { $_ -like 'package:*' }).Count
    if ($echo -eq 'AURA_OK' -and $ls -match 'No such file' -and $pkgs -ge 25) { return 'REAL' }
    return "FALSO (echo='$echo', paquetes=$pkgs)"
}

Out-Line "# Diagnóstico TV Box — $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
$candidates = Find-Candidates
if (-not $candidates) { Out-Line 'No encontré ningún puerto ADB abierto. Conecta por USB o activa la depuración.'; exit 1 }
$target = $null
Out-Line "`n## Conexiones ADB"
foreach ($c in $candidates) {
    $r = Test-RealAdb $c
    Out-Line "- $c → $r"
    if ($r -like 'FALSO*') { Alerta "${c} es un ADB FALSO (servicio que imita ADB: típico de malware)." }
    if ($r -eq 'REAL' -and -not $target) { $target = $c }
}
if (-not $target) { Out-Line "`nNo hay ADB real disponible. Revisa la guía (USB, puerto OTG, autorización)."; exit 2 }

# ---------------------------------------------------------------- 2. recolectar
adb -s $target push (Join-Path $PSScriptRoot 'diag-remoto.sh') /data/local/tmp/diag-remoto.sh | Out-Null
$raw = adb -s $target shell sh /data/local/tmp/diag-remoto.sh 2>$null
$sec = @{}; $cur = $null
foreach ($l in $raw) {
    if ($l -match '^##(\w+)') { $cur = $matches[1]; $sec[$cur] = New-Object System.Collections.Generic.List[string]; continue }
    if ($cur) { $sec[$cur].Add("$l".TrimEnd()) }
}
$id = @{}; foreach ($l in $sec['ID']) { if ($l -match '^([^=]+)=(.*)$') { $id[$matches[1]] = $matches[2] } }

# ---------------------------------------------------------------- 3. identidad real vs anunciada
$sdkNames = @{ 21 = '5.0'; 22 = '5.1'; 23 = '6'; 24 = '7.0'; 25 = '7.1'; 26 = '8.0'; 27 = '8.1'; 28 = '9'; 29 = '10'; 30 = '11'; 31 = '12'; 32 = '12L'; 33 = '13'; 34 = '14'; 35 = '15' }
$sdk = [int]("0" + $id['ro.build.version.sdk'])
$real = $sdkNames[$sdk]
Out-Line "`n## Identidad"
Out-Line "- Fabricante/marca: $($id['ro.product.manufacturer']) / $($id['ro.product.brand']) · modelo: $($id['ro.product.model'])"
Out-Line "- Chip: $($id['ro.product.chipset']) $($id['ro.build.product']) $($id['ro.hi.product']) · plataforma: $($id['ro.board.platform']) / $($id['ro.hardware'])"
Out-Line "- Android real: $real (API $sdk) · el firmware dice: $($id['ro.build.version.release'])"
if ($real -and $id['ro.build.version.release'] -and -not $id['ro.build.version.release'].StartsWith($real)) { Alerta "Versión de Android falsificada: dice $($id['ro.build.version.release']) pero la API $sdk es Android $real." }
if ($id['ro.build.description'] -and $real -and $id['ro.build.description'] -notmatch " $real ") { Out-Line "  (ro.build.description: $($id['ro.build.description']))" }
$abis = $id['ro.product.cpu.abilist']
Out-Line "- Procesador: $abis → $(if ($abis -match 'arm64|x86_64') { '64 bits' } else { '32 bits (las apps sólo-arm64 no funcionarán)' })"

$memKb = 0; foreach ($l in $sec['MEM']) { if ($l -match 'MemTotal:\s+(\d+)') { $memKb = [long]$matches[1] } }
$memArg = ($sec['MEM'] | Where-Object { $_ -match '^mem=' }) -join ''
Out-Line "- RAM (kernel): $([math]::Round($memKb/1MB,2)) GB $memArg · el firmware dice: $($id['ro.memory.size'])"
if ($id['ro.memory.size'] -match '^(\d+)G' -and [double]$matches[1] -gt ($memKb/1MB + 0.3)) { Alerta "RAM falsificada: anuncia $($id['ro.memory.size']) y hay $([math]::Round($memKb/1MB,1)) GB." }

$dfTotal = 0; $dfFree = 0
foreach ($l in $sec['DF']) { $f = $l -split '\s+'; if ($f.Count -ge 4) { $dfTotal = [long]$f[1]; $dfFree = [long]$f[3] } }
Out-Line "- Almacenamiento para apps (kernel): $([math]::Round($dfTotal/1MB,2)) GB, libres $([math]::Round($dfFree/1MB,2)) GB · flash anunciada: $($id['ro.flash.size'])"
foreach ($l in $sec['DISKSTATS']) {
    if ($l -match 'Data-Free:\s+(\d+)K\s*/\s*(\d+)K') {
        $fwTotal = [long]$matches[2]
        Out-Line "- Lo que ven las apps: $([math]::Round($fwTotal/1MB,2)) GB totales"
        if ($dfTotal -gt 0 -and [math]::Abs($fwTotal - $dfTotal) / $dfTotal -gt 0.1) { Alerta "El framework FALSIFICA el almacenamiento ($([math]::Round($fwTotal/1MB,1)) GB vs $([math]::Round($dfTotal/1MB,1)) GB reales). Revisa libjavacore.so (docs/03)." }
    }
}
$ramrom = ($sec['RAMROM'] -join '').Trim()
if ($ramrom) { Out-Line "- /private/ramrom.config = '$ramrom' (lo que muestra Ajustes › Almacenamiento)" }
Out-Line "- Pantalla: $(($sec['DISPLAY'] -join ' ').Trim()) · el firmware dice: $($id['vendor.display-size'])"

# ---------------------------------------------------------------- 4. seguridad
Out-Line "`n## Seguridad"
Out-Line "- Firmware: $($id['ro.build.type']) / $($id['ro.build.tags']) · SELinux: $($id['selinux']) · ADB TCP: $($id['service.adb.tcp.port'])"
if (-not $id['ro.adb.secure'] -or $id['ro.adb.secure'] -eq '0') { Alerta "ADB por red SIN autorización (ro.adb.secure vacío): cualquiera en tu red puede controlar la caja." }
if ($id['selinux'] -eq 'Permissive') { Out-Line '  - SELinux en Permissive (protección desactivada de fábrica).' }
if ($id['ro.build.type'] -eq 'userdebug') { Out-Line '  - Firmware userdebug: `adb root` funciona (útil para limpiar).' }
Out-Line "- Pantalla de inicio actual: $(($sec['HOME'] -join '').Trim())"

$pk = @(); $byUid = @{}
foreach ($l in $sec['PKGS']) {
    if ($l -match '^package:(.+)=(\S+)\s+installer=(\S+)\s+uid:(\d+)') {
        $o = [pscustomobject]@{ path = $matches[1]; name = $matches[2]; installer = $matches[3]; uid = $matches[4] }
        $pk += $o; $byUid[$o.uid] = (($byUid[$o.uid], $o.name) -ne $null) -join ' '
    }
}
$okInstallers = 'com.android.vending', 'com.android.packageinstaller', 'com.google.android.packageinstaller', 'null'
Out-Line "`n## Apps ($($pk.Count) paquetes)"
foreach ($p in $pk) {
    $why = @()
    $isData = $p.path -like '/data/*'
    if ($isData -and $okInstallers -notcontains $p.installer) { $why += "instalada en silencio por $($p.installer)" }
    if ($isData -and $p.name -match '^com\.(android|google)\.' -and $okInstallers -notcontains $p.installer) { $why += 'usa un nombre de sistema' }
    elseif ($isData -and $p.name -match '^com\.android\.' -and $p.installer -ne 'com.android.vending') { Out-Line "  - Revisar: $($p.name) usa un nombre com.android.* (instalada a mano)" }
    if ($isData -and $p.uid -eq '1000') { $why += 'app descargada con permisos de SISTEMA (uid 1000)' }
    if ($sec['INSTALLERS'] -contains $p.name) { $why += 'puede instalar apps sin preguntar' }
    if ($why) { Alerta "$($p.name) [$($p.path)]: $($why -join '; ')" }
}
Out-Line "`nApps del sistema que no son AOSP/Google (revisar a mano):"
$pk | Where-Object { $_.path -like '/system/*' -and $_.name -notmatch '^(android|com\.android\.|com\.google\.)' } | ForEach-Object { Out-Line "  - $($_.name)  ($($_.path))" }

# ---------------------------------------------------------------- 5. red
Out-Line "`n## Red"
$adbPort = $target.Split(':')[-1]
foreach ($l in $sec['LISTEN']) {
    $f = $l -split '\s+'; if ($f.Count -lt 2) { continue }
    $port = [Convert]::ToInt32(($f[0] -split ':')[-1], 16); $uid = $f[1]
    if ("$port" -eq $adbPort) { continue }
    $who = if ($uid -eq '0') { 'ROOT' } else { $byUid[$uid] }
    Out-Line "- Escucha puerto $port (uid $uid $who)"
    if ($uid -eq '0' -and $port -ne 5037) { Alerta "Un servicio ROOT escucha en el puerto ${port}: posible puerta trasera (revisar con adb root + netstat -tlnp)." }
}
foreach ($l in $sec['CONN']) {
    if ($l -match '^\s*(\d+)\s+(\d+)') {
        $n = [int]$matches[1]; $uid = $matches[2]
        Out-Line "- $n conexiones salientes: uid $uid $($byUid[$uid])"
        if ($n -ge 10 -and $uid -ne '2000') { Alerta "uid $uid ($($byUid[$uid])) tiene $n conexiones simultáneas: patrón de PROXY RESIDENCIAL." }
    }
}

Out-Line "`n## Preinstalación tras formateo"
$sec['PREINSTALL'] | ForEach-Object { Out-Line "    $_" }

Out-Line "`n## Resumen: $($alertas.Count) alertas"
$alertas | ForEach-Object { Out-Line "- $_" }
Out-Line "`nInforme: $report"
