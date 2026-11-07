@echo off
rem Prepara una TV Box con Aura siguiendo docs\GUIA-NUEVA-TVBOX.md (ver scripts\preparar.ps1).
rem Todo queda registrado en logs\cajas\<ID de la caja>\. Si se detiene, vuelve a abrirlo: sigue donde se quedó.
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo  Preparar TV Box con Aura
echo  -----------------------
echo  Conecta la caja por cable de red y activa la Depuracion USB (guia, paso 2).
echo.
set "IP="
set /p "IP=IP de la TV Box (Enter para buscarla en la red): "
if "%IP%"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\preparar.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\preparar.ps1" -Ip "%IP%" %*
)
echo.
pause
