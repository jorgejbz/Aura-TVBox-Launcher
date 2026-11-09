# Lista de comprobación del paso 10 de la guía, automatizada. Sólo lectura, salvo la prueba de YouTube (abre videos).
# Deja un informe verificacion-<fecha>.md en -Carpeta (o en logs\). Sale con 1 si hay alguna FALLA.
#
# Uso: .\verificar.ps1 -Serial <IP>:3222 [-Perfil rk322x-tvbox-rk28] [-Apks apks\fabrica-rk322x] [-Youtube]
param(
    [Parameter(Mandatory)] [string] $Serial,
    [string] $Perfil = '',
    [string] $Apks = '',
    [string] $Carpeta = '',
    [switch] $Youtube
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Carpeta) { $Carpeta = Join-Path $root 'logs' }
New-Item -ItemType Directory -Force $Carpeta | Out-Null
$informe = Join-Path $Carpeta ("verificacion-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.md')
$filas = New-Object System.Collections.Generic.List[object]
function Sh($c) { (adb -s $Serial shell $c 2>&1 | Out-String).Trim() }
function Anotar($estado, $que, $detalle) {
    $filas.Add([pscustomobject]@{ Estado = $estado; Que = $que; Detalle = $detalle })
    $color = @{ 'OK' = 'Green'; 'AVISO' = 'Yellow'; 'FALLA' = 'Red' }[$estado]
    Write-Host ("  {0,-5} {1}: {2}" -f $estado, $que, $detalle) -ForegroundColor $color
}
$Perf = $null
if ($Perfil) { $Perf = Import-PowerShellDataFile (Join-Path $PSScriptRoot "perfiles\$Perfil.psd1") }
if ($Serial -match ':') { adb connect $Serial 2>&1 | Out-Null }
adb -s $Serial root 2>&1 | Out-Null; Start-Sleep 4; if ($Serial -match ':') { adb connect $Serial 2>&1 | Out-Null; Start-Sleep 2 }
Write-Host "`nVerificación de $Serial" -ForegroundColor Cyan

# 1. Inicio
$inicio = Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
$ruta = Sh 'pm path com.jajua.aura'
if ($inicio -eq 'com.jajua.aura/.HomeActivity' -and $ruta -match '/system/priv-app') { Anotar 'OK' 'Inicio' 'Aura, desde /system' }
elseif ($inicio -eq 'com.jajua.aura/.HomeActivity') { Anotar 'AVISO' 'Inicio' "Aura, pero no es la copia de sistema ($ruta)" }
else { Anotar 'FALLA' 'Inicio' "la pantalla de inicio es $inicio" }

# 2. Apps
$p3 = @(adb -s $Serial shell pm list packages -3 2>$null | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ -and $_ -ne 'com.jajua.aura' })
if ($Apks -and (Test-Path $Apks)) {
    $bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } | Select-Object -Last 1
    $esperadas = @(Get-ChildItem $Apks | ForEach-Object {
        $f = if ($_.PSIsContainer) { Join-Path $_.FullName 'base.apk' } else { $_.FullName }
        if (Test-Path $f) { (& (Join-Path $bt.FullName 'aapt2.exe') dump packagename $f 2>$null | Select-Object -First 1) } } | Where-Object { $_ })
    $faltan = @($esperadas | Where-Object { $p3 -notcontains $_ })
    $sobran = @($p3 | Where-Object { $esperadas -notcontains $_ })
    if ($faltan) { Anotar 'FALLA' 'Apps' "faltan: $($faltan -join ', ')" } else { Anotar 'OK' 'Apps' "las $($esperadas.Count) instaladas" }
    if ($sobran) { Anotar 'FALLA' 'Apps de más' ($sobran -join ', ') }
} else { Anotar 'AVISO' 'Apps' "instaladas: $($p3 -join ', ') (sin -Apks no se comparan)" }

# 3. Malware
if ($Perf) {
    $todos = @(adb -s $Serial shell pm list packages 2>$null | ForEach-Object { ($_ -replace '^package:', '').Trim() })
    $hay = @($Perf.Malware | Where-Object { $todos -contains $_ })
    if ($hay) { Anotar 'FALLA' 'Malware' ($hay -join ', ') } else { Anotar 'OK' 'Malware' 'ningún paquete conocido' }
}
$cola = Sh 'ls /sdcard/Download/app 2>/dev/null'
if ($cola) { Anotar 'FALLA' 'Cola de instalación' "/sdcard/Download/app: $cola" }

# 4. Red: qué escucha y quién se conecta
$adbPort = if ($Serial -match ':(\d+)$') { $matches[1] } else { '' }
$tcp = (Sh 'cat /proc/net/tcp /proc/net/tcp6') -split "`n"
$uids = @{}
foreach ($l in (Sh "dumpsys package | grep -E '^  Package \[|userId='") -split "`n") {
    if ($l -match 'Package \[([^\]]+)\]') { $pk = $matches[1] } elseif ($l -match 'userId=(\d+)' -and $pk) { if (-not $uids[$matches[1]]) { $uids[$matches[1]] = $pk } }
}
$escucha = @(); $conex = @{}
foreach ($l in $tcp) {
    $f = $l.Trim() -split '\s+'; if ($f.Count -lt 8 -or $f[0] -notmatch ':$') { continue }
    $puerto = [Convert]::ToInt32(($f[1] -split ':')[-1], 16)
    if ($f[3] -eq '0A' -and "$puerto" -ne $adbPort -and $puerto -ne 5037) { $escucha += "$puerto (uid $($f[7]))" }
    if ($f[3] -eq '01' -and "$([Convert]::ToInt32(($f[2] -split ':')[-1], 16))" -ne '0') {
        $quien = if ($f[7] -eq '0') { 'root/adb' } elseif ($uids[$f[7]]) { $uids[$f[7]] } else { "uid $($f[7])" }
        $conex[$quien] = 1 + [int]$conex[$quien]
    }
}
if ($escucha) { Anotar 'FALLA' 'Puertos abiertos' ($escucha -join ', ') } else { Anotar 'OK' 'Puertos abiertos' 'sólo ADB' }
$raras = @($conex.Keys | Where-Object { $_ -notmatch '^(com\.google\.|com\.android\.(vending|providers)|root/adb|android$)' -and $p3 -notcontains $_ })
$resumen = ($conex.Keys | ForEach-Object { "$_ ($($conex[$_]))" }) -join ', '
if ($raras) { Anotar 'AVISO' 'Conexiones' "revisa: $($raras -join ', ') | todas: $resumen" } else { Anotar 'OK' 'Conexiones' $(if ($resumen) { $resumen } else { 'ninguna' }) }

# 4b. Seguridad: lo que el malware suele dejar aunque no quede ningún paquete conocido
$procs = @(((Sh 'ps') -split "`n") | ForEach-Object { ($_.Trim() -split '\s+')[-1] } | Where-Object { $_ -match '[.:]' })
$rarosP = @($procs | Where-Object { $_ -match ':(daemon|watch|push)$|^android\.daemon$' -or ($Perf -and $Perf.Malware -contains ($_ -split ':')[0]) } | Sort-Object -Unique)
if ($rarosP) { Anotar 'FALLA' 'Procesos' "sospechosos: $($rarosP -join ', ')" } else { Anotar 'OK' 'Procesos' 'ninguno sospechoso' }
$udp = @(((Sh 'cat /proc/net/udp /proc/net/udp6') -split "`n") | ForEach-Object {
        # Se omiten las conexiones salientes a :443 (QUIC de YouTube/Google): no son puertos abiertos.
        $f = $_.Trim() -split '\s+'; if ($f.Count -ge 8 -and $f[0] -match ':$' -and $f[2] -notmatch ':01BB$') { [Convert]::ToInt32(($f[1] -split ':')[-1], 16) } } |
    Where-Object { $_ -notin 0, 68, 5353 } | Sort-Object -Unique)
if ($udp) { Anotar 'FALLA' 'Puertos UDP' ($udp -join ', ') } else { Anotar 'OK' 'Puertos UDP' 'sólo DHCP' }
$hosts = @(((Sh 'cat /system/etc/hosts') -split "`n") | Where-Object { $_.Trim() -and $_ -notmatch '^\s*#' -and $_ -notmatch '\blocalhost\b' })
if ($hosts) { Anotar 'FALLA' 'Archivo hosts' "redirige: $($hosts -join '; ')" } else { Anotar 'OK' 'Archivo hosts' 'sin redirecciones' }
$proxy = @((Sh 'settings get global http_proxy'), (Sh 'settings get global global_http_proxy_host')) | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne ':0' }
if ($proxy) { Anotar 'FALLA' 'Proxy' ($proxy -join ', ') } else { Anotar 'OK' 'Proxy' 'ninguno' }
$certs = Sh 'ls /data/misc/user/0/cacerts-added 2>/dev/null'
if ($certs) { Anotar 'AVISO' 'Certificados añadidos' $certs } else { Anotar 'OK' 'Certificados añadidos' 'ninguno' }
$admins = @(([regex]::Matches((Sh 'dumpsys device_policy'), '(?m)^\s+([\w.]+)/[\w.$]+:\s*$')) | ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -ne 'com.google.android.gms' } | Sort-Object -Unique)
if ($admins) { Anotar 'FALLA' 'Administradores del dispositivo' ($admins -join ', ') } else { Anotar 'OK' 'Administradores del dispositivo' 'sólo Google o ninguno' }
$acc2 = @((Sh 'settings get secure enabled_accessibility_services') -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -notmatch '^com\.jajua\.aura/|HiRMService' })
if ($acc2) { Anotar 'AVISO' 'Accesibilidad' "otros servicios: $($acc2 -join ', ')" } else { Anotar 'OK' 'Accesibilidad' 'sólo Aura' }
$cifs = Sh 'ls -ld /data/cifsmanager 2>/dev/null'
if ($cifs -and $cifs -notmatch '^drwx------') { Anotar 'FALLA' 'Carpeta cifsmanager' "$cifs (debe ser 0700: preinstall.sh la cierra)" }
elseif ($cifs) { Anotar 'OK' 'Carpeta cifsmanager' 'cerrada (0700)' }
$instaladoras = @($p3 | Where-Object { (Sh "dumpsys package $_") -match 'INSTALL_PACKAGES: granted=true' })
if ($instaladoras) { Anotar 'OK' 'Apps que pueden instalar otras' "$($instaladoras -join ', ') (son de tu carpeta de apps)" }

# 5. Datos reales
$sdk = Sh 'getprop ro.build.version.sdk'; $rel = Sh 'getprop ro.build.version.release'
$nombres = @{ '24' = '7.0'; '25' = '7.1'; '26' = '8.0'; '27' = '8.1'; '28' = '9'; '29' = '10'; '30' = '11'; '31' = '12'; '33' = '13'; '34' = '14' }
if ($nombres[$sdk] -and -not $rel.StartsWith($nombres[$sdk])) { Anotar 'FALLA' 'Versión de Android' "dice $rel, la API $sdk es $($nombres[$sdk])" } else { Anotar 'OK' 'Versión de Android' "$rel (API $sdk)" }
$df = [long](((Sh 'df -k /data | tail -n 1') -split '\s+')[1])
if ((Sh 'dumpsys diskstats | grep Data-Free') -match '/\s*(\d+)K') { $fw = [long]$matches[1] } else { $fw = 0 }
if ($df -gt 0 -and [math]::Abs($fw - $df) / $df -lt 0.03) { Anotar 'OK' 'Almacenamiento' "$([math]::Round($df/1MB,2)) GB, igual que el kernel" }
else { Anotar 'FALLA' 'Almacenamiento' "las apps ven $([math]::Round($fw/1MB,2)) GB y el kernel dice $([math]::Round($df/1MB,2)) GB" }
foreach ($k in 'ro.product.manufacturer', 'ro.product.chipset') { if ($Perf -and $Perf.BuildProp[$k] -and (Sh "getprop $k") -ne $Perf.BuildProp[$k]) { Anotar 'FALLA' $k (Sh "getprop $k") } }

# 6. Parches
if ($Perf -and $Perf.Ajustes) {
    $n = [int]("0" + (Sh 'grep -c priv-app/.*Settings /proc/mounts'))
    if ($n -ge 2) { Anotar 'OK' 'Ajustes parcheados' 'montados' } else { Anotar 'FALLA' 'Ajustes parcheados' "$n de 2 montados (logcat -s preinstall)" }
}
if ($Perf -and $Perf.ParcheAudio) {
    $tmp = Join-Path $env:TEMP 'aura-verificar'; New-Item -ItemType Directory -Force $tmp | Out-Null
    adb -s $Serial pull /system/lib/libaudiopolicymanagerdefault.so (Join-Path $tmp 'a.so') 2>&1 | Out-Null
    python (Join-Path $PSScriptRoot 'patch_audiopolicy.py') (Join-Path $tmp 'a.so') (Join-Path $tmp 'b.so') | Out-Null
    if ($LASTEXITCODE -eq 3) { Anotar 'OK' 'Parche de audio' 'aplicado' } else { Anotar 'FALLA' 'Parche de audio' 'no está aplicado (fabrica.ps1 -ParcheAudio)' }
    Remove-Item -Recurse -Force $tmp
}

# 7. Botones
$km = Sh 'cat /data/data/com.jajua.aura/shared_prefs/keymap.xml 2>/dev/null'
$acc = Sh 'settings get secure enabled_accessibility_services'
$teclas = ([regex]::Matches($km, '<string name="(\d+)"')).Count
if ($km -match '_imported" value="true"' -and $acc -match 'com.jajua.aura/com.jajua.aura.KeyMapService') { Anotar 'OK' 'Botones' "$teclas asignados, servicio activo" }
else { Anotar 'FALLA' 'Botones' "importados: $($km -match '_imported'); servicio: $acc" }

# 8. Cierres
$cierres = @(([regex]::Matches((Sh 'logcat -d -b crash'), 'Process: ([\w.:]+)')) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$vending = (Sh 'logcat -d -b crash') -match 'selfupdate'
$otros = @($cierres | Where-Object { -not ($_ -eq 'com.android.vending' -and $vending) })
if ($otros) { Anotar 'FALLA' 'Cierres' ($otros -join ', ') } elseif ($cierres) { Anotar 'OK' 'Cierres' 'sólo la autoactualización de Play Store (conocida)' } else { Anotar 'OK' 'Cierres' 'ninguno' }

# 9. Idioma, hora, imagen
Anotar $(if ((Sh 'getprop persist.sys.timezone') -and (Sh 'date') -notmatch '1970') { 'OK' } else { 'AVISO' }) 'Hora' "$(Sh 'getprop persist.sys.timezone'), $(Sh 'date')"
Anotar 'OK' 'Idioma' "$(Sh 'getprop persist.sys.locale') $(Sh 'getprop ro.product.locale')"
$modo = Sh 'cat /sys/class/display/HDMI/mode 2>/dev/null'
if ($modo -match '^(\d+)x(\d+)' -and (Sh 'cat /sys/class/graphics/fb0/disp_info 2>/dev/null') -match 'dsp_x\s*:\s*(\d+)') {
    $ancho = $matches[1]; $salida = ($modo -split 'x')[0]
    if ($ancho -eq $salida) { Anotar 'OK' 'Imagen' "$modo, pantalla completa" } else { Anotar 'AVISO' 'Imagen' "salida $modo, dibujado a $ancho de ancho (overscan)" }
}

# 10. YouTube con audio 5.1 y estéreo (abre videos en la TV)
if ($Youtube) {
    $yt = 'com.google.android.youtube.tv'
    $foco = Sh 'dumpsys activity activities | grep mFocusedActivity'
    if ((Sh "pm path $yt") -notlike 'package:*') { Anotar 'AVISO' 'YouTube' 'no está instalado' }
    elseif ($foco -notmatch 'com.jajua.aura|youtube') { Anotar 'AVISO' 'YouTube' 'la caja está en uso: no se probó' }
    else {
        $capa = "SurfaceView - $yt/com.google.android.apps.youtube.tv.activity.MainActivity"
        function Fps {
            $t = @(((Sh "dumpsys SurfaceFlinger --latency '$capa'") -split "`n") | ForEach-Object { $c = $_.Trim() -split '\s+'; if ($c.Count -eq 3) { [decimal]$c[1] } } |
                Where-Object { $_ -gt 0 -and $_ -lt 9e18 } | Sort-Object)
            if ($t.Count -lt 2) { return @(0, 0) }
            $hueco = 0; for ($i = 1; $i -lt $t.Count; $i++) { $hueco = [math]::Max($hueco, $t[$i] - $t[$i - 1]) }
            return @([math]::Round(($t.Count - 1) / (($t[-1] - $t[0]) / 1e9), 1), [math]::Round($hueco / 1e6))
        }
        function Medida { $u = [double](((Sh 'cat /proc/uptime') -split '\s+')[0]); $w = 0
            if ((Sh "dumpsys media.audio_flinger 2>/dev/null | grep -m1 'Frames written'") -match '(\d+)') { $w = [long]$matches[1] }
            return @($u, $w, [int](Sh "logcat -d | grep -c 'previous underrun'")) }
        # Tras un formateo, YouTube pide entrar: "Mirar como invitado".
        Sh "am start -n $yt/com.google.android.apps.youtube.tv.activity.ShellActivity" | Out-Null; Start-Sleep 15
        foreach ($k in 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_CENTER') { Sh "input keyevent KEYCODE_$k" | Out-Null; Start-Sleep 1 }
        Start-Sleep 12; Sh "am force-stop $yt" | Out-Null
        foreach ($v in @(@('aqz-KE-bpKQ', 60, 'Big Buck Bunny 60 fps, audio 5.1'), @('eRsGyueVLvQ', 24, 'Sintel, estéreo'))) {
          # Si el video no llegó a arrancar (YouTube se quedó en otra pantalla), se repite una vez antes de dar falla.
          for ($intento = 1; $intento -le 2; $intento++) {
            Sh "am force-stop $yt; logcat -c; am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=$($v[0])' -n $yt/com.google.android.apps.youtube.tv.activity.ShellActivity" | Out-Null
            Start-Sleep 40; $a = Medida; $f1 = Fps; Start-Sleep 12; $f2 = Fps; Start-Sleep 12; $f3 = Fps; $b = Medida
            $ytpid = Sh "pidof $yt"
            $mask = ([regex]::Matches((Sh 'dumpsys media.audio_flinger 2>/dev/null'), "(?m)^\s*\d+\s+(?:yes|no)\s+$ytpid\s+\d+\s+[0-9A-F]+\s+([0-9A-F]{8})") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) -join ','
            $vel = [math]::Round((($b[1] - $a[1]) / ($b[0] - $a[0])) / 44100, 2); $cortes = $b[2] - $a[2]
            $fps = [math]::Min([math]::Min($f1[0], $f2[0]), $f3[0]); $hueco = [math]::Max([math]::Max($f1[1], $f2[1]), $f3[1])
            if ($fps -gt 0 -or $mask) { break }
            Write-Host "  (el video no arrancó: reintento)" -ForegroundColor DarkGray
          }
            $det = "audio $($vel)x, $cortes cortes, $fps fps (esperado $($v[1])), hueco máx. $hueco ms, canales $mask$(if ($intento -gt 1) { ', al 2º intento' })"
            if ($vel -ge 0.95 -and $cortes -eq 0 -and $fps -ge 0.9 * $v[1]) { Anotar 'OK' "YouTube: $($v[2])" $det } else { Anotar 'FALLA' "YouTube: $($v[2])" $det }
        }
        Sh "am force-stop $yt; input keyevent KEYCODE_HOME" | Out-Null
    }
}

# 11. ADB
Anotar 'AVISO' 'ADB' "abierto en la red ($Serial): ciérralo en Aura › Sistema › Cerrar ADB cuando termines"

# Una verificación que registró menos comprobaciones de las esperadas no vale: cuenta como falla.
if ($filas.Count -lt 12) { Anotar 'FALLA' 'Verificación incompleta' "sólo se registraron $($filas.Count) comprobaciones: revisa el registro" }
$fallas = @($filas | Where-Object Estado -eq 'FALLA').Count
@("# Verificación $(Get-Date -Format 'yyyy-MM-dd HH:mm') — $Serial", '', '| Estado | Qué | Detalle |', '|---|---|---|') +
    ($filas | ForEach-Object { "| $($_.Estado) | $($_.Que) | $($_.Detalle -replace '\|', '/') |" }) +
    @('', "**$fallas fallas.**") | Set-Content -Encoding UTF8 $informe
Write-Host "`n$fallas fallas. Informe: $informe"
if ($fallas) { exit 1 } else { exit 0 }
