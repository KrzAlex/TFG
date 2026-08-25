package com.tfg.temieeg.eeg

import android.util.Log
import kotlin.math.abs

/**
 * Detecta gestos de cabeza a partir del giroscopio del MUSE.
 *
 * Gestos detectados:
 *   NOD   (asentir "sí") — oscilación en el eje Y del giroscopio (pitch real)
 *   SHAKE (negar  "no")  — oscilación en el eje Z del giroscopio (yaw)
 *
 * Ejes del MUSE 2 (orientación física del IMU sobre la frente):
 *   X = roll  — inclinar la cabeza de oreja a hombro
 *   Y = pitch — asentir arriba/abajo  ← NOD
 *   Z = yaw   — negar izquierda/derecha  ← SHAKE
 *
 * Por qué Y y no X para el nod:
 *   El gesto "no" (yaw/Z) provoca cross-talk en X (rotación de cuello),
 *   pero apenas afecta a Y. Usando Y para el nod se elimina prácticamente
 *   todo solapamiento entre los dos detectores.
 *
 * Supresión cruzada de ejes:
 *   Aunque Y ya es limpio, se mantiene la supresión: si Z supera [shakeThreshold]
 *   el estado NOD se resetea para evitar cualquier falso positivo residual.
 *
 * Umbrales independientes:
 *   El NOD suele tener menor amplitud que el SHAKE → umbrales distintos por defecto.
 *   Ambos son ajustables en la pantalla de Configuración.
 */
class HeadGestureDetector(
    /** Umbral en °/s para detectar asentir (gyroY — pitch). Suele ser menor que [shakeThreshold]. */
    var nodThreshold: Float = 30f,
    /** Umbral en °/s para detectar negar  (gyroZ — yaw). */
    var shakeThreshold: Float = 50f,
    /** Tiempo máximo entre las dos oscilaciones de un gesto (ms). */
    var gestureWindowMs: Long = 800L,
    /** Tiempo mínimo entre dos detecciones del mismo gesto (ms). */
    var gestureDebounceMs: Long = 1500L,
    /** Fuente de tiempo (ms). Inyectable para tests deterministas; por defecto el reloj real. */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    var onNod:   (() -> Unit)? = null
    var onShake: (() -> Unit)? = null

    // Estado eje pitch (gyroY) — nod
    private var nodLastDir      = 0
    private var nodWindowStart  = 0L
    private var nodLastFireTime = 0L

    // Estado eje yaw (gyroZ) — shake
    private var shakeLastDir      = 0
    private var shakeWindowStart  = 0L
    private var shakeLastFireTime = 0L

    // ── API ──────────────────────────────────────────────────────────────────

    fun addSample(@Suppress("UNUSED_PARAMETER") gyroX: Float, gyroY: Float, gyroZ: Float) {
        val now = clock()

        // ── Supresión cruzada ─────────────────────────────────────────────────
        // Si el eje de SHAKE (Z) está activo, resetear el estado del NOD (Y)
        // como medida de seguridad, aunque el cross-talk Y↔Z es mínimo.
        val shakeAxisActive = abs(gyroZ) >= shakeThreshold

        if (shakeAxisActive) {
            nodLastDir     = 0
            nodWindowStart = 0L
        } else {
            checkAxis(
                value       = gyroY,           // ← Y es el pitch real en MUSE 2
                now         = now,
                threshold   = nodThreshold,
                lastDir     = ::nodLastDir::get,      setLastDir = { nodLastDir = it },
                windowStart = ::nodWindowStart::get,  setWindow  = { nodWindowStart = it },
                lastFire    = ::nodLastFireTime::get,  setFire   = { nodLastFireTime = it },
                label       = "NOD",
                callback    = onNod
            )
        }

        checkAxis(
            value       = gyroZ,
            now         = now,
            threshold   = shakeThreshold,
            lastDir     = ::shakeLastDir::get,      setLastDir = { shakeLastDir = it },
            windowStart = ::shakeWindowStart::get,  setWindow  = { shakeWindowStart = it },
            lastFire    = ::shakeLastFireTime::get,  setFire   = { shakeLastFireTime = it },
            label       = "SHAKE",
            callback    = onShake
        )
    }

    fun reset() {
        nodLastDir = 0;   nodWindowStart = 0L;   nodLastFireTime = 0L
        shakeLastDir = 0; shakeWindowStart = 0L; shakeLastFireTime = 0L
    }

    // ── Lógica interna ────────────────────────────────────────────────────────

    private fun checkAxis(
        value: Float,
        now: Long,
        threshold: Float,
        lastDir: () -> Int,      setLastDir: (Int) -> Unit,
        windowStart: () -> Long, setWindow:  (Long) -> Unit,
        lastFire: () -> Long,    setFire:    (Long) -> Unit,
        label: String,
        callback: (() -> Unit)?
    ) {
        // ── 1. Expirar ventana pendiente ────────────────────────────────────
        if (lastDir() != 0 && (now - windowStart()) > gestureWindowMs) {
            setLastDir(0)
            setWindow(0L)
        }

        // ── 2. Clasificar muestra actual ────────────────────────────────────
        val dir = when {
            value >  threshold  ->  1
            value < -threshold  -> -1
            else                -> return   // dentro del rango de ruido → ignorar
        }

        // ── 3. Mismo sentido que el pico pendiente → refrescar ventana ──────
        if (dir == lastDir()) {
            setWindow(now)
            return
        }

        // ── 4. Sin pico previo → registrar primer pico ──────────────────────
        if (lastDir() == 0) {
            setLastDir(dir)
            setWindow(now)
            return
        }

        // ── 5. Sentido opuesto dentro de la ventana → GESTO DETECTADO ───────
        if ((now - lastFire()) > gestureDebounceMs) {
            Log.d(TAG, "$label detectado (value=${"%.1f".format(value)} °/s, " +
                       "Δt=${now - windowStart()} ms, umbral=$threshold)")
            setFire(now)
            setLastDir(0)
            setWindow(0L)
            callback?.invoke()
        } else {
            // Debounce activo: ignorar y resetear para no acumular
            setLastDir(0)
            setWindow(0L)
        }
    }

    companion object {
        private const val TAG = "HeadGestureDetector"
    }
}
