# TVBox Apple Launcher

Herramientas para convertir una TV Box Android genérica en un equipo limpio, honesto y minimalista:
el launcher **Aura** (estilo Apple TV, inspirado en [Lumen](https://github.com/jacksonjao/lumen)) más
scripts para diagnosticar firmware falsificado/malware, respaldar, limpiar y dejar la configuración "de fábrica".

**¿Caja nueva? Empieza por [docs/GUIA-NUEVA-TVBOX.md](docs/GUIA-NUEVA-TVBOX.md).**

## Aura
Launcher Android nativo (Java, sin dependencias, Android 5.0+, ~60 KB) para control remoto:
- Favoritas grandes, accesos (Archivos, Ajustes, Red, Botones, Sistema) y todas las apps; fondo que toma el color de la app.
- **Botones**: asigna apps a los botones del control (servicio de accesibilidad que sólo filtra teclas).
- **Sistema**: datos reales del hardware leídos del kernel (no del framework, que algunos firmwares falsifican).
- No muestra apps que no existen ni repite las que ya tienen acceso propio.
- Si está en `/system/priv-app`, restaura favoritas/botones desde `/system/etc/aura/defaults.properties` tras un formateo.

Compilar: `cd launcher; .\gradlew.bat assembleRelease` (JAVA_HOME → Android Studio\jbr). La firma de release
sale de `launcher/keystore.properties` (no incluida en el repo); sin ella se usa la llave de depuración.

## Scripts (`scripts/`)
| Script | Para qué |
|---|---|
| `diagnostico.ps1` | Encuentra la caja, detecta ADB falso, compara datos anunciados vs reales, marca malware/proxy/puertas traseras. Sólo lectura. |
| `respaldar.ps1` | Copia las particiones críticas (requiere `adb root`). |
| `fabrica.ps1` | Aura como app de sistema + tus apps en `/system/preinstall` para que sobrevivan a un formateo. |
| `preinstall.sh` | Reemplazo del preinstalador del firmware con soporte para APK divididos. |
| `patch_javacore.py` | Quita la falsificación de almacenamiento de `libjavacore.so` (verifica bytes; aborta si el build es otro). |
| `capturar.ps1` / `ui-estado.ps1` | Capturas de pantalla y estado de la UI para probar. |

## Documentación
- `docs/01-diagnostico.md`, `02-launcher-aura.md`, `03-limpieza-y-datos-reales.md`: historial de la caja 1.
- `docs/capturas/`: pruebas en emulador y en la caja real.

No se incluyen (ver `.gitignore`): respaldos de firmware, APK de terceros, la llave de firma ni logs.
