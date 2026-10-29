# Aura TV Box Launcher · v1.0

Convierte una TV Box Android genérica en un equipo limpio, honesto y minimalista:

- **Aura**: launcher estilo Apple TV (inspirado en [Lumen](https://github.com/jacksonjao/lumen)) para control remoto.
- **Scripts** para detectar firmware que miente (versión, RAM, almacenamiento), ADB falso, malware preinstalado,
  proxys y puertas traseras; respaldar; limpiar; y dejar tu configuración como "de fábrica" (sobrevive a un formateo).

Probado en una caja HiSilicon Hi3798MV300H que se vendía como "Android 11.1, 4 GB/32 GB" y era Android 9 con
1 GB/3,2 GB, un proxy residencial, una puerta trasera root y una cadena de instaladores ocultos
(historial en `docs/01`–`03`).

---

## Sólo quiero el launcher

No hace falta limpiar tu TV Box: Aura se instala como cualquier app (Android 5.0 o superior, cualquier procesador).

**⬇️ [Descargar Aura-1.0.apk](https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/latest/download/Aura-1.0.apk)**
· todas las versiones en [Releases](https://github.com/jorgejbz/Aura-TVBox-Launcher/releases)

Elige una forma de instalarlo:
- **Con una memoria USB** (sin PC conectada): copia el APK a la memoria, conéctala a la TV Box, ábrelo con el
  explorador de archivos de la caja y pulsa *Instalar*. Si pide permiso de "fuentes desconocidas", actívalo
  sólo para ese explorador.
- **Desde la propia TV**: instala una app para descargar archivos (por ejemplo *Downloader* de Play Store),
  escribe el enlace de descarga de arriba e instala.
- **Por ADB desde la PC**: `adb install -r Aura-1.0.apk`.

Después:
1. Pulsa **Inicio** en el control y elige **Aura → Siempre**. En Android 10 o superior también puedes hacerlo en
   *Ajustes › Apps › Apps predeterminadas › App de inicio*. Tu launcher anterior no se borra: puedes volver a él ahí mismo.
2. Para usar los botones del control con tus apps, abre Aura › **Botones** › *Activar el servicio* y enciende
   **"Botones del control (Aura)"** en *Ajustes › Accesibilidad*. Sólo recibe pulsaciones de teclas; no lee la pantalla.
3. Mantén **OK** (o pulsa **Menú**) sobre una app para añadirla a Favoritas, moverla o ver su información.

> Si tu caja muestra datos raros (dice tener más RAM o una versión de Android que no cuadra), la tarjeta
> **Sistema** de Aura te enseña los datos reales. Si quieres ir más allá y quitar malware de fábrica, sigue la
> guía de abajo.

## ¿Usas Claude Code?
Copia el contenido de **[PROMPT-CLAUDE-CODE.txt](PROMPT-CLAUDE-CODE.txt)**, completa los datos de tu caja y pégalo en
Claude Code: seguirá esta guía paso a paso, pidiéndote permiso antes de cada cambio importante.

## Inicio rápido: una TV Box nueva

> Cada caja es distinta. **El diagnóstico decide qué pasos aplican**; no des por cierto lo que dice la caja.
> Detalle de cada paso: **[docs/GUIA-NUEVA-TVBOX.md](docs/GUIA-NUEVA-TVBOX.md)**.

### 0. Preparar la PC (una vez)
1. Instala **Android Studio** (trae `adb`, `aapt2`, `apksigner` y Java).
2. `JAVA_HOME` de usuario → `C:\Program Files\Android\Android Studio\jbr`.
3. `git clone https://github.com/jorgejbz/Aura-TVBox-Launcher` (o usa tu carpeta local).

### 1. Conectar la caja
En la TV: *Ajustes › Preferencias › Información* → pulsa 7 veces **Compilación** → *Opciones de desarrollador* →
**Depuración USB**. Conecta la caja a la misma red que la PC (Ethernet es lo más estable).

### 2. Diagnosticar (sólo lectura)
```powershell
.\scripts\diagnostico.ps1            # busca la caja en tu red; o: -Ip <IP_DE_TU_TVBOX>
```
Deja un informe en `logs/` y marca con ⚠️: ADB falso, versión/RAM/almacenamiento falsificados, apps instaladas en
silencio, apps con permisos de sistema, servicios root escuchando y apps con muchas conexiones (proxy).
Si no encuentra ADB: cable USB con datos al puerto OTG de la caja (ver guía).

### 3. Respaldar (antes de tocar nada)
```powershell
.\scripts\respaldar.ps1 -Serial <IP_DE_TU_TVBOX>:<PUERTO>
```
Guarda el respaldo fuera de GitHub. Necesita un firmware que permita `adb root` (los `userdebug`).

### 4. Limpiar
Según el informe y la guía: primero deshabilitar los **instaladores** ocultos, luego desinstalar lo que bajaron,
reiniciar y volver a diagnosticar. Borrar de `/system` sólo con respaldo y sabiendo qué es cada cosa.

### 5. Instalar Aura
Descarga `Aura-1.0.apk` de **[Releases](https://github.com/jorgejbz/Aura-TVBox-Launcher/releases)** (o compílalo) y:
```powershell
adb -s <IP_DE_TU_TVBOX>:<PUERTO> install -r Aura-1.0.apk
```
Pulsa Inicio en el control → **Aura** → *Siempre*. Los botones del control se asignan en Aura › **Botones**.

### 6. Dejarlo "de fábrica" (opcional)
Edita `scripts/aura-defaults.properties` (favoritas y botones) e instala antes tus apps en la caja; luego:
```powershell
.\scripts\fabrica.ps1 -Serial <IP_DE_TU_TVBOX>:<PUERTO> -CopiarDeLaCaja com.stremio.one,com.google.android.youtube.tv -Probar
```
Tras un formateo, la caja arrancará en Aura con esas apps. Se detiene sin tocar nada si el firmware no tiene
mecanismo de preinstalación.

### 7. Cerrar
- No uses "Actualización del sistema" / "Local update" del fabricante: reinstala el firmware original.
- Si el ADB por red no pide permiso, apaga la Depuración USB al terminar.

---

## Aura
Launcher Android nativo: Java, sin dependencias, Android 5.0+, ~60 KB.
- Favoritas grandes, accesos (Archivos, Ajustes, Red, Botones, Sistema) y todas las apps sin repetidos.
- Fondo que toma el color de la app enfocada; foco siempre visible; navegación sólo con flechas, OK, Atrás e Inicio.
- **Botones**: asigna apps a botones del control (servicio de accesibilidad que sólo filtra teclas, no lee la pantalla).
- **Sistema**: datos reales leídos del kernel, no del framework (que algunos firmwares falsifican).
- Como app de sistema (`/system/priv-app`) recupera favoritas y botones desde `/system/etc/aura/defaults.properties`.

Compilar: `cd launcher; .\gradlew.bat assembleRelease`.

## Scripts
| Script | Para qué |
|---|---|
| `diagnostico.ps1` + `diag-remoto.sh` | Encuentra la caja, ADB real vs falso, datos reales vs anunciados, malware/proxy/puertas traseras. |
| `respaldar.ps1` | Copia las particiones críticas con SHA-256. |
| `fabrica.ps1` + `preinstall.sh` | Aura como app de sistema y tus apps en `/system/preinstall` (incluye APK divididos). |
| `patch_javacore.py` | Quita la falsificación de almacenamiento de `libjavacore.so` (verifica los bytes; aborta si el build es otro). |
| `capturar.ps1`, `ui-estado.ps1` | Capturas y estado de pantalla para pruebas. |

## Carpeta local (no se sube a GitHub)
| Carpeta | Contenido |
|---|---|
| `llave-firma/` | **Llave de firma de Aura.** Sólo el dueño puede firmar actualizaciones que se instalen encima. Haz copia. |
| `apks/fabrica/` | Copia exacta de las apps usadas (para la siguiente caja). |
| `build/` | `Aura-1.0.apk` firmado. |
| `logs/` | Informes de diagnóstico (contienen IP/MAC de tu red). |

## Licencia y actualizaciones
Código bajo **[licencia MIT](LICENSE)**: puedes usarlo, modificarlo y redistribuirlo libremente conservando el aviso
de copyright. Las versiones oficiales y las actualizaciones las publica sólo el autor en este repositorio, y los APK
oficiales de Aura van firmados con su llave: una copia modificada por terceros no puede instalarse encima de un Aura
oficial. Es una herramienta para tu propio equipo; úsala bajo tu responsabilidad (modificar `/system` puede dejar
una caja sin arrancar si se hace sin respaldo).
