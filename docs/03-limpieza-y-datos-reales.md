# 03 — Limpieza de malware y datos reales (2026-10-29)

Autorizado expresamente por el usuario ("tienes todos los permisos que necesites", "si ocupas modificarlo hazlo").
Log detallado: `logs/limpieza-*.log`. Respaldo previo de particiones: `backups/particiones-2026-10-29/` (con SHA256.txt).

## Identidad real
HiSilicon **Hi3798MV300H**, 4 núcleos ARMv7 **32 bits**, **1 GB RAM** (`mem=1G`), eMMC **8 GB** (3,2 GB para apps),
salida **1920×1080 @ 60 Hz**, **Android 9 (API 28)**, firmware `userdebug/test-keys`, SELinux **Permissive**.
ADB real en TCP **3222** sin autenticación (`ro.adb.secure` vacío). El ADB de 5555 visto el primer día era falso.

## Malware encontrado y eliminado
| Componente | Qué hacía | Acción |
|---|---|---|
| `/system/app/AndroidService` (`com.android.cts.service`, uid sistema) | Instalador silencioso: reinstaló 4 apps tras el formateo | **Borrado** |
| `/system/app/MboxLauncher` (`com.droidlogic.mboxlauncher`, uid sistema) | Launcher con "proxypluginservice", instalador; se abría al arrancar | **Borrado** |
| `/system/app/HiMac` (`com.example.himac`) | Descargaba y ejecutaba `xynode`: **proxy residencial**, ~50 conexiones simultáneas | **Borrado** |
| `/system/bin/cmd_root` (servicio init) | **Puerta trasera root** en TCP 40000 (`popen` de comandos) | **Borrado** + `disabled` en `/init.bigfish.rc` |
| `com.android.gms.service`, `com.charon.rocketfly` (uid sistema), `com.android.network`, `com.android.network.iotvads`, `com.tv.napp` | Descargados por los anteriores | **Desinstalados** |

## Apps de fábrica retiradas
Borradas de /system: HiFileManager, HiMusic, HiGalleryL, DyFactoryTest. Desinstaladas: Firefox, HappyCast (pedía READ_LOGS).
Conservadas: HiVideoPlayer (reproductor del sistema), HiRMService (gestor de recursos del decodificador; servicio de
accesibilidad propio que vigila la ventana activa para repartir el decodificador; sin red), Airplay (AirPlay/DLNA;
se le revocaron ubicación, micrófono y teléfono), Play Store, servicios de Google.

## Falsificación de datos corregida
| Dónde | Truco | Corrección |
|---|---|---|
| `/system/lib/libjavacore.so`, `makeStructStatVfs` | Constante 0x1DCD65 bloques = **8 GB** fija; libres desplazados igual | Parche de 16 bytes (`scripts/patch_javacore.py`): devuelve los valores reales. Original en `backups/framework-original/` |
| TvSettings `RamRomSdk` | Lee `/private/ramrom.config` ("RAM+ROM"); si falta muestra **4 GB / 32 GB** fijos | Creado `/private/ramrom.config` = `1+3.2` (partición que sobrevive al formateo) |
| `/system/build.prop` | `ro.build.version.release=11.1`, `ro.build.id=D9 PRO 5G` | `9`, `PPR1.180610.011` |
| `/vendor/build.prop` | `ro.memory.size=2G` | `1G` |
No se tocó: la huella `ro.build.fingerprint` (Google Play depende de ella), `ro.product.model=TVBOX`, `vendor.display-size`.

## Verificado tras reinicio
Arranca en Aura · versión 9 en Ajustes · Almacenamiento 1 GB / 3,2 GB · sólo puerto 3222 abierto · conexiones salientes
sólo de Google Play/servicios, YouTube y Stremio · 0 crashes · 53 paquetes (antes 66).

## Cómo revertir
- Una partición entera: `adb root`, `blockdev --setrw`, `dd` del .img correspondiente (o flasheo con HiTool).
- Sólo libjavacore: copiar `backups/framework-original/libjavacore.so` a `/system/lib/` (vía archivo nuevo + `mv`).
- Ajustes: borrar `/private/ramrom.config` (volvería a mostrar 4/32 GB).

## Pendiente / riesgos
- ADB 3222 sin autenticación queda abierto por decisión del usuario.
- SELinux en Permissive (de fábrica). Ponerlo en Enforcing podría romper el firmware; no se cambió.
- **No aplicar "Actualización del sistema" / "Local update" del fabricante**: reinstalaría el firmware con malware.

## Duplicado de pantalla (2026-10-29)
- Cuenta de Google iniciada en Play Store (a petición del usuario). Recomendado cambiar la contraseña: se compartió en el chat.
- Instalado **AirScreen 2.18.0** (`com.ionitech.airscreen`) desde Play Store. Se anuncia como **AS-TVBOX**:
  AirPlay (`_airplay`, `_raop`) para iPhone/iPad/Mac y Google Cast (`_googlecast`, 8009) para Android; también DLNA/Miracast.
  Verificado por mDNS desde la PC, también después de reiniciar (tarda ~1-2 min en anunciarse tras el arranque).
  Conserva el permiso de ubicación: Android 9 lo exige para Wi-Fi Direct (Miracast).
- Desinstalado el Airplay viejo (`com.agileapps.airplay`).
- YouTube actualizado 2.02.08 → 5.30.340.

## "Imagen de fábrica" propia (2026-10-29)
Tras un formateo la caja queda con Aura y las apps del usuario, sin nada del firmware original:
- `/system/priv-app/Aura/Aura.apk` (1.0, la versión publicada): Aura es app de sistema privilegiada = launcher desde el primer arranque;
  con WRITE_SECURE_SETTINGS reactiva una vez el servicio de botones.
- `/system/etc/aura/defaults.properties`: favoritas + botones (138 Stremio, 139 Xuper CC, 140 AirScreen, 183 YouTube).
  Si cambias favoritas/botones y quieres conservarlos tras un formateo, hay que volver a copiar este archivo.
- `/system/preinstall/`: vaciado (tenía Airplay, HappyCast, Firefox, Chrome, DisneyPlus, Prime Video, GetApps, magis viejo)
  y ahora contiene ZArchiver, Stremio, Xuper 4.34.5, Xuper Crystal Clear, AirScreen/ (3 splits), YouTube/ (2 splits).
  Copias en `apks/fabrica/`.
- `/system/bin/preinstall.sh` reescrito (`scripts/preinstall.sh`): instala también apps divididas por sesión;
  se quitó el arranque automático de la app de pruebas de fábrica. Probado en sitio: 6/6 "Success".
- Aura oculta de "Todas las apps" las apps que ya tienen mosaico (ZArchiver, Ajustes).
- No sobreviven al formateo (normal en Android): cuenta de Google, sesión/Premium de AirScreen, datos dentro de cada app.

## Incidente Wi-Fi (2026-10-29 03:30)
Tras un reinicio el chip Wi-Fi (Unisoc UWE5621, SDIO) no respondió: `mmc2: error -110 whilst initialising SDIO card`.
Es un fallo a nivel kernel/hardware (el bus SDIO se inicializa antes que Android); típico tras muchos reinicios en caliente.
Solución: desconectar la corriente 30 s. Mientras tanto, ADB por Ethernet en <IP_TVBOX>:3222.
