package com.tfg.temieeg.eeg

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * Tests del decodificador Morse por parpadeo.
 *
 * Usa Robolectric para el Handler/Looper y un reloj inyectable. El helper
 * [advance] avanza en paralelo el reloj lógico y el reloj del looper para que
 * los `postDelayed` y los `gap` entre parpadeos queden sincronizados.
 */
@RunWith(RobolectricTestRunner::class)
class MorseDecoderTest {

    private var t = 0L

    private fun decoder() = MorseDecoder(
        dashWindowMs = 500L, letterGapMs = 1500L, wordGapMs = 3000L, clock = { t }
    )

    private fun advance(ms: Long) {
        t += ms
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun unParpadeoLento_decodificaPunto_letraE() {
        val d = decoder()
        d.recordBlink()
        advance(600)     // > dashWindow → se clasifica como PUNTO
        advance(1000)    // > letterGap  → se cierra la letra "·" = E
        assertEquals("E", d.decodedText)
    }

    @Test
    fun dobleParpadeoRapido_decodificaRaya_letraT() {
        val d = decoder()
        d.recordBlink()
        advance(100)     // dentro de dashWindow
        d.recordBlink()  // segundo parpadeo rápido → RAYA
        advance(2000)    // cierre de letra "—" = T
        assertEquals("T", d.decodedText)
    }

    @Test
    fun puntoLuegoRaya_decodificaLetraA() {
        val d = decoder()
        d.recordBlink()
        advance(600)     // PUNTO
        d.recordBlink()  // nuevo símbolo dentro de la misma letra
        advance(100)
        d.recordBlink()  // segundo rápido → RAYA  → "·—"
        advance(2000)    // cierre → "·—" = A
        assertEquals("A", d.decodedText)
    }

    @Test
    fun clear_borraElTextoDecodificado() {
        val d = decoder()
        d.recordBlink(); advance(600); advance(1000)   // "E"
        d.clear()
        assertEquals("", d.decodedText)
    }

    @Test
    fun tablaMorse_esCorrectaYCompleta() {
        // No requiere construir el decoder (tabla estática) → valida el mapeo ITU.
        assertEquals('S', MorseDecoder.MORSE_TABLE["···"])
        assertEquals('O', MorseDecoder.MORSE_TABLE["———"])
        assertEquals('A', MorseDecoder.MORSE_TABLE["·—"])
        assertEquals('5', MorseDecoder.MORSE_TABLE["·····"])
        assertEquals(36, MorseDecoder.MORSE_TABLE.size)   // 26 letras + 10 dígitos
    }

    @Test
    fun busquedaInversa_devuelveElPatronDeCadaLetra() {
        // Lo usa la sala Morse para mostrar en pantalla que hay que hacer,
        // en vez de obligar a abrir el menu de ayuda en cada letra.
        assertEquals("···", MorseDecoder.codeFor('S'))
        assertEquals("—", MorseDecoder.codeFor('T'))
        assertEquals("·—", MorseDecoder.codeFor('a'))   // acepta minusculas
        assertEquals(null, MorseDecoder.codeFor('Ñ'))         // fuera de la tabla ITU
    }

    @Test
    fun patronEspaciado_seLeeMejorEnPantalla() {
        assertEquals("· · ·", MorseDecoder.spacedCodeFor('S'))
        assertEquals("· —", MorseDecoder.spacedCodeFor('A'))
        assertEquals(null, MorseDecoder.spacedCodeFor('Ñ'))
    }

    @Test
    fun tablaInversa_cubreTodaLaTabla() {
        // Ninguna letra o digito puede quedarse sin patron que mostrar.
        MorseDecoder.MORSE_TABLE.values.forEach { letra ->
            assertEquals("patron de $letra", true, MorseDecoder.codeFor(letra) != null)
        }
    }
}
