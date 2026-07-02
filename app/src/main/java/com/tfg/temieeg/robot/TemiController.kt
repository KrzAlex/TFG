package com.tfg.temieeg.robot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.Robot.TtsListener
import com.tfg.temieeg.data.MentalState
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * Adapta el comportamiento del robot Temi al estado mental detectado.
 *
 * [robot] puede ser null cuando la app corre en un dispositivo no-Temi
 * (móvil Android para pruebas). En ese caso los comportamientos se loguean
 * en Logcat pero no ejecutan ninguna acción física.
 *
 * Ciclo de vida:
 *   Llamar a [start] en Activity.onStart() y [stop] en Activity.onStop().
 *
 * Aplica un cooldown mínimo de [MIN_STATE_DURATION_MS] entre cambios para
 * evitar que el robot reaccione a fluctuaciones momentáneas de la señal EEG.
 *
 * Comportamientos por estado:
 *   STRESS    → voz calmante + cabeza ligeramente arriba (postura abierta)
 *   ATTENTION → refuerzo positivo + cabeza neutra
 *   CALM      → invitación a interactuar + cabeza neutra
 *   NEUTRAL   → modo seguimiento (beWithMe)
 */
class TemiController(private val robot: Robot?, private val appContext: Context) : OnRobotReadyListener, TtsListener, OnGoToLocationStatusChangedListener {

    /**
     * Se dispara en el hilo principal cuando el TTS actual termina
     * (COMPLETED, CANCELED o ERROR). Usado por [EscapeRoomEngine] para
     * encadenar acciones de robot sin bloquear el hilo.
     */
    var onTtsEnd: (() -> Unit)? = null

    /**
     * Se dispara en el hilo principal cuando el robot llega a su destino
     * (COMPLETE) o cancela la navegación (ABORT). Usado por [EscapeRoomEngine]
     * para encadenar SPEAK tras GOTO sin tiempos fijos.
     */
    var onGoToEnd: (() -> Unit)? = null

    /** true cuando no hay hardware Temi disponible (modo prueba en móvil). */
    val isSimulated: Boolean get() = robot == null

    /** true cuando el robot ha confirmado que está listo para recibir comandos. */
    private var robotReady = false

    private val handler = Handler(Looper.getMainLooper())

    // ── TTS nativo Android (fallback sin internet) ────────────────────────────

    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    init {
        androidTts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = Locale("es", "ES")
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        log("Android TTS onDone — disparando onTtsEnd")
                        handler.post { onTtsEnd?.invoke() }
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        log("Android TTS onError — disparando onTtsEnd")
                        handler.post { onTtsEnd?.invoke() }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        log("Android TTS onError(code=$errorCode) — disparando onTtsEnd")
                        handler.post { onTtsEnd?.invoke() }
                    }
                })
                androidTtsReady = true
                log("Android TTS listo (fallback offline)")
            } else {
                log("Android TTS no disponible (status=$status)")
            }
        }
    }

    fun shutdown() {
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
    }

    private var currentState: MentalState = MentalState.NEUTRAL
    private var lastStateChange: Long = 0L

    /** Última descripción de acción — para el SessionLogger y la UI. */
    private var lastActionDescription: String = "Esperando"

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    fun start() {
        if (isSimulated) {
            log("⚠ Modo simulado — Robot.getInstance() devolvió null. Asegúrate de que el TemiSdkContentProvider está en el manifest.")
            return
        }
        log("✅ Robot SDK activo — registrando listener de ready…")
        robot?.addOnRobotReadyListener(this)
        robot?.addTtsListener(this)
        robot?.addOnGoToLocationStatusChangedListener(this)
    }

    fun stop() {
        robot?.removeOnRobotReadyListener(this)
        robot?.removeTtsListener(this)
        robot?.removeOnGoToLocationStatusChangedListener(this)
        robotReady = false
    }

    // ── OnRobotReadyListener ──────────────────────────────────────────────────

    override fun onRobotReady(isReady: Boolean) {
        robotReady = isReady
        log("Robot listo: $isReady")
        if (isReady) {
            robot?.tiltAngle(HEAD_NEUTRAL, HEAD_SPEED)
            val locs = robot?.locations ?: emptyList()
            log("Ubicaciones en el mapa (${locs.size}): $locs")
        }
    }

    // ── TtsListener ──────────────────────────────────────────────────────────

    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
        log("TTS status → ${ttsRequest.status}  text=\"${ttsRequest.speech.take(40)}\"")
        when (ttsRequest.status) {
            TtsRequest.Status.COMPLETED,
            TtsRequest.Status.CANCELED,
            TtsRequest.Status.ERROR -> handler.post { onTtsEnd?.invoke() }
            else -> Unit
        }
    }

    // ── OnGoToLocationStatusChangedListener ──────────────────────────────────

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        log("goTo '$location' → status=$status")
        when (status) {
            OnGoToLocationStatusChangedListener.COMPLETE ->
                handler.post { onGoToEnd?.invoke() }
            OnGoToLocationStatusChangedListener.ABORT -> {
                // Navegación abortada (camino bloqueado): continuar la cadena de
                // acciones igualmente para que el juego no se quede esperando.
                log("⚠ Navegación abortada — continuando la cadena de acciones")
                handler.post { onGoToEnd?.invoke() }
            }
        }
    }

    // ── Reacción a estado mental ──────────────────────────────────────────────

    fun onStateChanged(newState: MentalState) {
        val now = System.currentTimeMillis()
        if (newState == currentState) return
        if (now - lastStateChange < MIN_STATE_DURATION_MS) return

        currentState = newState
        lastStateChange = now
        applyBehavior(newState)
    }

    private fun applyBehavior(state: MentalState) {
        log("Nuevo estado → $state${if (isSimulated) " [simulado]" else ""}")
        when (state) {
            MentalState.STRESS    -> applyStressBehavior()
            MentalState.ATTENTION -> applyAttentionBehavior()
            MentalState.CALM      -> applyCalmBehavior()
            MentalState.NEUTRAL   -> applyNeutralBehavior()
        }
    }

    private fun applyStressBehavior() {
        lastActionDescription = "Modo calma activado"
        tiltHead(HEAD_UP)
    }

    private fun applyAttentionBehavior() {
        lastActionDescription = "Alta concentración detectada"
        tiltHead(HEAD_NEUTRAL)
    }

    private fun applyCalmBehavior() {
        lastActionDescription = "Estado relajado — listo para interactuar"
        tiltHead(HEAD_NEUTRAL)
    }

    private fun applyNeutralBehavior() {
        lastActionDescription = "Neutral — sin seguimiento"
        // beWithMe desactivado temporalmente
        // robot?.beWithMe()
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Hace hablar a Temi. Público para que los módulos del escape room
     * puedan narrar texto directamente sin pasar por el estado mental.
     * En modo simulado solo loguea el texto en Logcat.
     */
    fun speak(text: String) {
        log("TTS → \"$text\"")
        if (isSimulated) {
            // Sin hardware real: simulamos el fin del TTS para no bloquear la cadena
            // de dispatchActions (igual que hace goTo() en modo simulado).
            handler.postDelayed({ onTtsEnd?.invoke() }, 300L)
            return
        }
        if (!robotReady) { log("Robot no listo — usando Android TTS (fallback)"); speakAndroid(text); return }

        if (hasInternet()) {
            // TTS de Temi con voz y animación (requiere internet)
            try {
                robot?.speak(
                    TtsRequest.create(
                        speech = text,
                        isShowOnConversationLayer = false,
                        showAnimationOnly = false   // false = habla de verdad (con audio)
                    )
                )
            } catch (e: Exception) {
                log("Error TTS Temi: ${e.message} — usando fallback Android TTS")
                speakAndroid(text)
            }
        } else {
            // Sin internet → TTS nativo Android (offline)
            log("Sin internet — usando Android TTS (offline)")
            speakAndroid(text)
        }
    }

    private fun speakAndroid(text: String) {
        if (!androidTtsReady) {
            log("Android TTS no listo — texto descartado")
            handler.postDelayed({ onTtsEnd?.invoke() }, 500L)
            return
        }
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "temi_tts")
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Navega a una ubicación guardada en el mapa de Temi.
     * Desactivado temporalmente — descomentar cuando el mapa tenga ubicaciones guardadas.
     */
    fun goTo(location: String) {
        log("goTo → $location")
        if (isSimulated || !robotReady) {
            // Sin hardware real, simulamos llegada inmediata para no bloquear la cadena
            handler.postDelayed({ onGoToEnd?.invoke() }, 500L)
            return
        }
        try {
            robot?.goTo(location)
        } catch (e: Exception) {
            log("Error goTo: ${e.message}")
            handler.post { onGoToEnd?.invoke() }
        }
    }

    /**
     * Gira el robot [degrees] grados en el sitio.
     * Positivo = derecha, negativo = izquierda.
     * Ignorado en modo simulado.
     */
    fun turnBy(degrees: Int) {
        log("turnBy → $degrees°")
        if (isSimulated || !robotReady) return
        try {
            robot?.turnBy(degrees, 0.5f)
        } catch (e: Exception) {
            log("Error turnBy: ${e.message}")
        }
    }

    /** Detiene cualquier movimiento en curso. */
    fun stopMovement() {
        robot?.stopMovement()
        log("Movimiento detenido")
    }

    /** Descripción de la última acción ejecutada (para log y UI). */
    fun lastAction(): String = lastActionDescription

    /**
     * Devuelve la lista de ubicaciones guardadas en el mapa actual del Temi.
     * En modo simulado devuelve una lista vacía.
     */
    fun getLocations(): List<String> = robot?.locations ?: emptyList()

    /**
     * Exporta las ubicaciones guardadas a un fichero JSON en almacenamiento externo.
     * El fichero se llama temi_locations.json y se puede copiar al PC con adb pull.
     * Devuelve el [File] creado, o null si no hay ubicaciones o hay error.
     */
    fun exportLocations(context: Context): File? {
        val locs = getLocations()
        if (locs.isEmpty()) return null
        return try {
            val json = JSONArray().also { arr -> locs.forEach { arr.put(it) } }
            val file = File(context.getExternalFilesDir(null), "temi_locations.json")
            file.writeText(json.toString(2))
            log("Ubicaciones exportadas → ${file.absolutePath} (${locs.size} items)")
            file
        } catch (e: Exception) {
            log("Error exportando ubicaciones: ${e.message}")
            null
        }
    }

    /**
     * Animación de celebración al completar una sala: cabeza arriba → neutro.
     * Añade personalidad y feedback visual tras superar un reto.
     */
    fun celebrate() {
        log("celebrate()")
        if (isSimulated || !robotReady) return
        try {
            robot?.tiltAngle(HEAD_UP, 1.0f)
            handler.postDelayed({
                if (robotReady) robot?.tiltAngle(HEAD_NEUTRAL, HEAD_SPEED)
            }, 1200L)
        } catch (e: Exception) {
            log("Error celebrate: ${e.message}")
        }
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

    fun tiltHead(degrees: Int) {
        log("tiltHead → $degrees°")
        if (isSimulated || !robotReady) return
        try {
            robot?.tiltAngle(degrees, HEAD_SPEED)
        } catch (e: Exception) {
            log("Error tiltAngle: ${e.message}")
        }
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    // ── Constantes ────────────────────────────────────────────────────────────

    companion object {
        const val MIN_STATE_DURATION_MS = 5_000L   // cooldown entre reacciones
        // En el SDK de Temi 0° inclina la pantalla hacia el suelo.
        // La posición de pie natural (pantalla mirando al frente) es ~25°.
        const val HEAD_NEUTRAL  = 25               // posición de pie normal
        private const val HEAD_UP = 38             // cabeza ligeramente arriba (estrés)
        private const val HEAD_SPEED    = 0.5f
        private const val TAG           = "TemiController"
    }
}
