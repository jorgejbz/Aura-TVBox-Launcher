# Control de la TV → TV Box (puente HDMI-CEC)

Permite manejar la TV Box con **el control remoto de la TV**, a través de HDMI-CEC
(Samsung lo llama *Anynet+*, LG *SimpLink*, Sony *Bravia Sync*…), en firmwares que lo bloquean.

## El problema
En algunos firmwares, el fabricante modificó `HdmiCecLocalDevice.handleUserControlPressed` para que
**descarte las teclas** que manda la TV: llegan al hardware pero la caja responde `<Feature Abort>` y no hace nada.
Se reconoce en `logcat` por la línea `W HdmiCecLocalDevice: handleUserControlPressed return`.

## La solución (sin tocar el núcleo de Android)
`CecBridge` es un programa Java pequeño que corre en segundo plano (`app_process`, usuario `shell`):
1. Lee del registro de HDMI cada `<User Control Pressed>` que llega de la TV.
   El servicio activa `log.tag.HDMI=D` al arrancar; el registro normal descarta pulsaciones repetidas en 20 s.
2. Lo traduce a la tecla de Android equivalente y la inyecta con `InputManager.injectInputEvent`,
   igual que el comando oficial `input`.

Retraso medido: **2–4 ms** desde que llega la tecla al hardware hasta que Android la recibe.

## Probado y funcionando
| Equipo | Modelo |
|---|---|
| TV | **Samsung UN50TU7000FXZX** (serie TU7000, 2020), Anynet+ activado |
| Control de la TV | **Samsung BN59-01259B** |
| TV Box | HiSilicon **Hi3798MV300H**, Android 9 (firmware "TVBOX") |

Con otras TV/cajas puede funcionar igual (el protocolo es estándar), pero no está probado.

### Qué hace cada botón (en esa combinación)
| Botón del control Samsung | En la TV Box |
|---|---|
| Flechas, OK | Navegación |
| Atrás | Atrás |
| **Atrás dos veces rápido** (< 650 ms) | **Inicio de la TV Box** (Aura) |
| Colores (rojo, verde, amarillo, azul) | Menú |
| Play / pausa | Reproducir/pausar (la TV muestra su propio panel de mini botones; se usa desde ahí) |
| Anterior / siguiente del panel | Pista anterior / siguiente |
| Volumen | Lo controla la TV directamente (no pasa por la caja) |
| Encendido | Al apagar la TV, la caja se apaga sola (Anynet+) |

### Limitaciones de esa combinación
- **Netflix, Prime Video, www e Inicio** no llegan a la caja: la TV los usa para sus propias apps y menú.
- **No se detecta "mantener presionado"**: Samsung envía un pulso fijo de ~210 ms aunque sostengas el botón,
  y nunca manda dos teclas con menos de ~470 ms de separación (por eso el doble toque usa 650 ms).
- **Encender la TV no enciende la caja**: este firmware reinicia al salir del reposo (`persist.suspend.mode`
  siempre arranca desde cero), así que nada escucha a la TV mientras está apagada. Se enciende con su propio control.

## Instalar
Requiere un firmware que permita `adb root` y escribir en `/system` (ver la guía principal). Desde la raíz del repo:
```powershell
.\scripts\control-tv.ps1 -Serial <IP_DE_TU_TVBOX>:<PUERTO>
```
Compila `CecBridge`, lo copia a `/system/etc/aura/cecbridge.dex`, instala el servicio `/system/etc/init/aura_cec.rc`
y lo arranca. Al estar en `/system`, sobrevive a un formateo.

Comprobar: `adb shell getprop init.svc.aura_cec` → `running`; `adb logcat -s CecBridge` muestra cada tecla
(`rx 0x02 -> KEYCODE_DPAD_DOWN`). Los códigos que llegan sin asignar aparecen como `(sin asignar)`:
agrégalos en `toAndroid()` de `CecBridge.java`.

Quitar: borrar esos dos archivos de `/system` y reiniciar.
