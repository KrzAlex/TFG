package com.tfg.temieeg.game

import android.os.Looper
import com.tfg.temieeg.data.MentalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests del motor de Escape Room: secuencia de salas, salto, aborto y fin.
 *
 * Usa un módulo de prueba que supera el reto en cuanto recibe un parpadeo,
 * evitando depender de temporizadores reales de los módulos concretos.
 */
@RunWith(RobolectricTestRunner::class)
class EscapeRoomEngineTest {

    /** Módulo mínimo: supera el reto al primer parpadeo. */
    private class InstantModule(title: String) :
        RoomModule(title, "narración", "pista") {
        override fun onBlink() { onSuccess?.invoke() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun def(vararg titles: String) =
        EscapeRoomDef("test", "Nivel de prueba", titles.map { InstantModule(it) })

    @Test
    fun start_entraEnLaPrimeraSala() {
        val engine = EscapeRoomEngine()
        val rooms = mutableListOf<Triple<Int, Int, String>>()
        engine.onRoomChanged = { c, t, ti -> rooms.add(Triple(c, t, ti)) }

        engine.load(def("A", "B"))
        engine.start()

        assertTrue(engine.isRunning)
        assertEquals(Triple(1, 2, "A"), rooms.last())
        assertEquals("InstantModule", engine.currentModuleTypeName)
    }

    @Test
    fun superarSala_avanzaALaSiguiente_yCompleta() {
        val engine = EscapeRoomEngine()
        val rooms = mutableListOf<Triple<Int, Int, String>>()
        var completed = false
        engine.onRoomChanged = { c, t, ti -> rooms.add(Triple(c, t, ti)) }
        engine.onCompleted = { completed = true }

        engine.load(def("A", "B"))
        engine.start()

        engine.onBlink(); idle()                  // supera A → sala B
        assertEquals(Triple(2, 2, "B"), rooms.last())

        engine.onBlink(); idle()                  // supera B → completado
        assertTrue(completed)
        assertFalse(engine.isRunning)
    }

    @Test
    fun skipCurrentRoom_avanzaSinSuperar() {
        val engine = EscapeRoomEngine()
        val rooms = mutableListOf<Triple<Int, Int, String>>()
        engine.onRoomChanged = { c, t, ti -> rooms.add(Triple(c, t, ti)) }

        engine.load(def("A", "B"))
        engine.start()
        engine.skipCurrentRoom(); idle()

        assertEquals(Triple(2, 2, "B"), rooms.last())
    }

    @Test
    fun abort_detieneElJuego() {
        val engine = EscapeRoomEngine()
        engine.load(def("A", "B"))
        engine.start()
        assertTrue(engine.isRunning)

        engine.abort()
        assertFalse(engine.isRunning)
    }

    @Test
    fun load_ignoradoMientrasHayPartidaEnCurso() {
        val engine = EscapeRoomEngine()
        engine.load(def("A"))
        engine.start()
        engine.load(EscapeRoomDef("otro", "Otro nivel", listOf(InstantModule("Z"))))
        assertEquals("Nivel de prueba", engine.currentLevelName)   // no cambió
    }

    @Test
    fun gestosIgnorados_siNoHayPartida() {
        val engine = EscapeRoomEngine()
        var completed = false
        engine.onCompleted = { completed = true }
        // Sin load()/start(): los eventos BCI no deben hacer nada
        engine.onBlink(); idle()
        engine.onNod(); idle()
        engine.onMentalStateUpdate(MentalState.CALM); idle()
        assertFalse(completed)
        assertFalse(engine.isRunning)
    }
}
