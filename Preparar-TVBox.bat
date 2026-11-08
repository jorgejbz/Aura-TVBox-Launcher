@echo off
rem Prepara una TV Box con Aura siguiendo docs\GUIA-NUEVA-TVBOX.md (ver scripts\preparar.ps1).
rem Todo queda registrado en logs\cajas\<ID de la caja>\. Si se detiene, vuelve a abrirlo: sigue donde se quedo.
rem Abre ademas Claude Code como vigilante (CLAUDE.md, "Modo vigilante"). Para no abrirlo: set AURA_SIN_CLAUDE=1
rem
rem Funciona de dos maneras:
rem  - Dentro del proyecto (junto a scripts\): usa esa copia tal cual.
rem  - Suelto, en la carpeta de las apps de fabrica (la que tiene fabrica-rk322x\): baja el proyecto de GitHub a
rem    %LOCALAPPDATA%\Aura-TVBox, lo actualiza cada vez y toma las apps de esta carpeta. Los programas que falten en
rem    la PC (adb, build-tools, Java, Python) los instala preparar.ps1. Otra carpeta de apps: set AURA_APKS=<carpeta>
rem    Para no actualizar el proyecto: set AURA_SIN_ACTUALIZAR=1
chcp 65001 >nul
setlocal
set "AQUI=%~dp0"
set "AQUI=%AQUI:~0,-1%"
if exist "%AQUI%\scripts\preparar.ps1" (
  set "REPO=%AQUI%"
  goto :listo
)

set "REPO=%LOCALAPPDATA%\Aura-TVBox"
if not defined AURA_APKS set "AURA_APKS=%AQUI%"
echo.
echo  Apps de fabrica: %AURA_APKS%
if defined AURA_SIN_ACTUALIZAR goto :comprobar
echo  Bajando la ultima version del proyecto de GitHub...
set "TMPD=%TEMP%\aura-repo"
if exist "%TMPD%" rd /s /q "%TMPD%"
mkdir "%TMPD%"
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing 'https://github.com/jorgejbz/Aura-TVBox-Launcher/archive/refs/heads/main.zip' -OutFile (Join-Path $env:TMPD 'repo.zip')"
if errorlevel 1 goto :sinred
tar -xf "%TMPD%\repo.zip" -C "%TMPD%"
if errorlevel 1 goto :sinred
rem /E sin /PURGE: logs\, backups\, build\ y apks\ de esta PC no se tocan.
robocopy "%TMPD%\Aura-TVBox-Launcher-main" "%REPO%" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto :sinred
echo  Proyecto al dia en %REPO%
rd /s /q "%TMPD%"
goto :comprobar

:sinred
if exist "%TMPD%" rd /s /q "%TMPD%"
echo  No pude bajar el proyecto de GitHub.
if not exist "%REPO%\scripts\preparar.ps1" (
  echo  Revisa la conexion a internet y vuelve a abrir este archivo.
  echo.
  pause
  exit /b 1
)
echo  Sigo con la copia que ya hay en %REPO%

:comprobar
if not exist "%AURA_APKS%\fabrica-*" (
  echo.
  echo  AVISO: no veo carpetas fabrica-* en %AURA_APKS%.
  echo  Pon este archivo en la carpeta de las apps de fabrica, o usa: set AURA_APKS=carpeta
)

:listo
cd /d "%REPO%"
echo.
echo  Preparar TV Box con Aura
echo  -----------------------
echo  Conecta la caja por cable de red y activa la Depuracion USB (guia, paso 2).
echo.
set "IP=%AURA_IP%"
if not defined IP set /p "IP=IP de la TV Box (Enter para buscarla en la red): "
if defined AURA_IP echo  IP de la TV Box: %IP%
if not defined AURA_SIN_CLAUDE (
  where claude >nul 2>nul && (
    echo  Abriendo Claude Code como vigilante en otra ventana...
    start "Claude Code - vigilante TV Box" /D "%REPO%" claude "Modo vigilante: acabo de lanzar Preparar-TVBox.bat para la TV Box con IP %IP%. Sigue la seccion Modo vigilante de CLAUDE.md: vigila el registro, corrige y relanza si se detiene, y avisame al terminar."
  )
)
if "%IP%"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\scripts\preparar.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\scripts\preparar.ps1" -Ip "%IP%" %*
)
echo.
pause
