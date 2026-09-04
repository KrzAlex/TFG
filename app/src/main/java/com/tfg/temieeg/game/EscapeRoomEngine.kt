package com.tfg.temieeg.game

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.eeg.MuseReceiver

/**
 * Motor genérico de Escape Room.
 *
 * Acepta cualquier [EscapeRoomDef] y ejecuta sus módulos en orden.
 * Los módulos se comunican con el motor mediante callbacks internos;
 * el motor expone callbacks hacia la UI a través de las propiedades públicas.
 *
 * Uso típico:
 *   engine.onRoomChanged      = { current, total, title -> ... }
 *   engine.onNarration        = { text -> ... }
 *   ...
 *   engine.load(EscapeRoomCatalog.CLASSIC)
 *   engine.start()
 *
 * Ciclo de vida:
 *   load()  →  start()  →  [módulos en orden]  →  onCompleted()
 *                 ↑                                      |
 *                 └──────────── abort() ─────────────────┘  (en cualquier momento)
 */
class EscapeRoomEngine {

    // ── Callbacks de UI ──────────────────────────────────────────────────────

    /**
     * Se llama al entrar en cada sala.
     * Parámetros: (salaActual: Int, totalSalas: Int, tituloSala: String)
     */
    var onRoomChanged: ((Int, Int, String) -> Unit)? = null

    /** Narración del inicio de sala (el robot la lee). */
    var onNarration: ((String) -> Unit)? = null

    /** Pista de acción — puede actualizarse varias veces durante la sala. */
    var onHint: ((String) -> Unit)? = null

    /** Feedback de éxito/fallo. (mensaje, esÉxito) */
    var onFeedback: ((String, Boolean) -> Unit)? = null

    /** Símbolos Morse en curso (solo relevante en [MorseModule]). */
    var onMorseSymbols: ((String) -> Unit)? = null

    /** Se llama al superar el último módulo. */
    var onCompleted: (() -> Unit)? = null

    /** Se llama cada vez que se supera una sala (antes de avanzar a la siguiente). */
    var onCelebrate: (() -> Unit)? = null

    /**
     * Se llama cuando una respuesta bifurca el recorrido, con los indices de
     * sala de origen y destino (0-based). Permite dejar constancia en el log de
     * que rama tomo el jugador, que si no seria imposible de reconstruir.
     */
    var onBranch: ((from: Int, to: Int) -> Unit)? = null

    /** Suceso propio de un módulo para el registro de sesión: (tipo, detalle). */
    var onModuleEvent: ((String, String) -> Unit)? = null

    /** Permite al motor ajustar el debounce de parpadeo en [MuseDirectReceiver]. */
    var onSetBlinkDebounce: ((Long) -> Unit)? = null

    /**
     * Llamar desde fuera cuando el TTS del robot termina (COMPLETED / CANCELED / ERROR).
     * El dispatcher de acciones lo usa para avanzar al siguiente comando tras un SPEAK.
     */
    fun onTtsEnded() {
        // Cancelar el timeout de seguridad: el TTS respondió a tiempo.
        pendingTtsTimeout?.let { handler.removeCallbacks(it) }
        pendingTtsTimeout = null
        // Programa la reactivación del BCI con margen de gracia.
        scheduleTemiSpeechEnd()
        val cont = pendingTtsContinuation ?: return
        pendingTtsContinuation = null
        handler.post(cont)
    }

    /** Llamar cuando el robot llega a su destino (COMPLETE / ABORT). */
    fun onGoToEnded() {
        pendingGotoTimeout?.let { handler.removeCallbacks(it) }
        pendingGotoTimeout = null
        val cont = pendingGotoContinuation ?: return
        pendingGotoContinuation = null
        handler.post(cont)
    }

    /**
     * Se llama cuando cambia el estado de habla del robot.
     * true  → el robot acaba de empezar a hablar (BCI bloqueada).
     * false → el robot ha terminado de hablar + gracia [BCI_GRACE_MS] (BCI reactivada).
     * Útil para mostrar/ocultar un indicador visual en la UI.
     */
    var onTemiSpeakingChanged: ((Boolean) -> Unit)? = null

    /** Texto que debe leer el robot Temi en voz alta. */
    var onTemiSpeak: ((String) -> Unit)? = null

    /**
     * Acción del robot al iniciar una sala (SPEAK, GOTO, TILT_HEAD).
     * La UI lo delega a [TemiController].
     */
    var onRobotAction: ((RobotAction) -> Unit)? = null

    /**
     * Reproducir vídeo introductorio de una sala.
     * Parámetros: (resId: Int?, path: String?, onComplete: () -> Unit)
     * resId  → vídeo embebido en res/raw/   (niveles del catálogo)
     * path   → ruta absoluta en almacenamiento interno (niveles personalizados)
     * La UI reproduce el vídeo y llama a onComplete() al terminar o al saltarlo.
     * Si este callback es null, el módulo arranca directamente sin vídeo.
     */
    var onPlayVideo: ((resId: Int?, path: String?, onComplete: () -> Unit) -> Unit)? = null

    /**
     * Inicia un vídeo en bucle simultáneamente al reto ([VideoStateModule]).
     * La UI NO llama a ningún onComplete — el vídeo sigue hasta que el motor
     * llame a [onStopConcurrentVideo] o se aborte el juego.
     */
    var onStartConcurrentVideo: ((path: String?) -> Unit)? = null

    /** Detiene el vídeo concurrente iniciado con [onStartConcurrentVideo]. */
    var onStopConcurrentVideo: (() -> Unit)? = null

    /**
     * Imagen de ayuda de la sala actual.
     * path = ruta absoluta al fichero, null = sin imagen (ocultar botón de referencia).
     */
    var onHintImage: ((path: String?) -> Unit)? = null

    /**
     * true al entrar en una sala Morse, false al salir.
     * La UI lo usa para mostrar el botón de referencia de código Morse.
     */
    var onMorseModule: ((Boolean) -> Unit)? = null

    // ── Estado interno ────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    private var def:          EscapeRoomDef? = null
    private var currentIndex: Int            = -1
    private var running:      Boolean        = false

    /** Continuación pendiente tras un SPEAK — se invoca cuando el TTS termina. */
    private var pendingTtsContinuation: (() -> Unit)? = null

    /** Runnable del timeout de seguridad del SPEAK activo — se cancela cuando llega el callback. */
    private var pendingTtsTimeout: Runnable? = null

    /**
     * true mientras el robot está hablando (SPEAK de robotActions o onTemiSpeak de un módulo).
     * Bloquea los eventos BCI discretos para evitar que el jugador registre gestos
     * accidentales mientras escucha al robot.
     * Los eventos de estado mental (CalmModule, VideoStateModule) no se bloquean.
     */
    private var temiSpeaking = false

    /**
     * Runnable programado para poner [temiSpeaking] = false tras el margen de gracia.
     * Se cancela si arranca un nuevo habla antes de que expire, evitando la condición
     * de carrera en que el timer antiguo pisaba el [temiSpeaking]=true del habla nuevo.
     */
    private var pendingTemiSpeakingReset: Runnable? = null

    /**
     * Momento (reloj monotono, el mismo que usa Handler.postDelayed) en que
     * arranco la locucion actual, y su duracion minima estimada.
     */
    private var speechStartedAt = 0L
    private var speechMinMs     = 0L

    /** Libera el BCI si el callback de fin de TTS no llega nunca. */
    private var pendingSpeechFailsafe: Runnable? = null

    /** Continuación pendiente tras un GOTO — se invoca cuando el robot llega. */
    private var pendingGotoContinuation: (() -> Unit)? = null

    /** Runnable del timeout de seguridad del GOTO activo — se cancela al llegar. */
    private var pendingGotoTimeout: Runnable? = null

    private val currentModule: RoomModule?
        get() = def?.modules?.getOrNull(currentIndex)

    /** true mientras hay una partida en curso. */
    val isRunning: Boolean get() = running

    /**
     * Nombre simple de la clase del módulo activo (p.ej. "CalmModule").
     * Útil para seleccionar el icono del módulo en la UI y para el log.
     * null si no hay partida en curso.
     */
    val currentModuleTypeName: String? get() = currentModule?.javaClass?.simpleName

    /** Nombre del nivel cargado (p.ej. "El Escape Clásico"). "" si no hay nivel cargado. */
    val currentLevelName: String get() = def?.name ?: ""

    /** true si el robot está hablando en este instante (bloquea también la entrada BCI). */
    val isTemiSpeaking: Boolean get() = temiSpeaking

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Carga un [EscapeRoomDef]. Debe llamarse antes de [start].
     * Si el juego está en curso, la llamada se ignora (aborta primero).
     */
    fun load(newDef: EscapeRoomDef) {
        if (running) {
            Log.w(TAG, "load() ignorado — juego en curso. Llama a abort() antes.")
            return
        }
        def = newDef
        log("Cargado: '${newDef.name}' — ${newDef.modules.size} salas")
    }

    /** Inicia el escape room cargado desde el primer módulo. */
    fun start() {
        val d = def ?: run { Log.e(TAG, "start() sin EscapeRoomDef — llama a load() antes"); return }
        if (running) return
        running      = true
        currentIndex = -1
        log("▶ Iniciando '${d.name}'")
        val introRes  = d.introVideoResId
        val introPath = d.introVideoPath
        if ((introRes != null || introPath != null) && onPlayVideo != null) {
            log("  → Vídeo de entrada (resId=$introRes, path=$introPath)")
            onPlayVideo!!.invoke(introRes, introPath) { if (running) advance(d) }
        } else {
            advance(d)
        }
    }

    /**
     * Salta la sala actual y avanza a la siguiente sin completar el reto.
     * La UI lo ofrece como botón de rescate cuando el jugador lleva demasiado
     * tiempo atascado en una sala (p. ej. señal EEG pobre o reto muy difícil).
     */
    fun skipCurrentRoom() {
        if (!running) return
        val d = def ?: return
        log("⏭ Sala ${currentIndex + 1} saltada por el usuario")
        advance(d)   // advance() ya aborta el módulo actual y avanza al siguiente
    }

    /** Detiene el juego en cualquier momento y restaura los valores por defecto. */
    fun abort() {
        currentModule?.abort()
        running                = false
        currentIndex           = -1
        pendingTemiSpeakingReset?.let { handler.removeCallbacks(it) }
        pendingTemiSpeakingReset = null
        pendingSpeechFailsafe?.let { handler.removeCallbacks(it) }
        pendingSpeechFailsafe    = null
        temiSpeaking             = false
        pendingTtsContinuation   = null
        pendingTtsTimeout?.let { handler.removeCallbacks(it) }
        pendingTtsTimeout        = null
        pendingGotoContinuation  = null
        pendingGotoTimeout?.let  { handler.removeCallbacks(it) }
        pendingGotoTimeout       = null
        restoreDefaults()
        handler.removeCallbacksAndMessages(null)
        log("⏹ Juego abortado")
    }

    // ── Entrada de eventos BCI ────────────────────────────────────────────────

    // Los eventos de estado mental NO se bloquean: CalmModule y VideoStateModule
    // necesitan la señal continua incluso mientras el robot habla.
    fun onMentalStateUpdate(state: MentalState) { if (running) currentModule?.onMentalState(state) }

    // Los gestos discretos SÍ se bloquean durante el habla del robot.
    fun onBlink()     { if (running && !temiSpeaking) currentModule?.onBlink()     }
    fun onJawClench() { if (running && !temiSpeaking) currentModule?.onJawClench() }
    fun onNod()       { if (running && !temiSpeaking) currentModule?.onNod()       }
    fun onShake()     { if (running && !temiSpeaking) currentModule?.onShake()     }
    /**
     * Amplitud de ruido ambiente (0–32 767) — reenvía al módulo activo.
     * Se bloquea mientras el robot habla ([temiSpeaking]=true) para evitar que el
     * micrófono recoja la propia voz de Temi y dispare avisos de silencio en bucle.
     */
    fun onNoiseLevel(amplitude: Int) { if (running && !temiSpeaking) currentModule?.onNoiseLevel(amplitude) }

    // ── Lógica interna ────────────────────────────────────────────────────────

    private fun advance(d: EscapeRoomDef, targetIndex: Int = currentIndex + 1) {
        // Limpiar módulo anterior y restaurar defaults
        currentModule?.abort()
        restoreDefaults()
        handler.removeCallbacksAndMessages(null)

        // removeCallbacksAndMessages también borró el reset programado de temiSpeaking.
        // Si el robot estaba hablando, reprogramar el desbloqueo: garantiza que el BCI
        // no quede bloqueado para siempre (el próximo beginTemiSpeech lo cancela si
        // el módulo nuevo vuelve a hablar, que es el caso habitual).
        if (temiSpeaking) scheduleTemiSpeechEnd()

        currentIndex = targetIndex

        if (currentIndex >= d.modules.size) {
            complete(); return
        }

        val module = d.modules[currentIndex]
        val total  = d.modules.size

        wireModule(module, d)

        onRoomChanged?.invoke(currentIndex + 1, total, module.title)
        onMorseModule?.invoke(module is MorseModule)
        onHintImage?.invoke(module.hintImagePath)
        onNarration?.invoke(module.narration)
        onHint?.invoke(module.hint)
        onMorseSymbols?.invoke("")
        onFeedback?.invoke("", false)

        log("▶ Sala ${currentIndex + 1}/$total — '${module.title}'")

        val vid  = module.videoResId
        val path = module.videoPath

        // Lanza las acciones del robot en secuencia y después llama a module.start().
        // Cada acción se ejecuta cuando termina la anterior:
        //   WAIT   → espera N ms con postDelayed
        //   SPEAK  → espera al callback onTtsEnded() (fin real del TTS)
        //   resto  → espera 1000 ms fijos para dar tiempo al movimiento
        val launchModule: () -> Unit = {
            val actions = module.robotActions
            if (actions.isNotEmpty()) {
                dispatchActions(actions) { if (running) module.start() }
            } else {
                module.start()
            }
        }

        if ((vid != null || path != null) && onPlayVideo != null) {
            log("  → Vídeo introductorio (resId=$vid, path=$path)")
            onPlayVideo!!.invoke(vid, path) { if (running) launchModule() }
        } else {
            launchModule()
        }
    }

    /**
     * Ejecuta [actions] una a una, esperando el tiempo correcto entre cada par:
     *   WAIT  → postDelayed N ms
     *   SPEAK → pausa hasta que [onTtsEnded] confirme el fin real del TTS
     *   resto → postDelayed 1000 ms (tiempo para que el movimiento arranque)
     * Al acabar llama a [onDone].
     */
    private fun dispatchActions(actions: List<RobotAction>, onDone: () -> Unit) {
        if (!running || actions.isEmpty()) {
            log("dispatchActions → done (running=$running, actions=${actions.size})")
            if (running) handler.post(onDone)
            return
        }
        val head = actions.first()
        val tail = actions.drop(1)
        log("dispatchActions → ${head.type} \"${head.param}\" (${tail.size} restantes)")
        when (head.type) {
            RobotAction.Type.WAIT -> {
                val ms = head.param.toLongOrNull() ?: 1000L
                handler.postDelayed({ dispatchActions(tail, onDone) }, ms)
            }
            RobotAction.Type.SPEAK -> {
                beginTemiSpeech(head.param)
                onRobotAction?.invoke(head)
                pendingTtsContinuation = { dispatchActions(tail, onDone) }
                // Timeout de seguridad: si onTtsStatusChanged no llega en el tiempo
                // esperado (p.ej. sin internet o TTS service caído), continuamos solos.
                // Estimación: ~120 ms por carácter + 5 s de margen, entre 8 s y 60 s.
                // Temi habla despacio, 120 ms/carácter da mejor margen que 70 ms.
                val ttsTimeoutMs = (head.param.length * 120L + 5_000L).coerceIn(8_000L, 60_000L)
                val ttsTimeout = Runnable {
                    log("⚠ SPEAK timeout (${ttsTimeoutMs}ms) — continuando sin callback de TTS")
                    pendingTtsTimeout = null
                    if (pendingTtsContinuation != null) {
                        pendingTtsContinuation = null
                        scheduleTemiSpeechEnd()
                        dispatchActions(tail, onDone)
                    }
                }
                pendingTtsTimeout = ttsTimeout
                handler.postDelayed(ttsTimeout, ttsTimeoutMs)
            }
            RobotAction.Type.GOTO -> {
                onRobotAction?.invoke(head)
                pendingGotoContinuation = { dispatchActions(tail, onDone) }
                // Timeout de seguridad: se cancela en onGoToEnded() para no re-disparar
                val timeout = Runnable {
                    log("⚠ GOTO timeout — continuando sin callback de llegada")
                    pendingGotoTimeout = null
                    pendingGotoContinuation = null
                    dispatchActions(tail, onDone)
                }
                pendingGotoTimeout = timeout
                handler.postDelayed(timeout, 60_000L)
            }
            else -> {
                onRobotAction?.invoke(head)
                handler.postDelayed({ dispatchActions(tail, onDone) }, 1000L)
            }
        }
    }

    /**
     * Conecta los callbacks internos del módulo con los del motor.
     * El módulo llama a [onSuccess] cuando supera el reto;
     * el motor avanza automáticamente al siguiente módulo.
     */
    private fun wireModule(module: RoomModule, d: EscapeRoomDef) {
        module.onSuccess = {
            handler.post {
                onCelebrate?.invoke()
                val nextIdx = currentIndex + 1
                val tvRes  = d.transitionVideoResId
                val tvPath = d.transitionVideoPath
                if ((tvRes != null || tvPath != null) && nextIdx < d.modules.size && onPlayVideo != null) {
                    log("  → Vídeo de transición antes de sala ${nextIdx + 1}")
                    onPlayVideo!!.invoke(tvRes, tvPath) { if (running) advance(d) }
                } else {
                    advance(d)
                }
            }
        }
        module.onSuccessAt              = { idx ->
            handler.post {
                onBranch?.invoke(currentIndex, idx)
                onCelebrate?.invoke()
                advance(d, idx)
            }
        }
        module.onFeedback               = onFeedback
        module.onMorseSymbols           = onMorseSymbols
        // Intercepta el habla del módulo: cancela resets pendientes y activa el bloqueo BCI
        module.onTemiSpeak              = { text -> beginTemiSpeech(text); onTemiSpeak?.invoke(text) }
        module.onSetBlinkDebounce       = onSetBlinkDebounce
        module.onHintChanged            = onHint
        module.onLogEvent               = { type, detail -> onModuleEvent?.invoke(type, detail) }
        module.onStartConcurrentVideo   = onStartConcurrentVideo
        module.onStopConcurrentVideo    = onStopConcurrentVideo
    }

    private fun complete() {
        running = false
        log("🎉 '${def?.name}' completado")
        onCompleted?.invoke()
    }

    /** Restaura el debounce de parpadeo al valor por defecto tras cada sala. */
    private fun restoreDefaults() {
        onSetBlinkDebounce?.invoke(MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS)
    }

    /**
     * Marca el inicio de un habla del robot: cancela cualquier reset pendiente
     * y activa el bloqueo BCI.  Llamar siempre que el robot vaya a hablar.
     */
    private fun beginTemiSpeech(text: String? = null) {
        pendingTemiSpeakingReset?.let { handler.removeCallbacks(it) }
        pendingTemiSpeakingReset = null
        pendingSpeechFailsafe?.let { handler.removeCallbacks(it) }
        pendingSpeechFailsafe = null

        // Duracion minima estimada del habla. El callback de fin de TTS no es
        // fiable como unica referencia: Temi lo notifica en cuanto acepta la
        // peticion en algunos firmwares, y el modo simulado lo falsea. Si nos
        // fiamos solo de el, el BCI se desbloquea con el robot aun hablando y
        // el eco de los altavoces se cuela como parpadeos o gestos.
        speechStartedAt = android.os.SystemClock.uptimeMillis()
        speechMinMs = estimateSpeechMs(text)

        if (!temiSpeaking) {
            temiSpeaking = true
            onTemiSpeakingChanged?.invoke(true)
            log("🔒 BCI bloqueada — robot hablando (~${speechMinMs}ms estimados)")
        }

        // Red de seguridad: si el callback de fin de TTS no llega nunca, el BCI
        // quedaria bloqueado para siempre. Se libera solo pasado un margen amplio.
        val failsafe = Runnable {
            pendingSpeechFailsafe = null
            if (temiSpeaking) {
                log("⚠ Sin callback de fin de TTS — liberando BCI por seguridad")
                scheduleTemiSpeechEnd()
            }
        }
        pendingSpeechFailsafe = failsafe
        handler.postDelayed(failsafe, speechMinMs + SPEECH_FAILSAFE_EXTRA_MS)
    }

    /**
     * Estima cuanto durara una locucion a partir de su longitud. Temi habla
     * despacio; [SPEECH_MS_PER_CHAR] esta calibrado por debajo de su ritmo real
     * para no penalizar al jugador mas de lo necesario.
     */
    private fun estimateSpeechMs(text: String?): Long {
        val n = text?.length ?: 0
        if (n == 0) return SPEECH_MIN_MS
        return (n * SPEECH_MS_PER_CHAR).coerceIn(SPEECH_MIN_MS, SPEECH_MAX_MS)
    }

    /**
     * Programa la desactivación del bloqueo BCI tras [BCI_GRACE_MS] ms.
     * Si un nuevo habla arranca antes de que expire, [beginTemiSpeech] cancela
     * este timer y evita que pise el nuevo [temiSpeaking]=true.
     */
    private fun scheduleTemiSpeechEnd() {
        pendingTemiSpeakingReset?.let { handler.removeCallbacks(it) }
        pendingSpeechFailsafe?.let { handler.removeCallbacks(it) }
        pendingSpeechFailsafe = null

        // No liberar antes de que la locucion haya podido terminar de verdad:
        // se espera lo que falte de la duracion estimada y, encima, la gracia
        // para que se disipe el eco de los altavoces.
        val elapsed   = android.os.SystemClock.uptimeMillis() - speechStartedAt
        val remaining = (speechMinMs - elapsed).coerceAtLeast(0L)
        val delay     = remaining + BCI_GRACE_MS

        val reset = Runnable {
            pendingTemiSpeakingReset = null
            temiSpeaking = false
            onTemiSpeakingChanged?.invoke(false)
            log("🔓 BCI desbloqueada (fin TTS + gracia ${BCI_GRACE_MS}ms)")
            // Notifica al módulo activo (one-shot) para que encadene lógica tras el habla
            currentModule?.onTemiSpeakDone?.also { currentModule?.onTemiSpeakDone = null }?.invoke()
        }
        pendingTemiSpeakingReset = reset
        handler.postDelayed(reset, delay)
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    companion object {
        private const val TAG          = "EscapeRoomEngine"
        /**
         * Ms de margen tras el fin del TTS antes de volver a aceptar gestos BCI.
         * 1 200 ms da tiempo suficiente para que el eco acústico de los altavoces
         * de Temi se disipe y no genere parpadeos/mandíbulas falsos en la diadema Muse.
         */
        private const val BCI_GRACE_MS = 1_200L

        /** Ritmo estimado de habla (ms por caracter) para la duracion minima. */
        private const val SPEECH_MS_PER_CHAR = 60L
        private const val SPEECH_MIN_MS      = 1_200L
        private const val SPEECH_MAX_MS      = 20_000L
        /** Margen sobre la duracion estimada antes de liberar por seguridad. */
        private const val SPEECH_FAILSAFE_EXTRA_MS = 8_000L
    }
}
