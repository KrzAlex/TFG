package com.tfg.temieeg.eeg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests del detector de gestos de cabeza (giroscopio → nod/shake).
 *
 * Usa el reloj inyectable para controlar ventanas y debounce de forma determinista.
 * El reloj arranca en un valor alto para que el primer gesto supere el debounce
 * inicial (en producción `now` son millis epoch, siempre > gestureDebounceMs).
 */
class HeadGestureDetectorTest {

    private var t = 1_000_000L

    private fun detector() = HeadGestureDetector(
        nodThreshold = 30f,
        shakeThreshold = 50f,
        gestureWindowMs = 800L,
        gestureDebounceMs = 1500L,
        clock = { t }
    )

    @Test
    fun oscilacionEnY_detectaNod() {
        var nods = 0; var shakes = 0
        val d = detector().apply { onNod = { nods++ }; onShake = { shakes++ } }
        t = 1_000_000L; d.addSample(0f, 40f, 0f)    // pico +Y
        t = 1_000_100L; d.addSample(0f, -40f, 0f)   // pico -Y dentro de ventana → NOD
        assertEquals(1, nods)
        assertEquals(0, shakes)
    }

    @Test
    fun oscilacionEnZ_detectaShake() {
        var nods = 0; var shakes = 0
        val d = detector().apply { onNod = { nods++ }; onShake = { shakes++ } }
        t = 1_000_000L; d.addSample(0f, 0f, 60f)
        t = 1_000_100L; d.addSample(0f, 0f, -60f)
        assertEquals(1, shakes)
        assertEquals(0, nods)
    }

    @Test
    fun pordebajoDelUmbral_noDetectaNada() {
        var nods = 0; var shakes = 0
        val d = detector().apply { onNod = { nods++ }; onShake = { shakes++ } }
        t = 1_000_000L; d.addSample(0f, 20f, 0f)    // |20| < 30
        t = 1_000_100L; d.addSample(0f, -20f, 0f)
        assertEquals(0, nods)
        assertEquals(0, shakes)
    }

    @Test
    fun debounce_impideDobleDisparo() {
        var nods = 0
        val d = detector().apply { onNod = { nods++ } }
        t = 1_000_000L; d.addSample(0f, 40f, 0f)
        t = 1_000_100L; d.addSample(0f, -40f, 0f)   // NOD #1
        t = 1_000_200L; d.addSample(0f, 40f, 0f)
        t = 1_000_300L; d.addSample(0f, -40f, 0f)   // dentro de 1500ms → bloqueado
        assertEquals(1, nods)
    }

    @Test
    fun ventanaExpirada_noDetecta() {
        var nods = 0
        val d = detector().apply { onNod = { nods++ } }
        t = 1_000_000L; d.addSample(0f, 40f, 0f)
        t = 1_000_900L; d.addSample(0f, -40f, 0f)   // 900ms > ventana 800 → no cuenta
        assertEquals(0, nods)
    }

    @Test
    fun supresionCruzada_zActivo_noDisparaNodYSiShake() {
        var nods = 0; var shakes = 0
        val d = detector().apply { onNod = { nods++ }; onShake = { shakes++ } }
        // Y oscila pero Z está por encima de su umbral → el estado NOD se suprime
        t = 1_000_000L; d.addSample(0f, 40f, 60f)
        t = 1_000_100L; d.addSample(0f, -40f, -60f)
        assertEquals(0, nods)
        assertEquals(1, shakes)
    }
}
