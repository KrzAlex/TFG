package com.tfg.temieeg.eeg

import android.util.Log
import com.tfg.temieeg.BuildConfig
import com.illposed.osc.OSCPortIn
import com.tfg.temieeg.data.MuseState
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Receptor OSC completo para Mind Monitor + MUSE.
 *
 * Canales que Mind Monitor SÍ envía y que escuchamos:
 *   /muse/elements/alpha_absolute    — banda alpha (4 electrodos)
 *   /muse/elements/beta_absolute     — banda beta
 *   /muse/elements/theta_absolute    — banda theta
 *   /muse/elements/delta_absolute    — banda delta
 *   /muse/elements/gamma_absolute    — banda gamma (Muse 2+)
 *   /muse/elements/horseshoe         — calidad de señal por electrodo
 *   /muse/elements/blink             — detección de parpadeo
 *   /muse/acc                        — acelerómetro (g)
 *   /muse/gyro                       — giroscopio (°/s)
 *   /muse/batt                       — batería del MUSE
 *
 * "Elements activos" = alpha Y beta recibidos recientemente.
 * Solo entonces se emite [onMuseDataReceived] para clasificar.
 */
class OSCReceiver(private val port: Int = DEFAULT_PORT) : MuseReceiver {

    override var onMuseDataReceived: ((MuseState) -> Unit)? = null
    override var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    override var onConnectingChanged: ((Boolean) -> Unit)? = null   // OSC no tiene estado "conectando"

    /**
     * true  = alpha y beta llegando → clasificación activa.
     * false = sin bandas EEG → activar Elements en Mind Monitor → OSC Settings.
     */
    override var onElementsStateChanged: ((Boolean) -> Unit)? = null

    private var server: OSCPortIn? = null
    private val currentState = AtomicReference(MuseState())

    /**
     * Tiempo mínimo entre parpadeos válidos (ms).
     * Normal / juegos : 500 ms — filtra movimientos de frente.
     * Modo Morse       : 150 ms — permite doble parpadeo para la raya.
     */
    @Volatile override var blinkDebounceMs: Long = MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS

    @Volatile private var lastMessageTime    = 0L
    @Volatile private var lastAlphaTime      = 0L
    @Volatile private var lastBetaTime       = 0L
    @Volatile private var lastBlinkTime      = 0L
    @Volatile private var lastJawClenchTime  = 0L

    @Volatile private var connected      = false
    @Volatile private var elementsActive = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OSCSampler").apply { isDaemon = true }
    }
    private var samplerFuture: ScheduledFuture<*>? = null

    // ── Inicio / parada ───────────────────────────────────────────────────────

    override fun start() {
        try {
            server = OSCPortIn(port)
        } catch (e: SocketException) {
            Log.e(TAG, "No se pudo abrir el puerto OSC $port", e)
            return
        }

        registerListeners()
        server?.startListening()

        samplerFuture = scheduler.scheduleAtFixedRate(
            ::onTick, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
        Log.i(TAG, "OSCReceiver escuchando en puerto $port")
    }

    override fun stop() {
        samplerFuture?.cancel(false)
        samplerFuture = null
        server?.stopListening()
        server?.close()
        server = null
        if (connected)      { connected = false;      onConnectionStateChanged?.invoke(false) }
        if (elementsActive) { elementsActive = false;  onElementsStateChanged?.invoke(false) }
        Log.i(TAG, "OSCReceiver detenido")
    }

    // ── Registro de listeners ─────────────────────────────────────────────────

    private fun registerListeners() {

        // ── Bandas EEG (4 electrodos, media) ──────────────────────────────
        server?.addListener("/muse/elements/alpha_absolute") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            val now = System.currentTimeMillis()
            lastMessageTime = now; lastAlphaTime = now
            currentState.updateAndGet { it.copy(alphaAbsolute = avg) }
        }

        server?.addListener("/muse/elements/beta_absolute") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            val now = System.currentTimeMillis()
            lastMessageTime = now; lastBetaTime = now
            currentState.updateAndGet { it.copy(betaAbsolute = avg) }
        }

        server?.addListener("/muse/elements/theta_absolute") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(thetaAbsolute = avg) }
        }

        server?.addListener("/muse/elements/delta_absolute") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(deltaAbsolute = avg) }
        }

        server?.addListener("/muse/elements/gamma_absolute") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(gammaAbsolute = avg) }
        }

        // ── Calidad de señal (horseshoe) ───────────────────────────────────
        // Valores: 1=buen contacto, 2=regular, 4=sin contacto
        server?.addListener("/muse/elements/horseshoe") { _, msg ->
            val avg = avgFloat(msg.arguments) ?: return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(signalQuality = avg) }
        }

        // ── Apriete de mandíbula ───────────────────────────────────────────
        // Se registra el instante para suprimir el falso parpadeo que genera.
        server?.addListener("/muse/elements/jaw_clench") { _, msg ->
            val v = msg.arguments.firstOrNull()
            val isClench = when (v) {
                is Int   -> v == 1
                is Float -> v == 1f
                else     -> return@addListener
            }
            if (!isClench) return@addListener
            val now = System.currentTimeMillis()
            lastJawClenchTime = now
            lastMessageTime   = now
            currentState.updateAndGet { it.copy(jawClench = true) }
            Log.d(TAG, "Jaw clench detectado")
        }

        // ── Parpadeo ───────────────────────────────────────────────────────
        // Debounce: parpadeos reales ocurren cada ≥ 400 ms.
        // Los artefactos de movimiento de frente llegan en ráfaga (< 200 ms).
        // Los artefactos de mandíbula llegan junto con jaw_clench: se suprimen
        // si hubo un jaw_clench en los últimos JAW_BLINK_SUPPRESS_MS.
        server?.addListener("/muse/elements/blink") { _, msg ->
            val v = msg.arguments.firstOrNull()
            val isBlink = when (v) {
                is Int   -> v == 1
                is Float -> v == 1f
                else     -> return@addListener
            }
            if (!isBlink) return@addListener

            val now = System.currentTimeMillis()
            if (now - lastBlinkTime    < blinkDebounceMs)         return@addListener
            if (now - lastJawClenchTime < JAW_BLINK_SUPPRESS_MS) return@addListener  // artefacto mandíbula

            lastBlinkTime = now
            lastMessageTime = now
            currentState.updateAndGet { it.copy(blink = true) }
        }

        // ── Acelerómetro ───────────────────────────────────────────────────
        server?.addListener("/muse/acc") { _, msg ->
            val xyz = FloatArray(3)
            if (!first3Floats(msg.arguments, xyz)) return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(accX = xyz[0], accY = xyz[1], accZ = xyz[2]) }
        }

        // ── Giroscopio ─────────────────────────────────────────────────────
        server?.addListener("/muse/gyro") { _, msg ->
            val xyz = FloatArray(3)
            if (!first3Floats(msg.arguments, xyz)) return@addListener
            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(gyroX = xyz[0], gyroY = xyz[1], gyroZ = xyz[2]) }
        }

        // ── Batería ────────────────────────────────────────────────────────
        // Mind Monitor envía /muse/batt con 4 enteros:
        //   [0] carga restante en unidades internas (0 – ~10000)
        //   [1] temperatura de la batería en décimas de Kelvin
        //       → temp_C = [1] × 0.1 − 273.15
        //   [2] capacidad total  (-1 si no disponible en este firmware)
        //   [3] carga actual en las mismas unidades que [0]
        //
        // Conversión a %:
        //   · Si [2] > 0 → porcentaje = [0] / [2] × 100  (ratio real)
        //   · Si [2] ≤ 0 → porcentaje = [0] / 100         (escala 0–10000)
        //   · Si [0] ya está en 0–100 → usar directamente
        server?.addListener("/muse/batt") { _, msg ->
            val args = msg.arguments
            if (args.isEmpty()) return@addListener

            val charge = (args[0] as? Number)?.toFloat() ?: return@addListener
            val full   = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f

            val pct: Int = when {
                charge in 0f..100f -> charge.toInt()          // ya es porcentaje
                full > 0f          -> ((charge / full) * 100f).toInt()  // ratio real
                else               -> (charge / 100f).toInt() // escala 0–10000
            }

            // Temperatura en °C (opcional, solo para log)
            val tempRaw = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val tempC   = tempRaw * 0.1f - 273.15f
            if (BuildConfig.DEBUG) Log.d(TAG, "Batería: %d%%  |  Temp batería: %.1f°C".format(pct, tempC))

            lastMessageTime = System.currentTimeMillis()
            currentState.updateAndGet { it.copy(battery = pct.coerceIn(0, 100)) }
        }
    }

    // ── Tick periódico (4 Hz) ─────────────────────────────────────────────────

    private fun onTick() {
        val now = System.currentTimeMillis()

        val nowConnected = lastMessageTime > 0 && (now - lastMessageTime) < CONNECTION_TIMEOUT_MS
        val nowElements  = lastAlphaTime > 0 && (now - lastAlphaTime) < ELEMENTS_TIMEOUT_MS
                        && lastBetaTime  > 0 && (now - lastBetaTime)  < ELEMENTS_TIMEOUT_MS

        if (nowConnected != connected) {
            connected = nowConnected
            onConnectionStateChanged?.invoke(connected)
            if (connected) onElementsStateChanged?.invoke(elementsActive)
            else if (elementsActive) elementsActive = false
        }

        if (nowElements != elementsActive) {
            elementsActive = nowElements
            onElementsStateChanged?.invoke(elementsActive)
            Log.i(TAG, if (nowElements) "Bandas EEG activas ✓" else "Sin bandas — actívalas en Mind Monitor → OSC")
        }

        if (nowConnected && nowElements) {
            // Reset blink y jawClench después de emitir (son eventos puntuales).
            // Si ambos llegan en el mismo tick (jaw_clench genera un blink falso),
            // se suprime el blink para que solo se dispare el jaw_clench.
            val raw   = currentState.getAndUpdate { it.copy(blink = false, jawClench = false) }
            val state = if (raw.jawClench && raw.blink) raw.copy(blink = false) else raw
            onMuseDataReceived?.invoke(state.copy(timestamp = now))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Media de los argumentos Float de un mensaje OSC, sin asignar la lista
     * intermedia que producía `filterIsInstance<Float>()`. Mind Monitor emite
     * decenas de mensajes por segundo, así que esta ruta domina la basura
     * generada en el hilo de red. Devuelve null si no hay ningún Float.
     */
    private fun avgFloat(args: List<Any?>): Float? {
        var sum = 0.0
        var n = 0
        for (i in args.indices) {           // por índice: evita también el Iterator
            val v = args[i]
            if (v is Float) { sum += v; n++ }
        }
        return if (n == 0) null else (sum / n).toFloat()
    }

    /**
     * Copia los tres primeros Float de [args] en [out] (acelerómetro/giroscopio).
     * Mantiene la semántica de "primeros tres floats" del filtrado anterior pero
     * sin crear una List<Float> con boxing por cada mensaje.
     */
    private fun first3Floats(args: List<Any?>, out: FloatArray): Boolean {
        var n = 0
        for (i in args.indices) {
            val v = args[i]
            if (v is Float) {
                out[n++] = v
                if (n == 3) return true
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_PORT = 5000
        private const val SAMPLE_INTERVAL_MS    = 250L
        private const val CONNECTION_TIMEOUT_MS = 3000L
        private const val ELEMENTS_TIMEOUT_MS   = 5000L
        private const val JAW_BLINK_SUPPRESS_MS = 300L
        private const val TAG = "OSCReceiver"
    }
}
