# Respalda las particiones críticas de la TV Box en backups/<caja>-<fecha>/ antes de modificar nada.
# Requiere `adb root` (firmwares userdebug). No toca la partición de datos (userdata/cache).
# Uso: .\respaldar.ps1 -Serial <IP_TVBOX>:3222
param(
    [Parameter(Mandatory)] [string] $Serial,
    # Particiones a copiar si existen (los nombres cambian entre fabricantes).
    [string[]] $Particiones = @('boot', 'recovery', 'recoverybak', 'system', 'vendor', 'odm', 'product',
        'misc', 'bootargs', 'bootargsbak', 'dtb', 'dtbo', 'vbmeta', 'private', 'deviceinfo', 'baseparam', 'pqparam', 'logo')
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

adb -s $Serial root | Out-Null
Start-Sleep 4
if ($Serial -match ':') { adb connect $Serial | Out-Null; Start-Sleep 2 }
if ((adb -s $Serial shell id -u).Trim() -ne '0') { throw 'adb root no funciona en este firmware: no se puede respaldar por particiones.' }

# by-name puede estar en /dev/block/by-name o /dev/block/platform/<soc>/by-name
$dir = (adb -s $Serial shell "for d in /dev/block/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do [ -d `$d ] && echo `$d && break; done").Trim()
if (-not $dir) { throw 'No encontré la tabla de particiones (by-name).' }
$map = @{}
adb -s $Serial shell "ls -l $dir" | ForEach-Object { if ($_ -match '\s(\S+)\s+->\s+(\S+)$') { $map[$matches[1]] = $matches[2] } }
"Particiones en la caja: $(($map.Keys | Sort-Object) -join ', ')"

$name = (adb -s $Serial shell getprop ro.product.model).Trim() -replace '[^\w-]', '_'
$out = Join-Path $root ("backups\$name-" + (Get-Date -Format 'yyyyMMdd-HHmm'))
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($p in $Particiones) {
    if (-not $map.ContainsKey($p)) { continue }
    $t = Get-Date
    adb -s $Serial pull $map[$p] (Join-Path $out "$p.img") | Out-Null
    $f = Get-Item (Join-Path $out "$p.img")
    "{0}  {1}" -f (Get-FileHash $f.FullName -Algorithm SHA256).Hash, $f.Name | Add-Content (Join-Path $out 'SHA256.txt')
    "{0,-12} {1,8:N1} MB  ({2:N0} s)" -f $p, ($f.Length / 1MB), ((Get-Date) - $t).TotalSeconds
}
foreach ($f in '/system/build.prop', '/vendor/build.prop') { adb -s $Serial pull $f (Join-Path $out ($f.Trim('/') -replace '/', '-')) 2>$null | Out-Null }
adb -s $Serial shell pm list packages -f -i -U | Set-Content (Join-Path $out 'paquetes.txt')
"Respaldo listo en $out (no lo subas a GitHub: es el firmware de fábrica)."
