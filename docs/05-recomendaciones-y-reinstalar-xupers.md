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
