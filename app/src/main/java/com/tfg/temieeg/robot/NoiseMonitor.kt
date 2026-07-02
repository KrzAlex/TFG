package com.tfg.temieeg.robot

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Monitoriza el nivel de ruido ambiente usando el micrófono del dispositivo.
 *
 * No graba audio: usa [MediaRecorder.getMaxAmplitude] y descarta los datos
 * (el archivo temporal se borra al detener).
 *
 * Requiere permiso RECORD_AUDIO (solicitado en MainActivity antes de llamar a [start]).
 *
 * Uso:
 *   noiseMonitor.onAmplitude = { amp -> if (amp > NoiseMonitor.DEFAULT_THRESHOLD) ... }
 *   noiseMonitor.start()
 *   // ...
 *   noiseMonitor.stop()
 */
class NoiseMonitor(private val context: Context) {

    /**
     * Se llama en el hilo principal cada [checkIntervalMs] con la amplitud máxima
     * registrada desde la última llamada. Rango: 0 (silencio) – 32 767 (muy alto).
     */
    var onAmplitude: ((Int) -> Unit)? = null

    /** Intervalo entre mediciones (ms). Por defecto 400 ms (≈ 2.5 Hz). */
    var checkIntervalMs: Long = 400L

    private val handler  = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var active   = false

    /** Archivo temporal donde MediaRecorder vierte los datos (se borra en [stop]). */
    private val tmpFile: File
        get() = File(context.cacheDir, "noise_monitor.tmp")

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    fun start() {
        if (active) return
        try {
            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(tmpFile.absolutePath)
                prepare()
                start()
            }
            active = true
            scheduleCheck()
            Log.d(TAG, "Iniciado — intervalo ${checkIntervalMs} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar NoiseMonitor: ${e.message}")
        }
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) { /* grabadora ya detenida */ }
        recorder = null
        tmpFile.delete()
        Log.d(TAG, "Detenido")
    }

    val isActive: Boolean get() = active

    // ── Bucle de muestreo ─────────────────────────────────────────────────────

    private fun scheduleCheck() {
        handler.postDelayed({
            if (!active) return@postDelayed
            val amplitude = recorder?.maxAmplitude ?: 0
            onAmplitude?.invoke(amplitude)
            scheduleCheck()
        }, checkIntervalMs)
    }

    // ── Helper de creación (gestiona deprecación API 31) ──────────────────────

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else MediaRecorder()

    // ── Constantes ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NoiseMonitor"

        /**
         * Umbral de amplitud por defecto para considerar que hay "ruido".
         * Ajustable según el entorno: sala silenciosa ≈ 200–500,
         * conversación normal ≈ 1500–3000, grito ≈ 6000+.
         */
        const val DEFAULT_THRESHOLD = 1500
    }
}
