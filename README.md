# TFG — Escape Room Neurocontrolado: EEG + Robot Social Temi

App Android (Kotlin) que corre en el robot social **Temi** y convierte la señal
EEG de la diadema **MUSE** en un juego de escape room controlado con la mente:
el jugador supera salas manteniendo la calma, escribiendo código Morse con
parpadeos, respondiendo preguntas con gestos de cabeza y combinando parpadeo +
apriete de mandíbula, mientras el robot narra la historia, se mueve por la sala
y guía la experiencia.

## Características

- **Clasificación de estado mental en tiempo real** (STRESS / ATTENTION / CALM /
  NEUTRAL) a partir de las bandas EEG α, β, θ, γ con índices normalizados y
  umbrales calibrables desde la pantalla de Configuración.
- **Escape room por módulos**: 6 tipos de prueba (calma, Morse, sí/no,
  parpadeo+mandíbula, animación del robot, estado mental + vídeo) que se
  combinan libremente para crear historias. 5 niveles de catálogo incluidos.
- **Doble vía de conexión con MUSE**, intercambiable en caliente:
  - **OSC/WiFi** vía la app Mind Monitor (por defecto, sin SDK extra).
  - **Bluetooth directo** con LibMuse SDK (sin teléfono intermediario).
- **Control del robot Temi**: TTS con fallback a TTS Android offline,
  navegación a ubicaciones del mapa, giros e inclinación de cabeza,
  encadenados por callbacks reales (fin de TTS / llegada) con timeouts de seguridad.
- **Editor de niveles integrado** en la app (crear, editar, reordenar,
  duplicar salas, adjuntar vídeos) + editor web standalone
  ([`level-editor/index.html`](level-editor/index.html)). Import/export en JSON
  (solo estructura) o ZIP (con vídeos).
- **Botón de rescate**: si el jugador lleva 30 s atascado en una sala aparece
  "⏭ Saltar sala" para poder avanzar.
- **Logging científico**: cada partida genera automáticamente un CSV con
  ~4 muestras/s de métricas EEG, movimiento, gestos, contexto de juego y
  acciones del robot, listo para análisis.
- **Robustez BCI**: los gestos se ignoran cuando la señal es mala (HSI > 2) o
  mientras el robot habla (+1,2 s de gracia para el eco de los altavoces).
- Funciona también en **cualquier móvil Android** sin hardware Temi
  (modo simulado: las acciones del robot se loguean en Logcat).

## Arquitectura

```
MUSE ──BLE──> Mind Monitor ──OSC/UDP:5000──> OSCReceiver ─┐
MUSE ──────────BLE (LibMuse)───────────> MuseDirectReceiver ─┤  (interfaz MuseReceiver)
                                                            ▼
                                              MuseState (snapshot ~4 Hz)
                                                            ▼
        ┌──────────────────────────── MainActivity (pipeline) ─────────────────────────┐
        ▼                    ▼                      ▼                    ▼              ▼
MentalStateProcessor  HeadGestureDetector     MorseDecoder        SessionLogger   UI/charts
 (α,β,θ,γ → estado)    (gyro → nod/shake)   (blinks → letras)      (CSV)
        └──────────────┬─────────────────────────┘
                       ▼
              EscapeRoomEngine ──RobotAction──> TemiController ──> Robot Temi (SDK)
              (módulos, narrativa)                                  TTS · goTo · turnBy · tiltAngle
```

## Estructura del proyecto

```
app/src/main/java/com/tfg/temieeg/
├── data/        MuseState, MentalState, SessionEntry
├── eeg/         MuseReceiver (interfaz), OSCReceiver, MuseDirectReceiver,
│                MentalStateProcessor, MorseDecoder, HeadGestureDetector
├── game/        EscapeRoomEngine, RoomModule (6 tipos), EscapeRoomDef + catálogo,
│                CustomLevelStorage (JSON/ZIP)
├── robot/       TemiController, NoiseMonitor
├── logging/     SessionLogger
└── ui/          MainActivity, LevelEditorController, HeadDirectionView

level-editor/index.html      Editor de niveles web (exporta JSON importable)
osc_test_sender.py           Simulador de Mind Monitor (probar sin MUSE)
demo_laboratorio.json        Nivel de ejemplo exportado
```

## Compilación

Requisitos: Android Studio (o su JBR como JAVA_HOME) y SDK 34.

```powershell
# Windows / PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug      # APK para móviles normales
.\gradlew.bat assembleTemi       # APK para el robot Temi
```

**Variantes de build** (importante):

| Variante | Destino | Nota |
|---|---|---|
| `debug` | Móvil Android normal | Su manifest elimina `TemiSdkContentProvider` para que la app no se cierre fuera del robot |
| `temi`  | Robot Temi | Conserva el provider; el SDK se inicializa en el robot. Firma debug (instalable directo) |
| `release` | Entrega final | Sin minify |

Antes de instalar en el robot: registrar la app en
https://developer.robotemi.com y sustituir `REEMPLAZAR_CON_API_KEY_TEMI` en
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml).

## Probar sin hardware

```bash
pip install python-osc
python osc_test_sender.py --ip <IP_del_dispositivo> --port 5000 --scenario sweep
```

Escenarios: `stress`, `attention`, `calm`, `neutral`, `sweep` (cicla todos).
La IP del dispositivo se muestra en la pantalla de Configuración de la app.

Con MUSE real vía Mind Monitor: misma red WiFi, configurar en Mind Monitor la
IP del dispositivo y puerto 5000, y activar **todos los elementos** en
OSC Settings (bandas absolutas, horseshoe, blink, jaw clench).

## Datos de sesión

Cada partida exporta automáticamente `game_<nivel>_<timestamp>.csv` al
almacenamiento externo de la app (compartible vía FileProvider). También hay
grabación manual con el botón "Iniciar sesión". Columnas: timestamp, estado,
índices (concentration, mellow, gammaActivity), bandas crudas (α β θ δ γ),
calidad de señal, giroscopio/acelerómetro, eventos de gesto, acción del robot
y contexto de juego (nivel, sala, módulo, si el robot hablaba).
