# Muestra qué hay en pantalla: actividad en primer plano, elemento enfocado y textos visibles.
# Uso: .\ui-estado.ps1 -Serial emulator-5584
param([Parameter(Mandatory)] [string] $Serial)
$top = (adb -s $Serial shell dumpsys activity activities | Select-String 'topResumedActivity|mResumedActivity' | Select-Object -First 1).Line
"actividad: $("$top".Trim())"
adb -s $Serial shell uiautomator dump /sdcard/aura-ui.xml | Out-Null
[xml] $x = adb -s $Serial shell cat /sdcard/aura-ui.xml
$nodes = $x.SelectNodes('//node')
$f = $nodes | Where-Object { $_.focused -eq 'true' } | Select-Object -Last 1
"enfocado: $($f.'content-desc') $($f.text) $($f.bounds)"
"textos: " + (($nodes | Where-Object { $_.text } | ForEach-Object text | Select-Object -First 14) -join ' | ')
