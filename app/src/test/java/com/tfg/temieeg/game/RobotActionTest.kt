package com.tfg.temieeg.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests del parseo de acciones de robot (formato del editor de niveles). */
class RobotActionTest {

    @Test
    fun fromString_parseaTipoYParametro() {
        val a = RobotAction.fromString("SPEAK:Hola mundo")
        assertEquals(RobotAction.Type.SPEAK, a?.type)
        assertEquals("Hola mundo", a?.param)
    }

    @Test
    fun fromString_ignoraMayusculasYEspacios() {
        val a = RobotAction.fromString("  goto : sala1 ")
        assertEquals(RobotAction.Type.GOTO, a?.type)
        assertEquals("sala1", a?.param)
    }

    @Test
    fun fromString_sinParametro() {
        val a = RobotAction.fromString("WAIT")
        assertEquals(RobotAction.Type.WAIT, a?.type)
        assertEquals("", a?.param)
    }

    @Test
    fun fromString_tipoDesconocido_devuelveNull() {
        assertNull(RobotAction.fromString("VOLAR:100"))
    }

    @Test
    fun asString_esInversoDeFromString() {
        val original = RobotAction(RobotAction.Type.TILT_HEAD, "20")
        val round = RobotAction.fromString(RobotAction.asString(original))
        assertEquals(original, round)
    }
}
