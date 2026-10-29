# Guía: preparar una TV Box nueva

Lo aprendido con la primera caja (HiSilicon Hi3798MV300H), convertido en pasos. Cada caja puede ser distinta:
el diagnóstico decide qué pasos aplican. **No asumas que lo que dice la caja es verdad.**

## 0. Requisitos en la PC (Windows)
- Android SDK platform-tools (`adb`) y build-tools (`aapt2`, `apksigner`); Android Studio (trae Java en `jbr`).
- `JAVA_HOME` de usuario → `C:\Program Files\Android\Android Studio\jbr`.
- La llave de firma de Aura (`aura-release.jks` + `keystore.properties`) copiada en `launcher/` desde tu respaldo
  privado. Sin ella Aura se firma con la llave de depuración (funciona, pero no actualiza un Aura firmado con la otra).

## 1. Conectar
1. En la caja: Ajustes › Preferencias › Información › pulsar 7 veces "Compilación" › Opciones de desarrollador ›
   **Depuración USB**. Misma red que la PC (Wi-Fi o, mejor, Ethernet).
2. `.\scripts\diagnostico.ps1` busca la caja en la red (puertos 5555, 3222, …) y **prueba si el ADB es real**.
   - La primera caja tenía un **ADB falso en 5555** (respuestas fijas) y el **real en 3222**.
   - Si nada responde: cable USB A–A/A–C con datos en el puerto **OTG** de la caja (prueba cada puerto),
     luego `adb devices`. Si sale `unauthorized`, acepta el aviso en la TV.

## 2. Diagnóstico (sólo lectura)
`.\scripts\diagnostico.ps1` deja un informe en `logs/` y marca:
| Alerta | Qué significa | Qué hicimos en la caja 1 |
|---|---|---|
| ADB falso | Servicio que imita ADB | Venía del malware; desapareció al limpiarlo |
| Versión de Android falsificada | `ro.build.version.release` no cuadra con la API | Corregido en `/system/build.prop` |
| RAM falsificada | `ro.memory.size` > RAM del kernel | Corregido en `/vendor/build.prop` |
| El framework falsifica el almacenamiento | `StatFs` ≠ `df` | Constante parcheada en `libjavacore.so` (`scripts/patch_javacore.py`, verifica bytes y aborta si no coinciden) |
| `ramrom.config` | Ajustes › Almacenamiento lee `/private/ramrom.config` ("RAM+ROM") | Escrito con los valores reales |
| App instalada en silencio por otra app | Cadena de instaladores ocultos | Desinstalada; su instalador borrado de /system |
| App con uid 1000 descargada | Malware con permisos de sistema | Desinstalada |
| Servicio ROOT escuchando | Puerta trasera (`cmd_root`, puerto 40000) | Binario borrado + `disabled` en el .rc |
| ≥10 conexiones de una app | Proxy residencial (`xynode` de `com.example.himac`) | App borrada de /system |
También revisa a mano la lista "Apps del sistema que no son AOSP/Google" (en la caja 1: `com.android.cts.service`,
`mboxlauncher`, `himac` eran malware; `hiRMService` y `HiVideoPlayer` eran necesarios para el video).

## 3. Respaldo (antes de tocar nada)
`.\scripts\respaldar.ps1 -Serial IP:PUERTO` → `backups/<modelo>-<fecha>/` con SHA-256. Requiere `adb root`
(firmwares `userdebug`). Guárdalo fuera de GitHub.

## 4. Limpieza
Decide con el informe. Orden que funcionó:
1. `pm disable-user --user 0` de los **instaladores** primero (si no, reinstalan lo que borres).
2. `pm uninstall` de lo que descargaron.
3. Reiniciar y verificar con `diagnostico.ps1` que no volvieron.
4. Con autorización explícita: borrar de `/system` (necesita `adb root`, `blockdev --setrw <dev>` y
   `mount -o remount,rw /`). Para reemplazar archivos en uso (librerías): copia nueva + `mv`, nunca sobrescribir.
5. Reiniciar y volver a diagnosticar.
Si el chip Wi-Fi deja de aparecer tras muchos reinicios (`mmc2: error -110 … SDIO`): desconecta la corriente 30 s.

## 5. Aura
```powershell
cd launcher; .\gradlew.bat assembleRelease; copy app\build\outputs\apk\release\app-release.apk ..\build\Aura-<versión>.apk
adb -s IP:PUERTO install -r ..\build\Aura-<versión>.apk
```
Fijar como inicio: Android 10+ `cmd role add-role-holder android.app.role.HOME com.jajua.aura`;
Android 9 o menos: pulsar Inicio › Aura › Siempre (o hacerlo app de sistema, paso 6).
Botones del control: `settings put secure enabled_accessibility_services com.jajua.aura/com.jajua.aura.KeyMapService`
y `settings put secure accessibility_enabled 1` (o desde Aura › Botones).

## 6. "De fábrica" (sobrevive a un formateo)
Edita `scripts/aura-defaults.properties` (favoritas y botones) y ejecuta:
```powershell
.\scripts\fabrica.ps1 -Serial IP:PUERTO -CopiarDeLaCaja ru.zdevs.zarchiver,com.stremio.one,com.ionitech.airscreen,com.google.android.youtube.tv -Probar
```
Copia las apps tal como están instaladas (incluidas las divididas), las deja en `/system/preinstall`, instala
`scripts/preinstall.sh` (soporta APK divididos) y Aura en `/system/priv-app`. Se detiene si el firmware no tiene
el mecanismo de preinstalación. Reinicia y, si quieres, prueba un formateo real.

No sobrevive a un formateo (normal): cuentas (Google, Premium de AirScreen), Wi-Fi, datos dentro de cada app.

## 7. Cerrar
- Nunca usar "Actualización del sistema"/"Local update" del fabricante: reinstala el firmware original.
- Si `ro.adb.secure` está vacío, ADB por red no pide permiso: apaga la Depuración USB al terminar.
