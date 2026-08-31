package com.tfg.temieeg.game

import android.os.Looper
import com.tfg.temieeg.data.MentalState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * Sala de calma: la superación debe ocurrir una sola vez.
 *
 * El fallo que cubre este test: las muestras de estado mental llegan ~4 veces
 * por segundo y no se bloquean mientras el robot habla, así que las que caían
 * entre la superación y el cambio de sala volvían a entrar por onMentalState,
 * veían el contador parado y lo relanzaban. Resultado en pantalla: la
 * felicitación repetida y los segundos disparándose.
 */
@RunWith(RobolectricTestRunner::class)
class CalmModuleTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private class Counters {
        var success = 0
        var okFeedback = 0
        var hintChanges = 0
    }

    private fun buildModule(seconds: Int): Pair<CalmModule, Counters> {
        val c = Counters()
        val m = CalmModule("Sala", "narración", secondsRequired = seconds)
        m.onSuccess = { c.success++ }
        m.onFeedback = { _, ok -> if (ok) c.okFeedback++ }
        m.onHintChanged = { c.hintChanges++ }
        m.onTemiSpeak = { }
        return m to c
    }

    /** Lleva el módulo hasta superar el reto manteniendo la calma. */
    private fun completeChallenge(m: CalmModule, seconds: Int) {
        m.start()
        m.onMentalState(MentalState.CALM)
        idle()                       // primer tick
        repeat(seconds) { idleFor(1_000) }
    }

    @Test
    fun mantenerLaCalma_superaLaSala() {
        val (m, c) = buildModule(2)
        completeChallenge(m, 2)
        idleFor(1_500)               // retardo previo a onSuccess

        assertEquals("debe superarse una vez", 1, c.success)
        assertEquals("una sola felicitación", 1, c.okFeedback)
    }

    @Test
    fun trasSuperarla_lasMuestrasPosterioresNoLaRelanzan() {
        val (m, c) = buildModule(2)
        completeChallenge(m, 2)

        val hintsAlCompletar = c.hintChanges

        // El flujo real sigue enviando estado mental (~4 Hz) durante el segundo
        // y medio que tarda en avanzarse de sala.
        repeat(6) { m.onMentalState(MentalState.CALM); idleFor(250) }
        idleFor(1_500)

        assertEquals("no debe felicitar varias veces", 1, c.okFeedback)
        assertEquals("no debe superarse varias veces", 1, c.success)
        assertEquals("el contador no debe seguir avanzando", hintsAlCompletar, c.hintChanges)
    }

    @Test
    fun perderLaCalma_reiniciaElContador() {
        val (m, c) = buildModule(3)
        m.start()
        m.onMentalState(MentalState.CALM)
        idle()
        idleFor(1_000)                       // 1 s de calma

        m.onMentalState(MentalState.STRESS)  // se pierde la calma
        idleFor(2_000)

        assertEquals("no debe superarse al perder la calma", 0, c.success)
    }

    @Test
    fun reiniciarLaSala_permiteSuperarlaDeNuevo() {
        // Los módulos del catálogo son instancias únicas y se reutilizan entre
        // partidas: start() debe dejar el estado limpio.
        val (m, c) = buildModule(2)
        completeChallenge(m, 2)
        idleFor(1_500)
        assertEquals(1, c.success)

        completeChallenge(m, 2)
        idleFor(1_500)
        assertEquals("una segunda partida vuelve a poder superarse", 2, c.success)
    }
}
