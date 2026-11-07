# Guía: preparar una TV Box nueva

Receta completa, sacada de tres cajas reales: una HiSilicon Hi3798MV300H con Android 9 (`docs/01`–`03`) y dos Rockchip
RK3228A con Android 7.1 y el mismo firmware (`docs/04`). Todas se vendían como "Android 11.1, 4 GB/32 GB" y traían un
proxy residencial.

**Tres reglas:**
1. **No creas lo que dice la caja**, ni lo que dicen sus pantallas: compara siempre con el kernel y con el hardware.
2. **Que el diagnóstico no alerte no significa que esté limpia.** Haz también las revisiones a mano del paso 3.
3. **Nada queda hecho hasta verificarlo**: reinicia tras cada etapa y termina con un formateo real.

## Mapa del procedimiento
| Paso | Qué | Toca la caja |
|---|---|---|
| 1 | Preparar la PC | No |
| 2 | Conectar y encontrar el ADB real | No |
| 3 | Diagnosticar y revisar a mano | No |
| 4 | Respaldar y verificar el respaldo | No |
| 5 | Quitar el malware | Sí |
| 6 | Quitar las falsificaciones | Sí |
| 7 | Quitar apps del fabricante | Sí |
| 8 | Dejar Aura, apps y ajustes "de fábrica" | Sí |
| 9 | Revisar imagen, sonido y control | Ajustes |
| 10 | Formatear y verificar | Borra `/data` |
| 11 | Cerrar ADB y terminar | Ajustes |

**Antes de borrar nada, avisa de lo que se va a hacer y consigue autorización del dueño de la caja.**

**Atajo:** `Preparar-TVBox.bat` hace los pasos 3 a 10 solo si el firmware tiene perfil en `scripts/perfiles/`. Con un
firmware nuevo se detiene después del respaldo y hay que seguir esta guía a mano. Al terminar, guarda lo aprendido como
perfil nuevo: qué es malware, qué borrar de `/system`, qué valores de `build.prop` corregir y qué parches aplicar.
Los registros de cada caja quedan en `logs\cajas\<ID>\`. Con un perfil conocido no pregunta nada (`-Preguntar` para
confirmar cada bloque), avisa con voz y, si la verificación sale sin fallas, borra el respaldo de esa caja.

## 1. Preparar la PC (Windows)
- Android Studio: trae `adb`, y en `build-tools` `aapt2`, `apksigner`, `zipalign` y `dexdump`.
- `JAVA_HOME` de usuario → `C:\Program Files\Android\Android Studio\jbr`.
- `pip install capstone pyelftools pillow`, para `patch_javacore.py`, `patch_audiopolicy.py` y `bootanimation.py`.
- `build\Aura.apk`: compílalo (`cd launcher; .\gradlew.bat assembleRelease`) o bájalo de
  [Releases](https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/latest/download/Aura.apk) y guárdalo ahí.
  Sólo el dueño de `llave-firma/` puede firmar versiones que se instalen encima de un Aura oficial.
- `apks\fabrica\`: las apps que quieres en la caja, o llénala con `fabrica.ps1 -CopiarDeLaCaja`. No se sube a Git.
  Revisa cada una con `aapt2 dump badging app.apk`: `minSdkVersion`, `native-code` y el nombre del paquete. Las cajas
  suelen ser de 32 bits, y una app sólo `arm64-v8a` no funcionará.

## 2. Conectar
1. En la caja: Ajustes › Preferencias › Información › pulsar 7 veces "Compilación" › Opciones de desarrollador ›
   **Depuración USB**.
2. **Usa Ethernet si puedes:** es lo más rápido y, tras un formateo, la caja vuelve sola a la red. Por Wi-Fi hay que
   reconectarla desde la TV. **No uses el hotspot del celular:** el malware consume tus datos móviles.
3. Busca el ADB: `.\scripts\diagnostico.ps1 -Ip <IP>`. Sin `-Ip` escanea una sola red /24 y se salta las `172.x`, como
   la de un hotspot de iPhone. Puertos vistos: **3222** (ADB real en las dos cajas), 5555 (ADB falso en una), 5000
   (`lz_plug`, no es ADB).
4. Un `adb connect` puede fallar una vez con 10060 aunque el puerto esté abierto: reintenta.

### Si no aparece ningún ADB
Una tercera caja (MXQRO JDH-01, HiSilicon) sigue **sin resolver**. Lo que se probó, para no repetirlo a ciegas:
| Intento | Resultado |
|---|---|
| Escaneo de los 65535 puertos | Sólo TCP 5000, mudo |
| Combinación de fábrica 9 0 9 9 9 (`com.ssdcyd.factorytest`) | Sin efecto visible |
| USB A–A a un PC con sólo puertos USB 3 (xHCI) | "Device Descriptor Request Failed" en bucle |
| UART soldada (3,3 V) | 0 bytes: consola silenciada |

Siguiente intento sin probar: un **hub USB 2.0** entre la caja y el PC. **Peligro:** el cargador de esas cajas tiene
tierra flotante y al conectar la tierra a un PC con toma de tierra quemó un adaptador USB-TTL. Usa una laptop **con
batería y sin cargador**, y nunca conectes +5 V ni 3,3 V del adaptador.

## 3. Diagnosticar (sólo lectura)
```powershell
.\scripts\diagnostico.ps1 -Serial <IP>:<PUERTO>
```
Deja un informe en `logs/`. Si sale "0 paquetes" o la red vacía, el informe **no sirve**: el script no entendió ese
firmware. Corrígelo antes de seguir.

| Alerta | Qué significa | Solución que funcionó |
|---|---|---|
| ADB falso | Servicio que imita ADB | Desapareció al limpiar |
| Versión falsificada | `ro.build.version.release` no cuadra con la API | Paso 6 |
| RAM o almacenamiento falsificados | Propiedades o `StatFs` ≠ kernel | Paso 6 |
| App instalada en silencio por otra | Cadena de instaladores ocultos | Paso 5 |
| App descargada con uid 1000 | Malware con permisos de sistema | Paso 5 |
| Servicio ROOT escuchando | Puerta trasera | Borrar el binario (paso 5) |
| ≥10 conexiones de una app | Proxy residencial | Paso 5 |

### Revisiones a mano (obligatorias)
El diagnóstico **no** detecta esto por sí solo:
```powershell
adb -s <SERIE> root
adb -s <SERIE> shell "netstat -tnp | grep ESTABLISHED"   # quién se conecta a dónde (necesita root)
adb -s <SERIE> shell "netstat -tlnp"                     # qué escucha en la red
adb -s <SERIE> shell "ps -A"                             # en Android 7: ps
adb -s <SERIE> shell "getprop | grep init.svc"           # servicios de init
adb -s <SERIE> shell "pm list packages -f -i"            # apps, ruta e instalador
```
Busca apps que **no** sean AOSP ni Google, nombres que imitan al sistema (`com.android.tvh`) y procesos con `:daemon` o
`:watch`. Después mira dentro del APK sospechoso:
```powershell
adb -s <SERIE> pull <RUTA>.apk app.apk; mkdir x; tar -xf app.apk -C x
Get-ChildItem x -Recurse -Include *.dex,*.so |
  Select-String 'gaganode|earn\.fm|proxypluginservice|xynode|jiagu' -AllMatches |
  ForEach-Object { $_.Matches.Value } | Sort-Object -Unique
```
`jiagu` es un empaquetador que oculta el código: es legítimo en algunas apps, pero sospechoso en una de sistema.

Malware ya identificado:
| Componente | Dónde apareció | Qué es |
|---|---|---|
| `com.example.himac` (`xynode`) | HiSilicon | Proxy residencial |
| `com.rockchip.itvbox` (`epark`) | Rockchip | Proxy residencial Gaganode / earn.fm |
| `com.lz.sysplugin` + `/system/bin/lz_plug` | Rockchip | ~23 conexiones; servicio root en el puerto 5000 |
| `com.droidlogic.mboxlauncher` | Las dos | Launcher con `proxypluginservice` e instalador |
| `com.android.cts.service` | HiSilicon | Instalador silencioso |
| `com.ztv.service` | Rockchip | Procesos `android.daemon` persistentes |
| `com.android.tvh`, `com.tv.napp`, `com.android.appstore` | Rockchip | Descargados o preinstalados |
| `com.android.gms.service`, `com.charon.rocketfly`, `com.android.network*` | Las dos | Descargados |
| `com.android.network.iotvads` | Rockchip | Se instaló solo durante el respaldo y conectó al puerto 8048 |
| `/system/bin/cmd_root` | HiSilicon | Puerta trasera root en el puerto 40000 |

Revisa también `/sdcard/Download/app/`: ahí el malware deja en cola lo próximo que va a instalar (se vieron
`AdPreController` y `CleanUp`). Cada caja puede traer paquetes nuevos: repite `pm list packages -3` tras cada paso.

**Agujero del firmware Rockchip, sin malware dentro:** `init.rk30board.rc` recrea en cada arranque
`/data/cifsmanager` con permisos 777, y el servicio `cifsmanager` ejecuta `/data/cifsmanager/cifsmanager.sh` como root.
Con SELinux en *Permissive*, cualquier app podría dejar ahí un script. `scripts/preinstall.sh` vacía la carpeta y la deja
`0700 root` al final de cada arranque, y `verificar.ps1` lo comprueba.

**Auditoría más allá de los paquetes** (la hace `verificar.ps1`): procesos `:daemon`/`:watch`, puertos TCP y UDP,
`/system/etc/hosts`, proxy global, certificados de usuario, administradores del dispositivo y servicios de accesibilidad.
Para descartar un troyano en el *zygote*, mira las bibliotecas que carga: `cat /proc/$(pidof zygote)/maps`.

Compara además **lo que muestran las pantallas** con el kernel. Abre Ajustes › Almacenamiento y Memoria, y compara con:
```powershell
adb -s <SERIE> shell "df -k /data; grep MemTotal /proc/meminfo; cat /proc/iomem | grep 'System RAM'"
adb -s <SERIE> shell "grep Hardware /proc/cpuinfo; getprop ro.build.version.sdk; getprop ro.build.fingerprint"
```
`df` y `/proc/iomem` mandan. Una RAM sin decimales exacta en `/proc/meminfo` puede ser un redondeo del kernel.

## 4. Respaldar
```powershell
.\scripts\respaldar.ps1 -Serial <IP>:<PUERTO>
```
Requiere `adb root` (firmwares `userdebug`). Lee y copia a `backups\<modelo>-<fecha>\`, sin subir a GitHub.
- **Es lento por el malware:** para el proxy mientras se copia. `am force-stop <paquete>` no modifica nada y se revierte
  al reiniciar. En la caja Rockchip la velocidad se multiplicó por 4.
- **Verifica cada partición:** compara `SHA256.txt` con `adb shell "busybox sha256sum /dev/block/<particion>"`.
- Si el script no conoce los nombres de partición de un chip, pásalos con `-Particiones` (mira `ls /dev/block/by-name`
  o `/dev/block/platform/*/by-name`). No copies `userdata`, `cache` ni `kpanic`.

## 5. Quitar el malware
Este orden evita que lo que borras vuelva, y deja siempre una pantalla de inicio:
```powershell
adb -s <SERIE> install -r build\Aura.apk
adb -s <SERIE> shell "cmd package set-home-activity com.jajua.aura/.HomeActivity"   # probado en Android 7.1
adb -s <SERIE> shell "pm disable <paquete>"     # 1. primero los instaladores y el launcher del fabricante
adb -s <SERIE> shell "pm uninstall <paquete>"   # 2. lo que bajaron, en /data
```
3. **Neutraliza la reinstalación.** El `preinstall.sh` del firmware puede reinstalar todo en cada arranque y lanzar el
   malware. Sustitúyelo por `scripts/preinstall.sh` **sin cambiar su etiqueta SELinux**, y crea la marca:
   ```powershell
   adb -s <SERIE> push scripts\preinstall.sh /data/local/tmp/preinstall.sh
   adb -s <SERIE> remount
   adb -s <SERIE> shell "cat /data/local/tmp/preinstall.sh > /system/bin/preinstall.sh; touch /data/local/symbol_thirdpart_apks_installed"
   ```
4. **Reinicia y verifica** que nada volvió: procesos, `netstat` y `pm list packages -3`.
5. **Borra de `/system`** las carpetas de `/system/app` y `/system/priv-app` y los binarios identificados, y reinicia.
   Un servicio de init cuyo binario falta se desactiva solo, por lo que no hace falta editar los `.rc`. Eso importa en
   las cajas donde `/` es un ramdisk: ahí los `.rc` no se pueden editar de forma permanente.
6. **Antes de borrar algo que parezca abrir ADB, averigua quién lo abre:**
   `grep -rn "adb.tcp\|3222" /init*.rc /vendor/etc/init /system/etc/init`. Si lo abriera el malware, te quedarías fuera.

### Escribir en `/system`
- Monta: `adb remount`. Si falla, `blockdev --setrw <dispositivo>` y `mount -o remount,rw /` (o `/system`).
  En la caja Rockchip `mount -o remount` daba `EBUSY` y sólo `adb remount` funcionó.
- Archivos en uso, como bibliotecas: copia nueva y `mv`, nunca sobrescribir.
- Revisa `ls -lZ`: los archivos nuevos van con `u:object_r:system_file:s0`, y los scripts de init conservan la suya.
- Para pruebas con ADB cerrado, ver paso 11.

Si el chip Wi-Fi deja de aparecer tras muchos reinicios (`mmc2: error -110 … SDIO`): desconecta la corriente 30 s.

## 6. Quitar las falsificaciones
| Dónde | Cómo se ve | Cómo se quita |
|---|---|---|
| `/system/build.prop`, `/vendor/build.prop` | Versión, RAM, fabricante, marca o chip falsos | Edita el valor. **No toques `ro.build.fingerprint`:** Google Play depende de ella |
| `libjavacore.so` (lo que ven las apps) | `StatFs` ≠ `df` | `patch_javacore.py`; instala con copia + `mv` |
| `Settings` y `TvSettings` | Pantallas con 4 GB, 32 GB | `patch_settings.py` y `-Bind` (abajo) |
| `/private/ramrom.config` (HiSilicon) | Ajustes lee "RAM+ROM" y, si falta, inventa 4 GB/32 GB | Escríbelo: `1+3.2` |
| `libaudiopolicymanagerdefault.so` (Rockchip) | El HDMI no declara canales; YouTube supone 8 y los videos con audio 5.1 van a tirones | `fabrica.ps1 -ParcheAudio` (abajo) |
| Kernel, `/proc/meminfo` | Redondeo pequeño (~27 MB) | Se deja: exige recompilar el kernel |

Los parches comprueban cada byte y **abortan sin escribir** si el firmware es otro.

### Parchear `libjavacore.so`
```powershell
adb -s <SERIE> pull /system/lib/libjavacore.so
python scripts\patch_javacore.py libjavacore.so libjavacore.new
```
Reinicia y comprueba que `dumpsys diskstats` coincide con `df`. Si dice "unknown build", busca la constante
`0x1DCD65` (bytes `4c f6 65 5x`) en `makeStructStatVfs`, añade el nuevo build al script y prueba otra vez.

### Parchear `Settings` y `TvSettings`
Comprueba la firma: `apksigner verify --print-certs Settings.apk`. Si **no** es la clave de pruebas de AOSP, Android
rechaza un APK modificado. **No debilites la verificación de firmas.** Los originales se quedan en `/system`, y
`preinstall.sh` monta las copias encima al terminar cada arranque:
```powershell
adb -s <SERIE> pull /system/priv-app/TvSettings/TvSettings.apk
python scripts\patch_settings.py TvSettings.apk p.apk
zipalign -f -p 4 p.apk apks\bind\priv-app@TvSettings@TvSettings.apk
# Igual para Settings. Luego: fabrica.ps1 -Bind apks\bind
```
Si el montaje falla, vuelven los números falsos, pero Android sigue arrancando con los originales.

### Si el build de Ajustes es otro
Desensambla y busca la falsificación:
```powershell
tar -xf Settings.apk classes.dex
dexdump -d classes.dex > settings.txt
Select-String 'const-wide.*(0000000800000000|0000000100000000)|const-wide/high16.*#(41f0|4220)' settings.txt
```
- **Almacenamiento:** la rama era `if (nombre.equals("data"))`. Cambia su `if-eqz` por `goto/16` (primer byte `38` → `29`,
  segundo `00`, offset igual).
- **RAM:** una constante que sobrescribe el valor recién leído de `MemInfoReader`. Cámbiala por `nop` (`00 00`).

Añade esos offsets a `BUILDS` en `patch_settings.py`: son posiciones de archivo como las imprime `dexdump`. Verifica
en pantalla cada dato, sin cierres en `logcat -d -b crash` ni `VerifyError`.

### Audio 5.1 en YouTube (Rockchip)
Síntoma: los videos con audio 5.1 se cortan, con círculo de carga y pocos fotogramas; los estéreo van bien. Compruébalo:
```powershell
adb -s <SERIE> shell "dumpsys media.audio_policy | sed -n '/Available output devices/,/Available input/p'"   # Profiles: vacío
adb -s <SERIE> shell "dumpsys media.audio_flinger | grep -E '^ +[0-9]+ +yes'"   # pista de YouTube con canales 0000003F
```
El sistema de audio de Rockchip añade HDMI, SPDIF y altavoz sin lista de canales, y YouTube lo toma como 8 canales.
`audio_policy.conf` **no** lo arregla: ni las máscaras de la salida `hdmi` ni una sección `devices` llegan a esos
dispositivos. `fabrica.ps1 -ParcheAudio` aplica `patch_audiopolicy.py`, que hace que un dispositivo sin canales informe
estéreo, lo único que entrega el HAL. Comprueba los bytes, no escribe nada si el firmware es otro y avisa si ya estaba
aplicado. Para revertirlo, restaura la biblioteca desde `system.img`.

## 7. Quitar apps del fabricante
Borra de `/system` lo que no se use, **reiniciando y probando después**. Si dudas, déjala.
| Quitar | Conservar |
|---|---|
| Launchers y exploradores del fabricante | `TvSettings`, `Settings`, `SettingsProvider`, `SystemUI`, `Shell` |
| Actualizadores (`LocalUpdate`, `RKUpdateService`) | `LatinIME` (único teclado que debe quedar) |
| Apps de prueba (`StressTest`, `DeviceTest`, `DyFactoryTest*`, `Videoplayercustom`), videos de prueba | `PackageInstaller`, `DefaultContainerService`, `DownloadProvider`, `MediaProvider` |
| `PinyinIME`, `Gallery2`, `PicoTts`, `BasicDreams`, `PrintSpooler`, `Katniss` | Servicios de Google y `Phonesky`, si usas Play |
| El WebView que **no** esté activo (`settings get global webview_provider`) | El WebView activo |
| Samba y binarios de prueba | `HiRMService` y `HiVideoPlayer` en HiSilicon: **sin ellos no hay video** |

- `DocumentsUI`: su icono ya lo oculta Aura. Si la borras, ninguna app podrá abrir el selector de archivos del sistema.
- Antes de quitar un teclado, comprueba `settings get secure default_input_method`.
- Si `/system/app/<Carpeta>` existe, pero su paquete no sale en `pm list packages`, también es basura y se puede borrar.

## 8. Dejarlo "de fábrica"
1. Edita `scripts/aura-defaults.properties`: favoritas y botones. En una caja recién limpiada, quita antes el Aura
   temporal del paso 5 (`pm uninstall com.jajua.aura`), para que quede sólo la copia de sistema.
2. Lanza:
   ```powershell
   .\scripts\fabrica.ps1 -Serial <IP>:<PUERTO> -Apks apks\fabrica -Bind apks\bind -ParcheAudio -Idioma es-MX -ZonaHoraria America/Mexico_City -Overscan 100
   ```
   Pone Aura en `/system/priv-app`, tus apps en `/system/preinstall` (**borra lo que hubiera ahí**), `preinstall.sh`, la
   animación de arranque y los ajustes. `-Probar` instala las apps sin esperar a un formateo. Quita las opciones que no
   apliquen: `-Overscan` y `-ParcheAudio` sólo se han probado en Rockchip. Se detiene si el firmware no tiene mecanismo
   de preinstalación.
3. Reinicia. Las favoritas y botones de fábrica se importan en un inicio limpio: compruébalos tras el formateo del
   paso 10.

Sin `/system/preinstall` ni servicio `preinstall`, el script se detiene. Adapta el método o instala las apps a mano.

**Logo de arranque.** `bootanimation.zip` es sólo la animación de Android (en `/system/media`). Pruébala sin reiniciar:
```powershell
adb -s <SERIE> shell "setprop service.bootanim.exit 0; start bootanim"
adb -s <SERIE> shell "setprop service.bootanim.exit 1; stop bootanim"
```
Antes de ella puede salir un logo del cargador, en una partición aparte (`resource`, `logo`). **No la toques:** un error
ahí puede dejar la caja sin arrancar.

## 9. Revisar imagen, sonido y control
**Imagen por HDMI (Rockchip).**
```powershell
adb -s <SERIE> shell "cat /sys/class/display/HDMI/mode; cat /sys/class/display/HDMI/modes"
adb -s <SERIE> shell "cat /sys/class/graphics/fb0/disp_info; getprop persist.sys.overscan.main"
```
Si `dsp_x`/`dsp_y` son menores que la salida (1845×1038 en 1080p), hay overscan: se ve con márgenes y borroso. Usa
`-Overscan 100`. Si la TV corta el borde, ponla en *Ajuste de pantalla* (*Screen Fit*).

**Video.** Reproduce algo y busca el decodificador de hardware: `logcat -d | grep -i omx`. Debe salir un `OMX.<chip>...`.
Las apps de prueba o los servicios de video mal borrados se notan aquí. Prueba también un video con audio **5.1** (por
ejemplo *Big Buck Bunny 60fps 4K*, `aqz-KE-bpKQ`): uno estéreo puede ir perfecto aunque falle el 5.1. Mide los
fotogramas reales con el nombre exacto de la capa:
```powershell
adb -s <SERIE> shell "dumpsys SurfaceFlinger --latency 'SurfaceView - com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.MainActivity'"
```
Son 127 tiempos en nanosegundos: deben avanzar 1/fps cada vez, sin huecos de segundos. El número que sale junto al
nombre de la capa en `dumpsys SurfaceFlinger` **no** son los fps.

**Control remoto.** No todos los botones existen en todos los controles:
```powershell
adb -s <SERIE> shell "getevent -pl"                     # dispositivos y teclas que declara cada uno
adb -s <SERIE> shell "cat /system/usr/keylayout/<archivo>.kl"
adb -s <SERIE> shell "sendevent /dev/input/eventN 1 <codigo> 1; sendevent /dev/input/eventN 0 0 0; sendevent /dev/input/eventN 1 <codigo> 0; sendevent /dev/input/eventN 0 0 0"
```
`input keyevent` no pasa por el filtro de botones de Aura: para probarlos usa `sendevent`. Si un botón no abre su app,
comprueba que el dispositivo declara esa tecla. Si el servicio está apagado, ver "Problemas" abajo.

**Control de la TV (HDMI-CEC).** Actívalo en la TV (Samsung *Anynet+*, LG *SimpLink*…) y revisa
`adb shell dumpsys hdmi_control`. Si aparecen `<User Control Pressed>` respondidos con `<Feature Abort>`, el firmware
bloquea las teclas: `.\scripts\control-tv.ps1 -Serial <IP>:<PUERTO>`. Detalles en `cec-bridge/README.md`.

## 10. Formatear y verificar
Pide permiso: borra cuentas, Wi-Fi y datos de las apps. Hazlo **por Ethernet**. Puedes usar Ajustes › Restablecer, o en
Android 7.1:
```powershell
adb -s <SERIE> shell "am broadcast -a android.intent.action.MASTER_CLEAR -n android/com.android.server.MasterClearReceiver --receiver-foreground"
```
Tarda varios minutos, y las apps terminan al aparecer `/data/local/symbol_thirdpart_apks_installed`. Comprueba:
- [ ] Logo de Aura al arrancar y Aura como inicio, sin preguntar.
- [ ] `pm list packages -3` tiene sólo tus apps, y `pm list packages` ningún paquete del malware.
- [ ] `netstat -tlnp` sólo muestra ADB; `netstat -tnp`, sólo apps tuyas y de Google. Identifica cualquier otra.
- [ ] Aura › Sistema y Ajustes › Almacenamiento y Memoria muestran datos que coinciden con `df` y el hardware.
- [ ] Favoritas y botones: `cat /data/data/com.jajua.aura/shared_prefs/keymap.xml`.
- [ ] Parches montados: `grep Settings /proc/mounts` y `logcat -d -s preinstall`.
- [ ] Sin cierres: `logcat -d -b crash`.
- [ ] Red abre el panel lateral, y Actualizaciones llega a Aura y responde.
- [ ] Un video con audio 5.1 se reproduce fluido, con su pista en `00000003` (paso 6, "Audio 5.1").
- [ ] Idioma, hora e imagen correctos.

## 11. Cerrar
- **Cierra ADB** si la caja queda en una red compartida: Aura › Sistema › *Cerrar ADB*. Apagar "Depuración USB" no cierra
  el puerto en todos los firmwares. Verifica desde la PC con `Test-NetConnection <IP> -Port <PUERTO>`.
- Se reabre con *Abrir ADB*, desde la TV. **Un formateo lo reabre** (visto en Rockchip), así que revisa ese dato después.
- Al probar este botón, ten a alguien frente a la TV. Un guion de seguridad lanzado por ADB no sobrevive en Android 9:
  al detenerse `adbd`, muere con él.
- **Nunca uses "Actualización del sistema" ni "Local update" del fabricante**: reinstala el firmware con malware.
- Avisa de lo que queda pendiente, por ejemplo parches de 2017: la caja no recibirá más parches de seguridad.

## Actualizar Aura en una caja ya lista
**No uses `fabrica.ps1`**: borra `/system/preinstall`. Desde la 1.1, Aura se actualiza en Sistema › *Buscar
actualizaciones*, confirmando con el control. Esa actualización no cambia la copia de `/system`, que es la que vuelve
tras un formateo. Para cambiarla, o con una caja que tenga la 1.0, a mano:
```powershell
adb -s <SERIE> install -r build\Aura.apk      # conserva favoritas y botones
adb -s <SERIE> remount
adb -s <SERIE> push build\Aura.apk /data/local/tmp/Aura.apk
adb -s <SERIE> shell "cp /data/local/tmp/Aura.apk /system/priv-app/Aura/Aura.apk.new; chown root:root /system/priv-app/Aura/Aura.apk.new; chmod 644 /system/priv-app/Aura/Aura.apk.new; chcon u:object_r:system_file:s0 /system/priv-app/Aura/Aura.apk.new; mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk; sync"
```
No hagas `pm uninstall com.jajua.aura` si quieres conservar los datos.

## Publicar una versión de Aura
1. En `launcher/app/build.gradle.kts`, sube `versionCode` y `versionName`. Android rechaza versiones más bajas.
2. Compila con la llave y comprueba con `apksigner verify --print-certs` que la firma es la de siempre.
3. Publica: `gh release create vX.Y build\Aura.apk --latest`. El archivo **debe** llamarse `Aura.apk` y la etiqueta debe
   ser mayor que la instalada: es lo que lee el actualizador. No publiques llaves, firmware ni APK de terceros.

## Problemas conocidos
| Síntoma | Causa y solución |
|---|---|
| Diagnóstico con "0 paquetes" o red vacía | Firmware que no tiene `awk` o `pm -U`; revisa `diag-remoto.sh` |
| PowerShell se detiene con un error rojo de `adb` | `adb` escribe progreso en stderr; no redirijas la salida con `2>&1` |
| `mount: Device or resource busy` | Usa `adb remount` |
| Ajuste de `settings put` perdido al reiniciar | Espera unos segundos antes de reiniciar |
| Botones de Aura sin efecto | `settings get secure enabled_accessibility_services`: añade el de Aura con `:` **sin quitar** los demás, como `HiRMService` |
| Botones sin efecto tras probar por ADB | `am force-stop com.jajua.aura` hace que Android quite su servicio de accesibilidad, y Aura no lo reactiva solo. No lo uses; si pasó, ver la fila anterior y `accessibility_enabled 1` |
| Videos con audio 5.1 a tirones | Ver paso 6, "Audio 5.1 en YouTube" |
| `netstat -tnp` da `Segmentation fault` | Fallo esporádico de toybox: repite el comando |
| `tail -1` devuelve todas las líneas | El `tail` de toybox de Android 7 no entiende la forma corta: usa `tail -n 1` |
| Un `grep "dos palabras"` por `adb shell` desde PowerShell no encuentra nada | PowerShell 5.1 quita las comillas dobles internas: usa comillas simples dentro del comando |
| Reloj en 1970 al encender | La caja no tiene reloj con batería: se ajusta solo en ~90 s, al llegar a internet |
| Dos apps de Ajustes o selector al abrirlos | Aura usa la de TV; no borres `Settings`, puede tener receptores de HDMI |
| Apps se abren con un aviso de permisos | Normal la primera vez: acéptalo con el control |
| Un cierre de Play Store al arrancar tras formatear | Es su autoactualización: Android 7 le niega la sesión de instalación (`INTENT_PACKAGE_INSTALL_COMMIT`) y sigue con la versión de `/system`. Se repite tras cada formateo en Rockchip y no afecta a tus apps |
| Wi-Fi perdido tras formatear | Normal: se reconecta desde la TV, en Aura › Red |

No sobreviven a un formateo, y es normal: cuentas (Google, Premium de AirScreen), Wi-Fi y datos dentro de cada app.
