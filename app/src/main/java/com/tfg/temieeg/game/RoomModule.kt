package com.tfg.temieeg.game

import android.os.Handler
import android.os.Looper
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.eeg.MorseDecoder
import com.tfg.temieeg.robot.NoiseMonitor

// ── Tipos auxiliares ──────────────────────────────────────────────────────────

/**
 * Pregunta de sí/no para [YesNoModule].
 *
 * [gotoOnYes] / [gotoOnNo]: índice de sala (0-based) al que saltar cuando el
 * usuario responde SÍ o NO respectivamente. Si es null se aplica la lógica de
 * quiz estándar (correcto → siguiente pregunta, incorrecto → repetir).
 * Si se establece un goto, el concepto de "respuesta correcta" queda ignorado
 * para esa dirección.
 */
data class YesNoQuestion(
    val text: String,
    val expectedYes: Boolean = true,
    val gotoOnYes: Int? = null,
    val gotoOnNo:  Int? = null
)

/**
 * Acción del robot que se ejecuta al iniciar una sala (antes del módulo).
 * Formato de texto para el editor: "SPEAK:Hola", "GOTO:sala1", "TILT:20"
 */
data class RobotAction(
    val type: Type,
    val param: String = ""
) {
    enum class Type {
        SPEAK,
        GOTO,
        TILT_HEAD,
        /** Girar el robot N grados (positivo = derecha, negativo = izquierda). */
        TURN,
        /** Pausa entre acciones — el motor espera param ms antes de la siguiente acción. */
        WAIT
    }

    companion object {
        fun fromString(s: String): RobotAction? {
            val idx   = s.indexOf(':')
            val key   = if (idx < 0) s.trim().uppercase() else s.substring(0, idx).trim().uppercase()
            val param = if (idx < 0) "" else s.substring(idx + 1).trim()
            val type  = Type.entries.find { it.name == key } ?: return null
            return RobotAction(type, param)
        }
        fun asString(a: RobotAction) = "${a.type.name}:${a.param}"
    }
}

// ── Base ──────────────────────────────────────────────────────────────────────

/**
 * Módulo base para una sala de Escape Room.
 *
 * Cada módulo encapsula la lógica de un reto concreto (calma, morse, sí/no…).
 * El motor [EscapeRoomEngine] inyecta los callbacks antes de llamar a [start].
 *
 * Para crear un módulo propio:
 *   1. Extiende esta clase con los parámetros del reto.
 *   2. Sobreescribe los métodos open que necesites (onBlink, onMentalState…).
 *   3. Llama a `onSuccess?.invoke()` cuando el reto se supere.
 */
abstract class RoomModule(
    /** Nombre de la sala (se muestra en la cabecera del overlay). */
    val title: String,
    /** Narración inicial (el robot la lee en voz alta). */
    val narration: String,
    /** Pista de acción inicial (se actualiza dinámicamente con [onHintChanged]). */
    open val hint: String,
    /**
     * Recurso de vídeo embebido en res/raw/ (niveles del catálogo).
     * null = sin vídeo embebido.
     */
    val videoResId: Int? = null,
    /**
     * Ruta absoluta a un vídeo en almacenamiento interno (niveles personalizados).
     * null = sin vídeo de fichero.
     */
    val videoPath: String? = null,
    /**
     * Acciones del robot que se ejecutan al iniciar la sala (vídeo → acciones → módulo).
     * Vacía en el catálogo por defecto; se rellena en niveles personalizados.
     */
    val robotActions: List<RobotAction> = emptyList(),
    /**
     * Imagen de referencia/ayuda (ruta absoluta en almacenamiento interno).
     * Usada en [MorseModule] para mostrar la tabla de código Morse.
     * null = sin imagen de ayuda.
     */
    val hintImagePath: String? = null
) {
    /**
     * true si este módulo tiene al menos una acción SPEAK en sus robotActions.
     * Cuando es true, el motor ya narró el enunciado antes de llamar a start(),
     * por lo que los módulos deben evitar volver a leer la narración en start().
     */
    val hasRobotSpeech: Boolean
        get() = robotActions.any { it.type == RobotAction.Type.SPEAK }
    // Callbacks inyectados por EscapeRoomEngine — no modificar desde subclases
    internal var onSuccess:                (() -> Unit)?               = null
    /** Salta directamente a la sala con ese índice (0-based). Usado por [YesNoModule] para bifurcaciones. */
    internal var onSuccessAt:              ((Int) -> Unit)?            = null
    internal var onFeedback:               ((String, Boolean) -> Unit)? = null
    internal var onMorseSymbols:           ((String) -> Unit)?          = null
    internal var onTemiSpeak:              ((String) -> Unit)?          = null
    internal var onSetBlinkDebounce:       ((Long) -> Unit)?            = null
    /** Permite al módulo actualizar la pista en tiempo real (e.g. "3/5 s"). */
    internal var onHintChanged:            ((String) -> Unit)?          = null
    /** Inicia reproducción de vídeo en paralelo al reto (loop hasta que abort lo detenga). */
    internal var onStartConcurrentVideo:   ((path: String?) -> Unit)?   = null
    /** Detiene el vídeo concurrente iniciado con [onStartConcurrentVideo]. */
    internal var onStopConcurrentVideo:    (() -> Unit)?                = null

    /**
     * Callback de un solo disparo: el motor lo invoca cuando el BCI se desbloquea
     * tras el fin del TTS + gracia. Permite a los módulos encadenar lógica
     * exactamente cuando el robot termina de hablar, en lugar de usar delays fijos.
     * Se consume (= null) en cuanto se invoca.
     */
    internal var onTemiSpeakDone: (() -> Unit)? = null

    protected val handler = Handler(Looper.getMainLooper())

    /** Llamado por el motor justo antes de mostrar la sala. */
    open fun start() {}

    /** Llamado cuando el usuario sale del juego o el motor avanza a la siguiente sala. */
    open fun abort() { handler.removeCallbacksAndMessages(null) }

    open fun onMentalState(state: MentalState) {}
    open fun onBlink()                         {}
    open fun onJawClench()                     {}
    open fun onNod()                           {}
    open fun onShake()                         {}

    /**
     * El motor llama a este método con la amplitud de ruido ambiente (0–32 767).
     * Los módulos que requieran silencio (e.g. [MorseModule]) pueden sobreescribirlo.
     */
    open fun onNoiseLevel(amplitude: Int)      {}
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: CALMA — mantener estado mental CALM N segundos consecutivos
// ══════════════════════════════════════════════════════════════════════════════

/**
 * El usuario debe mantener [MentalState.CALM] durante [secondsRequired] segundos
 * consecutivos. Si pierde la calma, el contador se reinicia.
 */
class CalmModule(
    title: String,
    narration: String,
    hint: String = "Mantén la calma",
    val secondsRequired: Int = 5,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList()
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions) {

    private var calmSeconds = 0
    private var tickActive  = false
    /**
     * true en cuanto se supera la sala. Sin esta marca, cada nueva muestra de
     * estado mental (llegan ~4 por segundo y NO se bloquean mientras el robot
     * habla) volvia a entrar por onMentalState, veia tickActive=false y
     * relanzaba el contador: la felicitacion sonaba repetida y los segundos se
     * disparaban durante el segundo y medio previo al cambio de sala.
     */
    private var completed   = false

    private val calmTick = object : Runnable {
        override fun run() {
            if (!tickActive) return
            calmSeconds++
            onHintChanged?.invoke("$calmSeconds / $secondsRequired s 🧘")
            if (calmSeconds >= secondsRequired) {
                tickActive = false
                completed  = true
                onFeedback?.invoke("✅ ¡Calma mantenida! Puerta abierta.", true)
                onTemiSpeak?.invoke("¡Perfecto! La puerta se abre.")
                handler.postDelayed({ onSuccess?.invoke() }, 1500L)
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun start() {
        calmSeconds = 0
        tickActive  = false
        completed   = false   // los modulos del catalogo se reutilizan entre partidas
        if (!hasRobotSpeech) onTemiSpeak?.invoke(narration)
    }

    override fun abort() {
        tickActive = false
        super.abort()
    }

    override fun onMentalState(state: MentalState) {
        if (completed) return   // sala ya superada: ignorar el resto de muestras
        if (state == MentalState.CALM) {
            if (!tickActive) {
                tickActive = true
                handler.post(calmTick)
            }
        } else {
            if (tickActive) {
                tickActive = false
                handler.removeCallbacks(calmTick)
                calmSeconds = 0
                onHintChanged?.invoke(hint)
                onFeedback?.invoke("Mantén la calma…", false)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: MORSE — escribir una letra en código Morse con parpadeos
// ══════════════════════════════════════════════════════════════════════════════

/**
 * El robot anuncia una letra aleatoria de [letterPool].
 * El usuario debe escribirla en código Morse mediante parpadeos.
 * Un fallo muestra la letra correcta y ofrece un nuevo intento.
 */
class MorseModule(
    title: String,
    narration: String,
    hint: String = "1 parpadeo = ·      2 parpadeos rápidos = —",
    /** Letras que pueden salir (elige las más sencillas para niños). */
    val letterPool: List<Char> = "ETISAN".toList(),
    /**
     * Amplitud mínima (0–32 767) para que Temi pida silencio.
     * null desactiva la vigilancia de ruido.
     */
    val noiseThreshold: Int? = NoiseMonitor.DEFAULT_THRESHOLD,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList(),
    hintImagePath: String? = null
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions, hintImagePath) {

    // dashWindowMs subido a 500 ms: más tiempo para hacer el doble parpadeo (raya)
    private val decoder          = MorseDecoder(dashWindowMs = 500L)
    private var targetLetter     = ' '
    private var lastNoiseWarning = 0L

    override fun start() {
        onSetBlinkDebounce?.invoke(150L)   // parpadeos rápidos para el doble-guión
        if (!hasRobotSpeech) speakSilenced(narration)   // suprime ruido mientras narra
        decoder.onSymbolsUpdated = { onMorseSymbols?.invoke(it) }
        decoder.onLetterDecoded  = { letter -> evaluateLetter(letter) }
        handler.postDelayed(::pickNewLetter, 1200L)
    }

    override fun abort() {
        decoder.clear()
        lastNoiseWarning = 0L
        super.abort()
        // El motor restaura el debounce por defecto al avanzar
    }

    override fun onBlink() = decoder.recordBlink()

    /**
     * Si hay ruido por encima del umbral, Temi pide silencio.
     * El cooldown [NOISE_WARN_COOLDOWN_MS] evita repeticiones, y [speakSilenced]
     * lo extiende automáticamente mientras el propio Temi está hablando.
     */
    override fun onNoiseLevel(amplitude: Int) {
        val threshold = noiseThreshold ?: return          // vigilancia desactivada
        if (amplitude < threshold) return
        val now = System.currentTimeMillis()
        if (now - lastNoiseWarning < NOISE_WARN_COOLDOWN_MS) return
        onFeedback?.invoke("🤫 ¡Silencio! El Morse se hace con parpadeos.", false)
        speakSilenced(
            "Shhh… Hay que estar en silencio. " +
            "Tienes que decir la contraseña en código Morse con tus parpadeos."
        )
    }

    private fun pickNewLetter() {
        targetLetter = letterPool.random()
        // Se muestra tambien el patron de puntos y rayas: obligar a abrir el
        // menu de ayuda en cada letra rompia el ritmo del juego.
        onHintChanged?.invoke(hintFor(targetLetter))
        speakSilenced("Escribe la letra $targetLetter en código Morse. ${spokenCode(targetLetter)}")
    }

    /** Pista con la letra objetivo y su patron, p.ej. "Letra S  →  · · ·". */
    private fun hintFor(letter: Char): String {
        val code = MorseDecoder.spacedCodeFor(letter)
        return if (code != null) "Letra $letter  →  $code" else "Escribe en Morse → $letter"
    }

    /** El patron dictado en voz alta ("punto punto punto"), para reforzar por audio. */
    private fun spokenCode(letter: Char): String {
        val code = MorseDecoder.codeFor(letter) ?: return ""
        return code.map { if (it == MorseDecoder.DASH_CHAR) "raya" else "punto" }
            .joinToString(", ")
    }

    private fun evaluateLetter(letter: Char) {
        decoder.clear()
        onMorseSymbols?.invoke("")
        if (letter == targetLetter) {
            onFeedback?.invoke("✅ ¡Correcto! Código aceptado.", true)
            speakSilenced("¡Muy bien! El código es correcto.")
            handler.postDelayed({ onSuccess?.invoke() }, 1500L)
        } else {
            val code = MorseDecoder.spacedCodeFor(targetLetter)
            onFeedback?.invoke(
                if (code != null) "❌ Era «$targetLetter»  ($code) — inténtalo de nuevo."
                else              "❌ Era «$targetLetter» — inténtalo de nuevo.",
                false
            )
            speakSilenced("No es correcto. La letra era $targetLetter. Inténtalo de nuevo.")
            handler.postDelayed(::pickNewLetter, 1800L)
        }
    }

    /**
     * Invoca onTemiSpeak y suprime la detección de ruido durante la locución estimada
     * más un margen de [NOISE_WARN_COOLDOWN_MS] para evitar que el micrófono recoja
     * la propia voz del robot y dispare el aviso de silencio.
     *
     * Estimación: ~300 ms por palabra + margen fijo de 2 s.
     */
    private fun speakSilenced(text: String) {
        val words = text.trim().split("\\s+".toRegex()).size
        val suppressUntil = System.currentTimeMillis() + words * 300L + 2_000L
        // Para que el check `now - lastNoiseWarning < COOLDOWN` siga siendo cierto
        // durante todo el periodo, desplazamos lastNoiseWarning al futuro.
        lastNoiseWarning = suppressUntil - NOISE_WARN_COOLDOWN_MS
        onTemiSpeak?.invoke(text)
    }

    companion object {
        /** Tiempo mínimo entre avisos de silencio (ms). */
        const val NOISE_WARN_COOLDOWN_MS = 4_000L
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: SÍ/NO — responder preguntas con gestos de cabeza
// ══════════════════════════════════════════════════════════════════════════════

/**
 * El robot formula [questions] una a una.
 *   Asentir (nod)  = SÍ
 *   Negar  (shake) = NO
 *
 * Cada gesto requiere confirmación doble para evitar activaciones accidentales:
 *   1er gesto → "Repite para confirmar" (ventana de [CONFIRM_WINDOW_MS] ms)
 *   2º gesto igual → confirma y procesa la respuesta
 *   2º gesto distinto o timeout → cancela y repite la pregunta
 *
 * Un fallo repite la misma pregunta sin penalizar.
 */
class YesNoModule(
    title: String,
    narration: String,
    hint: String = "Asiente ✅ SÍ  ·  Niega ❌ NO",
    val questions: List<YesNoQuestion>,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList()
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions) {

    private var currentQ        = 0
    private var answered        = false
    private var awaitingConfirm = false
    private var pendingYes      = false

    private val confirmTimeout = Runnable {
        if (!awaitingConfirm) return@Runnable
        awaitingConfirm = false
        onFeedback?.invoke("⏱ Tiempo agotado — inténtalo de nuevo.", false)
        onTemiSpeak?.invoke("Se acabó el tiempo. Inténtalo de nuevo.")
        // Restablecer hint cuando el robot termine de hablar
        onTemiSpeakDone = {
            onFeedback?.invoke("", false)
            questions.getOrNull(currentQ)?.let {
                onHintChanged?.invoke("${currentQ + 1}/${questions.size}: ${it.text}")
            }
        }
    }

    override fun start() {
        currentQ        = 0
        answered        = false
        awaitingConfirm = false
        if (!hasRobotSpeech) onTemiSpeak?.invoke(narration)
        // Formular la primera pregunta cuando el robot termine de hablar,
        // no con un delay fijo que podría ser menor que la duración del TTS.
        onTemiSpeakDone = { askCurrentQuestion() }
    }

    override fun abort() {
        awaitingConfirm = false
        answered        = false
        onTemiSpeakDone = null
        super.abort()
    }

    override fun onNod()   { if (!answered) handleGesture(true)  }
    override fun onShake() { if (!answered) handleGesture(false) }

    private fun handleGesture(isYes: Boolean) {
        if (!awaitingConfirm) {
            // Primer gesto: pedir confirmación
            awaitingConfirm = true
            pendingYes      = isYes
            val confirm = if (isYes) "SÍ — asiente de nuevo para confirmar"
                          else       "NO — niega de nuevo para confirmar"
            onFeedback?.invoke("❓ $confirm", false)
            onTemiSpeak?.invoke("¿Confirmas? Repite el gesto.")
            onHintChanged?.invoke("⏳ Repite el gesto en ${CONFIRM_WINDOW_MS / 1000}s o haz el contrario para cancelar")
            handler.removeCallbacks(confirmTimeout)
            // Iniciar la cuenta atrás DESPUÉS de que el robot termine de hablar,
            // no en el mismo instante (el TTS puede durar más que la ventana de confirmación).
            onTemiSpeakDone = {
                handler.removeCallbacks(confirmTimeout)
                handler.postDelayed(confirmTimeout, CONFIRM_WINDOW_MS)
            }
        } else {
            // Segundo gesto: confirmar o cancelar
            handler.removeCallbacks(confirmTimeout)
            onTemiSpeakDone = null   // cancelar cualquier inicio pendiente del timer
            awaitingConfirm = false
            if (isYes == pendingYes) {
                answered = true
                checkAnswer(isYes)
            } else {
                // Gesto distinto → cancelar
                onFeedback?.invoke("↩ Cancelado — inténtalo de nuevo.", false)
                onTemiSpeak?.invoke("Cancelado. Inténtalo de nuevo.")
                onTemiSpeakDone = {
                    onFeedback?.invoke("", false)
                    questions.getOrNull(currentQ)?.let {
                        onHintChanged?.invoke("${currentQ + 1}/${questions.size}: ${it.text}")
                    }
                }
            }
        }
    }

    private fun askCurrentQuestion() {
        if (currentQ >= questions.size) {
            onFeedback?.invoke("✅ ¡Has respondido todo correctamente!", true)
            onTemiSpeak?.invoke("¡Excelente! Todas las respuestas son correctas.")
            onTemiSpeakDone = { handler.postDelayed({ onSuccess?.invoke() }, 400L) }
            return
        }
        answered        = false
        awaitingConfirm = false
        val q = questions[currentQ]
        onHintChanged?.invoke("${currentQ + 1}/${questions.size}: ${q.text}")
        onTemiSpeak?.invoke(q.text)
        onFeedback?.invoke("", false)
    }

    private fun checkAnswer(answeredYes: Boolean) {
        val q = questions[currentQ]

        // Bifurcación: si la pregunta tiene goto para esta respuesta, saltar a esa sala
        val gotoIdx = if (answeredYes) q.gotoOnYes else q.gotoOnNo
        if (gotoIdx != null) {
            val label = if (answeredYes) "SÍ" else "NO"
            onFeedback?.invoke("➡ $label — cambiando de sala…", true)
            onTemiSpeak?.invoke(if (answeredYes) "¡Sí!" else "No.")
            onTemiSpeakDone = { onSuccessAt?.invoke(gotoIdx) }
            return
        }

        // Comportamiento estándar de quiz
        val correct = (answeredYes == q.expectedYes)
        if (correct) {
            onFeedback?.invoke("✅ ¡Correcto!", true)
            onTemiSpeak?.invoke("¡Correcto!")
            currentQ++
            // Pasar a la siguiente pregunta cuando el robot termine, no con delay fijo
            onTemiSpeakDone = { askCurrentQuestion() }
        } else {
            onFeedback?.invoke("❌ Incorrecto — inténtalo de nuevo.", false)
            onTemiSpeak?.invoke("Incorrecto. Inténtalo de nuevo.")
            onTemiSpeakDone = {
                answered = false
                onFeedback?.invoke("", false)
            }
        }
    }

    companion object {
        const val CONFIRM_WINDOW_MS = 5_000L   // 5s desde que el robot termina de hablar
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: PARPADEO + MANDÍBULA — secuencia de dos gestos
// ══════════════════════════════════════════════════════════════════════════════

/**
 * El usuario debe:
 *   1. Parpadear (gira la llave).
 *   2. Apretar la mandíbula antes de que expire [jawWindowMs].
 * Si el tiempo expira, el reto se reinicia.
 */
class BlinkClenchModule(
    title: String,
    narration: String,
    hint: String = "Parpadea 👁 para girar la llave · Aprieta 😬 la mandíbula para abrir",
    val jawWindowMs: Long = 4000L,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList()
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions) {

    private var keyTurned  = false
    private var waitingJaw = false

    override fun start() {
        keyTurned  = false
        waitingJaw = false
        if (!hasRobotSpeech) onTemiSpeak?.invoke(narration)
    }

    override fun abort() {
        keyTurned      = false
        waitingJaw     = false
        onTemiSpeakDone = null
        super.abort()
    }

    override fun onBlink() {
        if (keyTurned) return
        keyTurned  = true
        waitingJaw = true
        val secs = jawWindowMs / 1000
        onFeedback?.invoke("🔑 ¡Llave girada! — Aprieta la mandíbula en ${secs}s", true)
        onTemiSpeak?.invoke("¡Llave girada! Ahora aprieta la mandíbula.")
        // El timer de mandíbula empieza cuando el robot TERMINA de hablar,
        // no en el mismo instante — si no el usuario no tiene tiempo real de reaccionar.
        onTemiSpeakDone = {
            handler.postDelayed({
                if (waitingJaw) {
                    waitingJaw = false
                    keyTurned  = false
                    onFeedback?.invoke("⏱ Tiempo agotado. Inténtalo de nuevo.", false)
                    onTemiSpeak?.invoke("Se acabó el tiempo. Vuelve a intentarlo.")
                }
            }, jawWindowMs)
        }
    }

    override fun onJawClench() {
        if (!waitingJaw) return
        waitingJaw      = false
        onTemiSpeakDone = null   // cancelar inicio pendiente del timer si TTS aún no acabó
        handler.removeCallbacksAndMessages(null)
        onFeedback?.invoke("🚪 ¡La puerta se abre!", true)
        onTemiSpeak?.invoke("¡Lo habéis conseguido!")
        onTemiSpeakDone = { handler.postDelayed({ onSuccess?.invoke() }, 300L) }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: ANIMACIÓN ROBOT — acciones del robot + avance automático
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Sala sin reto BCI: el robot ejecuta sus [robotActions] (gestionadas por el
 * motor antes de llamar a [start]) y la sala avanza automáticamente tras
 * [delayMs] milisegundos. Ideal como interludio de celebración o transición.
 */
class RobotAnimModule(
    title: String,
    narration: String,
    hint: String = "El robot está actuando…",
    val delayMs: Long = 3000L,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList()
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions) {

    override fun start() {
        if (!hasRobotSpeech) onTemiSpeak?.invoke(narration)
        onHintChanged?.invoke(hint)
        handler.postDelayed({
            onFeedback?.invoke("✅ Continuando…", true)
            handler.postDelayed({ onSuccess?.invoke() }, 800L)
        }, delayMs)
    }

    override fun abort() {
        super.abort()
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MÓDULO: ESTADO CON VÍDEO — mantener estado EEG mientras se ve un vídeo
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Reproduce un vídeo en bucle con dos modos según [targetState]:
 *
 * - **null** — el vídeo se reproduce durante [secondsRequired] segundos y
 *   la sala avanza automáticamente sin requerir ningún estado EEG. Útil para
 *   vídeos narrativos o introductores que no necesitan interacción.
 *
 * - **MentalState** — el usuario debe mantener ese estado (CALM o ATTENTION)
 *   durante [secondsRequired] segundos consecutivos. Si el estado cambia, el
 *   contador se reinicia (el vídeo sigue corriendo).
 */
class VideoStateModule(
    title: String,
    narration: String,
    hint: String = "Mantén el estado mental requerido",
    /** null = solo reproducir el vídeo N segundos y avanzar automáticamente. */
    val targetState: MentalState?,
    val secondsRequired: Int = 5,
    videoResId: Int? = null,
    videoPath:  String? = null,
    robotActions: List<RobotAction> = emptyList()
) : RoomModule(title, narration, hint, videoResId, videoPath, robotActions) {

    private var seconds    = 0
    private var tickActive = false
    /** Ver CalmModule: impide que la sala se vuelva a superar en bucle. */
    private var completed  = false

    private val tick = object : Runnable {
        override fun run() {
            if (!tickActive) return
            seconds++
            val label = when (targetState) {
                MentalState.CALM      -> "calma 🧘"
                MentalState.ATTENTION -> "atención 🎯"
                else                  -> "vídeo ▶"
            }
            onHintChanged?.invoke("$seconds / $secondsRequired s — $label")
            if (seconds >= secondsRequired) {
                tickActive = false
                completed  = true
                onStopConcurrentVideo?.invoke()
                onFeedback?.invoke("✅ ¡Superado!", true)
                onTemiSpeak?.invoke("¡Perfecto! Lo has conseguido.")
                handler.postDelayed({ onSuccess?.invoke() }, 1500L)
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun start() {
        seconds    = 0
        tickActive = false
        completed  = false
        if (!hasRobotSpeech) onTemiSpeak?.invoke(narration)
        if (videoPath != null || videoResId != null) onStartConcurrentVideo?.invoke(videoPath)

        if (targetState == null) {
            // Modo libre: avanzar automáticamente tras secondsRequired segundos
            val hintLabel = if (secondsRequired > 0) "Reproduciendo vídeo… ($secondsRequired s)" else "Reproduciendo vídeo…"
            onHintChanged?.invoke(hintLabel)
            tickActive = true
            handler.post(tick)
        }
    }

    override fun abort() {
        tickActive = false
        onStopConcurrentVideo?.invoke()
        super.abort()
    }

    override fun onMentalState(state: MentalState) {
        if (completed) return                // sala ya superada
        val target = targetState ?: return   // modo libre: ignorar estado EEG
        if (state == target) {
            if (!tickActive) { tickActive = true; handler.post(tick) }
        } else {
            if (tickActive) {
                tickActive = false
                handler.removeCallbacks(tick)
                seconds = 0
                val label = if (target == MentalState.CALM) "calma 🧘" else "atención 🎯"
                onHintChanged?.invoke("Mantén la $label…")
                onFeedback?.invoke("Mantén el estado…", false)
            }
        }
    }
}
