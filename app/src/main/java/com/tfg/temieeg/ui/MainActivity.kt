package com.tfg.temieeg.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tfg.temieeg.robot.NoiseMonitor
import java.net.Inet4Address
import java.net.NetworkInterface
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.robotemi.sdk.Robot
import com.tfg.temieeg.R
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.data.SessionEntry
import androidx.activity.viewModels
import com.tfg.temieeg.databinding.ActivityMainBinding
import com.tfg.temieeg.eeg.HeadGestureDetector
import com.tfg.temieeg.eeg.MentalStateProcessor
import com.tfg.temieeg.eeg.MorseDecoder
import com.tfg.temieeg.eeg.MuseDirectReceiver
import com.tfg.temieeg.eeg.MuseReceiver
import com.tfg.temieeg.eeg.OSCReceiver
import androidx.appcompat.app.AlertDialog
import android.content.SharedPreferences
import android.widget.RadioGroup
import com.tfg.temieeg.game.CustomLevelStorage
import com.tfg.temieeg.game.EscapeRoomCatalog
import com.tfg.temieeg.game.EscapeRoomEngine
import com.tfg.temieeg.game.RobotAction
import android.widget.LinearLayout
import android.widget.TextView
import com.tfg.temieeg.logging.SessionLogger
import com.tfg.temieeg.robot.TemiController

/**
 * Activity principal — ata todos los módulos del pipeline:
 *
 *   MuseReceiver (OSC|BT) → MentalStateProcessor → TemiController
 *                                     ↘ SessionLogger
 *                                     ↘ UI (binding)
 *
 * Funciona tanto en el robot Temi como en cualquier móvil Android:
 * si [Robot.getInstance()] lanza una excepción (hardware no-Temi),
 * [TemiController] opera en modo simulado y loguea las acciones en Logcat.
 *
 * Ver TFG_CONTEXT_CLAUDE_CODE.md §5.4 y §10.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * Intenta obtener el singleton de Temi SDK.
     * Devuelve null si el dispositivo no es un robot Temi.
     */
    private val robot: Robot? by lazy {
        try {
            Robot.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Dispositivo no-Temi — TemiController en modo simulado: ${e.message}")
            null
        }
    }

    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private lateinit var temiController: TemiController
    // ── Pipeline BCI (propiedad de EegViewModel: sobrevive a cambios de config) ──
    private val eegViewModel: EegViewModel by viewModels()
    private val processor get() = eegViewModel.processor
    private lateinit var activeReceiver: MuseReceiver
    private lateinit var sessionLogger: SessionLogger
    private lateinit var prefs: SharedPreferences

    /** Modo de conexión activo. */
    private var connectionMode: ConnectionMode = ConnectionMode.OSC

    enum class ConnectionMode { OSC, BLUETOOTH }

    /** true mientras una sesión de grabación está activa. */
    private var sessionRunning = false

    /** Contadores de parpadeo. */
    private var blinkTotal   = 0
    private var blinkSession = 0
    private val blinkHandler = Handler(Looper.getMainLooper())

    /** Contadores de mandíbula. */
    private var jawTotal   = 0
    private var jawSession = 0
    private val jawHandler = Handler(Looper.getMainLooper())

    /** Decodificador Morse + estado del modo. */
    private val morseDecoder get() = eegViewModel.morseDecoder
    private var morseMode    = false

    /** Detector de gestos de cabeza (giroscopio). */
    private val headGestureDetector get() = eegViewModel.headGestureDetector
    private var nodTotal   = 0
    private var shakeTotal = 0
    private val nodHandler   = Handler(Looper.getMainLooper())
    private val shakeHandler = Handler(Looper.getMainLooper())

    /** Motor modular de Escape Room. */
    private val escapeRoomEngine get() = eegViewModel.escapeRoomEngine
    private var escapeRoomActive = false

    /**
     * Botón de rescate: si el jugador lleva [SKIP_OFFER_DELAY_MS] en la misma
     * sala sin superarla, aparece el botón «Saltar sala» para poder avanzar.
     * Se oculta y el contador se reinicia al entrar en cada sala.
     */
    private val skipOfferHandler  = Handler(Looper.getMainLooper())
    private val skipOfferRunnable = Runnable {
        if (escapeRoomActive) binding.btnEscapeSkip.visibility = View.VISIBLE
    }

    /** Sala activa — se actualiza con onRoomChanged para el log CSV. */
    private var currentRoomIndex = -1
    private var currentRoomTitle = ""

    /** Texto del hint guardado antes de mostrar el indicador de robot hablando. */
    private var savedHintBeforeSpeaking: String = ""

    /**
     * Logger dedicado a partidas de juego — independiente del [sessionLogger] manual.
     * Se inicia automáticamente al arrancar una partida y se exporta al terminar.
     * Nombre del fichero: game_<nivel>_<timestamp>.csv
     */
    private lateinit var gameLogger: SessionLogger
    private var gameLogging = false
    private var currentEscapeRoomName = ""

    /**
     * Última calidad de señal recibida (media HSI: 1=buena · 4=sin contacto).
     * -1 mientras no haya llegado ningún dato del dispositivo.
     * Se usa para bloquear gestos BCI cuando la banda no está bien colocada.
     */
    private var currentSignalQuality = -1f

    /** Flags de gesto pendiente para el log — se consumen en el siguiente tick EEG. */
    private var pendingBlink      = false
    private var pendingJawClench  = false
    private var pendingNod        = false
    private var pendingShake      = false

    // ── Editor de niveles ─────────────────────────────────────────────────────

    /**
     * Editor de niveles del Escape Room (ver [LevelEditorController]).
     * Se crea en [onCreate] porque registra ActivityResultLaunchers,
     * que deben quedar registrados antes de onStart.
     */
    private lateinit var levelEditor: LevelEditorController

    /** Monitor de ruido para la sala Morse (requiere RECORD_AUDIO). */
    private val noiseMonitor by lazy { NoiseMonitor(this) }
    /** true si el usuario ha habilitado el micrófono en Configuración. */
    private var noiseMicEnabled = true

    /** Launcher de permiso RECORD_AUDIO — inicia el monitor si se concede. */
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                noiseMonitor.start()
                Log.d(TAG, "RECORD_AUDIO concedido — NoiseMonitor iniciado")
            } else {
                Log.w(TAG, "RECORD_AUDIO denegado — NoiseMonitor inactivo")
                Toast.makeText(this,
                    "Sin permiso de micrófono, el modo silencio estará desactivado",
                    Toast.LENGTH_LONG).show()
            }
        }

    /** Launcher de permiso BLUETOOTH_CONNECT + BLUETOOTH_SCAN (Android 12+). */
    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                Log.d(TAG, "Permisos BT concedidos — iniciando MuseDirectReceiver")
                pendingBluetoothAction?.invoke()
            } else {
                Log.w(TAG, "Permisos BT denegados — sin conexión MUSE")
                Toast.makeText(this,
                    "Sin permiso Bluetooth no se puede conectar al MUSE",
                    Toast.LENGTH_LONG).show()
            }
            pendingBluetoothAction = null
        }

    /**
     * Solicita los permisos necesarios para el escaneo BLE según la versión de Android:
     *  - API 31+ (Android 12+): BLUETOOTH_CONNECT + BLUETOOTH_SCAN
     *  - API < 31 (Android 10/11): ACCESS_FINE_LOCATION (obligatorio para BLE scan)
     */
    private fun ensureBluetoothPermissions(onGranted: () -> Unit) {
        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            pendingBluetoothAction = onGranted
            requestBluetoothPermission.launch(missing.toTypedArray())
        }
    }

    private var pendingBluetoothAction: (() -> Unit)? = null

    // ── Gestión del receiver activo ───────────────────────────────────────────

    /** Crea el receiver adecuado según el modo indicado. */
    private fun createReceiver(mode: ConnectionMode): MuseReceiver =
        when (mode) {
            ConnectionMode.OSC       -> OSCReceiver()
            ConnectionMode.BLUETOOTH -> MuseDirectReceiver(this)
        }

    /**
     * Arranca [activeReceiver]:
     *   - Modo OSC: directamente (no necesita permisos especiales).
     *   - Modo BT: solicita permisos Bluetooth en Android 12+ antes de arrancar.
     */
    private fun startActiveReceiver() {
        when (connectionMode) {
            ConnectionMode.OSC       -> activeReceiver.start()
            ConnectionMode.BLUETOOTH -> ensureBluetoothPermissions { activeReceiver.start() }
        }
    }

    /**
     * Cambia el modo de conexión en caliente:
     *   1. Detiene el receiver actual.
     *   2. Crea el nuevo receiver y lo conecta al pipeline.
     *   3. Lo arranca (con solicitud de permisos si hace falta).
     *   4. Guarda la selección en SharedPreferences.
     */
    private fun switchConnectionMode(newMode: ConnectionMode) {
        if (newMode == connectionMode) return
        activeReceiver.stop()
        connectionMode = newMode
        activeReceiver = createReceiver(newMode)
        wireReceiverCallbacks()
        startActiveReceiver()
        prefs.edit().putString(PREF_CONNECTION_MODE, newMode.name).apply()
        Log.i(TAG, "Modo de conexión cambiado a $newMode")
    }

    /**
     * Conecta los callbacks del pipeline al [activeReceiver] actual.
     * Se llama tanto en [setupOscPipeline] (primera vez) como en [switchConnectionMode].
     */
    private fun wireReceiverCallbacks() {
        activeReceiver.onMuseDataReceived = { museState ->
            runOnUiThread {
                // El ViewModel es el punto de entrada del procesado (publica su StateFlow).
                val state   = eegViewModel.process(museState)
                val metrics = eegViewModel.metrics.value
                temiController.onStateChanged(state)
                renderState(state, metrics)
                updateAccDisplay(museState.accX, museState.accY, museState.accZ)
                updateDeviceStatus(museState)
                headGestureDetector.addSample(museState.gyroX, museState.gyroY, museState.gyroZ)
                updateGyroDisplay(museState.gyroX, museState.gyroY, museState.gyroZ)
                currentSignalQuality = museState.signalQuality
                if (escapeRoomActive) escapeRoomEngine.onMentalStateUpdate(state)
                if (museState.blink)     { pendingBlink     = true; onBlink() }
                if (museState.jawClench) { pendingJawClench = true; onJawClench() }
                if (sessionRunning || gameLogging) {
                    val entry = SessionEntry(
                        timestamp      = museState.timestamp,
                        state          = state,
                        concentration  = metrics["concentration"]  ?: 0f,
                        mellow         = metrics["mellow"]         ?: 0f,
                        gammaActivity  = metrics["gammaActivity"]  ?: 0f,
                        alpha          = museState.alphaAbsolute,
                        beta           = museState.betaAbsolute,
                        theta          = museState.thetaAbsolute,
                        delta          = museState.deltaAbsolute,
                        gamma          = museState.gammaAbsolute,
                        signalQuality  = museState.signalQuality,
                        gyroY          = museState.gyroY,
                        gyroZ          = museState.gyroZ,
                        accX           = museState.accX,
                        accY           = museState.accY,
                        accZ           = museState.accZ,
                        blinkEvent     = pendingBlink,
                        jawClenchEvent = pendingJawClench,
                        nodEvent       = pendingNod,
                        shakeEvent     = pendingShake,
                        robotAction    = temiController.lastAction(),
                        roomIndex      = currentRoomIndex,
                        roomTitle      = currentRoomTitle,
                        escapeRoomName = currentEscapeRoomName,
                        moduleType     = escapeRoomEngine.currentModuleTypeName ?: "",
                        temiSpeaking   = escapeRoomEngine.isTemiSpeaking
                    )
                    if (sessionRunning) sessionLogger.log(entry)
                    if (gameLogging)    gameLogger.log(entry)
                    pendingBlink     = false
                    pendingJawClench = false
                    pendingNod       = false
                    pendingShake     = false
                }
            }
        }
        activeReceiver.onConnectingChanged = { isConnecting ->
            runOnUiThread {
                // Solo mostramos "Conectando…" si aún no estamos conectados
                if (isConnecting) {
                    val text = getString(R.string.muse_connecting)
                    binding.tvConnectionStatus.text     = text
                    binding.tvHomeConnectionStatus.text = text
                }
            }
        }
        activeReceiver.onConnectionStateChanged = { connected ->
            runOnUiThread {
                val text = if (connected) getString(R.string.muse_connected)
                           else           getString(R.string.muse_disconnected)
                binding.tvConnectionStatus.text     = text
                binding.tvHomeConnectionStatus.text = text
            }
        }
        activeReceiver.onElementsStateChanged = { elementsOk ->
            runOnUiThread {
                val text = if (elementsOk) getString(R.string.muse_connected)
                           else            getString(R.string.muse_no_elements)
                binding.tvConnectionStatus.text     = text
                binding.tvHomeConnectionStatus.text = text
            }
        }

        activeReceiver.onMuseDevicesFound = { names ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.muse_select_device))
                    .setItems(names.toTypedArray()) { _, i ->
                        activeReceiver.selectMuse(names[i])
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        temiController = TemiController(robot, applicationContext)
        sessionLogger  = SessionLogger(this)
        gameLogger     = SessionLogger(this)
        prefs          = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        connectionMode = if (prefs.getString(PREF_CONNECTION_MODE, ConnectionMode.OSC.name)
                             == ConnectionMode.BLUETOOTH.name) ConnectionMode.BLUETOOTH
                         else ConnectionMode.OSC
        activeReceiver = createReceiver(connectionMode)
        loadSavedSettings()

        levelEditor = LevelEditorController(
            activity       = this,
            binding        = binding,
            getLocations   = { temiController.getLocations() },
            onPlayLevel    = { def ->
                escapeRoomEngine.load(def)
                showHome()
                binding.levelEditorScreen.visibility = View.GONE
                startEscapeRoom()
            },
            onNavigateHome = { showHome() }
        )

        setupAccChart()
        setupGyroChart()
        setupOscPipeline()
        setupSessionButton()
        setupMorse()
        setupHeadGestures()
        setupEscapeRoom()
        levelEditor.setup()
        setupSettings()
        setupScreenNavigation()
        renderState(MentalState.NEUTRAL, emptyMap())

        // Avisa al usuario si está corriendo fuera del robot
        if (temiController.isSimulated) {
            Toast.makeText(this, "Modo prueba — sin hardware Temi", Toast.LENGTH_LONG).show()
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        startActiveReceiver()
        temiController.start()
        // Si la app vuelve al frente con una partida en curso, reactivar el micrófono
        if (escapeRoomActive) startNoiseMonitor()
    }

    override fun onStop() {
        super.onStop()
        activeReceiver.stop()
        temiController.stop()
        // El micrófono no debe seguir grabando con la app en segundo plano
        noiseMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        temiController.shutdown()

        // Los callbacks diferidos sobreviven a la Activity y mantienen viva su
        // referencia (y tocarían vistas ya destruidas). Se cancelan explícitamente.
        calibrationTimer?.cancel()
        calibrationTimer = null
        processor.cancelCalibration()
        listOf(blinkHandler, jawHandler, nodHandler, shakeHandler, skipOfferHandler)
            .forEach { it.removeCallbacksAndMessages(null) }

        // Cortar la entrada del pipeline: el receptor vive en su propio hilo y
        // podría emitir una ultima muestra hacia una UI ya destruida.
        activeReceiver.onMuseDataReceived      = null
        activeReceiver.onConnectionStateChanged = null
        activeReceiver.onConnectingChanged      = null
        noiseMonitor.onAmplitude                = null
    }

    // ── Pipeline MUSE → Procesador → Robot + UI ───────────────────────────────

    /** Conecta el receiver inicial al pipeline. El cambio de modo usa [wireReceiverCallbacks]. */
    private fun setupOscPipeline() = wireReceiverCallbacks()

    // ── Botón inicio / parada de sesión ───────────────────────────────────────

    private fun setupSessionButton() {
        binding.btnStartSession.setOnClickListener {
            if (sessionRunning) stopSession() else startSession()
        }
    }

    private fun startSession() {
        sessionLogger.clearSession()
        processor.reset()
        blinkSession = 0
        jawSession   = 0
        sessionRunning = true
        val label = getString(R.string.btn_stop_session)
        binding.btnStartSession.text     = label
        binding.btnHomeStartSession.text = label
    }

    private fun stopSession() {
        sessionRunning = false
        val label = getString(R.string.btn_start_session)
        binding.btnStartSession.text     = label
        binding.btnHomeStartSession.text = label

        // Serializacion + escritura en hilo de fondo: en sesiones largas son miles
        // de filas y bloqueaban la UI. El callback vuelve al hilo principal.
        sessionLogger.exportCSVAsync { file ->
            if (isFinishing || isDestroyed) return@exportCSVAsync
            if (file == null) {
                Toast.makeText(this, "Sin datos que guardar", Toast.LENGTH_SHORT).show()
                return@exportCSVAsync
            }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_session)))
        }
    }

    // ── Modo Morse ─────────────────────────────────────────────────────────────

    private fun setupMorse() {
        // Switch activa / desactiva el modo Morse
        binding.switchMorse.setOnCheckedChangeListener { _, isChecked ->
            morseMode = isChecked
            if (isChecked) {
                // Debounce corto para capturar el doble parpadeo rápido
                activeReceiver.blinkDebounceMs = MORSE_DEBOUNCE_MS
                morseDecoder.clear()
                Toast.makeText(this, "Modo Morse activado", Toast.LENGTH_SHORT).show()
            } else {
                // Volver al debounce normal
                activeReceiver.blinkDebounceMs = MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS
                Toast.makeText(this, "Modo Morse desactivado", Toast.LENGTH_SHORT).show()
            }
            binding.tvMorseCurrent.text = ""
            binding.tvMorseDecoded.text = morseDecoder.decodedText
        }

        // Botón borrar
        binding.btnMorseClear.setOnClickListener {
            morseDecoder.clear()
            binding.tvMorseCurrent.text = ""
            binding.tvMorseDecoded.text = ""
        }

        // Tabla de referencia: se genera una vez y se muestra/oculta con el botón ?
        binding.tvMorseReference.text = morseReference
        binding.btnMorseHelp.setOnClickListener {
            binding.tvMorseReference.visibility =
                if (binding.tvMorseReference.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Callbacks del decodificador → los Runnables internos ya van al main Looper
        morseDecoder.onSymbolsUpdated = { symbols ->
            binding.tvMorseCurrent.text = symbols
        }
        morseDecoder.onDecodedTextChanged = { text ->
            binding.tvMorseDecoded.text = text
            binding.tvMorseCurrent.text = ""
        }
    }

    /**
     * Genera una tabla de referencia Morse en dos columnas a partir de la
     * misma [MorseDecoder.MORSE_TABLE] que usa el decodificador, garantizando
     * que la referencia visual y la lógica siempre estén sincronizadas.
     */
    /** La tabla es constante: se construye una vez y se reutiliza. */
    private val morseReference: String by lazy { buildMorseReference() }

    private fun buildMorseReference(): String {
        val entries = MorseDecoder.MORSE_TABLE.entries
            .sortedWith(compareBy({ it.value.isDigit() }, { it.value }))
        val sb = StringBuilder()
        entries.forEachIndexed { i, (code, letter) ->
            val cell = "%-2s  %-6s".format(letter, code)
            if (i % 2 == 0) sb.append(cell) else sb.append("   ").append(cell).append('\n')
        }
        if (entries.size % 2 != 0) sb.append('\n')
        return sb.toString().trimEnd()
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /**
     * Actualiza texto del estado, color y barras de progreso.
     * [metrics] puede ser vacío (estado inicial).
     */
    private fun renderState(state: MentalState, metrics: Map<String, Float>) {
        // Texto localizado (una sola resolución de string para ambas pantallas)
        val stateText = when (state) {
            MentalState.STRESS    -> getString(R.string.state_stress)
            MentalState.ATTENTION -> getString(R.string.state_attention)
            MentalState.CALM      -> getString(R.string.state_calm)
            MentalState.NEUTRAL   -> getString(R.string.state_neutral)
        }

        // Color semántico por estado
        val colorRes = when (state) {
            MentalState.STRESS    -> R.color.state_stress
            MentalState.ATTENTION -> R.color.state_attention
            MentalState.CALM      -> R.color.state_calm
            MentalState.NEUTRAL   -> R.color.state_neutral
        }
        val stateColor = ContextCompat.getColor(this, colorRes)

        // Home — el orbe de estado mental solo se repinta si la pantalla se ve.
        if (homeScreenVisible) {
            binding.tvHomeMentalState.text = stateText
            binding.tvHomeMentalState.setTextColor(stateColor)
        }

        // El resto (texto grande, barras y bandas brutas) es exclusivo de Dev:
        // sin la pantalla visible nos ahorramos varios .format() por muestra.
        if (!devScreenVisible) return

        binding.tvMentalState.text = stateText
        binding.tvMentalState.setTextColor(stateColor)

        // Barras de progreso + valores numéricos — concentration y mellow están en [0, 1]
        val conc  = metrics["concentration"] ?: 0f
        val mellow = metrics["mellow"]       ?: 0f

        binding.pbConcentration.progress     = (conc   * 100).toInt()
        binding.pbMellow.progress            = (mellow * 100).toInt()
        binding.tvConcentrationValue.text    = "%.2f".format(conc)
        binding.tvMellowValue.text           = "%.2f".format(mellow)

        // Bandas brutas para calibración visual
        val alpha = metrics["alpha"] ?: 0f
        val beta  = metrics["beta"]  ?: 0f
        val theta = metrics["theta"] ?: 0f
        binding.tvRawBands.text = "α %.2f  β %.2f  θ %.2f".format(alpha, beta, theta)
    }

    /** Muestra el indicador de parpadeo brevemente y actualiza el contador. */
    private fun onBlink() {
        blinkTotal++
        if (sessionRunning) blinkSession++

        // Mostrar indicador visual 700 ms
        binding.tvBlinkIndicator.visibility = View.VISIBLE
        blinkHandler.removeCallbacksAndMessages(null)
        blinkHandler.postDelayed(
            { binding.tvBlinkIndicator.visibility = View.INVISIBLE },
            700L
        )

        // Contador: total siempre, sesión solo si está activa
        binding.tvBlinkCount.text = if (sessionRunning)
            "Parpadeos — sesión: $blinkSession  total: $blinkTotal"
        else
            "Parpadeos: $blinkTotal"

        Log.d(TAG, "Parpadeo detectado — total=$blinkTotal sesión=$blinkSession")

        // Morse libre (solo si no hay escape room activo)
        if (morseMode && !escapeRoomActive) morseDecoder.recordBlink()
        // Escape Room — ignorado si la señal EEG no es estable
        if (escapeRoomActive) {
            if (isBciSignalStable()) escapeRoomEngine.onBlink()
            else Log.d(TAG, "Parpadeo ignorado — señal inestable (quality=$currentSignalQuality)")
        }
    }

    /** Muestra el indicador de mandíbula brevemente y actualiza el contador. */
    private fun onJawClench() {
        jawTotal++
        if (sessionRunning) jawSession++

        // En modo escape room suprimimos el indicador visual si la señal no es estable,
        // para no confundir al usuario (el gesto no se enviará al motor de todas formas).
        val showIndicator = !escapeRoomActive || isBciSignalStable()
        if (showIndicator) {
            binding.tvJawIndicator.visibility = View.VISIBLE
            jawHandler.removeCallbacksAndMessages(null)
            jawHandler.postDelayed(
                { binding.tvJawIndicator.visibility = View.INVISIBLE },
                700L
            )
        }

        binding.tvJawCount.text = if (sessionRunning)
            "Mandíbula — sesión: $jawSession  total: $jawTotal"
        else
            "Mandíbula: $jawTotal"

        Log.d(TAG, "Mandíbula detectada — total=$jawTotal sesión=$jawSession")
        if (escapeRoomActive) {
            if (isBciSignalStable()) escapeRoomEngine.onJawClench()
            else Log.d(TAG, "Mandíbula ignorada — señal inestable (quality=$currentSignalQuality)")
        }
    }

    /** Actualiza indicadores de calidad de señal y batería. */
    private fun updateDeviceStatus(state: com.tfg.temieeg.data.MuseState) {
        // Calidad de señal (horseshoe: 1=buena, 2=regular, 4=mala)
        val sigText = when {
            state.signalQuality < 0f    -> getString(R.string.signal_unknown)
            state.signalQuality <= 1.5f -> getString(R.string.signal_good)
            state.signalQuality <= 2.5f -> getString(R.string.signal_ok)
            else                        -> getString(R.string.signal_bad)
        }
        val sigColor = when {
            state.signalQuality < 0f    -> androidx.core.content.ContextCompat.getColor(this, R.color.state_neutral)
            state.signalQuality <= 1.5f -> androidx.core.content.ContextCompat.getColor(this, R.color.state_calm)
            state.signalQuality <= 2.5f -> androidx.core.content.ContextCompat.getColor(this, R.color.state_attention)
            else                        -> androidx.core.content.ContextCompat.getColor(this, R.color.state_stress)
        }

        val battText = if (state.battery >= 0) "🔋 ${state.battery}%" else ""

        // La calidad de señal y la batería cambian muy rara vez, pero este método
        // corre a ~4 Hz: si nada ha cambiado evitamos reasignar texto/color (cada
        // setText invalida y vuelve a medir la vista).
        if (sigText == lastSigText && battText == lastBattText) return
        lastSigText  = sigText
        lastBattText = battText

        // Dev
        binding.tvSignalQuality.text = sigText
        binding.tvSignalQuality.setTextColor(sigColor)
        binding.tvBattery.text = battText
        // Home screen
        binding.tvHomeSignalQuality.text = sigText
        binding.tvHomeSignalQuality.setTextColor(sigColor)
        binding.tvHomeBattery.text = battText
    }

    /** Últimos valores pintados de señal/batería — evitan setText redundantes. */
    private var lastSigText: String? = null
    private var lastBattText: String? = null

    // ── Acelerómetro: flecha + gráfico ───────────────────────────────────────

    /** Contadores de muestras para los gráficos (crecen siempre). */
    private var chartX     = 0f
    private var gyroChartX = 0f

    /**
     * Inicializa el LineChart con tres DataSets vacíos (X=rojo, Y=verde, Z=azul).
     * Escala Y fija en ±2 g. Sin tocar ni escalar (solo lectura).
     */
    private fun setupAccChart() {
        val chart = binding.accChart

        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled  = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.legend.isEnabled = true

        chart.xAxis.isEnabled    = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            axisMinimum = -2f
            axisMaximum =  2f
            setDrawGridLines(true)
            setLabelCount(5, true)
        }

        fun makeDataSet(label: String, colorHex: String) =
            LineDataSet(mutableListOf(), label).apply {
                color = Color.parseColor(colorHex)
                setDrawCircles(false)
                lineWidth = 1.8f
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
            }

        chart.data = LineData(
            makeDataSet("X", "#E53935"),  // rojo
            makeDataSet("Y", "#43A047"),  // verde
            makeDataSet("Z", "#1E88E5")   // azul
        )
    }

    /**
     * Actualiza la flecha [HeadDirectionView] y añade una muestra al gráfico.
     * El gráfico muestra siempre los últimos [CHART_WINDOW] puntos.
     */
    private fun updateAccDisplay(x: Float, y: Float, z: Float) {
        // La flecha y la gráfica solo existen en la pantalla Dev: si está oculta
        // nos ahorramos el redibujado completo en cada muestra (~4 Hz).
        if (!devScreenVisible) return

        // Flecha de orientación
        binding.headDirectionView.update(x, y, z)

        // Gráfico rolling
        chartX++
        val data = binding.accChart.data ?: return
        data.addEntry(Entry(chartX, x), 0)
        data.addEntry(Entry(chartX, y), 1)
        data.addEntry(Entry(chartX, z), 2)
        trimToWindow(data)

        data.notifyDataChanged()
        binding.accChart.notifyDataSetChanged()
        binding.accChart.setVisibleXRangeMaximum(CHART_WINDOW)
        binding.accChart.moveViewToX(chartX)
    }

    /**
     * Inicializa el gráfico de giroscopio con dos canales:
     *   Y (verde) = pitch → asentir (NOD)
     *   Z (azul)  = yaw   → negar  (SHAKE)
     * X no se muestra en el gráfico (no se usa para gestos).
     * Escala ±200 °/s. Líneas verdes = umbral NOD · Líneas naranjas = umbral SHAKE.
     */
    private fun setupGyroChart() {
        val chart = binding.gyroChart

        chart.description.isEnabled  = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled           = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.legend.isEnabled        = true

        chart.xAxis.isEnabled         = false
        chart.axisRight.isEnabled     = false
        chart.axisLeft.apply {
            axisMinimum = -200f
            axisMaximum =  200f
            setDrawGridLines(true)
            setLabelCount(9, true)   // etiquetas en -200, -150, …, 0, …, 150, 200
            // Las líneas de umbral se añaden en setupGyroChart → updateGyroChartLimitLines()
        }

        fun makeSet(label: String, colorHex: String) =
            com.github.mikephil.charting.data.LineDataSet(mutableListOf(), label).apply {
                color = android.graphics.Color.parseColor(colorHex)
                setDrawCircles(false)
                lineWidth = 1.8f
                setDrawValues(false)
                mode = com.github.mikephil.charting.data.LineDataSet.Mode.LINEAR
            }

        chart.data = com.github.mikephil.charting.data.LineData(
            makeSet("Y pitch (nod)",   "#43A047"),  // verde — asentir
            makeSet("Z yaw  (shake)",  "#1E88E5")   // azul  — negar
        )
        updateGyroChartLimitLines()   // líneas verdes (nod) y naranjas (shake)
    }

    /** Actualiza el texto de valores brutos y añade una muestra al gráfico de giroscopio. */
    private fun updateGyroDisplay(gyroX: Float, gyroY: Float, gyroZ: Float) {
        // Texto y gráfica son exclusivos de Dev: evitamos formateo y redibujado
        // cuando la pantalla no está visible (la detección de gestos NO pasa por aquí).
        if (!devScreenVisible) return

        // X se muestra en texto pero no en el gráfico (no se usa para gestos)
        binding.tvRawGyro.text = "X: %+.1f   Y: %+.1f   Z: %+.1f  °/s".format(gyroX, gyroY, gyroZ)

        gyroChartX++
        val data = binding.gyroChart.data ?: return
        data.addEntry(com.github.mikephil.charting.data.Entry(gyroChartX, gyroY), 0)  // Y = NOD
        data.addEntry(com.github.mikephil.charting.data.Entry(gyroChartX, gyroZ), 1)  // Z = SHAKE
        trimToWindow(data)

        data.notifyDataChanged()
        binding.gyroChart.notifyDataSetChanged()
        binding.gyroChart.setVisibleXRangeMaximum(CHART_WINDOW)
        binding.gyroChart.moveViewToX(gyroChartX)
    }

    /**
     * Elimina las muestras que ya salieron de la ventana visible.
     * Sin esto las Entry se acumulan sin límite durante toda la sesión
     * (4 Hz × horas en modo kiosk) aunque el gráfico solo muestre las últimas 40.
     */
    private fun trimToWindow(data: LineData) {
        val max = CHART_WINDOW.toInt()
        for (i in 0 until data.dataSetCount) {
            val set = data.getDataSetByIndex(i)
            while (set.entryCount > max) set.removeFirst()
        }
    }

    // ── Navegación entre pantallas ────────────────────────────────────────────

    private fun setupScreenNavigation() {
        // Home → Settings
        binding.btnSettingsMode.setOnClickListener { showSettings() }
        // Settings → Home
        binding.btnSettingsBack.setOnClickListener { showHome() }
        // Settings → Dev
        binding.btnDevMode.setOnClickListener { showDev() }
        // Dev → Settings
        binding.btnDevBack.setOnClickListener { showSettings() }
        // Home: sesión (misma lógica que en dev)
        binding.btnHomeStartSession.setOnClickListener {
            if (sessionRunning) stopSession() else startSession()
            binding.btnStartSession.text = binding.btnHomeStartSession.text
        }
    }

    /**
     * El pipeline corre a ~4 Hz y redibujar gráficas o medidores que no se ven es
     * puro coste de CPU y batería, así que las actualizaciones exclusivas de cada
     * pantalla se saltan cuando está oculta.
     *
     * Se derivan de la visibilidad real de la vista en lugar de un flag propio:
     * [LevelEditorController] también oculta [homeScreen] por su cuenta, y un flag
     * manual se desincronizaría en ese camino.
     */
    private val devScreenVisible  get() = binding.devScreen.visibility  == View.VISIBLE
    private val homeScreenVisible get() = binding.homeScreen.visibility == View.VISIBLE

    private fun showHome() {
        binding.homeScreen.visibility        = View.VISIBLE
        binding.settingsScreen.visibility    = View.GONE
        binding.devScreen.visibility         = View.GONE
        binding.levelEditorScreen.visibility = View.GONE
        binding.btnHomeStartSession.text     = binding.btnStartSession.text
    }

    private fun showSettings() {
        binding.homeScreen.visibility        = View.GONE
        binding.settingsScreen.visibility    = View.VISIBLE
        binding.devScreen.visibility         = View.GONE
        binding.levelEditorScreen.visibility = View.GONE
        binding.tvDeviceIp.text = getLocalIpAddress()
    }

    private fun showDev() {
        binding.homeScreen.visibility        = View.GONE
        binding.settingsScreen.visibility    = View.GONE
        binding.devScreen.visibility         = View.VISIBLE
        binding.levelEditorScreen.visibility = View.GONE
    }

    // ── Pantalla de Configuración ─────────────────────────────────────────────

    private fun setupSettings() {
        // ── Volumen ──────────────────────────────────────────────────────────
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.sbVolume.max = maxVol
        binding.sbVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        updateVolumeLabel()
        binding.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                updateVolumeLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Modo de conexión ─────────────────────────────────────────────────
        updateConnectionModeUi(connectionMode)
        binding.rgConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rbModeOsc) ConnectionMode.OSC
                          else ConnectionMode.BLUETOOTH
            updateConnectionModeUi(newMode)
            switchConnectionMode(newMode)
        }

        // ── IP / Puerto (solo en modo OSC) ────────────────────────────────────
        binding.tvDeviceIp.text = getLocalIpAddress()
        binding.tvOscPort.text  = OSCReceiver.DEFAULT_PORT.toString()

        // ── Dispositivo BT preferido ──────────────────────────────────────────
        binding.btnBtScanDevices.setOnClickListener { showMuseDevicePicker() }

        // ── Umbral ESTRÉS ────────────────────────────────────────────────────
        binding.sbStressThreshold.progress = (processor.stressThreshold * 100).toInt()
        updateThresholdLabels()
        binding.sbStressThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                processor.stressThreshold = progress / 100f
                updateThresholdLabels()
                if (fromUser) prefs.edit().putFloat(PREF_STRESS, processor.stressThreshold).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Umbral ATENCIÓN ──────────────────────────────────────────────────
        binding.sbAttentionThreshold.progress = (processor.attentionThreshold * 100).toInt()
        binding.sbAttentionThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                processor.attentionThreshold = progress / 100f
                updateThresholdLabels()
                if (fromUser) prefs.edit().putFloat(PREF_ATTENTION, processor.attentionThreshold).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Umbral CALMA ─────────────────────────────────────────────────────
        binding.sbCalmThreshold.progress = (processor.calmThreshold * 100).toInt()
        binding.sbCalmThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                processor.calmThreshold = progress / 100f
                updateThresholdLabels()
                if (fromUser) prefs.edit().putFloat(PREF_CALM, processor.calmThreshold).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Calibración por usuario ──────────────────────────────────────────
        binding.btnCalibrate.setOnClickListener { startCalibration() }

        // ── Debounce parpadeo (SeekBar 0-18, step 50ms → 100-1000 ms) ────────
        binding.sbBlinkDebounce.progress = ((activeReceiver.blinkDebounceMs - 100) / 50).toInt()
        updateBlinkDebounceLabel()
        binding.sbBlinkDebounce.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                activeReceiver.blinkDebounceMs = (progress + 2).toLong() * 50L
                updateBlinkDebounceLabel()
                if (fromUser) prefs.edit().putLong(PREF_BLINK_DEBOUNCE, activeReceiver.blinkDebounceMs).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Umbral NOD (asentir) ─────────────────────────────────────────────
        binding.sbNodThreshold.progress = thresholdToProgress(headGestureDetector.nodThreshold)
        updateGestureThresholdLabels()
        binding.sbNodThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                headGestureDetector.nodThreshold = progressToThreshold(progress)
                updateGestureThresholdLabels()
                updateGyroChartLimitLines()
                if (fromUser) prefs.edit().putFloat(PREF_NOD_THRESHOLD, headGestureDetector.nodThreshold).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Umbral SHAKE (negar) ─────────────────────────────────────────────
        binding.sbShakeThreshold.progress = thresholdToProgress(headGestureDetector.shakeThreshold)
        binding.sbShakeThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                headGestureDetector.shakeThreshold = progressToThreshold(progress)
                updateGestureThresholdLabels()
                updateGyroChartLimitLines()
                if (fromUser) prefs.edit().putFloat(PREF_SHAKE_THRESHOLD, headGestureDetector.shakeThreshold).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Micrófono (silencio en sala Morse) ───────────────────────────────
        binding.switchNoiseMic.isChecked = noiseMicEnabled
        binding.switchNoiseMic.setOnCheckedChangeListener { _, isChecked ->
            noiseMicEnabled = isChecked
            prefs.edit().putBoolean(PREF_NOISE_MIC, isChecked).apply()
        }

        // ── Restablecer por defecto ───────────────────────────────────────────
        binding.btnSettingsReset.setOnClickListener {
            processor.stressThreshold        = 0.22f
            processor.attentionThreshold     = 0.38f
            processor.calmThreshold          = 0.52f
            processor.gammaActivityThreshold = 0.15f
            activeReceiver.blinkDebounceMs         = MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS
            headGestureDetector.nodThreshold   = DEFAULT_NOD_THRESHOLD
            headGestureDetector.shakeThreshold = DEFAULT_SHAKE_THRESHOLD

            binding.sbStressThreshold.progress    = 22
            binding.sbAttentionThreshold.progress = 38
            binding.sbCalmThreshold.progress      = 52
            binding.sbBlinkDebounce.progress      = ((MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS - 100) / 50).toInt()
            binding.sbNodThreshold.progress       = thresholdToProgress(DEFAULT_NOD_THRESHOLD)
            binding.sbShakeThreshold.progress     = thresholdToProgress(DEFAULT_SHAKE_THRESHOLD)
            updateGyroChartLimitLines()

            noiseMicEnabled = true
            binding.switchNoiseMic.isChecked = true
            updateThresholdLabels()
            updateBlinkDebounceLabel()
            updateGestureThresholdLabels()

            // Persistir los valores por defecto para que sobrevivan al reinicio
            prefs.edit()
                .putFloat(PREF_STRESS,          0.22f)
                .putFloat(PREF_ATTENTION,        0.38f)
                .putFloat(PREF_CALM,             0.52f)
                .putFloat(PREF_GAMMA,            0.15f)
                .putLong(PREF_BLINK_DEBOUNCE,    MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS)
                .putFloat(PREF_NOD_THRESHOLD,    DEFAULT_NOD_THRESHOLD)
                .putFloat(PREF_SHAKE_THRESHOLD,  DEFAULT_SHAKE_THRESHOLD)
                .putBoolean(PREF_NOISE_MIC,      true)
                .apply()

            Toast.makeText(this, "Valores restablecidos", Toast.LENGTH_SHORT).show()
        }

        // ── Exportar ubicaciones Temi ─────────────────────────────────────────
        binding.btnExportLocations.setOnClickListener {
            val file = temiController.exportLocations(this)
            val msg = if (file != null)
                "Ubicaciones exportadas: ${file.name} (${temiController.getLocations().size} items)"
            else if (temiController.isSimulated)
                "Modo simulado — no hay ubicaciones que exportar"
            else
                "No hay ubicaciones guardadas en el mapa actual"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateVolumeLabel() {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.tvVolumeValue.text = "$cur / $max"
    }

    private var calibrationTimer: android.os.CountDownTimer? = null

    /**
     * Calibración por usuario: captura [CALIBRATION_SECONDS] s de línea base en
     * reposo y deriva umbrales personalizados (media ± σ de mellow/conc/gamma).
     * Requiere el MUSE conectado (el pipeline alimenta processor.addSample).
     */
    private fun startCalibration() {
        calibrationTimer?.cancel()
        processor.beginCalibration()
        binding.btnCalibrate.isEnabled = false

        calibrationTimer = object : android.os.CountDownTimer(CALIBRATION_SECONDS * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                binding.btnCalibrate.text =
                    getString(R.string.settings_calibrating, (msLeft / 1000L).toInt() + 1)
            }

            override fun onFinish() {
                val result = processor.finishCalibration()
                binding.btnCalibrate.isEnabled = true
                binding.btnCalibrate.text = getString(R.string.settings_calibrate)

                if (result == null) {
                    android.widget.Toast.makeText(
                        this@MainActivity, R.string.settings_calibrate_fail,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return
                }

                // Reflejar en los sliders + etiquetas (fromUser=false → no re-persiste).
                binding.sbStressThreshold.progress    = (processor.stressThreshold * 100).toInt()
                binding.sbAttentionThreshold.progress = (processor.attentionThreshold * 100).toInt()
                binding.sbCalmThreshold.progress      = (processor.calmThreshold * 100).toInt()
                updateThresholdLabels()

                // Persistir los umbrales personalizados.
                prefs.edit()
                    .putFloat(PREF_STRESS,    processor.stressThreshold)
                    .putFloat(PREF_ATTENTION, processor.attentionThreshold)
                    .putFloat(PREF_CALM,      processor.calmThreshold)
                    .putFloat(PREF_GAMMA,     processor.gammaActivityThreshold)
                    .apply()

                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.settings_calibrate_done,
                        result.stressThreshold, result.attentionThreshold,
                        result.calmThreshold, result.samples
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun updateThresholdLabels() {
        binding.tvStressThresholdValue.text    = "%.2f".format(processor.stressThreshold)
        binding.tvAttentionThresholdValue.text = "%.2f".format(processor.attentionThreshold)
        binding.tvCalmThresholdValue.text      = "%.2f".format(processor.calmThreshold)
        // Actualizar también el texto informativo del dev screen
        binding.tvThresholds.text = "Umbrales: Estrés mellow<%.2f · Atención conc>%.2f · Calma mellow>%.2f"
            .format(processor.stressThreshold, processor.attentionThreshold, processor.calmThreshold)
    }

    private fun updateBlinkDebounceLabel() {
        binding.tvBlinkDebounceValue.text = "${activeReceiver.blinkDebounceMs} ms"
    }

    private fun updateGestureThresholdLabels() {
        binding.tvNodThresholdValue.text   = "%.0f °/s".format(headGestureDetector.nodThreshold)
        binding.tvShakeThresholdValue.text = "%.0f °/s".format(headGestureDetector.shakeThreshold)
    }

    /** Actualiza el RadioGroup y la visibilidad de los detalles de cada modo. */
    private fun updateConnectionModeUi(mode: ConnectionMode) {
        val isOsc = mode == ConnectionMode.OSC
        binding.rgConnectionMode.check(if (isOsc) R.id.rbModeOsc else R.id.rbModeBluetooth)
        binding.layoutOscDetails.visibility  = if (isOsc) View.VISIBLE else View.GONE
        binding.tvBtNote.visibility          = if (isOsc) View.GONE    else View.VISIBLE
        binding.layoutBtPreferred.visibility = if (isOsc) View.GONE    else View.VISIBLE
        if (!isOsc) refreshBtPreferredUi()
    }

    private fun refreshBtPreferredUi() {
        val musePref = getSharedPreferences("muse_direct_prefs", MODE_PRIVATE)
            .getString(MuseDirectReceiver.PREF_DEVICE_NAME, null)
        binding.tvBtPreferredDevice.text = musePref ?: getString(R.string.bt_no_preference)
    }

    private fun showMuseDevicePicker() {
        // Diálogo de progreso mientras dura el escaneo (3 s)
        val scanningView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(64, 40, 64, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.bt_scanning)
                setPadding(40, 0, 0, 0)
                textSize = 15f
            })
        }
        val scanDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.bt_scan_devices))
            .setView(scanningView)
            .setCancelable(false)
            .show()

        activeReceiver.scanForDevices(3_000L) { devices ->
            runOnUiThread {
                scanDialog.dismiss()
                showMuseDeviceList(devices)
            }
        }
    }

    private fun showMuseDeviceList(devices: List<String>) {
        val preferred = getSharedPreferences("muse_direct_prefs", MODE_PRIVATE)
            .getString(MuseDirectReceiver.PREF_DEVICE_NAME, null)

        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.bt_scan_devices))
                .setMessage(getString(R.string.bt_no_devices_found))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = devices.map { name ->
            if (name == preferred) "✓  $name" else "    $name"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bt_scan_devices))
            .setItems(labels) { _, i ->
                activeReceiver.selectMuse(devices[i])
                refreshBtPreferredUi()
            }
            .apply {
                if (preferred != null) {
                    setNeutralButton(getString(R.string.bt_forget)) { _, _ ->
                        getSharedPreferences("muse_direct_prefs", MODE_PRIVATE)
                            .edit().remove(MuseDirectReceiver.PREF_DEVICE_NAME).apply()
                        refreshBtPreferredUi()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // SeekBar 0-199 ↔ umbral 1-200 (paso 1 °/s)
    private fun progressToThreshold(progress: Int): Float = (progress + 1).toFloat()
    private fun thresholdToProgress(threshold: Float): Int =
        (threshold.toInt() - 1).coerceIn(0, 199)

    /**
     * Actualiza las líneas de umbral del gráfico de giroscopio:
     *   Verde (✅) = umbral NOD  (gyroY)
     *   Naranja (❌) = umbral SHAKE (gyroZ)
     */
    private fun updateGyroChartLimitLines() {
        val axis = binding.gyroChart.axisLeft
        axis.removeAllLimitLines()

        fun addPair(value: Float, label: String, colorHex: String) {
            val color = android.graphics.Color.parseColor(colorHex)
            axis.addLimitLine(
                com.github.mikephil.charting.components.LimitLine(value, label).apply {
                    lineColor     = color
                    lineWidth     = 1.2f
                    textColor     = color
                    textSize      = 8f
                    labelPosition = com.github.mikephil.charting.components.LimitLine
                                        .LimitLabelPosition.RIGHT_TOP
                }
            )
            axis.addLimitLine(
                com.github.mikephil.charting.components.LimitLine(-value, "").apply {
                    lineColor = color
                    lineWidth = 1.2f
                }
            )
        }

        addPair(headGestureDetector.nodThreshold,   "nod",   "#43A047")  // verde
        addPair(headGestureDetector.shakeThreshold, "shake", "#FF9800")  // naranja
        binding.gyroChart.invalidate()
    }

    /** Devuelve la IP IPv4 del dispositivo, o "—" si no está en red. */
    private fun getLocalIpAddress(): String {
        try {
            // API 23+: ConnectivityManager + LinkProperties (sin APIs deprecadas)
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork ?: return fallbackIp())
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
                ?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpAddress CM: ${e.message}")
        }
        return fallbackIp()
    }

    /** Fallback via NetworkInterface para redes no WiFi (Ethernet, hotspot…). */
    private fun fallbackIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "—"
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpAddress NI: ${e.message}")
            "—"
        }
    }

    // ── Giroscopio: asentir / negar ───────────────────────────────────────────

    private fun setupHeadGestures() {
        headGestureDetector.onNod = { runOnUiThread { onNod() } }
        headGestureDetector.onShake = { runOnUiThread { onShake() } }
    }

    private fun onNod() {
        nodTotal++
        pendingNod = true
        binding.tvNodIndicator.visibility = View.VISIBLE
        nodHandler.removeCallbacksAndMessages(null)
        nodHandler.postDelayed({ binding.tvNodIndicator.visibility = View.INVISIBLE }, 700L)
        binding.tvNodCount.text = "Asentir: $nodTotal"
        Log.d(TAG, "Nod — total=$nodTotal")
        if (escapeRoomActive) {
            if (isBciSignalStable()) escapeRoomEngine.onNod()
            else Log.d(TAG, "Nod ignorado — señal inestable (quality=$currentSignalQuality)")
        }
    }

    private fun onShake() {
        shakeTotal++
        pendingShake = true
        binding.tvShakeIndicator.visibility = View.VISIBLE
        shakeHandler.removeCallbacksAndMessages(null)
        shakeHandler.postDelayed({ binding.tvShakeIndicator.visibility = View.INVISIBLE }, 700L)
        binding.tvShakeCount.text = "Negar: $shakeTotal"
        Log.d(TAG, "Shake — total=$shakeTotal")
        if (escapeRoomActive) {
            if (isBciSignalStable()) escapeRoomEngine.onShake()
            else Log.d(TAG, "Shake ignorado — señal inestable (quality=$currentSignalQuality)")
        }
    }

    /**
     * true cuando la calidad de señal EEG es suficiente para fiarse de los gestos BCI.
     *
     * Umbral: media HSI ≤ 2.0  (1=bueno · 2=marginal · 3=malo · 4=sin contacto).
     * Por encima de 2.0 la banda no está bien colocada y los artefactos aumentan,
     * lo que produce falsos positivos en parpadeos y mandíbula.
     *
     * Devuelve false también cuando aún no se ha recibido ningún dato (-1).
     */
    private fun isBciSignalStable(): Boolean =
        currentSignalQuality in 0f..2.0f

    /** Emoji representativo del tipo de módulo activo. */
    private fun moduleIconRes(typeName: String?): Int = when (typeName) {
        "CalmModule"        -> R.drawable.ic_mod_calm
        "MorseModule"       -> R.drawable.ic_mod_morse
        "YesNoModule"       -> R.drawable.ic_mod_yesno
        "BlinkClenchModule" -> R.drawable.ic_mod_blink
        "RobotAnimModule"   -> R.drawable.ic_mod_robot
        "VideoStateModule"  -> R.drawable.ic_mod_target
        else                -> R.drawable.ic_mod_default
    }

    // ── UX: feedback háptico + tutorial ─────────────────────────────────────────

    private val vibrator: android.os.Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }

    /** Refuerzo háptico multimodal: pulso corto en acierto, doble en fallo. */
    private fun vibrateFeedback(success: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val effect = if (success)
                android.os.VibrationEffect.createOneShot(110L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            else
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 70, 80, 70), -1)
            v.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            if (success) v.vibrate(110L) else v.vibrate(longArrayOf(0, 70, 80, 70), -1)
        }
    }

    /**
     * Muestra el tutorial de gestos la primera vez que se juega (persistido).
     * Después ejecuta [next]. Reduce la frustración y la necesidad del botón de rescate.
     */
    private fun maybeShowTutorialThen(next: () -> Unit) {
        if (prefs.getBoolean(PREF_TUTORIAL_SEEN, false)) { next(); return }
        val content = layoutInflater.inflate(R.layout.dialog_tutorial, null)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.ThemeOverlay_TemiEeg_Dialog
        )
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.tutorial_ok) { d, _ ->
                prefs.edit().putBoolean(PREF_TUTORIAL_SEEN, true).apply()
                d.dismiss()
                next()
            }
            .show()
    }

    // ── Escape Room BCI ───────────────────────────────────────────────────────

    private fun setupEscapeRoom() {
        // Botón play → tutorial (1ª vez) → selector de historia
        binding.btnPlayEscapeRoom.setOnClickListener { maybeShowTutorialThen { showEscapeRoomPicker() } }
        binding.btnEscapeExit.setOnClickListener { stopEscapeRoom() }
        binding.btnEscapeSkip.setOnClickListener {
            cancelSkipOffer()
            escapeRoomEngine.skipCurrentRoom()
        }

        // NoiseMonitor → motor (el módulo activo decide si reaccionar o no)
        noiseMonitor.onAmplitude = { amp -> escapeRoomEngine.onNoiseLevel(amp) }

        // Callbacks del motor → UI
        escapeRoomEngine.onSetBlinkDebounce = { ms -> activeReceiver.blinkDebounceMs = ms }

        escapeRoomEngine.onRoomChanged = { current, total, title ->
            currentRoomIndex = current
            currentRoomTitle = title
            restartSkipOffer()
            binding.tvEscapeProgress.text = "Sala $current / $total"
            binding.progressEscapeRooms.progress = ((current - 1) * 100) / total
            binding.tvEscapeModuleType.setImageResource(moduleIconRes(escapeRoomEngine.currentModuleTypeName))
            binding.tvEscapeRoomName.text     = title
            binding.tvEscapeFeedback.text     = ""
            binding.tvEscapeFeedback.background = null
            binding.tvEscapeMorseSymbols.text = ""
            binding.gameScrollView.post { binding.gameScrollView.scrollTo(0, 0) }
        }

        escapeRoomEngine.onNarration    = { text    -> binding.tvEscapeNarration.text    = text    }
        escapeRoomEngine.onHint         = { hint    -> binding.tvEscapeHint.text          = hint    }
        escapeRoomEngine.onMorseSymbols = { symbols -> binding.tvEscapeMorseSymbols.text = symbols }

        escapeRoomEngine.onFeedback = { msg, isSuccess ->
            binding.tvEscapeFeedback.text = msg
            if (msg.isEmpty()) {
                binding.tvEscapeFeedback.background = null
            } else {
                binding.tvEscapeFeedback.background = ContextCompat.getDrawable(
                    this,
                    if (isSuccess) R.drawable.bg_feedback_success else R.drawable.bg_feedback_error
                )
                binding.tvEscapeFeedback.setTextColor(
                    ContextCompat.getColor(this, if (isSuccess) R.color.state_calm else R.color.state_stress)
                )
                // Pulso de feedback
                binding.tvEscapeFeedback.animate()
                    .scaleX(1.04f).scaleY(1.04f).setDuration(120)
                    .withEndAction {
                        binding.tvEscapeFeedback.animate()
                            .scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                // Feedback háptico: refuerzo multimodal cuando la vista está en el juego
                vibrateFeedback(isSuccess)
            }
        }

        escapeRoomEngine.onCompleted = {
            cancelSkipOffer()
            binding.progressEscapeRooms.progress = 100
            binding.tvEscapeModuleType.setImageResource(R.drawable.ic_trophy)
            binding.tvEscapeRoomName.text         = "¡Misión completada!"
            binding.tvEscapeNarration.text        = "Has superado todos los desafíos mentales.\n¡Enhorabuena!"
            binding.tvEscapeHint.text             = ""
            binding.tvEscapeFeedback.text         = ""
            binding.tvEscapeFeedback.background   = null
            binding.tvEscapeMorseSymbols.text     = ""
            binding.tvEscapeProgress.text         = getString(R.string.escape_room_completed)
            // Exportar log al terminar la partida correctamente
            exportGameLog()
            Handler(Looper.getMainLooper()).postDelayed({ stopEscapeRoom() }, 4000L)
        }

        escapeRoomEngine.onCelebrate    = { temiController.celebrate() }
        escapeRoomEngine.onTemiSpeak    = { text   -> temiController.speak(text) }
        temiController.onTtsEnd         = { escapeRoomEngine.onTtsEnded() }
        temiController.onGoToEnd        = { escapeRoomEngine.onGoToEnded() }

        escapeRoomEngine.onRobotAction  = { action ->
            when (action.type) {
                RobotAction.Type.SPEAK     -> temiController.speak(action.param)
                RobotAction.Type.GOTO      -> temiController.goTo(action.param)
                RobotAction.Type.TILT_HEAD -> temiController.tiltHead(action.param.toIntOrNull() ?: 0)
                RobotAction.Type.TURN      -> temiController.turnBy(action.param.toIntOrNull() ?: 0)
                RobotAction.Type.WAIT      -> { /* consumido por el motor como retardo — nunca llega aquí */ }
            }
        }

        // Indicador visual de robot hablando — muestra 🎙 y bloquea la atención del usuario
        // mientras el BCI está suspendido para evitar confusión por gestos ignorados.
        escapeRoomEngine.onTemiSpeakingChanged = { speaking ->
            if (speaking) {
                savedHintBeforeSpeaking = binding.tvEscapeHint.text.toString()
                binding.tvEscapeModuleType.setImageResource(R.drawable.ic_mic)
                binding.tvEscapeHint.text = "Escucha al robot… (BCI en pausa)"
            } else {
                binding.tvEscapeModuleType.setImageResource(moduleIconRes(escapeRoomEngine.currentModuleTypeName))
                binding.tvEscapeHint.text = savedHintBeforeSpeaking
            }
        }

        escapeRoomEngine.onPlayVideo = { resId, path, onComplete ->
            showVideoPlayer(resId, path, onComplete)
        }

        escapeRoomEngine.onStartConcurrentVideo = { path ->
            if (path != null) {
                val uri = android.net.Uri.fromFile(java.io.File(path))
                binding.videoPlayerContainer.visibility = View.VISIBLE
                binding.videoView.setVideoURI(uri)
                // Loop: restart on completion instead of calling onComplete
                binding.videoView.setOnCompletionListener { binding.videoView.start() }
                // Hide skip — the video runs until the EEG challenge succeeds
                binding.btnSkipVideo.visibility = View.GONE
                binding.videoView.start()
            }
        }

        escapeRoomEngine.onStopConcurrentVideo = {
            binding.videoPlayerContainer.visibility = View.GONE
            binding.videoView.stopPlayback()
            binding.btnSkipVideo.visibility = View.VISIBLE
        }

        // Morse: mostrar botón de referencia siempre en salas Morse
        var inMorseRoom = false
        escapeRoomEngine.onMorseModule = { isMorse ->
            inMorseRoom = isMorse
            if (isMorse) {
                binding.btnHintImage.visibility = View.VISIBLE
                binding.btnHintImage.setOnClickListener { showMorseReferenceDialog() }
            }
        }

        escapeRoomEngine.onHintImage = { path ->
            if (path != null) {
                // Nivel personalizado con imagen adjunta → mostrar imagen
                binding.btnHintImage.visibility = View.VISIBLE
                binding.btnHintImage.setOnClickListener {
                    val bmp = android.graphics.BitmapFactory.decodeFile(path)
                    if (bmp != null) {
                        binding.imgHint.setImageBitmap(bmp)
                        binding.hintImageContainer.visibility = View.VISIBLE
                    }
                }
            } else if (!inMorseRoom) {
                // Sin imagen y no es sala Morse → ocultar botón
                binding.btnHintImage.visibility = View.GONE
                binding.hintImageContainer.visibility = View.GONE
            }
            // Si inMorseRoom && path == null: onMorseModule ya configuró el botón
        }

        binding.btnCloseHintImage.setOnClickListener {
            binding.hintImageContainer.visibility = View.GONE
        }
        binding.hintImageContainer.setOnClickListener {
            binding.hintImageContainer.visibility = View.GONE
        }
    }

    /** Muestra una tabla de referencia del código Morse en un diálogo. */
    private fun showMorseReferenceDialog() {
        val table = buildString {
            appendLine("  1 parpadeo  =  punto  ·")
            appendLine("  2 rápidos   =  raya   —")
            appendLine()
            appendLine("A  · —       N  — ·")
            appendLine("B  — · · ·   O  — — —")
            appendLine("C  — · — ·   P  · — — ·")
            appendLine("D  — · ·     Q  — — · —")
            appendLine("E  ·         R  · — ·")
            appendLine("F  · · — ·   S  · · ·")
            appendLine("G  — — ·     T  —")
            appendLine("H  · · · ·   U  · · —")
            appendLine("I  · ·       V  · · · —")
            appendLine("J  · — — —   W  · — —")
            appendLine("K  — · —     X  — · · —")
            appendLine("L  · — · ·   Y  — · — —")
            append(  "M  — —       Z  — — · ·")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Código Morse")
            .setMessage(table)
            .setPositiveButton("Cerrar", null)
            .show()
        // Fuente monoespaciada para que las columnas queden alineadas
        dialog.findViewById<android.widget.TextView>(android.R.id.message)
            ?.typeface = android.graphics.Typeface.MONOSPACE
    }

    private fun showVideoPlayer(resId: Int?, path: String?, onComplete: () -> Unit) {
        val uri = when {
            resId != null -> android.net.Uri.parse("android.resource://$packageName/$resId")
            path  != null -> android.net.Uri.fromFile(java.io.File(path))
            else          -> return onComplete()
        }
        binding.videoPlayerContainer.visibility = View.VISIBLE
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnCompletionListener { hideVideoPlayer(onComplete) }
        binding.btnSkipVideo.setOnClickListener {
            binding.videoView.stopPlayback()
            hideVideoPlayer(onComplete)
        }
        binding.videoView.start()
    }

    private fun hideVideoPlayer(onComplete: () -> Unit) {
        binding.videoPlayerContainer.visibility = View.GONE
        binding.videoView.stopPlayback()
        onComplete()
    }

    /** Muestra un diálogo para elegir entre los escape rooms disponibles (catálogo + personalizados). */
    private fun showEscapeRoomPicker() {
        val builtin = EscapeRoomCatalog.all
        val custom  = CustomLevelStorage.loadAll(this)
        val rooms   = builtin + custom

        val content   = layoutInflater.inflate(R.layout.dialog_story_picker, null)
        val container = content.findViewById<android.widget.LinearLayout>(R.id.storyListContainer)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.ThemeOverlay_TemiEeg_Dialog
        )
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .create()

        rooms.forEachIndexed { index, room ->
            val row = layoutInflater.inflate(R.layout.item_story_row, container, false)
            row.findViewById<android.widget.TextView>(R.id.storyRowName).text = room.name
            row.findViewById<android.widget.ImageView>(R.id.storyRowIcon)
                .setImageResource(if (index < builtin.size) R.drawable.ic_play else R.drawable.ic_mod_default)
            row.setOnClickListener {
                dialog.dismiss()
                escapeRoomEngine.load(room)
                startEscapeRoom()
            }
            container.addView(row)
        }
        dialog.show()
    }

    private fun startEscapeRoom() {
        // Diagnóstico de robot — visible en Logcat con tag "EscapeRoom"
        val simulated = temiController.isSimulated
        Log.d("EscapeRoom", if (simulated) "⚠ Robot en MODO SIMULADO — Robot.getInstance() = null" else "✅ Robot SDK activo")
        if (!simulated) {
            val locs = temiController.getLocations()
            if (locs.isEmpty()) Log.w("EscapeRoom", "⚠ No hay ubicaciones guardadas — comandos GOTO serán ignorados")
            else                Log.d("EscapeRoom", "📍 Ubicaciones disponibles: $locs")
        }

        escapeRoomActive = true
        binding.switchMorse.isEnabled        = false
        binding.homeScreen.visibility        = View.GONE
        binding.settingsScreen.visibility    = View.GONE
        binding.devScreen.visibility         = View.GONE
        binding.levelEditorScreen.visibility = View.GONE
        binding.escapeRoomOverlay.visibility = View.VISIBLE

        // ── Logging automático de partida ────────────────────────────────────
        currentEscapeRoomName = escapeRoomEngine.currentLevelName
        gameLogger.clearSession()
        gameLogging = true
        Log.d(TAG, "▶ Game log iniciado para «$currentEscapeRoomName»")

        startNoiseMonitor()
        escapeRoomEngine.start()
    }

    /** Reinicia el contador del botón de rescate (se llama al entrar en cada sala). */
    private fun restartSkipOffer() {
        binding.btnEscapeSkip.visibility = View.GONE
        skipOfferHandler.removeCallbacks(skipOfferRunnable)
        skipOfferHandler.postDelayed(skipOfferRunnable, SKIP_OFFER_DELAY_MS)
    }

    /** Oculta el botón de rescate y cancela el contador pendiente. */
    private fun cancelSkipOffer() {
        skipOfferHandler.removeCallbacks(skipOfferRunnable)
        binding.btnEscapeSkip.visibility = View.GONE
    }

    private fun stopEscapeRoom() {
        escapeRoomEngine.abort()
        noiseMonitor.stop()
        cancelSkipOffer()
        escapeRoomActive = false
        currentRoomIndex = -1
        currentRoomTitle = ""
        activeReceiver.blinkDebounceMs          = MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS
        binding.switchMorse.isEnabled           = true
        binding.escapeRoomOverlay.visibility    = View.GONE
        binding.videoPlayerContainer.visibility = View.GONE
        binding.videoView.stopPlayback()
        // Resetear estado visual para la próxima partida
        binding.progressEscapeRooms.progress   = 0
        binding.tvEscapeFeedback.background    = null
        binding.tvEscapeModuleType.setImageDrawable(null)

        // ── Exportar log de partida ──────────────────────────────────────────
        exportGameLog()
        showHome()
    }

    /**
     * Detiene el logging automático de partida y exporta el CSV.
     * El archivo se guarda en el almacenamiento externo de la app y se muestra
     * un toast con el nombre del fichero para facilitar su localización.
     */
    private fun exportGameLog() {
        if (!gameLogging) return
        gameLogging = false
        val levelName = currentEscapeRoomName
        currentEscapeRoomName = ""
        if (gameLogger.size() == 0) {
            Log.d(TAG, "Game log vacío — no se exporta CSV")
            return
        }
        val rows = gameLogger.size()
        // Export en hilo de fondo (ver exportCSVAsync): evita bloquear la UI al
        // terminar la partida, justo cuando el robot sigue narrando.
        gameLogger.exportCSVAsync(levelName = levelName) { file ->
            if (isFinishing || isDestroyed) return@exportCSVAsync
            if (file != null) {
                Log.i(TAG, "Game log exportado: ${file.absolutePath} ($rows muestras)")
                Toast.makeText(this, "💾 Sesión guardada:\n${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Log.e(TAG, "Error exportando game log")
            }
        }
    }

    /**
     * Arranca el [NoiseMonitor] solo si el usuario lo ha habilitado en Configuración.
     * Si falta el permiso RECORD_AUDIO, lo solicita primero.
     */
    private fun startNoiseMonitor() {
        if (!noiseMicEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            noiseMonitor.start()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Carga todos los ajustes persistidos desde [SharedPreferences].
     * Debe llamarse en [onCreate] después de inicializar [processor],
     * [activeReceiver] y [headGestureDetector].
     */
    private fun loadSavedSettings() {
        processor.stressThreshold        = prefs.getFloat(PREF_STRESS,     0.22f)
        processor.attentionThreshold     = prefs.getFloat(PREF_ATTENTION,  0.38f)
        processor.calmThreshold          = prefs.getFloat(PREF_CALM,       0.52f)
        processor.gammaActivityThreshold = prefs.getFloat(PREF_GAMMA,      0.15f)
        activeReceiver.blinkDebounceMs =
            prefs.getLong(PREF_BLINK_DEBOUNCE, MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS)
        headGestureDetector.nodThreshold   =
            prefs.getFloat(PREF_NOD_THRESHOLD,   DEFAULT_NOD_THRESHOLD)
        headGestureDetector.shakeThreshold =
            prefs.getFloat(PREF_SHAKE_THRESHOLD, DEFAULT_SHAKE_THRESHOLD)
        noiseMicEnabled = prefs.getBoolean(PREF_NOISE_MIC, true)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val CHART_WINDOW              = 40f   // últimas ~10 s a 4 Hz
        private const val MORSE_DEBOUNCE_MS         = 150L  // parpadeos cortos para el doble
        private const val SKIP_OFFER_DELAY_MS       = 30_000L  // atascado → ofrecer saltar sala
        private const val DEFAULT_NOD_THRESHOLD   = 30f  // NOD más suave → umbral menor
        private const val DEFAULT_SHAKE_THRESHOLD = 50f  // SHAKE más enérgico → umbral mayor
        private const val PREFS_NAME                = "temi_eeg_prefs"
        private const val PREF_CONNECTION_MODE      = "connection_mode"
        private const val PREF_STRESS               = "stress_threshold"
        private const val PREF_ATTENTION            = "attention_threshold"
        private const val PREF_CALM                 = "calm_threshold"
        private const val PREF_GAMMA                = "gamma_activity_threshold"
        private const val CALIBRATION_SECONDS       = 20
        private const val PREF_BLINK_DEBOUNCE       = "blink_debounce"
        private const val PREF_NOD_THRESHOLD        = "nod_threshold"
        private const val PREF_SHAKE_THRESHOLD      = "shake_threshold"
        private const val PREF_NOISE_MIC            = "noise_mic_enabled"
        private const val PREF_TUTORIAL_SEEN        = "tutorial_seen"
    }
}
