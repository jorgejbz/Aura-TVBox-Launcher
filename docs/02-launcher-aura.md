# 02 — Launcher "Aura" (2026-10-28)

Launcher Android nativo (Java, sin librerías externas), inspirado visualmente en Lumen y Apple TV.
- Código: `launcher/` · APK firmado: `build/Aura-1.0.0.apk` (46 KB) · minSdk 21 (Android 5.0+), cualquier ABI.
- Firma release: `llave-firma/aura-release.jks` + `keystore.properties` (fuera de Git). **No perderla**:
  sin ella, las actualizaciones exigen desinstalar Aura.
- Compilar: `cd launcher; .\gradlew.bat assembleRelease` (JAVA_HOME de usuario → Android Studio\jbr).

## Pantalla
- Encabezado: fecha (idioma del sistema), red (Wi-Fi/Ethernet/sin conexión), espacio libre, hora.
- Zona destacada: nombre de la app enfocada; el fondo se tiñe con su color.
- FAVORITAS: tarjetas grandes 16:9. Por defecto se sugieren Stremio, Xuper, Xuper Crystal Clear y otras apps
  multimedia conocidas, **solo si están instaladas**.
- Accesos: Archivos (solo si ZArchiver u otro gestor está instalado), Ajustes y Red (solo si el sistema los abre).
- TODAS LAS APPS: cuadrícula ordenada alfabéticamente; usa el banner de TV de la app cuando existe.
- Escala sobre un lienzo de 960×540 con márgenes seguros del 5% (overscan). Se reconstruye si cambia la resolución.

## Control remoto
- Flechas: mover · OK: abrir · Mantener OK o Menú: opciones (añadir/quitar/mover favorita, info, desinstalar si es de usuario).
- Atrás / Inicio en la pantalla principal: vuelve arriba. Al regresar de una app se conserva el foco.
- Los extremos de cada fila no "saltan" a otras filas.

## Probado en emulador (Android 14, perfil TV 1080p) — capturas en `docs/capturas/`
| Prueba | Resultado |
|---|---|
| Arranque, foco inicial visible | OK (tras corregir el modo táctil) |
| Navegación D-pad, desplazamiento vertical/horizontal | OK |
| Menú de opciones, añadir/mover favoritas, persistencia | OK (tras corregir pérdida de foco al reconstruir) |
| Abrir app (Ajustes) y volver con Inicio | OK |
| Predeterminado + reinicio | OK con el rol HOME (`set-home-activity` NO persistía en Android 14) |
| 720p y 4K | OK (tras añadir reescalado en caliente) |
| Errores en logcat (crash buffer) | Ninguno |

**No probado aún en la TV Box real** (no hay ADB real). Rendimiento real, mando físico y versión de Android pendientes.

## Errores encontrados y corregidos
1. Sin foco al arrancar en modo táctil → `setFocusableInTouchMode(true)`.
2. Al editar favoritas el foco saltaba a la primera tarjeta → se ignora el foco transitorio durante la reconstrucción.
3. → en la última tarjeta saltaba a otra fila → extremos bloqueados.
4. Cambio de resolución no reescalaba → `onConfigurationChanged` reconstruye.
5. Aviso "Viewing full screen" del modo inmersivo → eliminado.
