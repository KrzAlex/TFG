package com.tfg.temieeg.game

import com.tfg.temieeg.eeg.MorseDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprobaciones del catálogo de niveles.
 *
 * Son errores que no da el compilador y que solo se verían jugando: un índice
 * de bifurcación fuera de rango deja al jugador atascado, y una letra fuera de
 * la tabla ITU haría imposible superar una sala Morse.
 */
class EscapeRoomCatalogTest {

    private val niveles = EscapeRoomCatalog.all

    @Test
    fun todosLosNivelesTienenSalas() {
        assertTrue("el catálogo no puede estar vacío", niveles.isNotEmpty())
        niveles.forEach { nivel ->
            assertTrue("«${nivel.name}» no tiene salas", nivel.modules.isNotEmpty())
        }
    }

    @Test
    fun losIdentificadoresNoSeRepiten() {
        val ids = niveles.map { it.id }
        assertEquals("hay identificadores duplicados", ids.size, ids.toSet().size)
    }

    @Test
    fun lasBifurcacionesApuntanASalasQueExisten() {
        niveles.forEach { nivel ->
            val ultima = nivel.modules.lastIndex
            nivel.modules.filterIsInstance<YesNoModule>().forEach { modulo ->
                modulo.questions.forEach { pregunta ->
                    listOf("gotoOnYes" to pregunta.gotoOnYes, "gotoOnNo" to pregunta.gotoOnNo)
                        .forEach { (campo, destino) ->
                            if (destino != null) {
                                assertTrue(
                                    "«${nivel.name}» → $campo=$destino fuera de rango (0..$ultima)",
                                    destino in 0..ultima
                                )
                            }
                        }
                }
            }
        }
    }

    @Test
    fun unaBifurcacionSiempreEstaEnLaUltimaPregunta() {
        // El módulo salta de sala en cuanto resuelve una pregunta con goto, así
        // que cualquier pregunta posterior nunca llegaría a formularse.
        niveles.forEach { nivel ->
            nivel.modules.filterIsInstance<YesNoModule>().forEach { modulo ->
                modulo.questions.forEachIndexed { i, pregunta ->
                    val bifurca = pregunta.gotoOnYes != null || pregunta.gotoOnNo != null
                    if (bifurca) {
                        assertEquals(
                            "«${nivel.name}»: la pregunta $i bifurca pero no es la última",
                            modulo.questions.lastIndex, i
                        )
                    }
                }
            }
        }
    }

    @Test
    fun lasLetrasMorseEstanEnLaTablaITU() {
        niveles.forEach { nivel ->
            nivel.modules.filterIsInstance<MorseModule>().forEach { modulo ->
                assertTrue("«${nivel.name}» tiene una sala Morse sin letras",
                    modulo.letterPool.isNotEmpty())
                modulo.letterPool.forEach { letra ->
                    assertNotNull(
                        "«${nivel.name}»: la letra $letra no tiene código Morse",
                        MorseDecoder.codeFor(letra)
                    )
                }
            }
        }
    }

    @Test
    fun todasLasSalasTienenTituloYPista() {
        niveles.forEach { nivel ->
            nivel.modules.forEach { modulo ->
                assertTrue("«${nivel.name}» tiene una sala sin título",
                    modulo.title.isNotBlank())
                assertTrue("«${nivel.name}» → «${modulo.title}» no tiene pista",
                    modulo.hint.isNotBlank())
            }
        }
    }

    @Test
    fun laCurvaDeDificultadVaDeMenosAMas() {
        // Clásico es el tutorial y Castillo el nivel exigente: el tiempo de
        // calma sube y la ventana de mandíbula se acorta entre uno y otro.
        val calma = { d: EscapeRoomDef ->
            d.modules.filterIsInstance<CalmModule>().maxOf { it.secondsRequired }
        }
        val ventana = { d: EscapeRoomDef ->
            d.modules.filterIsInstance<BlinkClenchModule>().minOf { it.jawWindowMs }
        }

        assertTrue("la calma debe exigir más en Castillo que en Clásico",
            calma(EscapeRoomCatalog.CASTLE) > calma(EscapeRoomCatalog.CLASSIC))
        assertTrue("la ventana debe ser más corta en Castillo que en Clásico",
            ventana(EscapeRoomCatalog.CASTLE) < ventana(EscapeRoomCatalog.CLASSIC))
    }

    @Test
    fun elTutorialUsaLasLetrasMorseMasSimples() {
        // E y T son las únicas de un solo símbolo: el punto y la raya.
        val tutorial = EscapeRoomCatalog.CLASSIC.modules.filterIsInstance<MorseModule>().first()
        tutorial.letterPool.forEach { letra ->
            assertEquals("«$letra» no es una letra de un solo símbolo",
                1, MorseDecoder.codeFor(letra)!!.length)
        }
    }
}
