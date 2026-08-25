package com.tfg.temieeg.eeg

import android.util.Log
import com.tfg.temieeg.BuildConfig
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.data.MuseState
import kotlin.math.pow

/**
 * Clasifica el estado mental a partir de las bandas EEG brutas de Mind Monitor.
 *
 * ── Índices derivados (dominio lineal, denominador α+β+θ+γ) ────────────────
 *
 *   linA = 10^alpha,  linB = 10^beta,  linT = 10^theta,  linG = 10^gamma
 *   total = linA + linB + linT + linG
 *
 *   mellow        = linA / total  ∈ [0,1]  — alpha dominante  → calma
 *   concentration = linB / total  ∈ [0,1]  — beta dominante   → alerta/estrés
 *   gammaActivity = linG / total  ∈ [0,1]  — gamma dominante  → foco cognitivo activo
 *
 * ── Por qué gamma mejora la clasificación ─────────────────────────────────
 *
 *   El problema clásico α+β+θ: beta alto se da tanto en STRESS como en ATTENTION.
 *   Gamma permite discriminarlos:
 *     · ATTENTION real (foco, "flujo") → beta ALTO + gamma ALTO
 *     · STRESS/ansiedad               → beta ALTO + gamma normal/bajo
 *   Incluir gamma en el denominador también estabiliza los otros índices.
 *
 * ── Lógica de prioridad ───────────────────────────────────────────────────
 *
 *   STRESS     → mellow < [stressThreshold]
 *   ATTENTION  → concentration > [attentionThreshold] Y gammaActivity > [gammaActivityThreshold]
 *   CALM       → mellow > [calmThreshold]
 *   NEUTRAL    → resto (beta alto sin gamma = estado ambiguo no clasificado como atención)
 *
 * Las muestras con mala calidad de señal (horseshoe > [SIGNAL_BAD_THRESHOLD])
 * se descartan para no contaminar los buffers.
 */
class MentalStateProcessor(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) {

    private val concentrationBuffer  = ArrayDeque<Float>(bufferSize)
    private val mellowBuffer         = ArrayDeque<Float>(bufferSize)
    private val gammaActivityBuffer  = ArrayDeque<Float>(bufferSize)
    private val betaBuffer           = ArrayDeque<Float>(bufferSize)
    private val alphaBuffer          = ArrayDeque<Float>(bufferSize)
    private val thetaBuffer          = ArrayDeque<Float>(bufferSize)
    private val deltaBuffer          = ArrayDeque<Float>(bufferSize)
    private val gammaBuffer          = ArrayDeque<Float>(bufferSize)

    // ── Umbrales configurables ────────────────────────────────────────────────
    // Valores típicos en reposo: mellow ≈ 0.35–0.50, conc ≈ 0.18–0.32, gamma ≈ 0.08–0.18
    var stressThreshold        = 0.22f  // mellow < 0.22       → STRESS
    var attentionThreshold     = 0.38f  // conc   > 0.38       → candidato ATTENTION
    var calmThreshold          = 0.52f  // mellow > 0.52       → CALM
    var gammaActivityThreshold = 0.15f  // gammaActivity > 0.15 confirma ATTENTION real
                                        // (separa foco cognitivo de beta de ansiedad)

    fun addSample(state: MuseState) {
        if (state.signalQuality > SIGNAL_BAD_THRESHOLD) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Muestra descartada — señal mala (horseshoe=${state.signalQuality})")
            return
        }

        val aLog = state.alphaAbsolute
        val bLog = state.betaAbsolute
        val tLog = state.thetaAbsolute
        val gLog = state.gammaAbsolute

        // Una sola conversión log→lineal por muestra (denominador común α+β+θ+γ).
        // aLog==0 && bLog==0 se trata como "sin datos EEG todavía" → índices a 0;
        // gLog<=0 idem para gamma (Muse 1 no envía esa banda).
        val linA  = 10.0.pow(aLog.toDouble()).toFloat()
        val linB  = 10.0.pow(bLog.toDouble()).toFloat()
        val linG  = linPow(gLog)
        val denom = linA + linB + linPow(tLog) + linG
        val hasEeg = !(aLog == 0f && bLog == 0f)

        val mellow        = if (hasEeg)    (linA / denom).coerceIn(0f, 1f) else 0f
        val concentration = if (hasEeg)    (linB / denom).coerceIn(0f, 1f) else 0f
        val gammaActivity = if (gLog > 0f) (linG / denom).coerceIn(0f, 1f) else 0f

        averagesDirty = true
        push(concentrationBuffer, concentration)
        push(mellowBuffer,        mellow)
        push(gammaActivityBuffer, gammaActivity)
        push(alphaBuffer,         state.alphaAbsolute)
        push(betaBuffer,          state.betaAbsolute)
        push(thetaBuffer,         state.thetaAbsolute)
        push(deltaBuffer,         state.deltaAbsolute)
        push(gammaBuffer,         state.gammaAbsolute)

        // Durante una calibración en reposo, acumulamos los índices por muestra
        // (no la media móvil) para estimar la línea base personal del usuario.
        if (calibrating && hasEeg) {
            calibMellow.add(mellow)
            calibConc.add(concentration)
            calibGamma.add(gammaActivity)
        }
    }

    // ── Calibración por usuario ────────────────────────────────────────────────

    private val calibMellow = ArrayList<Float>()
    private val calibConc   = ArrayList<Float>()
    private val calibGamma  = ArrayList<Float>()
    @Volatile private var calibrating = false

    /** Nº de muestras válidas acumuladas en la calibración en curso. */
    val calibrationSampleCount: Int get() = calibMellow.size

    /** Inicia la captura de línea base (el usuario debe estar en reposo). */
    fun beginCalibration() {
        calibrating = true
        calibMellow.clear(); calibConc.clear(); calibGamma.clear()
    }

    /** Cancela la calibración sin aplicar cambios. */
    fun cancelCalibration() { calibrating = false }

    /**
     * Cierra la calibración. Si hay al menos [minSamples] muestras, calcula los
     * umbrales personalizados a partir de la línea base y **los aplica**; devuelve
     * el resultado. Si no hay muestras suficientes devuelve null (no cambia nada).
     */
    fun finishCalibration(minSamples: Int = MIN_CALIBRATION_SAMPLES): Calibration? {
        calibrating = false
        if (calibMellow.size < minSamples) return null
        val c = computeCalibration(calibMellow, calibConc, calibGamma)
        stressThreshold        = c.stressThreshold
        attentionThreshold     = c.attentionThreshold
        calmThreshold          = c.calmThreshold
        gammaActivityThreshold = c.gammaActivityThreshold
        return c
    }

    fun getCurrentState(): MentalState {
        if (concentrationBuffer.size < MIN_SAMPLES) return MentalState.NEUTRAL

        refreshAverages()
        val avgConcentration  = avgConc
        val avgMellow         = avgMell
        val avgGammaActivity  = avgGamm

        if (avgConcentration < 0.001f && avgMellow < 0.001f) return MentalState.NEUTRAL

        // El formateo solo se paga en builds de desarrollo (debug/temi); en release
        // este log corría ~4 veces por segundo construyendo un String descartado.
        if (BuildConfig.DEBUG) {
            Log.d(TAG,
                "mellow=%.3f  conc=%.3f  γAct=%.3f  " +
                "[stress<%.2f | attn>%.2f+γ>%.2f | calm>%.2f]".format(
                    avgMellow, avgConcentration, avgGammaActivity,
                    stressThreshold, attentionThreshold, gammaActivityThreshold, calmThreshold))
        }

        return when {
            avgMellow < stressThreshold                                              -> MentalState.STRESS
            avgConcentration > attentionThreshold
                && avgGammaActivity > gammaActivityThreshold                        -> MentalState.ATTENTION
            avgMellow > calmThreshold                                               -> MentalState.CALM
            else                                                                    -> MentalState.NEUTRAL
        }
    }

    fun getMetrics(): Map<String, Float> {
        refreshAverages()
        return metricsOf()
    }

    private fun metricsOf(): Map<String, Float> = mapOf(
        "concentration"  to (avgConc.takeIf { !it.isNaN() } ?: 0f),
        "mellow"         to (avgMell.takeIf { !it.isNaN() } ?: 0f),
        "gammaActivity"  to (avgGamm.takeIf { !it.isNaN() } ?: 0f),
        "alpha"          to (alphaBuffer.lastOrNull()  ?: 0f),
        "beta"           to (betaBuffer.lastOrNull()   ?: 0f),
        "theta"          to (thetaBuffer.lastOrNull()  ?: 0f),
        "delta"          to (deltaBuffer.lastOrNull()  ?: 0f),
        "gamma"          to (gammaBuffer.lastOrNull()  ?: 0f)
    )

    fun reset() {
        averagesDirty = true
        listOf(concentrationBuffer, mellowBuffer, gammaActivityBuffer,
               alphaBuffer, betaBuffer, thetaBuffer, deltaBuffer, gammaBuffer
        ).forEach { it.clear() }
    }

    // ── Medias memoizadas ────────────────────────────────────────────────────
    // getCurrentState() y getMetrics() se invocan por cada muestra (~4 Hz) y antes
    // recorrían los mismos tres buffers dos veces. Se calculan una sola vez por
    // muestra y se reutilizan hasta que llega la siguiente.

    private var averagesDirty = true
    private var avgConc = 0f
    private var avgMell = 0f
    private var avgGamm = 0f

    private fun refreshAverages() {
        if (!averagesDirty) return
        averagesDirty = false
        avgConc = concentrationBuffer.average().toFloat()
        avgMell = mellowBuffer.average().toFloat()
        avgGamm = gammaActivityBuffer.average().toFloat()
    }

    // ── Índices derivados (denominador común α+β+θ+γ) ────────────────────────

    /** log→lineal; valores ≤ 0 se tratan como "banda ausente" (no como 10^0 = 1). */
    private fun linPow(logVal: Float) =
        if (logVal > 0f) 10.0.pow(logVal.toDouble()).toFloat() else 0f

    private fun push(buffer: ArrayDeque<Float>, value: Float) {
        if (buffer.size >= bufferSize) buffer.removeFirst()
        buffer.addLast(value)
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE       = 10
        private const val MIN_SAMPLES       = 3
        private const val SIGNAL_BAD_THRESHOLD = 3.0f
        private const val TAG               = "MentalStateProcessor"

        /** Muestras mínimas de reposo para una calibración fiable (~5 s a 4 Hz). */
        const val MIN_CALIBRATION_SAMPLES = 20

        /**
         * Deriva umbrales personalizados a partir de la línea base en reposo.
         * Función pura (sin estado ni Android) → unitaria y testeable.
         *
         *   estrés  = media(mellow) − 1σ   → detectar caídas de calma bajo el reposo
         *   calma   = media(mellow) + 1σ   → exigir superar claramente el reposo
         *   atención= media(conc)   + 1σ   → concentración por encima del reposo
         *   gamma   = media(gamma)  + 0.5σ → margen menor (banda más ruidosa)
         *
         * Todos se recortan a rangos sensatos para evitar umbrales degenerados
         * cuando la señal es muy plana (σ ≈ 0).
         */
        fun computeCalibration(
            mellow: List<Float>, conc: List<Float>, gamma: List<Float>
        ): Calibration {
            val mMean = mellow.average().toFloat(); val mSd = stdDev(mellow, mMean)
            val cMean = conc.average().toFloat();   val cSd = stdDev(conc, cMean)
            val gMean = gamma.average().toFloat();  val gSd = stdDev(gamma, gMean)
            return Calibration(
                stressThreshold        = (mMean - 1.0f * mSd).coerceIn(0.05f, 0.45f),
                attentionThreshold     = (cMean + 1.0f * cSd).coerceIn(0.20f, 0.80f),
                calmThreshold          = (mMean + 1.0f * mSd).coerceIn(0.45f, 0.95f),
                gammaActivityThreshold = (gMean + 0.5f * gSd).coerceIn(0.08f, 0.60f),
                samples                = mellow.size,
                meanMellow             = mMean,
                meanConcentration      = cMean,
                meanGamma              = gMean
            )
        }

        private fun stdDev(xs: List<Float>, mean: Float): Float {
            if (xs.size < 2) return 0f
            val variance = xs.sumOf { ((it - mean) * (it - mean)).toDouble() } / (xs.size - 1)
            return kotlin.math.sqrt(variance).toFloat()
        }
    }
}

/** Resultado de una calibración: umbrales personalizados + resumen de la línea base. */
data class Calibration(
    val stressThreshold: Float,
    val attentionThreshold: Float,
    val calmThreshold: Float,
    val gammaActivityThreshold: Float,
    val samples: Int,
    val meanMellow: Float,
    val meanConcentration: Float,
    val meanGamma: Float
)
