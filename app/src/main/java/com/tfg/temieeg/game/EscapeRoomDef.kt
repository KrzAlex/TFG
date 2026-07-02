package com.tfg.temieeg.game

import com.tfg.temieeg.data.MentalState

/**
 * Define un Escape Room completo: una historia dividida en salas ([RoomModule]).
 *
 * Para crear un escape room nuevo, añade una entrada en [EscapeRoomCatalog]:
 *
 *   val MI_HISTORIA = EscapeRoomDef(
 *       id      = "mi_historia",
 *       name    = "Mi Historia",
 *       modules = listOf(
 *           CalmModule(...),
 *           MorseModule(...),
 *           YesNoModule(..., questions = listOf(...)),
 *           BlinkClenchModule(...)
 *       )
 *   )
 *
 * No hay límite en el número ni en el tipo de módulos, y pueden repetirse.
 */
data class EscapeRoomDef(
    val id: String,
    val name: String,
    val modules: List<RoomModule>,
    /** Vídeo de entrada embebido en res/raw/ (niveles del catálogo). */
    val introVideoResId: Int? = null,
    /** Vídeo de entrada en almacenamiento interno (niveles personalizados). */
    val introVideoPath: String? = null,
    /** Vídeo de transición entre salas embebido en res/raw/ (niveles del catálogo). */
    val transitionVideoResId: Int? = null,
    /** Vídeo de transición entre salas en almacenamiento interno (niveles personalizados). */
    val transitionVideoPath: String? = null
)

// ══════════════════════════════════════════════════════════════════════════════
// CATÁLOGO — añade aquí nuevas historias
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Catálogo de escape rooms listos para usar.
 * [EscapeRoomEngine.load] acepta cualquier [EscapeRoomDef] de esta lista
 * (o cualquier otra que crees en tiempo de ejecución).
 */
object EscapeRoomCatalog {

    // ── Historia 1: El Escape Clásico ─────────────────────────────────────────

    val CLASSIC = EscapeRoomDef(
        id      = "classic",
        name    = "El Escape Clásico",
        modules = listOf(

            CalmModule(
                title           = "La Puerta de la Calma",
                narration       = "Ante ti se alza una puerta sellada por la energía " +
                                  "mental. Solo la calma puede abrirla.",
                hint            = "Mantén la calma 5 segundos",
                secondsRequired = 5,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Ante ti se alza una puerta sellada por la energía mental. " +
                        "Solo la calma puede abrirla. Mantén la calma durante cinco segundos.")
                )
            ),

            MorseModule(
                title      = "El Código Secreto",
                narration  = "Un panel críptico pide una clave en código Morse. " +
                             "Escucha la letra y escríbela con tus parpadeos.",
                hint       = "Escribe en Morse la letra que te digo",
                letterPool = "ETISAN".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Un panel críptico pide una clave en código Morse. " +
                        "Escucha la letra y escríbela con tus parpadeos.")
                )
            ),

            YesNoModule(
                title     = "El Oráculo",
                narration = "Una figura sabia bloquea el paso. Responde sus tres " +
                            "preguntas correctamente para continuar.",
                hint      = "Asiente ✅ SÍ  ·  Niega ❌ NO",
                questions = listOf(
                    YesNoQuestion("¿Eres capaz de mantener la calma?",         true),
                    YesNoQuestion("¿Tienes miedo de los retos mentales?",       false),
                    YesNoQuestion("¿Estás listo para el último desafío?",       true)
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Una figura sabia bloquea el paso. Responde mis tres preguntas correctamente. " +
                        "Asiente para Sí. Niega para No.")
                )
            ),

            BlinkClenchModule(
                title       = "La Cerradura Final",
                narration   = "La última puerta tiene dos mecanismos. " +
                              "Usa todos tus poderes mentales para escapar.",
                hint        = "Parpadea 👁 para girar la llave · Aprieta 😬 la mandíbula para abrir",
                jawWindowMs = 4000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "La última puerta tiene dos mecanismos. " +
                        "Parpadea para girar la llave. Luego aprieta la mandíbula para abrirla.")
                )
            )
        )
    )

    // ── Historia 2: Aventura Espacial ─────────────────────────────────────────

    val SPACE = EscapeRoomDef(
        id      = "space",
        name    = "Aventura Espacial",
        modules = listOf(

            CalmModule(
                title           = "Nave en Deriva",
                narration       = "La nave espacial está a la deriva en el vacío. " +
                                  "Solo tu mente en calma puede estabilizarla.",
                hint            = "Estabiliza la nave — mantén la calma 4 s",
                secondsRequired = 4,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "La nave espacial está a la deriva en el vacío. " +
                        "Solo tu mente en calma puede estabilizarla. Mantén la calma durante cuatro segundos.")
                )
            ),

            YesNoModule(
                title     = "El Robot Guardián",
                narration = "Un robot guardián custodia la sala de control. " +
                            "Responde correctamente a sus preguntas de seguridad.",
                hint      = "Asiente ✅ SÍ  ·  Niega ❌ NO",
                questions = listOf(
                    YesNoQuestion("¿Vienes en misión de paz?",        true),
                    YesNoQuestion("¿Eres un intruso peligroso?",       false),
                    YesNoQuestion("¿Conoces el protocolo estelar?",    true)
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Soy el robot guardián de la sala de control. " +
                        "Responde correctamente a mis preguntas de seguridad. " +
                        "Asiente para Sí. Niega para No.")
                )
            ),

            MorseModule(
                title      = "La Señal SOS",
                narration  = "Los sistemas de comunicación fallan. " +
                             "Debes enviar una señal de socorro en código Morse.",
                hint       = "Transmite en Morse la letra indicada",
                // Solo letras sencillas: punto (E·T), punto-punto (I··), punto-raya (A·-)
                // y raya-punto (N-·). Evitamos M(--) y O(---) que requieren
                // secuencias de doble parpadeo muy largas y generan falsos negativos.
                letterPool = "ETISAN".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Los sistemas de comunicación fallan. " +
                        "Debes enviar una señal de socorro en código Morse. " +
                        "Parpadea la letra que veas en pantalla.")
                )
            ),

            BlinkClenchModule(
                title       = "Panel de Evacuación",
                narration   = "El panel de evacuación requiere dos gestos precisos " +
                              "y coordinados para activarse.",
                hint        = "Parpadea 👁 para activar · Aprieta 😬 la mandíbula para lanzar",
                // 4 s desde que el robot termina de hablar (no desde el parpadeo).
                // El timer empieza después del TTS gracias a onTemiSpeakDone.
                jawWindowMs = 4000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "El panel de evacuación requiere dos gestos precisos. " +
                        "Parpadea para activar los motores. " +
                        "Luego aprieta la mandíbula para lanzar la cápsula.")
                )
            )
        )
    )

    // ── Historia 3: El Castillo Encantado ─────────────────────────────────────

    val CASTLE = EscapeRoomDef(
        id      = "castle",
        name    = "El Castillo Encantado",
        modules = listOf(

            MorseModule(
                title      = "El Grimorio",
                narration  = "El grimorio del castillo requiere una contraseña mágica " +
                             "escrita en el antiguo código de los magos.",
                hint       = "Escribe en Morse la runa indicada",
                letterPool = "MAGIE".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "El grimorio del castillo requiere una contraseña mágica " +
                        "escrita en el antiguo código de los magos. Parpadea la runa que aparezca.")
                )
            ),

            YesNoModule(
                title     = "El Fantasma del Torreón",
                narration = "El fantasma del torreón te reta con acertijos. " +
                            "Responde bien y te dejará pasar.",
                hint      = "Asiente ✅ SÍ  ·  Niega ❌ NO",
                questions = listOf(
                    YesNoQuestion("¿Eres un mago valiente?",           true),
                    YesNoQuestion("¿Le tienes miedo a los fantasmas?", false),
                    YesNoQuestion("¿Puedes resolver este acertijo?",   true),
                    YesNoQuestion("¿El sol sale por el oeste?",        false)
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Soy el fantasma del torreón. Te retaré con acertijos. " +
                        "Responde bien y te dejaré pasar. Asiente para Sí. Niega para No.")
                )
            ),

            CalmModule(
                title           = "La Sala de los Espejos",
                narration       = "Los espejos mágicos solo se rompen con la energía " +
                                  "de una mente completamente en calma.",
                hint            = "Siente la calma… 6 segundos",
                secondsRequired = 6,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Los espejos mágicos solo se rompen con la energía de una mente completamente en calma. " +
                        "Siente la calma durante seis segundos.")
                )
            ),

            BlinkClenchModule(
                title       = "El Portal Dimensional",
                narration   = "El portal al mundo real se activa con dos gestos " +
                              "mágicos realizados en el momento exacto.",
                hint        = "Parpadea 👁 para abrir el portal · Aprieta 😬 la mandíbula para cruzar",
                jawWindowMs = 4500L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "El portal al mundo real se activa con dos gestos mágicos. " +
                        "Parpadea para abrir el portal. Luego aprieta la mandíbula para cruzar.")
                )
            )
        )
    )

    // ── Historia 4: El Laboratorio del Dr. Mente (demo — todos los módulos) ──────
    //
    // Usa los comandos GOTO:entrada, GOTO:centro y GOTO:escritorio.
    // Para que el robot se mueva hay que tener esas ubicaciones guardadas en el
    // mapa del Temi (pulsación larga sobre un punto en la pantalla de inicio).
    // Si no existen, el GOTO se ignora silenciosamente y el resto funciona igual.

    val LABORATORIO = EscapeRoomDef(
        id                   = "lvl_demo_laboratorio",
        name                 = "El Laboratorio del Dr. Mente",
        introVideoResId      = com.tfg.temieeg.R.raw.entrada,
        transitionVideoResId = com.tfg.temieeg.R.raw.intermedio,
        modules = listOf(

            RobotAnimModule(
                title     = "Bienvenida",
                narration = "¡Bienvenido al Laboratorio del Dr. Mente! Soy Temi, tu guía en esta misión. " +
                            "Voy a ponerte a prueba en seis experimentos mentales. Sígueme.",
                hint      = "Observa y escucha al robot",
                delayMs   = 15_000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.WAIT,      "1500"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "38"),
                    RobotAction(RobotAction.Type.SPEAK,     "Bienvenido al Laboratorio del Dr. Mente. Soy tu asistente TEMI."),
                    RobotAction(RobotAction.Type.WAIT,      "3000"),
                    RobotAction(RobotAction.Type.TURN,      "90"),
                    RobotAction(RobotAction.Type.WAIT,      "1000"),
                    RobotAction(RobotAction.Type.TURN,      "-90"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,     "Prepárate. Comenzamos ahora.")
                )
            ),

            CalmModule(
                title           = "La Cámara de la Serenidad",
                narration       = "Primera prueba. El sensor cerebral mide tu nivel de relajación. " +
                                  "Cierra los ojos y respira despacio hasta alcanzar la calma.",
                hint            = "Respira lentamente — mantén la calma durante 5 segundos",
                secondsRequired = 5,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,     "Cierra los ojos. Inhala despacio. Exhala despacio.")
                )
            ),

            MorseModule(
                title        = "El Código Secreto",
                narration    = "La puerta del laboratorio está cifrada con código Morse. " +
                               "Parpadea la letra que te diga el robot. " +
                               "Un parpadeo largo es punto, dos rápidos seguidos es raya.",
                hint         = "Parpadea la letra en Morse — · punto  ·· raya",
                letterPool   = "ETISAN".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TURN,      "90"),
                    RobotAction(RobotAction.Type.WAIT,      "800"),
                    RobotAction(RobotAction.Type.TURN,      "-90"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,     "Parpadea la letra que veas en pantalla usando código Morse.")
                )
            ),

            YesNoModule(
                title        = "El Interrogatorio",
                narration    = "El sistema de seguridad verifica tu identidad. " +
                               "Responde las preguntas con gestos: asiente para SÍ, niega para NO. " +
                               "Debes repetir el gesto para confirmar.",
                hint         = "NOD = Sí  ·  SHAKE = No  ·  Repite el gesto para confirmar",
                questions    = listOf(
                    YesNoQuestion(
                        text        = "¿Eres el investigador autorizado para acceder al laboratorio?",
                        expectedYes = true
                    ),
                    YesNoQuestion(
                        text        = "¿Has compartido el código de acceso con alguien ajeno?",
                        expectedYes = false
                    )
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,     "Voy a hacerte dos preguntas de seguridad. Responde con la cabeza.")
                )
            ),

            BlinkClenchModule(
                title        = "El Interruptor Neural",
                narration    = "El generador principal está apagado. Para activarlo debes enviar una señal " +
                               "neural combinada: primero parpadea para cargarlo, luego aprieta la mandíbula " +
                               "para disparar el pulso.",
                hint         = "Parpadea → aprieta la mandíbula en menos de 4 segundos",
                jawWindowMs  = 4000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TURN,      "45"),
                    RobotAction(RobotAction.Type.WAIT,      "800"),
                    RobotAction(RobotAction.Type.TURN,      "-45"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,     "Parpadea primero para cargar. Luego aprieta la mandíbula para disparar.")
                )
            ),

            VideoStateModule(
                title           = "El Monitor Final",
                narration       = "Última prueba. El sistema monitoriza tu estado mental en tiempo real. " +
                                  "Mantén la calma durante 8 segundos. ¡Casi lo tienes!",
                hint            = "Mantén la calma — 8 segundos",
                targetState     = MentalState.CALM,
                secondsRequired = 8,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "38"),
                    RobotAction(RobotAction.Type.SPEAK,     "Esta es la prueba final. Confía en ti mismo y mantén la calma."),
                    RobotAction(RobotAction.Type.WAIT,      "3500"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25")
                )
            )
        )
    )

    // ── Prueba de navegación GOTO ─────────────────────────────────────────────
    //
    // Recorre cuatro ubicaciones reales del mapa del Temi en secuencia.
    // El robot se mueve a cada punto y anuncia en voz alta dónde está.
    // WAIT:15000 da 15 segundos por tramo para que el robot llegue; ajustar
    // según la distancia real entre los puntos del mapa.

    val NAV_TEST = EscapeRoomDef(
        id   = "nav_test",
        name = "Prueba de Navegación",
        modules = listOf(
            RobotAnimModule(
                title    = "Ruta de navegación",
                narration = "Iniciando prueba de navegación. El robot recorrerá cuatro puntos del mapa.",
                hint      = "Observa el recorrido del robot",
                delayMs   = 3000L,
                robotActions = listOf(
                    // Nombres exactos del mapa del Temi (case-sensitive).
                    // El motor espera el callback OnGoToLocationStatusChanged antes de cada SPEAK.

                    // ── Parada 1: Pasillo ──────────────────────────────────
                    RobotAction(RobotAction.Type.GOTO,  "pasillo"),
                    RobotAction(RobotAction.Type.SPEAK, "He llegado al pasillo. Primera parada completada."),

                    // ── Parada 2: Camino de EUPT Bikes ────────────────────
                    RobotAction(RobotAction.Type.GOTO,  "caminodeeuptbikes"),
                    RobotAction(RobotAction.Type.SPEAK, "Estoy en Camino de EUPT Bikes. Segunda parada completada."),

                    // ── Parada 3: La Bocalidad Puerta ─────────────────────
                    RobotAction(RobotAction.Type.GOTO,  "labocalidadpuerta"),
                    RobotAction(RobotAction.Type.SPEAK, "He llegado a la bocalidad puerta. Tercera parada completada."),

                    // ── Parada 4: Entrada ─────────────────────────────────
                    RobotAction(RobotAction.Type.GOTO,  "entrada"),
                    RobotAction(RobotAction.Type.SPEAK, "Estoy en la entrada. Cuarta y última parada. Ruta completada.")
                )
            )
        )
    )

    // ── Lista completa (MainActivity la itera para el selector) ───────────────

    val all: List<EscapeRoomDef> = listOf(CLASSIC, SPACE, CASTLE, LABORATORIO, NAV_TEST)
}
