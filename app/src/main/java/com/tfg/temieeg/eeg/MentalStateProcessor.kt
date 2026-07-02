package com.tfg.temieeg.eeg

import android.util.Log
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
            Log.d(TAG, "Muestra descartada — señal mala (horseshoe=${state.signalQuality})")
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

        push(concentrationBuffer, concentration)
        push(mellowBuffer,        mellow)
        push(gammaActivityBuffer, gammaActivity)
        push(alphaBuffer,         state.alphaAbsolute)
        push(betaBuffer,          state.betaAbsolute)
        push(thetaBuffer,         state.thetaAbsolute)
        push(deltaBuffer,         state.deltaAbsolute)
        push(gammaBuffer,         state.gammaAbsolute)
    }

    fun getCurrentState(): MentalState {
        if (concentrationBuffer.size < MIN_SAMPLES) return MentalState.NEUTRAL

        val avgConcentration  = concentrationBuffer.average().toFloat()
        val avgMellow         = mellowBuffer.average().toFloat()
        val avgGammaActivity  = gammaActivityBuffer.average().toFloat()

        if (avgConcentration < 0.001f && avgMellow < 0.001f) return MentalState.NEUTRAL

        Log.d(TAG,
            "mellow=%.3f  conc=%.3f  γAct=%.3f  " +
            "[stress<%.2f | attn>%.2f+γ>%.2f | calm>%.2f]".format(
                avgMellow, avgConcentration, avgGammaActivity,
                stressThreshold, attentionThreshold, gammaActivityThreshold, calmThreshold))

        return when {
            avgMellow < stressThreshold                                              -> MentalState.STRESS
            avgConcentration > attentionThreshold
                && avgGammaActivity > gammaActivityThreshold                        -> MentalState.ATTENTION
            avgMellow > calmThreshold                                               -> MentalState.CALM
            else                                                                    -> MentalState.NEUTRAL
        }
    }

    fun getMetrics(): Map<String, Float> = mapOf(
        "concentration"  to (concentrationBuffer.average().toFloat().takeIf { !it.isNaN() } ?: 0f),
        "mellow"         to (mellowBuffer.average().toFloat().takeIf         { !it.isNaN() } ?: 0f),
        "gammaActivity"  to (gammaActivityBuffer.average().toFloat().takeIf  { !it.isNaN() } ?: 0f),
        "alpha"          to (alphaBuffer.lastOrNull()  ?: 0f),
        "beta"           to (betaBuffer.lastOrNull()   ?: 0f),
        "theta"          to (thetaBuffer.lastOrNull()  ?: 0f),
        "delta"          to (deltaBuffer.lastOrNull()  ?: 0f),
        "gamma"          to (gammaBuffer.lastOrNull()  ?: 0f)
    )

    fun reset() {
        listOf(concentrationBuffer, mellowBuffer, gammaActivityBuffer,
               alphaBuffer, betaBuffer, thetaBuffer, deltaBuffer, gammaBuffer
        ).forEach { it.clear() }
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
    }
}
