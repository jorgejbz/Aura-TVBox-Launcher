# 04 — Segunda caja limpia: Rockchip RK3228A (noviembre de 2026)

## Identidad real
Rockchip **RK3228A**, 4 núcleos ARMv7 **32 bits**, **1 GB RAM**, eMMC de **8 GB** (5,4 GB para apps), interfaz a
1280×720, **Android 7.1.2 (API 25)**, parches de abril de 2017, firmware `userdebug`, SELinux **Permissive**.
ADB real en TCP **3222** sin autenticación. `adb root` funciona; usar `adb remount` (`mount -o remount` da `EBUSY`).

## Malware encontrado y eliminado
| Componente | Qué hacía | Acción |
|---|---|---|
| `/system/app/epark` (`com.rockchip.itvbox`) | Contiene el SDK de **proxy residencial** Gaganode / earn.fm, con procesos `:daemon` y `:watch` | **Borrado** |
| `/system/app/LZ_Sysplugin` (`com.lz.sysplugin`, uid sistema) | Cifrado con jiagu; puede instalar, borrar y reiniciar; ~23 conexiones simultáneas a puertos poco comunes | **Borrado** |
| `/system/app/MxqLauncher_new` (`com.droidlogic.mboxlauncher`, uid sistema) | Launcher con `proxypluginservice` e instalador | **Borrado** |
| `/system/priv-app/TvService` (`com.ztv.service`) | Procesos `android.daemon` separados de la app, hechos para seguir vivos | **Borrado** |
| `/system/bin/lz_plug` (servicio init, root) | Escucha en TCP 5000 para toda la red | **Borrado**: init lo desactiva si falta el binario |
| `com.android.tvh`, `com.tv.napp`, `com.android.appstore` | Instalados en el primer arranque, dos de ellos con permiso de instalar apps | **Desinstalados** |
| `preinstall.sh` del firmware | Reinstalaba `/system/preinstall` en cada arranque y lanzaba `epark` | **Reemplazado** |

Parar el proxy multiplicó por 4 la velocidad del respaldo por Wi-Fi: consumía la conexión de forma medible.

## Falsificación de datos
| Dónde | Truco | Corrección |
|---|---|---|
| `libjavacore.so`, `makeStructStatVfs` | Constante de 8 GB, en otra posición que en la caja HiSilicon | `patch_javacore.py` reconoce los dos builds |
| `Settings` y `TvSettings` | Rama añadida: si el volumen se llama `data`, 32 GiB fijos (6 métodos) | `patch_settings.py` |
| `Settings`, `MemInfo.calculateWeightInfo` | Lee la RAM real y la sobrescribe con 2³² (4 GiB) | `patch_settings.py` |
| `build.prop` | Android 11.1, fabricante AMLOGIC, marca google, chip AD25 | Corregidos; `ro.build.fingerprint` se conserva |
| Kernel, `/proc/meminfo` | Redondea `MemTotal` a 1 GiB exacto (~27 MB de más) | Sin corregir: exige recompilar el kernel |

### Por qué los APK parcheados no reemplazan a los originales
`Settings` y `TvSettings` comparten el uid de sistema y van firmados con una clave privada del fabricante, no con la de
pruebas de AOSP, así que Android rechazaría un APK modificado. No se debilita la verificación de firmas. Los originales
siguen en `/system` y pasan la verificación al arrancar; después `preinstall.sh` monta encima con *bind mount* las copias
de `/system/etc/aura/bind/`. Si eso falla, vuelven los números falsos, pero la caja arranca igual.

Los APK del fabricante no se publican. Se generan desde tu propia caja:
```powershell
adb pull /system/priv-app/TvSettings/TvSettings.apk
python -I scripts\patch_settings.py TvSettings.apk p.apk
zipalign -f -p 4 p.apk bind\priv-app@TvSettings@TvSettings.apk
```
Repite con `Settings` y pasa la carpeta a `fabrica.ps1 -Bind bind`. Un build desconocido aborta sin escribir nada.

## Imagen por HDMI
El firmware fija `overscan 95`: la imagen de 1080p se dibujaba a 1845×1038 con márgenes negros y un reescalado no entero
(`/sys/class/graphics/fb0/disp_info`). Con `-Overscan 100` ocupa los 1920×1080. La interfaz sigue a 720p, ampliada ×1,5.
Si la TV recorta los bordes, ponla en *Ajuste de pantalla* (*Screen Fit*).

## Apps del sistema retiradas
DocumentsUI, PinyinIME, Gallery2, PicoTts, BasicDreams, PrintSpooler, Katniss, RkExplorer, AllApp, los adaptadores de
sincronización de Calendario y Contactos, WebViewGoogle (la activa es `com.android.webview`), actualizadores
(LocalUpdate, RKUpdateService), apps de prueba (StressTest, DeviceTest, DyFactoryTest2, Videoplayercustom) y los binarios
de samba y de prueba. Sin DocumentsUI, una app que abra el selector de archivos del sistema no lo encontrará.

## Control remoto
El driver sólo declara ciertas teclas: F1–F8 y `PROG_RED`, entre otras, pero no F9 ni F10. Compruébalo con
`getevent -p` en el dispositivo `.pwm` antes de asignar botones.

## Verificado
Tras un formateo real con todo instalado: arranque con la animación de Aura, Aura 1.1 como launcher, 6 apps reinstaladas,
botones importados, RAM y almacenamiento reales en Ajustes, imagen a 1920×1080, idioma y zona horaria, sólo ADB
escuchando, ningún paquete del malware. YouTube reproduce con `OMX.rk.video_decoder.vp9`. *Cerrar ADB* cierra el puerto
medido desde otra PC y *Abrir ADB* lo reabre. El actualizador instaló una versión real desde GitHub sobre una de prueba.

## Interruptor de ADB en otros firmwares
En la caja HiSilicon (Android 9) *Cerrar ADB* detiene `adbd` y *Abrir ADB* lo recupera en el puerto 3222. Ese firmware fija
el puerto al arrancar (`init.bigfish.rc`). Comprueba el estado en Aura › Sistema después de reiniciar. Android 9 mata lo
que lanzó `adbd` al detenerlo: un guion de seguridad iniciado por ADB no sobrevive para reabrirlo.

## Cómo revertir
Una partición entera: `adb root`, `adb remount` o `blockdev --setrw`, y `dd` del `.img` del respaldo. Los parches de
Ajustes: borrar `/system/etc/aura/bind/` y reiniciar.
