# Prepara una TV Box de principio a fin siguiendo docs/GUIA-NUEVA-TVBOX.md:
# requisitos > diagnóstico > respaldo > limpieza > falsificaciones > fábrica > formateo > verificación.
#
# - Firmware conocido (scripts/perfiles/*.psd1): automático de principio a fin. Las protecciones no dependen de
#   confirmar: sólo actúa si el firmware coincide con un perfil y el respaldo se verificó partición por partición.
# - Firmware nuevo: hace el diagnóstico y el respaldo (sólo lectura) y se detiene. Sigue la guía a mano.
# - Cada caja tiene un ID sacado de su hardware (p. ej. RK3228A-1a2b3c4d). Todo queda en logs\cajas\<ID>\:
#   registro completo, diagnóstico, verificación, estado (para retomar donde se quedó) y una ficha de la caja.
# - Si la verificación final sale sin fallas, borra el respaldo de esa caja (salvo con -ConservarRespaldo).
# - Avisa con sonido y voz al terminar, al detenerse o si el firmware es nuevo.
#
# Uso:  .\preparar.ps1 -Ip 192.168.X.Y          (o sin -Ip: busca la caja en la red)
#       -Desde fabrica      repetir desde una etapa      -Simular   mostrar lo que haría, sin cambiar nada
#       -Apks carpeta       apps para la caja             -SinFormatear / -SinYoutube   saltar esas partes
#       -Preguntar          pedir confirmación antes de cada bloque que cambia la caja
#       -ConservarRespaldo  no borrar el respaldo al terminar
param(
    [string] $Ip = '',
    [string] $Serial = '',
    [string] $Apks = '',
    [string] $Idioma = 'es-MX',
    [string] $ZonaHoraria = 'America/Mexico_City',
    [ValidateSet('', 'diagnostico', 'respaldo', 'limpieza', 'falsificaciones', 'fabrica', 'formateo', 'verificacion')]
    [string] $Desde = '',
    [switch] $Simular,
    [switch] $SinFormatear,
    [switch] $SinYoutube,
    [switch] $Preguntar,
    [switch] $ConservarRespaldo
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:Log = Join-Path $logs "preparar-$stamp.log"
Start-Transcript -Path $script:Log | Out-Null
$Etapas = 'diagnostico', 'respaldo', 'limpieza', 'falsificaciones', 'fabrica', 'formateo', 'verificacion'
$script:S = $Serial
$script:Perfil = $null
$script:Caja = $null
$script:Estado = [ordered]@{ Id = ''; Serie = ''; Ip = $Ip; Perfil = ''; Respaldo = ''; Hechas = @(); Notas = @() }

# ------------------------------------------------------------------ utilidades
function Titulo($m) { Write-Host "`n==== $m ====" -ForegroundColor Cyan }
function Info($m) { Write-Host "  $m" }
function Aviso($m) { Write-Host "  AVISO: $m" -ForegroundColor Yellow; $script:Estado.Notas += "$(Get-Date -Format 'yyyy-MM-dd HH:mm') $m" }
function Guardar {
    if (-not $script:Caja -or $Simular) { return }
    $script:Estado | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $script:Caja 'estado.json')
}
# Sonido y voz para cuando hace falta la persona (si el equipo no tiene voz en español, sólo suena).
function Avisar($texto) {
    try {
        [System.Media.SystemSounds]::Exclamation.Play(); Start-Sleep -Milliseconds 400
        foreach ($f in 880, 1175, 1568) { [console]::Beep($f, 200) }
        Add-Type -AssemblyName System.Speech
        $voz = New-Object System.Speech.Synthesis.SpeechSynthesizer
        $es = $voz.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'es*' } | Select-Object -First 1
        if ($es) { $voz.SelectVoice($es.VoiceInfo.Name); $voz.Volume = 100; $voz.Speak($texto) }
    } catch { }
}
function Fallar($m) {
    Write-Host "`nDETENIDO: $m" -ForegroundColor Red
    Write-Host "Registro completo: $script:Log"
    if ($script:Estado) { $script:Estado.Notas += "$(Get-Date -Format 'yyyy-MM-dd HH:mm') DETENIDO: $m" }
    Guardar
    if (-not $Simular) { Avisar 'Atención: la preparación de la TV Box se detuvo. Revisa la ventana.' }
    Stop-Transcript | Out-Null; exit 1
}
function Sh($c) { (adb -s $script:S shell $c 2>&1 | Out-String).Trim() }
# Lo que cambia la caja pasa por aquí: con -Simular sólo se muestra.
function Cambio($desc, [scriptblock] $accion) {
    if ($Simular) { Write-Host "  [simulado] $desc" -ForegroundColor DarkYellow; return }
    Write-Host "  - $desc"; & $accion
}
function Confirmar($texto) {
    Write-Host "`n$texto" -ForegroundColor Yellow
    if ($Simular) { Write-Host '  [simulado] aquí se pediría confirmar' -ForegroundColor DarkYellow; return }
    if (-not $Preguntar) { Write-Host '  (automático: sigue sin preguntar; usa -Preguntar para confirmar cada bloque)' -ForegroundColor DarkGray; return }
    Avisar 'La TV Box pide confirmar. Revisa la ventana.'
    if ((Read-Host '¿Continuar? (s/n)') -ne 's') { Fallar 'Cancelado. Vuelve a ejecutar el .bat para seguir desde esta etapa.' }
}
function Root {
    if ((Sh 'id -u') -eq '0') { return }
    adb -s $script:S root 2>&1 | Out-Null
    # adbd se reinicia como root: la conexión por red tarda unos segundos en volver.
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep 3
        if ($script:S -match ':') {
            if ((adb -s $script:S get-state 2>&1 | Out-String).Trim() -ne 'device') { adb disconnect $script:S 2>&1 | Out-Null }
            adb connect $script:S 2>&1 | Out-Null; Start-Sleep 1
        }
        if ((Sh 'id -u') -eq '0') { return }
    }
    Fallar "adb root no funciona en este firmware (id -u = '$(Sh 'id -u')'): no se puede modificar /system (ver guía)."
}
function Esperar-Arranque([int] $minutos = 10, [switch] $ConApps) {
    $fin = (Get-Date).AddMinutes($minutos); Start-Sleep 25
    $offline = 0; $pedido = $false
    while ((Get-Date) -lt $fin) {
        if ($script:S -match ':') {
            # Tras un formateo, adbd puede quedar trabado: el puerto acepta pero no contesta y adb lo ve "offline".
            if ((adb -s $script:S get-state 2>&1 | Out-String) -match 'offline') { adb disconnect $script:S 2>&1 | Out-Null; $offline++ } else { $offline = 0 }
            adb connect $script:S 2>&1 | Out-Null
            if ($offline -ge 20 -and -not $pedido) {
                $pedido = $true
                Aviso 'ADB de la caja trabado (offline): en la TV apaga y vuelve a encender Ajustes › Opciones de desarrollador › Depuración USB.'
                Avisar 'El ADB de la TV Box se trabó. Apaga y enciende la depuración USB en la TV.'
            }
        }
        if ((Sh 'getprop sys.boot_completed') -eq '1' -and (-not $ConApps -or (Sh 'ls /data/local/symbol_thirdpart_apks_installed 2>/dev/null'))) {
            Start-Sleep 30; Root; return
        }
        Start-Sleep 6
    }
    Fallar "La caja no volvió en $minutos minutos. Si está por Wi-Fi, reconéctala desde la TV y vuelve a ejecutar."
}
function Reiniciar { Cambio 'Reiniciar la caja' { Sh 'sync' | Out-Null; adb -s $script:S reboot 2>&1 | Out-Null; Esperar-Arranque } }
function Paquetes3 { @(adb -s $script:S shell pm list packages -3 2>$null | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }) }
function Todos { @(adb -s $script:S shell pm list packages 2>$null | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }) }
function Remount { adb -s $script:S remount 2>&1 | Out-Null }
# Nombres de paquete de los APK de una carpeta (sueltos o divididos en subcarpetas con base.apk).
function Paquetes-De-Apks($dir) {
    if (-not $dir -or -not (Test-Path $dir)) { return @() }
    Get-ChildItem $dir | ForEach-Object {
        $f = if ($_.PSIsContainer) { Join-Path $_.FullName 'base.apk' } else { $_.FullName }
        if ((Test-Path $f) -and $f -like '*.apk') { & (Join-Path $script:BT 'aapt2.exe') dump packagename $f 2>$null | Select-Object -First 1 }
    } | Where-Object { $_ }
}
function Ejecutar-Py([string[]] $argumentos) { & python @argumentos | ForEach-Object { Write-Host "    $_" }; return $LASTEXITCODE }

# ID estable de la caja: chip + hash del número de serie del procesador, de Android o de la MAC.
function Obtener-Id {
    $chip = (Sh "grep -m1 -i '^Hardware' /proc/cpuinfo") -replace '^.*:\s*', ''
    $chip = ($chip -split '\s+')[-1]
    if (-not $chip) { $chip = Sh 'getprop ro.board.platform' }
    $chip = ($chip -replace '[^A-Za-z0-9]', '').ToUpper(); if (-not $chip) { $chip = 'TVBOX' }
    $malos = '^(|0+|unknown|0123456789abcdef|1234567890abcdef)$'
    $fuentes = @(
        @('cpu', ((Sh "grep -m1 -i '^Serial' /proc/cpuinfo") -replace '^.*:\s*', '')),
        @('android', (Sh 'getprop ro.serialno')),
        @('wlan', (Sh 'cat /sys/class/net/wlan0/address 2>/dev/null')),
        @('eth', (Sh 'cat /sys/class/net/eth0/address 2>/dev/null')))
    foreach ($f in $fuentes) {
        $v = "$($f[1])".Trim().ToLower()
        if ($v -notmatch $malos -and $v -notmatch 'no such|not found') {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            $h = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("$($f[0]):$v")) | ForEach-Object { $_.ToString('x2') }) -join ''
            return @{ Id = "$chip-$($h.Substring(0, 8))"; Fuente = $f[0] }
        }
    }
    return @{ Id = "$chip-sinserie-$stamp"; Fuente = 'ninguna (el ID no será estable)' }
}

# Carpeta de la caja: mueve aquí el registro y carga el estado guardado.
function Abrir-Caja {
    $r = Obtener-Id
    $script:Caja = Join-Path $logs "cajas\$($r.Id)"
    New-Item -ItemType Directory -Force $script:Caja | Out-Null
    $ef = Join-Path $script:Caja 'estado.json'
    if (Test-Path $ef) {
        $prev = Get-Content -Raw $ef | ConvertFrom-Json
        foreach ($k in 'Respaldo', 'Perfil') { if ($prev.$k) { $script:Estado[$k] = $prev.$k } }
        $script:Estado.Hechas = @($prev.Hechas | Where-Object { $_ })
        $script:Estado.Notas = @($prev.Notas | Where-Object { $_ })
        Info "Caja conocida: $($r.Id). Etapas ya hechas: $(if ($script:Estado.Hechas) { $script:Estado.Hechas -join ', ' } else { 'ninguna' })"
    } else { Info "Caja nueva: $($r.Id) (ID sacado de: $($r.Fuente))" }
    $script:Estado.Id = $r.Id; $script:Estado.Serie = $script:S
    $nuevo = Join-Path $script:Caja (Split-Path $script:Log -Leaf)
    if ($script:Log -ne $nuevo) {
        Stop-Transcript | Out-Null
        Move-Item $script:Log $nuevo -Force; $script:Log = $nuevo
        Start-Transcript -Path $script:Log -Append | Out-Null
    }
    $p = @{}; foreach ($k in 'ro.product.model', 'ro.board.platform', 'ro.build.version.sdk', 'ro.build.display.id', 'ro.build.fingerprint') { $p[$k] = Sh "getprop $k" }
    @("# Caja $($r.Id)", '', "- Chip: $((Sh "grep -m1 -i '^Hardware' /proc/cpuinfo") -replace '^.*:\s*', '') ($($p['ro.board.platform']))",
      "- Modelo según el firmware: $($p['ro.product.model'])", "- API: $($p['ro.build.version.sdk'])",
      "- Build: $($p['ro.build.display.id'])", "- Huella: $($p['ro.build.fingerprint'])", "- Última conexión: $script:S, $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
      "- ID sacado de: $($r.Fuente)") | Set-Content -Encoding UTF8 (Join-Path $script:Caja 'caja.md')
    Guardar
}
function Actualizar-Indice($etapa) {
    if ($Simular -or -not $script:Caja) { return }
    $f = Join-Path $logs 'cajas\indice.csv'
    $filas = @(); if (Test-Path $f) { $filas = @(Import-Csv $f | Where-Object { $_.Id -ne $script:Estado.Id }) }
    $primera = (Get-Item $script:Caja).CreationTime.ToString('yyyy-MM-dd HH:mm')
    $filas += [pscustomobject]@{ Id = $script:Estado.Id; Primera = $primera; Ultima = (Get-Date -Format 'yyyy-MM-dd HH:mm');
        Conexion = $script:S; Perfil = $script:Estado.Perfil; UltimaEtapa = $etapa }
    $filas | Export-Csv -NoTypeInformation -Encoding UTF8 $f
}

function Buscar-Perfil {
    foreach ($f in Get-ChildItem (Join-Path $PSScriptRoot 'perfiles') -Filter *.psd1 -ErrorAction SilentlyContinue) {
        $p = Import-PowerShellDataFile $f.FullName
        $ok = $true; foreach ($k in $p.Detectar.Keys) { if ((Sh "getprop $k") -ne $p.Detectar[$k]) { $ok = $false } }
        if ($ok) { $p.Archivo = $f.BaseName; return $p }
    }
    return $null
}

# Conexión rápida a una caja ya diagnosticada (para retomar sin repetir el diagnóstico).
function Conectar-Rapido {
    if ($script:S) { $cands = @($script:S) } elseif ($Ip) { $cands = @("${Ip}:3222", "${Ip}:5555", "${Ip}:5556") } else { return $false }
    foreach ($c in $cands) {
        if ($c -match ':') { adb connect $c 2>&1 | Out-Null; Start-Sleep 2 }
        if ((adb -s $c get-state 2>&1 | Out-String).Trim() -eq 'device' -and (adb -s $c shell echo AURA_OK 2>&1 | Out-String).Trim() -eq 'AURA_OK') {
            $script:S = $c; return $true
        }
    }
    return $false
}

# ------------------------------------------------------------------ etapas
# Apps de fábrica: -Apks; si no, la carpeta del perfil dentro de AURA_APKS (el .bat suelto junto a las apps, p. ej. en
# OneDrive); si no, la del proyecto (apks\...).
function Carpeta-Apps($Perf) {
    if ($Apks) { return $Apks }
    if ($env:AURA_APKS) { return Join-Path $env:AURA_APKS (Split-Path $Perf.Apks -Leaf) }
    return Join-Path $root $Perf.Apks
}

# Descarga y descomprime un .zip en una carpeta temporal nueva; devuelve esa carpeta.
function Bajar-Zip($url, $nombre) {
    Info "Bajando $nombre..."
    $tmp = Join-Path $env:TEMP "aura-$nombre"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue; New-Item -ItemType Directory $tmp | Out-Null
    $ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
    try { Invoke-WebRequest -UseBasicParsing $url -OutFile (Join-Path $tmp 'd.zip'); Expand-Archive (Join-Path $tmp 'd.zip') $tmp }
    catch { Fallar "No pude bajar $nombre ($url): $_" }
    return $tmp
}
function Python-Real { try { return ((& python -c 'print(3)' 2>$null | Out-String).Trim() -eq '3') } catch { return $false } }
function Version-Apk($f) {
    $l = & (Join-Path $script:BT 'aapt2.exe') dump badging $f 2>$null | Select-Object -First 1
    if ("$l" -match "versionCode='(\d+)'") { return [int]$matches[1] }; return 0
}
function Nombre-Apk($f) {
    $l = & (Join-Path $script:BT 'aapt2.exe') dump badging $f 2>$null | Select-Object -First 1
    if ("$l" -match "versionName='([^']*)'") { return $matches[1] }; return '?'
}
function Apksigner-Funciona { cmd /c "`"$(Join-Path $script:BT 'apksigner.bat')`" --version >nul 2>nul"; return ($LASTEXITCODE -eq 0) }

# Lo que falte en la PC se instala solo, sin Android Studio: adb y build-tools de Google, Java portátil de Adoptium
# (sólo si apksigner no arranca) y Python de python.org.
function Requisitos {
    Titulo 'Requisitos de la PC'
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $pt = Join-Path $sdk 'platform-tools'
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        if (-not (Test-Path (Join-Path $pt 'adb.exe'))) {
            $d = Bajar-Zip 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' 'platform-tools'
            New-Item -ItemType Directory -Force $sdk | Out-Null
            Move-Item (Join-Path $d 'platform-tools') $pt; Remove-Item -Recurse -Force $d
        }
        $env:Path = "$pt;$env:Path"
    }
    $btDir = Join-Path $sdk 'build-tools'
    $buscarBt = { Get-ChildItem $btDir -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'aapt2.exe')) } |
        Sort-Object { [version]$_.Name } | Select-Object -Last 1 }
    $bt = & $buscarBt
    if (-not $bt) {
        $d = Bajar-Zip 'https://dl.google.com/android/repository/build-tools_r34-windows.zip' 'build-tools'
        $dentro = Get-ChildItem $d -Recurse -Filter aapt2.exe | Select-Object -First 1
        if (-not $dentro) { Fallar 'El zip de build-tools no trae aapt2.exe.' }
        New-Item -ItemType Directory -Force $btDir | Out-Null
        Move-Item $dentro.DirectoryName (Join-Path $btDir '34.0.0'); Remove-Item -Recurse -Force $d
        $bt = & $buscarBt
    }
    $script:BT = $bt.FullName; Info "build-tools: $($bt.Name)"
    $jre = Join-Path $sdk 'aura-jre'
    if (Test-Path (Join-Path $jre 'bin\java.exe')) { $env:JAVA_HOME = $jre; $env:Path = "$jre\bin;$env:Path" }
    if (-not (Apksigner-Funciona)) {
        $d = Bajar-Zip 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse' 'java'
        $dentro = Get-ChildItem $d -Recurse -Filter java.exe | Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
        if (-not $dentro) { Fallar 'El zip de Java no trae bin\java.exe.' }
        Remove-Item -Recurse -Force $jre -ErrorAction SilentlyContinue
        Move-Item $dentro.Directory.Parent.FullName $jre; Remove-Item -Recurse -Force $d
        $env:JAVA_HOME = $jre; $env:Path = "$jre\bin;$env:Path"
        if (-not (Apksigner-Funciona)) { Fallar 'apksigner no arranca ni con el Java portátil.' }
    }
    $py = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312'
    if (-not (Python-Real) -and (Test-Path (Join-Path $py 'python.exe'))) { $env:Path = "$py;$py\Scripts;$env:Path" }
    if (-not (Python-Real)) {
        # El "python" de WindowsApps es un acceso a la Tienda, no Python: por eso se prueba ejecutándolo.
        Info 'Instalando Python 3.12 (sólo para este usuario)...'
        $exe = Join-Path $env:TEMP 'aura-python.exe'
        $ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
        try { Invoke-WebRequest -UseBasicParsing 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe' -OutFile $exe }
        catch { Fallar "No pude bajar Python: $_" }
        Start-Process $exe -Wait -ArgumentList '/quiet', 'InstallAllUsers=0', 'PrependPath=1', 'Include_test=0', 'Include_launcher=0'
        Remove-Item -Force $exe -ErrorAction SilentlyContinue
        $env:Path = "$py;$py\Scripts;$env:Path"
        if (-not (Python-Real)) { Fallar 'No pude instalar Python 3 (instálalo de python.org marcando "Add to PATH").' }
    }
    python -c "import capstone, elftools" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Info 'Instalando capstone y pyelftools para Python...'
        python -m pip install --user -q --disable-pip-version-check capstone pyelftools
        python -c "import capstone, elftools" 2>$null; if ($LASTEXITCODE -ne 0) { Fallar 'No pude instalar capstone y pyelftools (pip install --user capstone pyelftools).' }
    }
    # Aura va a /system: usa la versión publicada más nueva, salvo que build\Aura.apk sea más nuevo (recién compilado).
    $script:Aura = Join-Path $root 'build\Aura.apk'
    New-Item -ItemType Directory -Force (Split-Path $script:Aura) | Out-Null
    $ultima = Join-Path $env:TEMP 'Aura-ultima.apk'
    try { Invoke-WebRequest -UseBasicParsing 'https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/latest/download/Aura.apk' -OutFile $ultima }
    catch { $ultima = $null; if (-not (Test-Path $script:Aura)) { Fallar "No pude bajar Aura de GitHub: $_" } }
    if (-not $ultima) { $origen = 'copia guardada en esta PC: no pude revisar GitHub' }
    elseif (-not (Test-Path $script:Aura) -or (Version-Apk $ultima) -ge (Version-Apk $script:Aura)) {
        Copy-Item $ultima $script:Aura -Force
        $origen = 'la última publicada en GitHub'
    } else { $origen = "build\Aura.apk compilado, más nuevo que la publicada $(Nombre-Apk $ultima)" }
    if ($ultima) { Remove-Item $ultima -ErrorAction SilentlyContinue }
    # Sólo se acepta el Aura firmado con la llave del proyecto.
    # Por cmd, para descartar los avisos de Java de apksigner sin que PowerShell los muestre como error.
    $cert = (cmd /c "`"$(Join-Path $script:BT 'apksigner.bat')`" verify --print-certs `"$script:Aura`" 2>nul" | Select-String 'SHA-256 digest: (\w+)' | Select-Object -First 1).Matches.Groups[1].Value
    if ($cert -ne '1bf3f8f944dac8496f0f13be7a40304ef6622c51e778f4d9e160b99ebc12ff21') {
        Fallar "build\Aura.apk no tiene la firma de Aura ($cert). Bórralo y vuelve a ejecutar."
    }
    Info 'adb, Python y Aura firmado: listos.'
    # Lo primero que se ve al abrir el .bat: qué Aura va a quedar en /system de la caja.
    Write-Host "  Aura que se instalará: $(Nombre-Apk $script:Aura) ($origen)" -ForegroundColor Green
}

function Etapa-diagnostico {
    $a = @{}; if ($Serial) { $a.Serial = $Serial } elseif ($Ip) { $a.Ip = $Ip }
    $salida = @(& (Join-Path $PSScriptRoot 'diagnostico.ps1') @a)
    $salida | ForEach-Object { Write-Host $_ }
    $real = $salida | ForEach-Object { if ("$_" -match '^- (\S+) → REAL') { $matches[1] } } | Select-Object -First 1
    if (-not $real) { Fallar 'No encontré un ADB real. Activa la depuración USB y revisa la guía ("Si no aparece ningún ADB").' }
    $script:S = $real
    $inf = $salida | ForEach-Object { if ("$_".Trim() -match '^Informe: (.+)$') { $matches[1] } } | Select-Object -First 1
    Root; Abrir-Caja
    if ($inf -and (Test-Path $inf)) { Copy-Item $inf $script:Caja }
}

function Etapa-respaldo {
    if ($script:Perfil) { foreach ($p in $script:Perfil.Detener) { Sh "am force-stop $p" | Out-Null } }
    if ($Simular) { Write-Host '  [simulado] respaldar.ps1 y comprobación de cada partición' -ForegroundColor DarkYellow; return }
    try { $out = @(& (Join-Path $PSScriptRoot 'respaldar.ps1') -Serial $script:S) } catch { Fallar "El respaldo falló: $_" }
    $out | ForEach-Object { Write-Host $_ }
    $dir = $out | ForEach-Object { if ("$_" -match '^Respaldo listo en (.+?) \(') { $matches[1] } } | Select-Object -First 1
    if (-not $dir -or -not (Test-Path (Join-Path $dir 'SHA256.txt'))) { Fallar 'No encontré el respaldo terminado.' }
    $byname = Sh 'for d in /dev/block/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do [ -d $d ] && echo $d && break; done'
    $malos = 0
    foreach ($l in Get-Content (Join-Path $dir 'SHA256.txt')) {
        $h, $n = $l -split '\s+', 2; $part = $n.Trim() -replace '\.img$', ''
        $r = ((Sh "(busybox sha256sum $byname/$part || sha256sum $byname/$part) 2>/dev/null") -split '\s+')[0]
        if ($r -eq $h.ToLower()) { Info "$part OK" } else { Info "$part DISTINTO"; $malos++ }
    }
    if ($malos) { Fallar "$malos particiones no coinciden con la caja: repite el respaldo antes de cambiar nada." }
    foreach ($f in '/init.rc', '/init.rk30board.rc', '/init.box.rc', '/system/bin/preinstall.sh', '/system/etc/audio_policy.conf') {
        adb -s $script:S pull $f (Join-Path $dir (($f.Trim('/')) -replace '/', '-')) 2>&1 | Out-Null
    }
    $script:Estado.Respaldo = $dir; Info "Respaldo verificado: $dir"
}

function Etapa-limpieza {
    $Perf = $script:Perfil
    foreach ($k in $Perf.Comprobar.Keys) { if ((Sh $k) -ne $Perf.Comprobar[$k]) { Fallar "No se cumple '$k' = '$($Perf.Comprobar[$k])' (dio '$(Sh $k)'): revisa antes de borrar." } }
    $quitar = @(Paquetes3 | Where-Object { $_ -ne 'com.jajua.aura' })
    $sist = @($Perf.BorrarSistema | Where-Object { (Sh "ls -d $_ 2>/dev/null") })
    Confirmar ("Se va a limpiar la caja $($script:Estado.Id):`n" +
        "  - Aura temporal como inicio`n  - Deshabilitar el malware conocido`n" +
        "  - Desinstalar de /data: $(if ($quitar) { $quitar -join ', ' } else { 'nada' })`n" +
        "  - Reemplazar preinstall.sh del firmware y borrar la cola de instalación`n" +
        "  - Borrar de /system: $($sist.Count) elementos (malware, apps de prueba y del fabricante)`n" +
        "  - Reiniciar dos veces`n  Respaldo: $($script:Estado.Respaldo)")
    $copias = if ($script:Estado.Respaldo) { Join-Path $script:Estado.Respaldo 'data-apps' } else { '(sin respaldo)' }
    # Tus apps (las de la carpeta de fabrica.ps1) se reinstalan al final: no son sospechosas.
    $dirApps = Carpeta-Apps $Perf
    $tuyas = @(Paquetes-De-Apks $dirApps)
    Cambio 'Instalar Aura temporal y ponerla de inicio' {
        adb -s $script:S install -r $script:Aura 2>&1 | Out-Null
        Sh 'cmd package set-home-activity com.jajua.aura/.HomeActivity' | Out-Null
    }
    $todos = Todos
    foreach ($p in $Perf.Deshabilitar) { if ($todos -contains $p) { Cambio "Deshabilitar $p" { Sh "am force-stop $p; pm disable $p" | Out-Null } } }
    foreach ($p in $quitar) {
        $sospechosa = $Perf.Conocidas -notcontains $p -and $tuyas -notcontains $p
        if ($sospechosa -and -not $Simular -and $script:Estado.Respaldo) {
            New-Item -ItemType Directory -Force $copias | Out-Null
            $ruta = (Sh "pm path $p") -replace '^package:', '' -split "`n" | Select-Object -First 1
            if ($ruta) { adb -s $script:S pull $ruta.Trim() (Join-Path $copias "$p.apk") 2>&1 | Out-Null }
        }
        if ($sospechosa -and $Perf.Malware -notcontains $p) { Aviso "App desconocida en /data: $p (copia en $copias). Revísala." }
        Cambio "Desinstalar $p" { Sh "pm uninstall $p" | Out-Null }
    }
    if ($Perf.ReemplazarPreinstall) {
        Cambio 'Reemplazar preinstall.sh del firmware (conserva su etiqueta SELinux)' {
            adb -s $script:S push (Join-Path $PSScriptRoot 'preinstall.sh') /data/local/tmp/preinstall.sh 2>&1 | Out-Null; Remount
            Sh 'cat /data/local/tmp/preinstall.sh > /system/bin/preinstall.sh; touch /data/local/symbol_thirdpart_apks_installed; rm /data/local/tmp/preinstall.sh; sync' | Out-Null
        }
    }
    Cambio 'Borrar restos y la cola de instalación' { Sh ("rm -rf " + ($Perf.BorrarDatos -join ' ')) | Out-Null }
    Reiniciar
    $vuelto = if ($Simular) { @() } else { @(Paquetes3 | Where-Object { $_ -ne 'com.jajua.aura' }) }
    foreach ($p in $vuelto) { Aviso "Volvió o apareció $p tras reiniciar: lo desinstalo (revisa de dónde viene)."; Cambio "Desinstalar $p" { Sh "pm uninstall $p" | Out-Null } }
    Cambio "Borrar de /system $($sist.Count) elementos" { Remount; Sh ("rm -rf " + ($sist -join ' ') + '; sync') | Out-Null }
    Reiniciar
    if (-not $Simular) {
        $resto = @(Todos | Where-Object { $Perf.Malware -contains $_ })
        if ($resto) { Fallar "Sigue instalado: $($resto -join ', ')" }
        $extra = @((Sh 'ls /system/app /system/priv-app') -split '\s+' | Where-Object { $_ -and $_ -notmatch ':$' -and $Perf.SistemaEsperado -notcontains $_ })
        if ($extra) { Aviso "En /system quedan carpetas que el perfil no conoce: $($extra -join ', ')" }
        $escucha = Sh 'netstat -tlnp 2>/dev/null | grep LISTEN | grep -v adbd'
        if ($escucha) { Aviso "Algo más escucha en la red: $escucha" } else { Info 'Sólo ADB escucha en la red.' }
    }
}

function Instalar-Lib($local, $remoto) {
    adb -s $script:S push $local /data/local/tmp/aura-lib.new 2>&1 | Out-Null; Remount
    Sh ("cp /data/local/tmp/aura-lib.new $remoto.new && chown root:root $remoto.new && chmod 644 $remoto.new && " +
        "chcon u:object_r:system_file:s0 $remoto.new && mv $remoto.new $remoto; rm -f /data/local/tmp/aura-lib.new; sync") | Out-Null
}
function Almacenamiento-Real {
    $df = [long](((Sh 'df -k /data | tail -n 1') -split '\s+')[1])
    if ((Sh 'dumpsys diskstats | grep Data-Free') -match '/\s*(\d+)K') { $fw = [long]$matches[1] } else { $fw = 0 }
    return ($df -gt 0 -and [math]::Abs($fw - $df) / $df -lt 0.03)
}

function Etapa-falsificaciones {
    $Perf = $script:Perfil
    $tmp = Join-Path $env:TEMP 'aura-preparar'; New-Item -ItemType Directory -Force $tmp | Out-Null
    if ($Perf.Javacore) {
        if (Almacenamiento-Real) { Info 'libjavacore: el almacenamiento ya es real.' }
        else {
            adb -s $script:S pull /system/lib/libjavacore.so (Join-Path $tmp 'libjavacore.so') 2>&1 | Out-Null
            if ((Ejecutar-Py @((Join-Path $PSScriptRoot 'patch_javacore.py'), (Join-Path $tmp 'libjavacore.so'), (Join-Path $tmp 'libjavacore.new'))) -ne 0) {
                Fallar 'patch_javacore.py no reconoce este libjavacore.so.'
            }
            Cambio 'Instalar libjavacore.so parcheado' { Instalar-Lib (Join-Path $tmp 'libjavacore.new') '/system/lib/libjavacore.so' }
        }
    }
    foreach ($k in $Perf.BuildProp.Keys) {
        $v = $Perf.BuildProp[$k]
        if ((Sh "grep '^$k=' /system/build.prop") -ne "$k=$v") { Cambio "build.prop: $k=$v" { Remount; Sh "sed -i 's#^$k=.*#$k=$v#' /system/build.prop; sync" | Out-Null } }
    }
    if ($Perf.Ajustes) {
        $script:Bind = Join-Path $root "apks\bind-$($Perf.Archivo)"
        $nombres = 'priv-app@Settings@Settings.apk', 'priv-app@TvSettings@TvSettings.apk'
        if (@($nombres | Where-Object { Test-Path (Join-Path $script:Bind $_) }).Count -eq 2) { Info "Ajustes parcheados ya generados: $script:Bind" }
        elseif ($Simular) { Write-Host "  [simulado] generar Ajustes parcheados en $script:Bind" -ForegroundColor DarkYellow }
        else {
            if ([int]("0" + (Sh 'grep -c priv-app/.*Settings /proc/mounts'))) { Fallar 'Ajustes ya montados: genera los parcheados desde el respaldo o quita /system/etc/aura/bind y reinicia.' }
            New-Item -ItemType Directory -Force $script:Bind | Out-Null
            foreach ($app in 'Settings', 'TvSettings') {
                adb -s $script:S pull "/system/priv-app/$app/$app.apk" (Join-Path $tmp "$app.apk") 2>&1 | Out-Null
                if ((Ejecutar-Py @((Join-Path $PSScriptRoot 'patch_settings.py'), (Join-Path $tmp "$app.apk"), (Join-Path $tmp "$app.p.apk"))) -ne 0) { Fallar "patch_settings.py no reconoce $app.apk." }
                & (Join-Path $script:BT 'zipalign.exe') -f -p 4 (Join-Path $tmp "$app.p.apk") (Join-Path $script:Bind "priv-app@$app@$app.apk")
            }
            Info "Ajustes parcheados en $script:Bind"
        }
    }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

function Etapa-fabrica {
    $Perf = $script:Perfil
    $script:Apks = Carpeta-Apps $Perf
    if (-not (Get-ChildItem $script:Apks -Recurse -Filter *.apk -ErrorAction SilentlyContinue)) { Fallar "No hay APK en $script:Apks (ver guía, paso 1)." }
    if ((Sh 'pm path com.jajua.aura') -match '/data/app') { Cambio 'Quitar el Aura temporal (queda la de sistema)' { Sh 'pm uninstall com.jajua.aura' | Out-Null } }
    $a = @{ Serial = $script:S; Apks = $script:Apks; Idioma = $Idioma; ZonaHoraria = $ZonaHoraria; SinConfirmar = $true }
    if ($Perf.Overscan) { $a.Overscan = $Perf.Overscan }
    if ($Perf.Ajustes) { $a.Bind = Join-Path $root "apks\bind-$($Perf.Archivo)" }
    if ($Perf.ParcheAudio) { $a.ParcheAudio = $true }
    Confirmar "fabrica.ps1 va a escribir en /system: Aura, las apps de $script:Apks (borra /system/preinstall), animación, ajustes y parches."
    Cambio 'fabrica.ps1' {
        try { & (Join-Path $PSScriptRoot 'fabrica.ps1') @a | Where-Object { "$_" -notmatch 'file pushed' } | ForEach-Object { Write-Host "    $_" } }
        catch { Fallar "fabrica.ps1 falló: $_" }
    }
    Reiniciar
    if (-not $Simular) {
        $inicio = Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
        if ($inicio -ne 'com.jajua.aura/.HomeActivity') { Fallar "El inicio no es Aura ($inicio)." }
        Info 'Aura es la pantalla de inicio.'
    }
}

function Etapa-formateo {
    if ($SinFormatear) { Aviso 'Formateo omitido (-SinFormatear): la prueba final queda pendiente.'; return }
    Confirmar "Formateo real de la caja $($script:Estado.Id): borra /data (cuentas, Wi-Fi, datos de apps). Hazlo por Ethernet."
    Cambio 'Formatear y esperar a que se reinstalen las apps' {
        Sh 'sync' | Out-Null; Sh $script:Perfil.Formatear | Out-Null; Start-Sleep 60
        Esperar-Arranque -minutos 20 -ConApps
    }
}

function Etapa-verificacion {
    Cambio 'Quitar archivos temporales de la caja' { Sh 'rm -f /data/local/tmp/diag-remoto.sh /data/local/tmp/*.new /data/local/tmp/*.apk' | Out-Null }
    $a = @{ Serial = $script:S; Perfil = $script:Perfil.Archivo; Carpeta = $script:Caja }
    $a.Apks = Carpeta-Apps $script:Perfil
    if (-not $SinYoutube) { $a.Youtube = $true }
    & (Join-Path $PSScriptRoot 'verificar.ps1') @a
    $script:VerificacionOk = ($LASTEXITCODE -eq 0)
    if (-not $script:VerificacionOk) { Aviso 'La verificación encontró fallas: revisa el informe. El respaldo se conserva.' }
}

# Caja ya preparada (en otra PC o con un estado.json perdido): mira la caja misma para saber si una etapa que cambia
# /system ya está hecha. Devuelve '' si está hecha o el motivo por el que hay que hacerla.
function Falta-En-Caja($e) {
    $Perf = $script:Perfil
    $nuestro = [int]("0" + (Sh "grep -c 'Reinstalls the user' /system/bin/preinstall.sh 2>/dev/null"))
    switch ($e) {
        'limpieza' {
            $todos = Todos
            $mal = @($Perf.Malware | Where-Object { $todos -contains $_ })
            if ($mal) { return "queda malware: $($mal -join ', ')" }
            $sist = @($Perf.BorrarSistema | Where-Object { (Sh "ls -d $_ 2>/dev/null") })
            if ($sist) { return "quedan en /system: $($sist -join ', ')" }
            if ($Perf.ReemplazarPreinstall -and -not $nuestro) { return 'preinstall.sh sigue siendo el del firmware' }
        }
        'falsificaciones' {
            if ($Perf.Javacore -and -not (Almacenamiento-Real)) { return 'el almacenamiento sigue falsificado' }
            foreach ($k in $Perf.BuildProp.Keys) { if ((Sh "grep '^$k=' /system/build.prop") -ne "$k=$($Perf.BuildProp[$k])") { return "build.prop: falta $k" } }
            if ($Perf.Ajustes -and [int]("0" + (Sh 'ls /system/etc/aura/bind 2>/dev/null | grep -c Settings')) -lt 2) { return 'faltan los Ajustes parcheados' }
        }
        'fabrica' {
            if ((Sh 'pm path com.jajua.aura') -notmatch '/system/priv-app/Aura') { return 'Aura no es app de sistema' }
            $inicio = Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
            if ($inicio -ne 'com.jajua.aura/.HomeActivity') { return "el inicio no es Aura ($inicio)" }
            if (-not $nuestro) { return 'falta el preinstall.sh propio' }
            $dir = Carpeta-Apps $Perf
            if (Test-Path $dir) {
                $hay = @((Sh 'ls /system/preinstall') -split '\s+' | Where-Object { $_ })
                $faltan = @(Get-ChildItem $dir | Where-Object { $hay -notcontains $_.Name } | ForEach-Object { $_.Name })
                if ($faltan) { return "faltan en /system/preinstall: $($faltan -join ', ')" }
            }
        }
        default { return 'no se puede detectar' }
    }
    return ''
}

# ------------------------------------------------------------------ principal
Write-Host "Preparar TV Box — $(Get-Date -Format 'yyyy-MM-dd HH:mm')$(if ($Simular) { '  [SIMULACIÓN: no se cambia nada]' })" -ForegroundColor Green
Requisitos
if ($Desde -ne 'diagnostico' -and (Conectar-Rapido)) { Root; Abrir-Caja; Info "Conectado a $script:S" }
$empezar = $false
foreach ($e in $Etapas) {
    if ($Desde) { if ($e -eq $Desde) { $empezar = $true }; if (-not $empezar) { continue } }
    elseif ($script:Estado.Hechas -contains $e) { Info "Etapa '$e' ya hecha antes (para repetirla: -Desde $e)."; continue }
    if ($e -ne 'diagnostico' -and -not $script:Caja) { if (Conectar-Rapido) { Root; Abrir-Caja } else { Fallar 'No puedo conectar con la caja.' } }
    if ($e -ne 'diagnostico' -and -not $script:Perfil) {
        $script:Perfil = Buscar-Perfil
        if ($script:Perfil) { $script:Estado.Perfil = $script:Perfil.Archivo; Info "Firmware conocido: $($script:Perfil.Nombre)" }
    }
    if (-not $script:Perfil -and $e -notin 'diagnostico', 'respaldo') {
        Guardar; Actualizar-Indice 'respaldo'
        Write-Host "`nFirmware NUEVO: no hay un perfil para él en scripts\perfiles." -ForegroundColor Yellow
        Write-Host 'El diagnóstico y el respaldo están hechos. Sigue docs\GUIA-NUEVA-TVBOX.md desde el paso 5, o pídele a Claude Code'
        Write-Host "que siga con la caja $($script:Estado.Id) leyendo $script:Caja. Lo aprendido se guarda luego como perfil nuevo."
        Avisar 'Firmware nuevo: el diagnóstico y el respaldo están listos. Hace falta seguir a mano.'
        Stop-Transcript | Out-Null; exit 0
    }
    # Sin -Desde, salta las etapas que la caja ya tiene hechas (en orden: a la primera que falte, sigue normal).
    if (-not $Desde -and -not $script:Revisada -and $e -in 'limpieza', 'falsificaciones', 'fabrica') {
        $falta = Falta-En-Caja $e
        if (-not $falta) {
            Info "Etapa '$e' ya está hecha en la caja (detectado): la salto (para repetirla: -Desde $e)."
            if (-not $Simular) { $script:Estado.Hechas = @($script:Estado.Hechas | Where-Object { $_ -ne $e }) + $e; Guardar; Actualizar-Indice $e }
            continue
        }
        Info "Etapa '$e' pendiente: $falta."
        $script:Revisada = $true
    }
    Titulo "Etapa: $e"
    & "Etapa-$e"
    if (-not $Simular) { $script:Estado.Hechas = @($script:Estado.Hechas | Where-Object { $_ -ne $e }) + $e; Guardar; Actualizar-Indice $e }
}
Titulo 'Terminado'
Info "Caja: $($script:Estado.Id)   Carpeta: $script:Caja"
# Caja terminada y verificada: su respaldo ya no hace falta (el registro y los informes se quedan).
$resp = $script:Estado.Respaldo
if ($script:VerificacionOk -and -not $ConservarRespaldo -and -not $Simular -and $resp -and (Test-Path $resp) -and
    (Split-Path $resp -Parent) -eq (Join-Path $root 'backups')) {
    Remove-Item -Recurse -Force $resp
    Info "Respaldo borrado: la caja quedó verificada sin fallas ($resp)."
    $script:Estado.Respaldo = ''; Guardar
} elseif ($resp -and (Test-Path $resp)) { Info "Respaldo conservado en $resp" }
Info 'Pendiente en la TV: acepta los permisos de cada app la primera vez, comprueba imagen y sonido,'
Info 'y al final cierra el ADB en Aura › Sistema › Cerrar ADB.'
if ($script:Estado.Notas) { Write-Host "`nAvisos:"; $script:Estado.Notas | ForEach-Object { Write-Host "  $_" } }
if (-not $Simular) {
    if ($script:VerificacionOk) { Avisar 'La TV Box quedó lista y verificada sin fallas.' }
    else { Avisar 'La preparación terminó, pero la verificación encontró fallas. Revisa el informe.' }
}
Stop-Transcript | Out-Null
