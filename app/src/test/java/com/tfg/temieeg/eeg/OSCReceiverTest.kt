package com.tfg.temieeg.eeg

import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCPortOut
import com.tfg.temieeg.data.MuseState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Test de integración del receptor OSC: envía paquetes UDP reales a localhost
 * (como haría Mind Monitor) y comprueba que el parseo y el muestreo a 4 Hz
 * producen el [MuseState] esperado.
 *
 * Cubre el parseo sin asignaciones (avgFloat / first3Floats), que es el camino
 * más caliente de la app y no puede probarse sin hardware de otra forma.
 */
class OSCReceiverTest {

    private var receiver: OSCReceiver? = null
    private var sender: OSCPortOut? = null

    @After
    fun tearDown() {
        receiver?.stop()
        runCatching { sender?.close() }
    }

    /** Arranca receptor + emisor en un puerto libre y devuelve el par. */
    private fun startPipeline(port: Int): Pair<OSCReceiver, OSCPortOut> {
        val r = OSCReceiver(port).also { it.start(); receiver = it }
        val s = OSCPortOut(InetAddress.getByName("127.0.0.1"), port).also { sender = it }
        return r to s
    }

    private fun msg(address: String, vararg args: Any) = OSCMessage(address, args.toList())

    @Test
    fun paquetesReales_producenEstadoMuestreado() {
        val (r, s) = startPipeline(15321)
        val latch = CountDownLatch(1)
        val got = AtomicReference<MuseState?>(null)
        r.onMuseDataReceived = { st -> got.set(st); latch.countDown() }

        // alpha y beta son obligatorios para que el receptor considere las bandas activas
        repeat(6) {
            s.send(msg("/muse/elements/alpha_absolute", 1.0f, 1.0f, 2.0f, 2.0f))  // media 1.5
            s.send(msg("/muse/elements/beta_absolute",  0.0f, 1.0f, 0.0f, 1.0f))  // media 0.5
            s.send(msg("/muse/elements/horseshoe",      1.0f, 1.0f, 1.0f, 1.0f))
            s.send(msg("/muse/gyro",  10.0f, 20.0f, 30.0f))
            s.send(msg("/muse/acc",   0.1f,  0.2f,  0.3f))
            Thread.sleep(60)
        }

        assertTrue("no llegó ninguna muestra en 5 s", latch.await(5, TimeUnit.SECONDS))
        val st = got.get()!!
        assertEquals(1.5f, st.alphaAbsolute, 1e-3f)   // media de los 4 electrodos
        assertEquals(0.5f, st.betaAbsolute,  1e-3f)
        assertEquals(1.0f, st.signalQuality, 1e-3f)
        assertEquals(20.0f, st.gyroY, 1e-3f)          // eje del NOD
        assertEquals(30.0f, st.gyroZ, 1e-3f)          // eje del SHAKE
        assertEquals(0.2f,  st.accY,  1e-3f)
    }

    @Test
    fun parpadeoYMandibula_seEmitenComoEventos() {
        val (r, s) = startPipeline(15322)
        val latch = CountDownLatch(1)
        val seen = AtomicReference<MuseState?>(null)
        r.onMuseDataReceived = { st -> if (st.blink || st.jawClench) { seen.set(st); latch.countDown() } }

        repeat(8) {
            s.send(msg("/muse/elements/alpha_absolute", 1.0f))
            s.send(msg("/muse/elements/beta_absolute",  1.0f))
            s.send(msg("/muse/elements/blink", 1))
            Thread.sleep(60)
        }

        assertTrue("no se emitió ningún parpadeo", latch.await(5, TimeUnit.SECONDS))
        assertTrue(seen.get()!!.blink)
    }

    @Test
    fun mensajeSinFloats_noRompeElParseo() {
        val (r, s) = startPipeline(15323)
        val latch = CountDownLatch(1)
        r.onMuseDataReceived = { latch.countDown() }

        repeat(8) {
            s.send(msg("/muse/elements/alpha_absolute", "no-es-float"))  // se ignora
            s.send(msg("/muse/elements/alpha_absolute", 1.0f))
            s.send(msg("/muse/elements/beta_absolute",  1.0f))
            s.send(msg("/muse/gyro", 1.0f))                              // menos de 3 → se ignora
            Thread.sleep(60)
        }

        assertTrue("el receptor dejó de emitir tras un mensaje inválido", latch.await(5, TimeUnit.SECONDS))
    }
}
