# Aura 1.4 — Recomendaciones y «Reinstalar Xuper’s»

Estado: probado en local en una caja Rockchip RK3228A (Android 7.1). **No publicado.**

## 1. Pantalla de recomendaciones

- `RecommendationsActivity` (nueva). Pantalla completa con el fondo de Aura, dos columnas de tarjetas:
  A · Magis TV / Xuper (4.34.5, tercera de Favoritas), B · Xuper Crystal Clear, C · XuperAmigOnlyTV
  (incluye «cada cuenta de Telegram puede tener 2 dispositivos vinculados»), D · Stremio (inicio de sesión con el
  QR o el enlace link.stremio.com, registrarse o iniciar sesión, el código dura 5 min; nota: addons por Telegram con
  el contacto del dueño), E · Instalar otras apps.
- Cada tarjeta de app muestra la versión recomendada y la instalada (verde si coincide, ámbar si no).
- Control remoto: ▲▼ desplaza el texto; ◀▶ cambia entre «No volver a mostrar» y «Continuar» (enfocado al abrir);
  Atrás cierra sin guardar.
- **Cuándo aparece sola:** una vez por arranque de la caja (contador `Settings.Global.BOOT_COUNT`), la primera vez
  que se abre el inicio de Aura. No vuelve a salir si Android cierra Aura por falta de memoria a mitad del día.
  En Android < 7.0 (sin contador) sale una vez por proceso.
- **Preferencia:** `shared_prefs/recommendations.xml` → `hide_on_start=true` solo si se marca la casilla **y** se
  pulsa «Continuar». Sobrevive a reinicios; un formateo la borra (vuelve a mostrarse). Sin el archivo = mostrar.
- **Botón «Recomendaciones»:** píldora junto al estado de red (Ethernet) en la cabecera; se alcanza con ▲ desde la
  fila de Favoritas. Siempre disponible.
- Las versiones recomendadas están en `RecommendationsActivity.XUPER_VERSION` / `XuperApps.CRYSTAL_REAL_VERSION`.
- **Versión real de Crystal Clear:** su manifiesto dice 4.34.4 (lo que ve Android), pero la app muestra 4.99.2.
  Aura busca la cadena exacta `4.99.2` en los `classes*.dex` del APK instalado (en dex cada cadena es
  longitud + texto + 0); si está, muestra 4.99.2, si no, la versión de Android. Se calcula en segundo plano y se
  recuerda hasta que la app cambie. La tarjeta avisa: si la app pide iniciar sesión, no es la 4.99.2 → reinstalar.
- **Abierta con el botón:** solo «Continuar» (sin casilla ni nota). La casilla solo sale en el aviso al iniciar.

## 2. Reinstalar Xuper’s

- Botón en Aura › Sistema, junto a «Buscar actualizaciones» (se añade a la barra de botones del diálogo con el
  mismo estilo). Abre `ReinstallActivity` (nueva): elegir **Xuper Crystal Clear** o **Xuper (Magis TV)**, una a la vez.
- Lógica en `XuperApps` (nueva). Origen fijo: Release con etiqueta **`xupers`** del repo de Aura:

  ```
  https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/XuperCrystalClear.apk
  https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/Xuper.apk
  https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/SHA256SUMS.txt
  ```

  Son enlaces permanentes (GitHub redirige cada vez a una dirección temporal). No se usa la API ni `latest`.
  `defaults.properties` puede cambiar el origen con `xupers.release=dueño/repo/etiqueta`.
- **La Release `xupers` debe ser pre-release**: si fuera una release normal, `releases/latest` (la actualización
  de Aura) la tomaría como la última versión de Aura.
- Pasos: confirmar → comprobar espacio → bajar `SHA256SUMS.txt` (obligatorio) → bajar el APK a
  `cache/xupers/*.part` calculando SHA-256 → comprobar tamaño completo y hash → renombrar → comprobar nombre de
  paquete (`com.xuper.crystalclear` / `com.android.mgstv`, no se pueden intercambiar), minSdk, procesador (ABI) y
  firma frente a la app instalada → instalar con `PackageInstaller` encima (conserva datos) → comprobar que la
  versión instalada es la del APK → borrar el archivo.
- Nada se desinstala ni se borra sin que el usuario lo pida:
  - **Firma distinta** (otra Xuper bajada de otra página): explica que hay que desinstalar y que se pierden datos y
    sesión; «Desinstalar y continuar» abre la pantalla de desinstalación de Android y luego instala. Foco en «Cancelar».
  - **Versión instalada más nueva:** pide confirmación y vuelve a la recomendada **conservando datos**
    (`INSTALL_ALLOW_DOWNGRADE`; Android solo lo permite en firmwares `userdebug`, como estas cajas). Si Android lo
    rechaza, ofrece la misma ruta de desinstalar con aviso.
- «Cancelar» corta la descarga y borra el parcial. Una sola reinstalación a la vez. Sin permisos nuevos.
- Pruebas en banco: `setprop debug.aura.xupers.base http://<pc>:<puerto>/` cambia el origen (solo root/shell).
  La versión normal no permite HTTP; para probar con un servidor local hay que compilar con
  `usesCleartextTraffic="true"` temporalmente.

## 3. Arreglos encontrados al probar

- `AppLoader`: el icono de Crystal Clear es un PNG de 1024 px solo en hdpi; pedido a xxxhdpi se escalaba a ~2700 px
  (30 MB) y Aura se cerraba por falta de memoria al recargar la lista tras instalar esa app. Ahora iconos y banners
  se reducen (512 / 960 px) y, si falta memoria, se usa el icono normal.
- En este firmware el verificador de Play Store está roto: cada instalación espera ~17 s a que caduque. Normal.

## 4. Archivos

Nuevos: `RecommendationsActivity.java`, `ReinstallActivity.java`, `XuperApps.java`, este documento.
Modificados: `HomeActivity.java` (botón, aparición al iniciar, botón en Sistema), `Installer.java` (estado y mensaje
de Android, vuelta a versión anterior), `AppLoader.java`, `Defaults.java` (`xupers.release`), `SystemInfo.java`
(`prop` visible en el paquete), `AndroidManifest.xml` (2 actividades), `strings.xml`, `build.gradle.kts` (1.4 / 14).

## 5. Cambios posteriores (misma 1.4, aún local)

- La pantalla se llama **«Guía de uso»** (botón y título). Junto a ella hay otro botón, **«Recomendaciones»**
  (`RecommendationsActivity` con `EXTRA_CARE`): cuidados de la caja en 6 tarjetas (Internet por Ethernet,
  temperatura y reinicios por calor, control remoto, corriente eléctrica, limpieza, apps y espacio). Solo «Continuar».
- «Reinstalar Xuper’s» muestra en cada opción la versión instalada y la que se descargará «(recomendada para su
  uso)», y lo repite en la confirmación. Esa versión sale de `Target.recommended` (`XuperApps.XUPER_VERSION` /
  `CRYSTAL_REAL_VERSION`): debe coincidir con el APK subido a la Release.
- Prueba real 2026-11-26: Xuper 4.99.20 (misma firma) → 4.34.5 con Aura, conservando datos y sesión (mismas
  preferencias, mismo número de archivos). Xuper 4.34.5 muestra al abrir «Actualización de versión» a 4.99.20: la
  Guía de uso indica elegir «Cancelar».

## 6. Aura 1.4.1 (local)

- «Reinstalar Xuper’s»: si la versión instalada ya es la recomendada, la opción dice «✓ Ya tienes la recomendada para
  su uso (no es necesario reinstalar)» y la confirmación lo explica, con «Cancelar» enfocado y «Reinstalar de todos
  modos» disponible.
- Tercer botón en la cabecera, **«¿No funciona?»** (pantalla «¿No funciona una app?», `EXTRA_SCHEDULE`): de 8:00 a.m. a 10:00 p.m. usar Xuper Crystal Clear
  y Magis TV / Xuper; de 10:00 p.m. a 8:00 a.m. se caen (errores recientes del servicio) y se recomienda Stremio o
  XuperAmigOnlyTV; XuperAmigOnlyTV a veces se cae a las 3:00–4:00 a.m. y vuelve a las 8:00 a.m.
- **Instalaciones abandonadas:** una instalación hecha como root o por adb que se corta (apagón en el primer arranque
  tras formatear, o adb esperando al verificador roto de Play Store) deja el APK completo en `/data/app/vmdl*.tmp`
  (en la caja de pruebas, 2 de Stremio = 176 MB). Android las borra a los 3 días, pero sin reloj nunca pasa.
  `preinstall.sh` ahora las cancela en cada arranque (`pm install-abandon` de las sesiones con installerUid 0 o 2000
  en `/data/system/install_sessions.xml`; las de apps como Play Store o Aura no se tocan; el sed de toybox necesita
  `-E` para la alternancia). Aura no puede hacerlo: Android sólo deja cancelar una sesión a quien la creó o a root.
- `aura-a-sistema.ps1` (lo que usa `Actualizar-Aura-TVBox.bat`) también pone el `preinstall.sh` del repositorio,
  sólo si la caja ya tiene el de Aura TVBox, con `cat >` para conservar la etiqueta SELinux. Probado en la caja de
  pruebas: actualizó el script, reinició bien y Aura conservó el permiso de instalar sin ventana.
- **Aura también limpia (1.4.1, `StaleSessions.java`)**, para las cajas que sólo reciben la actualización de Aura y
  no los scripts: un minuto después de arrancar, revisa `PackageInstaller.getAllSessions()`. Las sesiones sin app
  instaladora (root/adb) que ya existían en un arranque anterior se cancelan con `su 0 pm install-abandon`,
  ejecutado por el adbd de la propia caja (cliente ADB mínimo hacia 127.0.0.1; `su` sólo lo permite a root y shell).
  Si el ADB estaba cerrado lo abre ~4 s y lo vuelve a cerrar. Sólo actúa cuando hay algo que limpiar.
  Ojo: el adbd de estos Rockchip atiende **una conexión a la vez**; con una PC conectada por adb, la limpieza falla
  (sin daño) y se reintenta en el siguiente arranque.
  Probado: con ADB abierto (limpió, 2 s) y con ADB cerrado (el puerto se abrió 4 s, a los ~110 s del arranque, y se
  volvió a cerrar).
- **Aviso «¿Eres nuevo? Consulta aquí →»** (1.4.1): en la cabecera, la fecha se desvanece y en su lugar aparece ese
  texto, con la flecha junto a «Guía de uso», cuyo borde se ilumina un poco. 5 s visible y 5 s la fecha, siempre que la
  pantalla de inicio esté a la vista (se detiene en `onStop`). No toma el foco ni tapa nada. Una burbuja bajo el
  botón se descartó porque se encimaba con el título grande de apps de nombre largo.
- De noche, **de 7:00 p.m. a 8:00 a.m.** (`NIGHT_FROM`/`NIGHT_TO` en HomeActivity), el aviso alterna con **«¿Xuper no carga? Mira
  aquí →»**, que ilumina el botón «¿No funciona?». Fuera de ese horario sólo sale «¿Eres nuevo? Consulta aquí →».
  Usa la hora de la caja: sin internet al encender, las primeras horas pueden ser incorrectas hasta que se sincroniza.

## 7. Aura 1.4.2 (local): aviso de actualización

- Al encender (una vez por arranque, ~8 s después, reintentando ~5 min si aún no hay red) Aura revisa
  `releases/latest`. Si hay una Aura más nueva, en cuanto la pantalla de inicio está al frente aparece
  `UpdateNoticeActivity` (grande): «Nueva versión de Aura X», la versión actual, las novedades de la Release
  (texto desplazable con ▲▼, alto máximo para no tapar los botones) y «Más tarde» / «Actualizar ahora» (enfocado).
- «Actualizar ahora» abre `UpdateActivity` con `EXTRA_INSTALL_NOW`: descarga e instala directo (sigue comprobando
  paquete, versión y firma).
- «Más tarde»: queda un botón chiquito ámbar **«↑ Aura X»** en la cabecera (junto a ¿No funciona?) hasta que se
  actualice; también lo muestra el revisado diario. Con ese botón el aviso «¿Eres nuevo?» usa su texto corto si no cabe.
- Las novedades se guardan en `apps_catalog` (`aura_notes`) al revisar GitHub.
- Probado con una compilación marcada 1.4.0 contra la 1.4.1 real publicada: aviso al iniciar, «Más tarde» → botón,
  «Actualizar ahora» → instaló la 1.4.1 de GitHub sin ventana. Con la 1.4.2 (sin versión más nueva) no sale nada.
  Sólo se verá de verdad cuando se publique una versión posterior a la que tenga la caja (p. ej. 1.4.3).
- Las novedades del aviso grande que no caben se desplazan solas: esperan 3 s, bajan despacio hasta el final,
  esperan 2.5 s, regresan arriba y repiten. ▲▼ toma el control a mano; tras 8 s sin teclas vuelve a desplazarse.
- Con el botón «↑ Aura» en la cabecera, el aviso «¿Eres nuevo? Consulta aquí →» se muestra completo en dos líneas
  (nunca recortado) y la fecha pasa a su forma corta («Jue 26 de nov») si no cabe.
- **Apps de la carpeta en línea («Buscar apps nuevas» / aviso diario):** lista con casillas, ninguna marcada; OK marca
  o desmarca; «Instalar (n)» instala sólo las elegidas y está desactivado sin selección. Probado en la caja con VLC.

### 1.4.2 — cuidado de la caja

- **Soporte con QR** (`QrCode.java`, generador propio, probado contra valores del estándar ISO 18004): tarjeta
  «Soporte» en la Guía de uso y en «¿No funciona?», y QR propio en la tarjeta de Stremio. Cada QR abre Telegram con
  @jorge_jbz y un mensaje ya escrito (`BoxCare.MSG_SUPPORT` / `MSG_ADDONS`), para saber que viene de un cliente.
- **Aviso de Xuper cambiado:** ~20 s tras encender, si Magis TV o Crystal Clear no están en la versión recomendada,
  sale «X cambió de versión» con «Regresar a la …» → `ReinstallActivity` directo a esa app (`EXTRA_PKG`). Una vez por
  encendido y versión. Probado: 4.99.20 → 4.34.5 descargada de la Release `xupers` real.
- **Aviso de calor:** con el reloj ya en hora, se anota cada encendido; 3 en 30 min → «Tu TV Box se reinició varias
  veces» (como máximo cada 2 h), con botón a Recomendaciones. Probado con 3 reinicios.
- **Probar mi internet** (botón en «¿No funciona?»): ~8 s de descarga del `Xuper.apk` de la Release `xupers` (el
  servidor de pruebas de Cloudflare respondía 403). Resultado con colores: ≥15 Mbps excelente, ≥5 HD, ≥2 lento.
- **Liberar espacio** (Sistema, bajo «Almacenamiento»): borra la caché de todas las apps (no datos ni sesiones).
  Usa `CLEAR_APP_CACHE` si Aura lo tiene (sólo cuando la copia de /system ya es 1.4.2+); si no, `su 0 pm trim-caches`
  por el adbd local, con reintentos (adbd atiende una conexión a la vez). Probado: borró 50 MB de caché de prueba.
- **Aviso nocturno** (7:00 p.m.–8:00 a.m.): al encender (esperando a que el reloj esté en hora) y **cada vez que se
  regresa a Aura desde otra app** (no desde pantallas propias de Aura; nunca dos veces en menos de 1 min). Dice que
  desde las 10 p.m. Crystal Clear y Magis TV *pueden* dejar de funcionar y que puede usar XuperAmigOnlyTV o Stremio.
  Casilla «Enterado, no volver a mostrar» (`box_care` → `night_hidden`). Botones: «Ver recomendaciones» (¿No
  funciona?), «¿Tienes dudas?» (mensaje flotante con el QR de soporte) y «Entendido».
- **Al regresar de una app** también sale el aviso grande de actualización si hay una Aura más nueva pendiente (además
  del de cada encendido; nunca dos veces en menos de 1 min). Orden: actualización → nocturno → reinicios/Xuper.
  Probado con la compilación «1.4.0»: encender de noche (nocturno → actualización), «Más tarde» sin repeticiones, y
  volver de YouTube (actualización → nocturno).
- **Tutorial de Botones** («¿Cómo funciona? (tutorial)», primera opción de Botones, arriba de «Asignar un botón…»):
  foto del control (`res/drawable-nodpi/remote_photo.jpg`, recortada de la foto del producto; los 4 botones de arriba
  de los de colores se editaron para decir YTB, PVD, GOG, DIN; el recorte sin editar está en
  `backups/remote_photo_orig.jpg`) con un marco ámbar que late sobre esa fila, los pasos, y «Asignar un botón» directo.
- **Sin internet:** cada minuto, con la pantalla de inicio a la vista, Aura pide `https://www.google.com/generate_204`
  (o GitHub si falla). Sin red, o con red pero sin internet (dos intentos), la línea de estado cambia a
  «Sin internet · Revisa el cable o el módem» en rojo; vuelve sola cuando regresa la conexión. Probado bloqueando
  80/443 con iptables en la caja.
- **Texto más grande** (Sistema): los textos de Aura crecen 15 % (`Ui.textPx`, preferencia `display/big_text`); la
  pantalla se reconstruye al instante. Los diálogos del sistema (AlertDialog) conservan su tamaño.
- **Resolución HDMI al máximo:** el firmware deja la salida fija de fábrica (`hdmi.vic=16`, 1080p60, en baseparamer),
  así que una TV 4K se quedaría en 1080p. Aura lee `/sys/class/display/HDMI/modes` (lo que aceptan TV y chip) y, al
  encender (25 s) y cada vez que se vuelve al inicio, si `mode` no es el mejor (más píxeles, progresivo, más Hz) lo
  escribe como root por el adbd local. Probado: bajado a 720p a mano → Aura lo regresó a 1080p (el máximo de esta TV).
  Los menús de Android siguen en 1280×720 (lo fija el kernel/fb0 del fabricante; no se toca); los videos salen a la
  resolución HDMI. Sistema ahora muestra «1920 × 1080 · 60 Hz (menús 720p)».
