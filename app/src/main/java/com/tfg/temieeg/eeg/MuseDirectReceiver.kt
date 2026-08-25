package com.tfg.temieeg.eeg

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.choosemuse.libmuse.Accelerometer
import com.choosemuse.libmuse.Battery
import com.choosemuse.libmuse.ConnectionState
import com.choosemuse.libmuse.Eeg
import com.choosemuse.libmuse.Gyro
import com.choosemuse.libmuse.Muse
import com.choosemuse.libmuse.MuseArtifactPacket
import com.choosemuse.libmuse.MuseConnectionListener
import com.choosemuse.libmuse.MuseConnectionPacket
import com.choosemuse.libmuse.MuseDataListener
import com.choosemuse.libmuse.MuseDataPacket
import com.choosemuse.libmuse.MuseDataPacketType
import com.choosemuse.libmuse.MuseListener
import com.choosemuse.libmuse.MuseManagerAndroid
import com.tfg.temieeg.data.MuseState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Receptor Bluetooth directo para MUSE 2 / MUSE S vía LibMuse SDK.
 *
 * Requiere en app/libs/:
 *   - libmuse_android.jar  (clases Java)
 * Y en app/src/main/jniLibs/:
 *   - arm64-v8a/libmuse_android.so
 *   - armeabi-v7a/libmuse_android.so
 *   - x86/libmuse_android.so
 *   - x86_64/libmuse_android.so
 *
 * Implementa [MuseReceiver] — [MainActivity] no necesita ningún cambio
 * respecto al modo OSC para usar esta clase.
 *
 * Flujo:
 *   start() → MuseManagerAndroid escanea BT → museListChanged() → connectTo()
 *           → dataListener recibe paquetes EEG/ACC/GYRO/ARTIFACTS
 *           → onTick() cada 250 ms publica [MuseState] al pipeline
 */
class MuseDirectReceiver(private val context: Context) : MuseReceiver {

    override var onMuseDataReceived:       ((MuseState) -> Unit)? = null
    override var onConnectionStateChanged: ((Boolean) -> Unit)?   = null
    override var onConnectingChanged:      ((Boolean) -> Unit)?   = null
    override var onElementsStateChanged:   ((Boolean) -> Unit)?   = null
    override var onMuseDevicesFound:       ((List<String>) -> Unit)? = null
    @Volatile override var blinkDebounceMs: Long = MuseReceiver.BLINK_DEBOUNCE_DEFAULT_MS

    // ── Estado interno ────────────────────────────────────────────────────────
    private val prefs = context.getSharedPreferences("muse_direct_prefs", Context.MODE_PRIVATE)

    private var manager: MuseManagerAndroid? = null
    private var muse: Muse? = null
    private val state = AtomicReference(MuseState())

    @Volatile private var connected         = false
    @Volatile private var connecting        = false
    @Volatile private var elementsActive    = false
    @Volatile private var awaitingSelection = false
    @Volatile private var lastBlinkTime     = 0L
    @Volatile private var lastJawClenchTime = 0L
    @Volatile private var lastAlphaTime     = 0L
    @Volatile private var lastBetaTime      = 0L

    /** Hilo de muestreo periódico (250 ms) que publica la muestra consolidada. */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MuseSampler").apply { isDaemon = true }
    }
    private var samplerFuture: ScheduledFuture<*>? = null
    /** Timeout que aborta un intento de conexión colgado y relanza el escáner. */
    private var connectTimeoutFuture: ScheduledFuture<*>? = null

    // ── API MuseReceiver ──────────────────────────────────────────────────────

    override fun start() {
        manager = MuseManagerAndroid.getInstance().apply {
            setContext(context)
            setMuseListener(museListener)
        }
        context.registerReceiver(btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startScanIfReady()
        samplerFuture = scheduler.scheduleAtFixedRate(
            ::onTick, 250L, 250L, TimeUnit.MILLISECONDS)
        Log.i(TAG, "MuseDirectReceiver iniciado — escaneando Bluetooth…")
    }

    override fun stop() {
        connectTimeoutFuture?.cancel(false)
        connectTimeoutFuture = null
        samplerFuture?.cancel(false)
        samplerFuture = null
        muse?.disconnect()
        muse = null
        manager?.stopListening()
        manager = null
        awaitingSelection = false
        try { context.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        if (connecting)     { connecting = false;     onConnectingChanged?.invoke(false)      }
        if (connected)      { connected = false;      onConnectionStateChanged?.invoke(false) }
        if (elementsActive) { elementsActive = false;  onElementsStateChanged?.invoke(false)  }
        Log.i(TAG, "MuseDirectReceiver detenido")
    }

    private val bluetoothAdapter: BluetoothAdapter? get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /** Lanza el escáner BLE solo si el Bluetooth del dispositivo está activo. */
    private fun startScanIfReady() {
        if (bluetoothAdapter?.isEnabled == true) {
            manager?.startListening()
        } else {
            Log.w(TAG, "Bluetooth desactivado — escáner en espera de activación")
        }
    }

    /** Reacciona a cambios de estado del Bluetooth del sistema. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth activado — relanzando escáner MUSE…")
                    if (muse == null && !connecting) startScanIfReady()
                }
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth desactivado — limpiando estado MUSE")
                    connectTimeoutFuture?.cancel(false)
                    connectTimeoutFuture = null
                    awaitingSelection   = false
                    muse?.disconnect()
                    muse = null
                    if (connecting) { connecting = false; onConnectingChanged?.invoke(false) }
                    if (connected)  { connected  = false; onConnectionStateChanged?.invoke(false) }
                }
            }
        }
    }

    // ── Detección de dispositivos ─────────────────────────────────────────────

    override fun getScannedDevices(): List<String> =
        manager?.getMuses()?.map { it.name } ?: emptyList()

    override fun scanForDevices(timeoutMs: Long, onResult: (List<String>) -> Unit) {
        // Fuerza un ciclo de escaneo BLE fresco
        if (muse == null && !connecting) {
            manager?.stopListening()
            startScanIfReady()
        }
        scheduler.schedule({
            onResult(getScannedDevices())
        }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun selectMuse(name: String) {
        awaitingSelection = false
        prefs.edit().putString(PREF_DEVICE_NAME, name).apply()
        val m = manager?.getMuses()?.find { it.name == name } ?: run {
            Log.w(TAG, "Dispositivo '$name' ya no disponible — relanzando escáner…")
            startScanIfReady()
            return
        }
        Log.i(TAG, "Usuario seleccionó: ${m.name} — conectando…")
        connectTo(m)
    }

    private val museListener = object : MuseListener() {
        override fun museListChanged() {
            val muses = manager?.getMuses() ?: return
            if (muses.isEmpty() || muse != null || awaitingSelection) return

            if (muses.size == 1) {
                Log.i(TAG, "MUSE encontrado: ${muses[0].name} — conectando…")
                connectTo(muses[0])
                return
            }

            // Varios dispositivos: intentar reconectar al preferido
            val preferred = prefs.getString(PREF_DEVICE_NAME, null)
            val prefMuse  = preferred?.let { p -> muses.find { it.name == p } }
            if (prefMuse != null) {
                Log.i(TAG, "MUSE preferido '${prefMuse.name}' encontrado — conectando automáticamente…")
                connectTo(prefMuse)
                return
            }

            // Sin preferencia guardada: pedir al usuario que elija
            Log.i(TAG, "${muses.size} dispositivos MUSE detectados — esperando selección del usuario")
            awaitingSelection = true
            onMuseDevicesFound?.invoke(muses.map { it.name })
        }
    }

    private fun connectTo(m: Muse) {
        prefs.edit().putString(PREF_DEVICE_NAME, m.name).apply()
        muse = m
        setConnecting(true)
        m.registerConnectionListener(connectionListener)
        listOf(
            MuseDataPacketType.ALPHA_ABSOLUTE,
            MuseDataPacketType.BETA_ABSOLUTE,
            MuseDataPacketType.THETA_ABSOLUTE,
            MuseDataPacketType.DELTA_ABSOLUTE,
            MuseDataPacketType.GAMMA_ABSOLUTE,
            MuseDataPacketType.ACCELEROMETER,
            MuseDataPacketType.GYRO,
            MuseDataPacketType.HSI,
            MuseDataPacketType.HSI_PRECISION,
            MuseDataPacketType.BATTERY,
            MuseDataPacketType.ARTIFACTS
        ).forEach { m.registerDataListener(dataListener, it) }
        m.runAsynchronously()

        // Timeout: si en 10 s no llega CONNECTED, abortar y relanzar escáner
        connectTimeoutFuture?.cancel(false)
        connectTimeoutFuture = scheduler.schedule({
            if (!connected) {
                Log.w(TAG, "Timeout conectando a ${m.name} — reintentando escáner…")
                this.muse?.disconnect()
                this.muse = null
                setConnecting(false)
                startScanIfReady()
            }
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun setConnecting(isConnecting: Boolean) {
        if (connecting == isConnecting) return
        connecting = isConnecting
        onConnectingChanged?.invoke(isConnecting)
    }

    // ── Listener de conexión ──────────────────────────────────────────────────

    private val connectionListener = object : MuseConnectionListener() {
        override fun receiveMuseConnectionPacket(p: MuseConnectionPacket, muse: Muse) {
            val isNow = p.currentConnectionState == ConnectionState.CONNECTED
            // Cancelar el timeout en cuanto la conexión se resuelve (éxito o fallo)
            if (isNow || p.currentConnectionState == ConnectionState.DISCONNECTED) {
                connectTimeoutFuture?.cancel(false)
                connectTimeoutFuture = null
                setConnecting(false)
            }
            if (isNow == connected) return
            connected = isNow
            Log.i(TAG, "Estado conexión MUSE: ${if (connected) "CONECTADO" else "DESCONECTADO"}")
            onConnectionStateChanged?.invoke(connected)
            if (!connected) {
                // Si se desconecta, limpiar referencia y volver a escanear
                this@MuseDirectReceiver.muse = null
                startScanIfReady()
            }
        }
    }

    // ── Listener de datos ─────────────────────────────────────────────────────

    private val dataListener = object : MuseDataListener() {

        override fun receiveMuseDataPacket(p: MuseDataPacket, muse: Muse) {
            val now = System.currentTimeMillis()
            when (p.packetType()) {

                MuseDataPacketType.ALPHA_ABSOLUTE -> {
                    eegMean(p)?.let {
                        lastAlphaTime = now
                        state.updateAndGet { s -> s.copy(alphaAbsolute = it) }
                    }
                }
                MuseDataPacketType.BETA_ABSOLUTE  -> {
                    eegMean(p)?.let {
                        lastBetaTime = now
                        state.updateAndGet { s -> s.copy(betaAbsolute = it) }
                    }
                }
                MuseDataPacketType.THETA_ABSOLUTE -> {
                    eegMean(p)?.let { state.updateAndGet { s -> s.copy(thetaAbsolute = it) } }
                }
                MuseDataPacketType.DELTA_ABSOLUTE -> {
                    eegMean(p)?.let { state.updateAndGet { s -> s.copy(deltaAbsolute = it) } }
                }
                MuseDataPacketType.GAMMA_ABSOLUTE -> {
                    eegMean(p)?.let { state.updateAndGet { s -> s.copy(gammaAbsolute = it) } }
                }

                MuseDataPacketType.ACCELEROMETER  -> {
                    state.updateAndGet { s -> s.copy(
                        accX = p.getAccelerometerValue(Accelerometer.X).toFloat(),
                        accY = p.getAccelerometerValue(Accelerometer.Y).toFloat(),
                        accZ = p.getAccelerometerValue(Accelerometer.Z).toFloat()
                    )}
                }

                MuseDataPacketType.GYRO           -> {
                    state.updateAndGet { s -> s.copy(
                        gyroX = p.getGyroValue(Gyro.X).toFloat(),
                        gyroY = p.getGyroValue(Gyro.Y).toFloat(),
                        gyroZ = p.getGyroValue(Gyro.Z).toFloat()
                    )}
                }

                MuseDataPacketType.HSI,
                MuseDataPacketType.HSI_PRECISION  -> {
                    // Head Signal Indicator: 1=buena, 2=regular, 4=sin contacto (media de 4 canales)
                    eegMean(p)?.let { state.updateAndGet { s -> s.copy(signalQuality = it) } }
                }

                MuseDataPacketType.BATTERY        -> {
                    val charge = p.getBatteryValue(Battery.CHARGE_PERCENTAGE_REMAINING)
                    state.updateAndGet { s -> s.copy(battery = charge.toInt().coerceIn(0, 100)) }
                }

                else -> { /* ignorar tipos no usados */ }
            }
        }

        override fun receiveMuseArtifactPacket(p: MuseArtifactPacket, muse: Muse) {
            val now = System.currentTimeMillis()

            // Mandíbula: prioridad sobre parpadeo — solo actualiza el estado; onTick lo publica
            if (p.jawClench) {
                lastJawClenchTime = now
                state.updateAndGet { it.copy(jawClench = true, blink = false) }
            }

            // Parpadeo: con debounce configurable y guardia anti-mandíbula (300 ms)
            // Solo actualiza el estado; onTick es el único que publica (evita doble disparo)
            if (p.blink
                && (now - lastBlinkTime)     >= blinkDebounceMs
                && (now - lastJawClenchTime) >= 300L
            ) {
                lastBlinkTime = now
                state.updateAndGet { it.copy(blink = true) }
            }
        }
    }

    // ── Sampler periódico ─────────────────────────────────────────────────────

    /**
     * Se ejecuta cada 250 ms en el hilo "MuseSampler".
     * Sólo publica si la conexión está activa y hay datos EEG recientes (< 5 s).
     */
    private fun onTick() {
        if (!connected) return
        val now = System.currentTimeMillis()
        val hasData = lastAlphaTime > 0 && (now - lastAlphaTime) < 5_000L
                   && lastBetaTime  > 0 && (now - lastBetaTime)  < 5_000L

        if (hasData != elementsActive) {
            elementsActive = hasData
            onElementsStateChanged?.invoke(hasData)
        }

        if (hasData) {
            // getAndUpdate resetea los flags de eventos (blink/jawClench) para el próximo ciclo
            val snap = state.getAndUpdate { it.copy(blink = false, jawClench = false) }
            onMuseDataReceived?.invoke(snap.copy(timestamp = now))
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Media de los 4 canales EEG de un paquete.
     * Devuelve null si todos son NaN o no finitos.
     */
    private fun eegMean(p: MuseDataPacket): Float? {
        // Sin listOf()+filter(): se llama por cada paquete EEG de LibMuse, y esas
        // dos listas intermedias (con boxing de Double) eran basura pura.
        var sum = 0.0
        var n = 0
        for (ch in EEG_CHANNELS) {
            val v = p.getEegChannelValue(ch)
            if (v.isFinite()) { sum += v; n++ }
        }
        return if (n == 0) null else (sum / n).toFloat()
    }

    companion object {
        /** Canales del MUSE, en un array reutilizable (evita crear la lista por paquete). */
        private val EEG_CHANNELS = arrayOf(Eeg.EEG1, Eeg.EEG2, Eeg.EEG3, Eeg.EEG4)

        private const val TAG                = "MuseDirectReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        const val PREF_DEVICE_NAME           = "preferred_muse_device"
    }
}
