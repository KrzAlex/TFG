package com.tfg.temieeg.game

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * Bloqueo del BCI mientras el robot habla.
 *
 * El eco de los altavoces de Temi hace que la diadema registre parpadeos y
 * gestos falsos, así que el motor ignora la entrada discreta durante la
 * locución. El fallo que cubren estos tests: si el callback de fin de TTS
 * llegaba antes de tiempo (Temi lo notifica pronto en algunos firmwares y el
 * modo simulado lo falseaba a los 300 ms), el bloqueo se levantaba con el
 * robot todavía hablando y el eco entraba como gestos del jugador.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechGuardTest {

    /** Módulo que narra al empezar y cuenta los gestos que le llegan. */
    private class SpeakingModule(private val text: String) :
        RoomModule("Sala", "narración", "pista") {
        var blinks = 0
        var nods = 0
        override fun start() { onTemiSpeak?.invoke(text) }
        override fun onBlink() { blinks++ }
        override fun onNod() { nods++ }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** Texto largo: ~60 ms por carácter da una locución estimada de varios segundos. */
    private val narration = "Ante ti se alza una puerta sellada por la energia mental."

    private fun startEngineSpeaking(): Pair<EscapeRoomEngine, SpeakingModule> {
        val module = SpeakingModule(narration)
        val engine = EscapeRoomEngine()
        engine.load(EscapeRoomDef("t", "Nivel", listOf(module)))
        engine.start()
        idle()
        return engine to module
    }

    @Test
    fun mientrasHabla_losGestosSeIgnoran() {
        val (engine, module) = startEngineSpeaking()

        engine.onBlink(); idle()
        engine.onNod();   idle()

        assertEquals("el parpadeo no debe llegar al módulo", 0, module.blinks)
        assertEquals("el gesto no debe llegar al módulo", 0, module.nods)
    }

    @Test
    fun callbackDeTtsPrematuro_noDesbloqueaAntesDeTiempo() {
        val (engine, module) = startEngineSpeaking()

        // El SDK informa de "fin de TTS" nada más empezar: el caso del fallo.
        engine.onTtsEnded()
        idleFor(2_000)

        engine.onBlink(); idle()
        assertEquals("sigue hablando: el eco no debe contar como parpadeo", 0, module.blinks)
    }

    @Test
    fun trasLaDuracionEstimadaMasGracia_seVuelveAAceptarEntrada() {
        val (engine, module) = startEngineSpeaking()
        engine.onTtsEnded()

        // Duración estimada (60 ms/carácter) + gracia por el eco + margen.
        idleFor(narration.length * 60L + 1_200L + 500L)

        engine.onBlink(); idle()
        assertEquals("terminada la locución, el parpadeo cuenta", 1, module.blinks)
    }

    @Test
    fun sinCallbackDeTts_elBciSeLiberaSolo() {
        val (engine, module) = startEngineSpeaking()

        // Nunca llega onTtsEnded(): sin red de seguridad el BCI quedaría
        // bloqueado para siempre y la partida sería injugable.
        // Se avanza en dos tramos: el primero dispara la red de seguridad y el
        // segundo cubre la gracia que ésta programa al ejecutarse.
        idleFor(narration.length * 60L + 8_000L + 200L)
        idleFor(1_200L + 200L)

        engine.onBlink(); idle()
        assertEquals("la red de seguridad debe haber liberado el BCI", 1, module.blinks)
    }

    @Test
    fun elEstadoMentalNoSeBloquea_duranteElHabla() {
        // Las salas de calma necesitan la señal continua aunque el robot hable.
        val module = object : RoomModule("Sala", "n", "p") {
            var updates = 0
            override fun start() { onTemiSpeak?.invoke("hablando") }
            override fun onMentalState(state: com.tfg.temieeg.data.MentalState) { updates++ }
        }
        val engine = EscapeRoomEngine()
        engine.load(EscapeRoomDef("t", "Nivel", listOf(module)))
        engine.start(); idle()

        engine.onMentalStateUpdate(com.tfg.temieeg.data.MentalState.CALM); idle()
        assertEquals(1, module.updates)
    }
}
