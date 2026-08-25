# TFG — Escape Room Neurocontrolado: EEG + Robot Social Temi

Aplicación Android (Kotlin) que se ejecuta en el robot social **Temi** y usa la
señal EEG de la diadema **MUSE** como mando de un escape room. El jugador supera
las salas manteniendo la calma, escribiendo código Morse con parpadeos,
respondiendo con gestos de cabeza y combinando parpadeo con apriete de mandíbula.
El robot narra la historia, se desplaza por el mapa y encadena sus acciones con el
avance del juego.

La interfaz está fijada en horizontal para la tablet del robot (1280×800). La app
también se ejecuta en cualquier móvil Android: sin hardware Temi las acciones del
robot no se ejecutan, se registran en Logcat.

---

## Cómo funciona

### Recorrido de la señal

```
MUSE ──BLE──> Mind Monitor ──OSC/UDP:5000──> OSCReceiver ─┐
MUSE ──────────BLE (LibMuse)───────────> MuseDirectReceiver ┤  (interfaz MuseReceiver)
                                                            ▼
                                              MuseState (snapshot ~4 Hz)
                                                            ▼
                                        EegViewModel (dueño del pipeline)
        ┌──────────────────┬──────────────────┬─────────────────┬──────────────┐
        ▼                  ▼                  ▼                 ▼              ▼
MentalStateProcessor  HeadGestureDetector  MorseDecoder   SessionLogger   MainActivity
 (α,β,θ,γ → estado)   (gyro → nod/shake)  (parpadeos→letra)   (CSV)        (pantallas)
        └──────────────────┴──────────────────┘
                       ▼
              EscapeRoomEngine ──RobotAction──> TemiController ──> SDK de Temi
              (módulos, narrativa)                                 TTS · goTo · turnBy · tiltAngle
```

Los dos receptores implementan la misma interfaz `MuseReceiver`, así que la vía de
conexión se cambia en caliente desde Configuración sin reiniciar la app. El
pipeline vive en `EegViewModel`, de modo que sobrevive a los cambios de
configuración de Android.

### Clasificación del estado mental

`MentalStateProcessor` convierte las bandas absolutas (log-potencia) a dominio
lineal y calcula tres índices normalizados sobre el total α+β+θ+γ:

- `mellow` = α / total — dominancia alpha, asociada a la calma.
- `concentration` = β / total — dominancia beta, asociada a alerta o estrés.
- `gammaActivity` = γ / total — foco cognitivo activo.

Sobre la media móvil de esos índices se decide el estado, en este orden:

| Estado | Condición |
|---|---|
| `STRESS` | `mellow < umbralEstrés` |
| `ATTENTION` | `concentration > umbralAtención` **y** `gammaActivity > umbralGamma` |
| `CALM` | `mellow > umbralCalma` |
| `NEUTRAL` | resto de casos |

Gamma participa en la decisión porque beta alto aparece tanto en estrés como en
atención: exigir gamma alto separa el foco real de la beta de ansiedad. Las
muestras con calidad de señal mala (horseshoe > 3) se descartan antes de entrar en
los buffers.

### Gestos y Morse

- **Asentir y negar**: `HeadGestureDetector` busca dos picos consecutivos de signo
  opuesto en el giroscopio dentro de una ventana de 800 ms. El eje Y (pitch)
  detecta el asentimiento y el eje Z (yaw) la negación. Si el eje Z está activo se
  descarta el estado del asentimiento, para evitar solapamiento entre ambos.
- **Morse**: `MorseDecoder` interpreta un parpadeo aislado como punto y dos
  parpadeos rápidos (menos de 500 ms) como raya. Un silencio de 1,5 s cierra la
  letra y uno de 3 s inserta un espacio. Al entrar en una sala Morse el motor baja
  el antirrebote de parpadeo de 500 ms a 150 ms para permitir el doble parpadeo.

### Salas y motor de juego

`EscapeRoomEngine` ejecuta cualquier `EscapeRoomDef`, que es una lista ordenada de
`RoomModule`. Hay seis tipos de sala:

| Módulo | Prueba |
|---|---|
| `CalmModule` | Mantener `CALM` durante N segundos seguidos |
| `MorseModule` | Escribir en Morse la letra que pide el robot |
| `YesNoModule` | Responder preguntas asintiendo o negando, con confirmación doble |
| `BlinkClenchModule` | Parpadear y después apretar la mandíbula antes de que expire la ventana |
| `RobotAnimModule` | Sala sin prueba: el robot actúa y la sala avanza sola |
| `VideoStateModule` | Mantener un estado mental mientras se reproduce un vídeo |

El ciclo es `load() → start() → [módulos en orden] → onCompleted()`, con `abort()`
disponible en cualquier momento. El motor encadena las acciones del robot con los
callbacks reales del SDK (fin de TTS, llegada a destino), no con retardos fijos, y
mantiene temporizadores de seguridad por si el callback no llega.

Dos comportamientos protegen la detección: los gestos se ignoran cuando la calidad
de señal es mala (HSI > 2) y mientras el robot habla, con 1,2 s de margen añadido
para que el eco de los altavoces no genere parpadeos falsos. Si el jugador lleva
30 s en la misma sala aparece el botón «Saltar sala».

---

## Requisitos

- Android Studio (o su JBR como `JAVA_HOME`) con **SDK 34**.
- **NDK 27.1.12297006**, instalable desde el SDK Manager de Android Studio. El
  proyecto lo declara en `app/build.gradle`; sin él AGP no encuentra `llvm-strip`
  y empaqueta `libmuse_android.so` con símbolos de depuración, lo que hace pasar
  la librería de 6,7 MB a 53,7 MB por arquitectura.
- `libmuse_android.jar` o `.aar` en `app/libs/` y los `.so` en
  `app/src/main/jniLibs/`. El SDK de LibMuse no está en Maven; se descarga de
  https://github.com/choosemuse/LibMuseAndroid
- Diadema MUSE. Para la vía OSC, además la app **Mind Monitor** en un móvil.

---

## Configuración

### 1. Clave de API de Temi

Registrar la aplicación en https://developer.robotemi.com y sustituir el valor
`REEMPLAZAR_CON_API_KEY_TEMI` en
[`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

```xml
<meta-data
    android:name="com.robotemi.sdk.metadata.SKILL_API_KEY"
    android:value="TU_CLAVE_AQUI" />
```

Sin esta clave el SDK no se inicializa en el robot. En un móvil normal no hace
falta, porque la variante `debug` elimina el provider del SDK.

### 2. Elegir la variante de build

| Variante | Destino | Comportamiento |
|---|---|---|
| `debug` | Móvil Android o emulador | `src/debug/AndroidManifest.xml` elimina `TemiSdkContentProvider`, de modo que la app no se cierra fuera del robot. Incluye las cuatro arquitecturas (~34 MB) |
| `temi` | Robot Temi | Hereda de `debug` pero **no** tiene manifest propio, así que conserva el provider y el SDK arranca. Firma de debug, instalable directamente. Solo ARM (~20 MB) |
| `release` | Entrega final | `minifyEnabled` y `shrinkResources` activados, solo ARM (~15 MB). Requiere firma propia |

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug      # móvil o emulador
.\gradlew.bat assembleTemi       # robot Temi
.\gradlew.bat assembleRelease    # entrega
.\gradlew.bat test               # tests unitarios en la JVM
```

Instalación en el robot con el cable conectado:

```bash
adb install -r app/build/outputs/apk/temi/app-temi.apk
```

### 3. Conectar la diadema MUSE

La vía se elige en **Configuración → Conexión**.

**Opción A — Mind Monitor (OSC sobre WiFi).** Es la opción por defecto y no
necesita el SDK de LibMuse.

1. Emparejar el MUSE con el móvil desde Mind Monitor.
2. Poner el móvil y el robot en la misma red WiFi.
3. En Mind Monitor, entrar en *OSC Settings* y escribir la IP que muestra la
   pantalla de Configuración de la app, con el puerto **5000**.
4. Activar en Mind Monitor **todos** los elementos que consume la app: bandas
   absolutas (alpha, beta, theta, delta, gamma), *horseshoe*, *blink*,
   *jaw clench*, *accelerometer* y *gyroscope*. Si faltan las bandas absolutas la
   app avisa con «Activa Alpha/Beta en Mind Monitor → OSC».

**Opción B — Bluetooth directo (LibMuse).** No necesita móvil intermedio, pero
requiere las librerías nativas en el proyecto. En **Configuración → Conexión →
MUSE Directo**, el botón «Ver dispositivos» escanea y permite fijar un MUSE como
dispositivo preferido, que se recuerda en los siguientes arranques.

### 4. Ajustar la detección

Estos valores se guardan en `SharedPreferences` y sobreviven al reinicio.
«Restablecer por defecto» devuelve todos los valores originales.

**Umbrales EEG.** Con la diadema puesta y el jugador en reposo, pulsar **Calibrar
en reposo** en Configuración → Sensibilidad EEG. La app mide 20 segundos de línea
base y deriva los umbrales personales a partir de la media y la desviación típica
de cada índice: estrés = media − σ, calma = media + σ, atención = media + σ. Si no
llegan muestras suficientes la calibración se cancela sin modificar nada. Los tres
deslizadores permiten además el ajuste manual.

**Detección BCI.** En la sección correspondiente:

- *Debounce parpadeo* (100–1000 ms): tiempo mínimo entre dos parpadeos válidos.
  Subirlo reduce los parpadeos falsos por movimiento de cejas.
- *Umbral asentir (NOD)* y *Umbral negar (SHAKE)*, en °/s: amplitud mínima del
  giro para contar como pico. El gesto de negar tiene más recorrido que el de
  asentir, de ahí que los valores por defecto sean 30 y 50.
- *Micrófono — silencio en sala Morse*: con esta opción activa el robot pide
  silencio cuando el ruido ambiente supera el umbral durante una sala Morse.
  Necesita el permiso `RECORD_AUDIO`, que se solicita en el primer uso.

**Ubicaciones del robot.** Las salas con acción `GOTO` usan ubicaciones guardadas
en el mapa de Temi, que se crean desde la interfaz del propio robot. «Exportar
ubicaciones del mapa» vuelca a fichero las disponibles, para conocer sus nombres
al crear niveles. Si una ubicación no existe, el comando se ignora y queda anotado
en Logcat.

---

## Probar sin diadema

`osc_test_sender.py` reproduce el flujo OSC de Mind Monitor:

```bash
pip install python-osc
python osc_test_sender.py --ip <IP_del_dispositivo> --port 5000 --scenario sweep
```

Escenarios disponibles: `stress`, `attention`, `calm`, `neutral` y `sweep`, que
cicla entre todos cambiando cada 15 segundos. La IP a la que apuntar es la que
muestra la pantalla de Configuración.

## Crear niveles

Dos editores producen el mismo formato:

- **En la app**: botón Crear Nivel. Permite añadir, reordenar, duplicar y borrar
  salas, configurar cada tipo de prueba, adjuntar vídeos y encadenar acciones del
  robot.
- **Web**: [`level-editor/index.html`](level-editor/index.html), que se abre en
  cualquier navegador y exporta un JSON importable.

La importación y exportación admite dos formatos: JSON, que lleva solo la
estructura, y ZIP, que empaqueta también los vídeos e imágenes de referencia.
[`demo_laboratorio.json`](demo_laboratorio.json) es un nivel de ejemplo.

## Datos de sesión

Cada partida genera `game_<nivel>_<timestamp>.csv` en el almacenamiento externo de
la app, compartible mediante `FileProvider`. El botón «Empezar registro» graba
además sesiones libres, fuera del juego. Se escriben unas 4 muestras por segundo
con estas columnas: marca de tiempo, estado clasificado, los tres índices
(`concentration`, `mellow`, `gammaActivity`), las bandas crudas α β θ δ γ, calidad
de señal, giroscopio y acelerómetro, eventos de parpadeo, mandíbula, asentir y
negar, acción del robot en curso y contexto de juego (nivel, sala, tipo de módulo
y si el robot estaba hablando).

En `files/` hay sesiones de ejemplo y `dashboard.html`, que las representa.

## Tests

```powershell
.\gradlew.bat test
```

38 tests unitarios que se ejecutan en la JVM, sin robot ni diadema, sobre el
clasificador de estado mental, el detector de gestos, el decodificador Morse, el
motor de escape room, el parseo de acciones del robot y la calibración.
`OSCReceiverTest` levanta el receptor y le envía paquetes OSC reales por UDP a
localhost para comprobar el parseo completo.

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
└── ui/          MainActivity, EegViewModel, LevelEditorController, HeadDirectionView

app/src/test/                Tests unitarios (JVM)
level-editor/index.html      Editor de niveles web
osc_test_sender.py           Simulador de Mind Monitor
demo_laboratorio.json        Nivel de ejemplo
files/                       CSV de ejemplo, dashboard.html, temi_locations.json
gen_tfg.js                   Generador del documento de la memoria (Node + docx)
```
