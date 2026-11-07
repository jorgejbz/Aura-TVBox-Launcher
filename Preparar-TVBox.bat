@echo off
rem Prepara una TV Box con Aura siguiendo docs\GUIA-NUEVA-TVBOX.md (ver scripts\preparar.ps1).
rem Todo queda registrado en logs\cajas\<ID de la caja>\. Si se detiene, vuelve a abrirlo: sigue donde se quedo.
rem Abre ademas Claude Code como vigilante (CLAUDE.md, "Modo vigilante"). Para no abrirlo: set AURA_SIN_CLAUDE=1
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo  Preparar TV Box con Aura
echo  -----------------------
echo  Conecta la caja por cable de red y activa la Depuracion USB (guia, paso 2).
echo.
set "IP="
set /p "IP=IP de la TV Box (Enter para buscarla en la red): "
if not defined AURA_SIN_CLAUDE (
  where claude >nul 2>nul && (
    echo  Abriendo Claude Code como vigilante en otra ventana...
    start "Claude Code - vigilante TV Box" /D "%~dp0" claude "Modo vigilante: acabo de lanzar Preparar-TVBox.bat para la TV Box con IP %IP%. Sigue la seccion Modo vigilante de CLAUDE.md: vigila el registro, corrige y relanza si se detiene, y avisame al terminar."
  )
)
if "%IP%"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\preparar.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\preparar.ps1" -Ip "%IP%" %*
)
echo.
pause
