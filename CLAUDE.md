# Aura TV Box — instrucciones para Claude Code

Proyecto: launcher Aura y receta para limpiar TV Box Android (malware, falsificaciones) y dejarlas "de fábrica".
La receta completa está en `docs/GUIA-NUEVA-TVBOX.md`; los casos reales en `docs/01`–`04`.
El proceso automático es `Preparar-TVBox.bat` → `scripts/preparar.ps1` (perfiles por firmware en `scripts/perfiles/`).

## Modo vigilante
Se activa cuando `Preparar-TVBox.bat` abre esta sesión, o cuando el usuario dice "vigila la preparación".

Si esta carpeta es `%LOCALAPPDATA%\Aura-TVBox` (el `.bat` suelto, junto a las apps en OneDrive), es una copia bajada de
GitHub que el `.bat` sobrescribe en cada arranque: los arreglos que hagas aquí se pierden. Hazlos para que funcione
esta vez y dile al usuario qué cambiar en el repo. Las apps vienen de `AURA_APKS` (ver `Carpeta-Apps` en preparar.ps1).

1. **Encuentra la ejecución en curso:** el registro más reciente es `logs/preparar-*.log` (antes de conocer la caja) o
   `logs/cajas/<ID>/preparar-*.log`. `logs/cajas/indice.csv` lista las cajas; `logs/cajas/<ID>/estado.json` dice qué
   etapas están hechas, la IP (`Serie`) y el respaldo.
2. **Vigila con Monitor** (no con esperas activas) las líneas que importan: `^====`, `AVISO`, `DETENIDO`,
   `Firmware NUEVO`, `fallas\.`, `Terminado`. El registro cambia de carpeta cuando se conoce el ID: sigue el más nuevo.
   Re-arma el monitor si expira y el proceso sigue.
3. **Si aparece `DETENIDO`:** lee el final del registro y `estado.json`, encuentra la causa, corrige el script y
   relánzalo; retoma solo desde la etapa que falló:
   `Start-Process powershell -WorkingDirectory <repo> -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','<repo>\scripts\preparar.ps1','-Ip','<IP>'`
   Nunca lances dos a la vez. Si el fallo es de la caja y no del script, explícaselo al usuario en vez de forzarlo.
   Si dudas de un cambio, pruébalo antes con `-Simular`.
4. **`Firmware NUEVO`:** el diagnóstico y el respaldo están hechos. Explícale al usuario lo que hay y sigue la guía
   desde el paso 5 **pidiendo autorización** antes de borrar, escribir en `/system` o formatear. Al terminar, crea el
   perfil nuevo en `scripts/perfiles/`.
5. **`Terminado`:** resume el informe `verificacion-*.md` (fallas y avisos) y recuerda: aceptar los permisos de las apps
   en la TV, comprobar imagen y sonido, y cerrar el ADB en Aura › Sistema › *Cerrar ADB*.
6. Al final, lista los cambios que hiciste en el repo para que el usuario los revise. **No hagas `git push`.**

## Reglas
- No toques otras cajas de la red ni una IP que el usuario no haya dado.
- Nada personal en el repo: IP, MAC, nombres de red, contraseñas, IDs de caja reales. `logs/`, `backups/` y `apks/`
  no se suben.
- Commits en este repo: autor `jorgejbz <158005073+jorgejbz@users.noreply.github.com>` y sin atribución a Claude. No uses
  `gh pr merge` (firma con el nombre real): fusiona en local con avance rápido.
- Scripts `.ps1`: UTF-8 **con BOM** y CRLF (PowerShell 5.1).

## Trampas ya conocidas (PowerShell 5.1 y Android 7)
- Las variables no distinguen mayúsculas: `$P` y `$p` son la misma. Tampoco uses `$home` ni una función llamada `R`
  (alias de `Invoke-History`).
- `adb shell "..."` desde PowerShell pierde las comillas dobles internas: usa comillas simples dentro.
- `tail -1` de toybox imprime todo: usa `tail -n 1`. `netstat -tnp` a veces da `Segmentation fault`: repite.
- Tras `adb root`, la conexión por red tarda en volver: reintenta.
- `am force-stop com.jajua.aura` apaga el servicio de botones de Aura: no lo uses.
- Los videos con audio 5.1 a tirones en Rockchip se corrigen con `patch_audiopolicy.py` (`fabrica.ps1 -ParcheAudio`).
- `/data/cifsmanager` (Rockchip) queda 777 en cada arranque y un servicio root ejecuta un script de ahí: `preinstall.sh`
  lo cierra. Si `verificar.ps1` marca esa carpeta, la caja tiene un `preinstall.sh` viejo.
- Si un video de la prueba de YouTube da 0 fps sin pista de audio, no arrancó (YouTube quedó en otra pantalla);
  `verificar.ps1` ya reintenta una vez.
- Tras un formateo, `adbd` puede quedar trabado: el puerto acepta pero no contesta y `adb` lo ve `offline` (ni
  reiniciar la caja ni *Cerrar/Abrir ADB* de Aura lo arreglan). Se destraba apagando y encendiendo *Depuración USB*
  en la TV; luego retoma con `-Desde verificacion` (no repitas el formateo).
- Una caja ya preparada en otra PC (sin `estado.json`) no se rehace: `preparar.ps1` detecta en la caja si limpieza,
  falsificaciones y fábrica ya están hechas y las salta.
