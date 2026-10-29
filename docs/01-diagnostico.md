# 01 — Diagnóstico inicial (2026-10-28)

Solo lectura. No se instaló, eliminó ni modificó nada en la TV Box.

## Red
- Laptop (Wi-Fi): <IP_PC>/24. TV Box: <IP_TVBOX>, MAC <MAC_TVBOX> (coincide con la indicada).
- Cable LAN directo laptop↔TV Box: solo IP APIPA 169.254.x (sin DHCP); no se usó.
- La conexión Wi-Fi de la TV Box es inestable: un ping tardó 2166 ms y luego dejó de responder durante unos minutos.
- Puertos TCP abiertos (1-10000, 37000-45000): 5555, 7788, 8080, 9800, 9801, 40000.
  - 9800/9801: HTTP `{"code":2002,"msg":"not support"}`; 7788: HTTP 404; 40000: protocolo no HTTP; 8080: cierra la conexión.

## HALLAZGO CRÍTICO: el "ADB" del puerto 5555 no es el ADB real de Android
`adb connect <IP_TVBOX>:5555` conecta sin pedir autorización y aparece como `device`, pero:
- `ls /system/bin`, `ls /sdcard` y `ls /zz_no_existe_123` devuelven la misma lista fija (la raíz).
- `echo` y `toybox` no existen (cualquier Android 6+ los incluye).
- `pm list packages` devuelve solo 4 paquetes: settings, phone, systemui, gms (sería imposible en un sistema real).
- `getprop` solo contesta unas pocas claves (`ro.product.model=TVBOX`, `ro.build.version.sdk=28`, una huella
  `google/walley/eros-p1:10/...:userdebug/test-keys`) y el resto queda vacío; `/proc/meminfo` da "Permission denied".
Conclusión: se trata de un servicio que imita a ADB con respuestas preprogramadas. **Ningún dato de sistema obtenido a través de él es fiable**
(ni el SDK 28, ni el kernel 4.9.112). La versión real de Android, la CPU y la ABI siguen **sin verificarse**.
Un ADB falso en el 5555, más varios puertos desconocidos y una versión de Android falsificada ("11.1" no existe),
coinciden con lo observado en TV Box genéricas con firmware modificado o malware preinstalado (p. ej. campañas tipo BadBox).
No está confirmado, pero el dispositivo debe tratarse como **no confiable**.

## Paquetes reales vistos en la memoria USB (E:\Android\data, creados por la TV Box)
- `com.droidlogic.mboxlauncher`: launcher de fábrica. "droidlogic" = SoC **Amlogic**.
- `com.android.gms.service`, `com.android.network`, `com.android.network.iotvads`, `com.android.network.cserver`,
  `com.imjustafriendlyguy.firmware`: **no son paquetes de Android ni de Google** (los servicios de Google se llaman
  `com.google.android.gms`). Usar nombres `com.android.*` falsos y "iotvads"/"cserver" es típico del adware/proxy
  preinstalado en TV Box genéricas. Las carpetas estaban vacías. Revisarlos en la auditoría (categoría C) cuando haya ADB real.

## USB
Con la TV Box conectada por USB, Windows no detectó ningún dispositivo nuevo (ni ADB, ni MTP, ni desconocido).
El cable o el puerto no transmiten datos, o ese puerto solo funciona como host.

## PC
- OK: adb 37.0.1 (SDK platform-tools), fastboot, git, python 3.12, winget, Android SDK con build-tools 30–37 (aapt2, apksigner), cmdline-tools 9.0.
- Java: `java` en PATH = Oracle 26.0.2.1. **JAVA_HOME = C:\Program Files\Java\jdk-17 → NO existe** (rompe apkanalyzer/apksigner/Gradle).
  Funciona usando JAVA_HOME = `C:\Program Files\Android\Android Studio\jbr`.
- Faltan (opcionales): Gradle global (no hace falta: se usará el wrapper del proyecto) y scrcpy (útil para ver la pantalla de la TV).

## Lumen (github.com/jacksonjao/lumen)
App web (JS/CSS/HTML) para **Samsung Tizen**, licencia MIT. **No es un APK ni funciona en Android.**
Se usará como inspiración visual (glass, Top Shelf, salvapantallas) en un launcher Android nativo propio.
Sus logos de marcas (`src/img/marks/*.svg`) son marcas registradas: no se reutilizan.

## Inventario APK (carpeta local con los APK del usuario)
| Archivo | App / paquete | Versión | minSdk | ABI | Firmante | Recomendación |
|---|---|---|---|---|---|---|
| ru.zdevs.zarchiver_1.0.10…apk | ZArchiver `ru.zdevs.zarchiver` | 1.0.10 | 14 | armeabi-v7a | Anton Prozorov (dev oficial) | Instalar |
| com.stremio.one-1.12.0…armeabi-v7a.apk | Stremio `com.stremio.one` | 1.12.0 | 24 | armeabi-v7a | Stremio (oficial) | Instalar |
| com.dvex.movp-90315.apk | Movie! Plus `com.dvex.movp` | 16.9 | 23 | **solo arm64-v8a** | "Dev-XploiT" | Revisar: falla si el sistema es de 32 bits |
| com.android.mgstv-43405.apk | Xuper `com.android.mgstv` | 4.34.5 | 19 | arm64/v7a | "sgmtv" | Revisar: usa un nombre `com.android.*` |
| XuperCrystalClear-aligned-signed.apk | Xuper Crystal Clear `com.xuper.crystalclear` | 4.34.4 | 24 | arm64/v7a | "Void Log / Ouroboros Nexus" (re-firmado) | Descartar: copia modificada de Xuper y duplicada |
| anime_flv_app_1_6_4_32.apk | animeFLV `com.silk.whoami` | 1.6.4 | 26 | arm64/v7a/x86_64 | "Skz / Jose" | Revisar |

Todos declaran actividad LEANBACK (aparecen en launchers de TV). Hay una copia de XuperCrystalClear también en Downloads.
SHA-256 de los archivos en `logs/apk-hashes.txt`.
