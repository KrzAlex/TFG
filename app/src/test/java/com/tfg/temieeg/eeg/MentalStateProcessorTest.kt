package com.tfg.temieeg.eeg

import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.data.MuseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la clasificación de estado mental (α,β,θ,γ → estado).
 *
 * Las bandas se pasan en log10-potencia (como Mind Monitor). Se construyen
 * muestras controladas para forzar cada rama de decisión sin hardware.
 */
class MentalStateProcessorTest {

    private fun sample(a: Float, b: Float, t: Float = 0f, g: Float = 0f, q: Float = 1f) =
        MuseState(alphaAbsolute = a, betaAbsolute = b, thetaAbsolute = t, gammaAbsolute = g, signalQuality = q)

    private fun feed(p: MentalStateProcessor, s: MuseState, n: Int = 4) = repeat(n) { p.addSample(s) }

    @Test
    fun alphaDominante_clasificaCalma() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 1.0f, b = 0.0f))   // mellow ≈ 0.91 > calmThreshold
        assertEquals(MentalState.CALM, p.getCurrentState())
    }

    @Test
    fun betaDominanteMellowBajo_clasificaEstres() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 0.0f, b = 1.0f))   // mellow ≈ 0.09 < stressThreshold
        assertEquals(MentalState.STRESS, p.getCurrentState())
    }

    @Test
    fun betaAltoConGamma_clasificaAtencion() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 0.8f, b = 1.0f, g = 0.8f))  // conc>0.38 y γ>0.15, mellow≥0.22
        assertEquals(MentalState.ATTENTION, p.getCurrentState())
    }

    @Test
    fun betaAltoSinGamma_noEsAtencion_esNeutral() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 0.8f, b = 1.0f, g = 0.0f))  // conc alto pero γ=0 → no atención
        assertEquals(MentalState.NEUTRAL, p.getCurrentState())
    }

    @Test
    fun señalMala_descartaMuestras() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 1.0f, b = 0.0f, q = 4.0f))  // horseshoe 4 > umbral → descartada
        assertEquals(MentalState.NEUTRAL, p.getCurrentState())
        assertEquals(0f, p.getMetrics()["mellow"]!!, 1e-4f)
    }

    @Test
    fun menosDelMinimoDeMuestras_devuelveNeutral() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 1.0f, b = 0.0f), n = 2)     // < MIN_SAMPLES (3)
        assertEquals(MentalState.NEUTRAL, p.getCurrentState())
    }

    @Test
    fun sinDatosEeg_alphaYBetaCero_devuelveNeutral() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 0.0f, b = 0.0f))            // hasEeg == false → índices 0
        assertEquals(MentalState.NEUTRAL, p.getCurrentState())
    }

    @Test
    fun reset_vaciaBuffers() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 1.0f, b = 0.0f))
        p.reset()
        assertEquals(MentalState.NEUTRAL, p.getCurrentState())
    }

    @Test
    fun umbralesConfigurables_cambianLaClasificacion() {
        val p = MentalStateProcessor()
        p.calmThreshold = 0.95f                        // sube el listón por encima del mellow real
        feed(p, sample(a = 1.0f, b = 0.0f))            // mellow ≈ 0.91 < 0.95 → ya no es calma
        assertTrue(p.getCurrentState() != MentalState.CALM)
    }

    @Test
    fun metrics_reflejanLaMediaDelBuffer() {
        val p = MentalStateProcessor()
        feed(p, sample(a = 1.0f, b = 0.0f))
        val m = p.getMetrics()
        assertTrue("mellow debería ser alto", m["mellow"]!! > 0.5f)
        assertTrue("concentration debería ser bajo", m["concentration"]!! < 0.2f)
    }
}
