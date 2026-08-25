package com.tfg.temieeg.eeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests de la derivación de umbrales personalizados (calibración por usuario). */
class CalibrationTest {

    @Test
    fun umbralesRodeanLaLineaBaseDeMellow() {
        // Reposo con mellow centrado en 0.40 y algo de dispersión.
        val mellow = listOf(0.36f, 0.38f, 0.40f, 0.42f, 0.44f)
        val conc   = listOf(0.20f, 0.22f, 0.24f, 0.26f, 0.28f)
        val gamma  = listOf(0.10f, 0.11f, 0.12f, 0.13f, 0.14f)

        val c = MentalStateProcessor.computeCalibration(mellow, conc, gamma)

        // El umbral de estrés queda por debajo y el de calma por encima de la media.
        assertTrue("estrés < media mellow", c.stressThreshold < c.meanMellow)
        assertTrue("calma > media mellow",  c.calmThreshold  > c.meanMellow)
        // Atención por encima de la concentración de reposo.
        assertTrue("atención > media conc", c.attentionThreshold > c.meanConcentration)
        assertEquals(5, c.samples)
    }

    @Test
    fun señalPlana_noProduceUmbralesDegenerados() {
        // σ ≈ 0: sin recorte, estrés y calma coincidirían con la media.
        val flat = List(30) { 0.30f }
        val c = MentalStateProcessor.computeCalibration(flat, flat, flat)

        // El recorte mantiene estrés ≤ 0.45 y calma ≥ 0.45 (nunca invertidos).
        assertTrue(c.stressThreshold <= 0.45f)
        assertTrue(c.calmThreshold  >= 0.45f)
        assertTrue("estrés < calma siempre", c.stressThreshold < c.calmThreshold)
    }

    @Test
    fun masDispersion_ensanchaLaBandaNeutral() {
        val tight = listOf(0.39f, 0.40f, 0.41f)
        val wide  = listOf(0.25f, 0.40f, 0.55f)
        val conc  = listOf(0.20f, 0.22f, 0.24f)
        val gamma = listOf(0.10f, 0.12f, 0.14f)

        val cTight = MentalStateProcessor.computeCalibration(tight, conc, gamma)
        val cWide  = MentalStateProcessor.computeCalibration(wide,  conc, gamma)

        val bandTight = cTight.calmThreshold - cTight.stressThreshold
        val bandWide  = cWide.calmThreshold  - cWide.stressThreshold
        assertTrue("más varianza → banda neutral más ancha", bandWide > bandTight)
    }
}
