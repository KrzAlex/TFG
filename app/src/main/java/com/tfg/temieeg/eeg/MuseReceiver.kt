package com.tfg.temieeg.eeg

import com.tfg.temieeg.data.MuseState

/**
 * Contrato común para los dos modos de recepción de datos del MUSE:
 *
 *   [OSCReceiver]        — UDP/OSC desde Mind Monitor (WiFi, sin SDK extra)
 *   [MuseDirectReceiver] — Bluetooth directo con LibMuse SDK (sin Mind Monitor)
 *
 * [MainActivity] trabaja siempre contra esta interfaz; el modo activo
 * se elige en la pantalla de Configuración y se persiste en SharedPreferences.
 */
interface MuseReceiver {

    /** Se llama cada ~250 ms con el snapshot completo de métricas. */
    var onMuseDataReceived: ((MuseState) -> Unit)?

    /** true = MUSE recibiendo datos · false = desconectado / sin señal. */
    var onConnectionStateChanged: ((Boolean) -> Unit)?

    /**
     * Notifica que hay un intento de conexión en curso (true = conectando, false = ya no).
     * Solo relevante en [MuseDirectReceiver]; [OSCReceiver] nunca lo invoca.
     */
    var onConnectingChanged: ((Boolean) -> Unit)?

    /**
     * true  = alpha y beta llegando → clasificación EEG activa.
     * false = conectado pero sin bandas (Mind Monitor: activar en OSC settings).
     */
    var onElementsStateChanged: ((Boolean) -> Unit)?

    /**
     * Tiempo mínimo entre parpadeos válidos (ms).
     * Normal: 500 ms · Modo Morse: 150 ms.
     */
    var blinkDebounceMs: Long

    /**
     * Se llama cuando se detectan varios dispositivos MUSE a la vez y no hay preferencia guardada.
     * La UI muestra un selector y llama a [selectMuse] con el nombre elegido.
     * Solo relevante en [MuseDirectReceiver]; ignorado en [OSCReceiver].
     */
    var onMuseDevicesFound: ((List<String>) -> Unit)?
        get() = null; set(_) {}

    /**
     * Confirma el dispositivo seleccionado por el usuario.
     * Guarda la preferencia y lanza la conexión.
     */
    fun selectMuse(name: String) = Unit

    /** Devuelve los nombres de los dispositivos MUSE detectados en el último escaneo BLE. */
    fun getScannedDevices(): List<String> = emptyList()

    /**
     * Lanza un escaneo activo durante [timeoutMs] ms y llama a [onResult] con los nombres
     * encontrados al terminar. Solo relevante en [MuseDirectReceiver].
     */
    fun scanForDevices(timeoutMs: Long = 3_000L, onResult: (List<String>) -> Unit) =
        onResult(emptyList())

    /** Inicia la recepción (OSC: abre socket UDP · BT: arranca escaneo Bluetooth). */
    fun start()

    /** Detiene la recepción y libera recursos. */
    fun stop()

    // ── Constante compartida ──────────────────────────────────────────────────
    companion object {
        const val BLINK_DEBOUNCE_DEFAULT_MS = 500L
    }
}
