package com.tfg.temieeg.logging

import androidx.test.core.app.ApplicationProvider
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.data.SessionEntry
import com.tfg.temieeg.data.SessionEvent
import com.tfg.temieeg.data.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Formato del CSV de sesión.
 *
 * El fichero es la única salida del sistema que se analiza después, así que su
 * formato es un contrato: la cabecera con los umbrales permite reproducir el
 * análisis, y los sucesos discretos son lo que hace posible calcular tiempos
 * por sala e intentos, que el muestreo continuo por sí solo no da.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLoggerTest {

    private fun logger() = SessionLogger(ApplicationProvider.getApplicationContext())

    private fun meta() = SessionMeta(
        appVersion = "0.1.0", device = "test", levelName = "Nivel",
        connectionMode = "OSC",
        stressThreshold = 0.22f, attentionThreshold = 0.38f,
        calmThreshold = 0.52f, gammaThreshold = 0.15f,
        blinkDebounceMs = 500L, nodThreshold = 30f, shakeThreshold = 50f,
        startedAt = "2026-08-31 12:00:00"
    )

    private fun sample(ts: Long = 1_000L) = SessionEntry(
        timestamp = ts, state = MentalState.CALM,
        concentration = 0.2f, mellow = 0.6f, gammaActivity = 0.1f,
        alpha = 1f, beta = 0f, theta = 0f, delta = 0f, gamma = 0f,
        signalQuality = 1f,
        gyroX = 1f, gyroY = 2f, gyroZ = 3f, accX = 0f, accY = 0f, accZ = 0f,
        blinkEvent = false, jawClenchEvent = false, nodEvent = false, shakeEvent = false,
        battery = 80, noiseLevel = 1200,
        robotAction = "", roomIndex = 0, roomTitle = "Sala"
    )

    private fun csvOf(l: SessionLogger): String {
        val f = l.exportCSV("Nivel")
        assertNotNull("no se generó el CSV", f)
        return f!!.readText()
    }

    @Test
    fun laCabeceraGuardaLosUmbralesEnUso() {
        val l = logger()
        l.startSession(meta())
        l.log(sample())

        val csv = csvOf(l)
        assertTrue("falta la versión de formato", csv.contains("# temi_eeg_session v2"))
        assertTrue("falta el umbral de estrés",   csv.contains("# threshold_stress=0.22"))
        assertTrue("falta el umbral de calma",    csv.contains("# threshold_calm=0.52"))
        assertTrue("falta el modo de conexión",   csv.contains("# connection=OSC"))
    }

    @Test
    fun seRegistranLosCamposNuevosDeSenal() {
        val l = logger()
        l.startSession(meta())
        l.log(sample())

        val header = csvOf(l).lines().first { !it.startsWith("#") }
        listOf("gyro_x", "battery", "noise", "event", "event_detail").forEach {
            assertTrue("falta la columna $it", header.contains(it))
        }
    }

    @Test
    fun losSucesosDePartidaQuedanEnSuPropiaFila() {
        val l = logger()
        l.startSession(meta())
        l.log(sample())
        l.logEvent(SessionEvent.ROOM_START, "1/4 La Puerta de la Calma")
        l.logEvent(SessionEvent.ROOM_SUCCESS, "¡Calma mantenida!")

        val csv = csvOf(l)
        assertTrue(csv.contains("ROOM_START"))
        assertTrue(csv.contains("La Puerta de la Calma"))
        assertTrue(csv.contains("ROOM_SUCCESS"))
    }

    @Test
    fun elTiempoRelativoArrancaEnLaPrimeraMuestra() {
        // elapsed_ms evita tener que restar marcas de tiempo al analizar.
        val l = logger()
        l.startSession(meta())
        l.log(sample(ts = 10_000L))
        l.log(sample(ts = 12_500L))

        val filas = csvOf(l).lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        assertEquals("la primera muestra debe estar en 0", "0", filas[0].split(",")[1])
        assertEquals("la segunda a 2500 ms", "2500", filas[1].split(",")[1])
    }

    @Test
    fun elTextoConComillasNoRompeElCsv() {
        val l = logger()
        l.startSession(meta())
        l.log(sample())
        l.logEvent(SessionEvent.ROOM_FAIL, "Era «S», dijo \"no\"")

        val csv = csvOf(l)
        assertTrue("las comillas deben doblarse (RFC 4180)", csv.contains("\"\"no\"\""))
    }

    @Test
    fun sinEntradas_noSeGeneraFichero() {
        assertEquals(null, logger().exportCSV("Nivel"))
    }
}
