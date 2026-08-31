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

    // ══════════════════════════════════════════════════════════════════════════
    // Los tres niveles narrativos forman una curva de dificultad:
    //
    //   CLASSIC  tutorial      · introduce un mecanismo por sala, tiempos amplios
    //   SPACE    intermedio    · añade elección de ruta (bifurcación real)
    //   CASTLE   exigente      · letras Morse largas, calma larga, ventana corta
    //
    // Convención de textos: `narration` es lo que se lee en pantalla (breve) y
    // el SPEAK de robotActions es lo que dice el robot (más extenso, con la
    // instrucción). No se duplican: cuando un módulo tiene SPEAK, el motor no
    // vuelve a narrar por su cuenta (RoomModule.hasRobotSpeech).
    //
    // Ninguno usa GOTO a propósito: las ubicaciones dependen del mapa concreto
    // de cada robot y un nivel del catálogo debe funcionar en cualquiera.
    // ══════════════════════════════════════════════════════════════════════════

    // ── Historia 1: El Escape Clásico ─────────────────────────────────────────
    //
    // Nivel tutorial. Cada sala enseña UN mecanismo y perdona los fallos:
    // calma corta, las dos letras Morse de un solo símbolo (E = ·, T = —),
    // dos preguntas y una ventana de mandíbula generosa.

    val CLASSIC = EscapeRoomDef(
        id                   = "classic",
        name                 = "El Escape Clásico",
        introVideoResId      = com.tfg.temieeg.R.raw.clasico_intro,
        transitionVideoResId = com.tfg.temieeg.R.raw.clasico_transicion,
        modules = listOf(

            CalmModule(
                title           = "La Puerta de la Calma",
                narration       = "Una puerta sellada por energía mental. Solo se abre " +
                                  "cuando tu mente se serena.",
                hint            = "Respira despacio y mantén la calma",
                secondsRequired = 4,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Bienvenido. Ante ti hay una puerta sellada por energía mental. " +
                        "Para abrirla solo tienes que relajarte: respira despacio y mantén " +
                        "la calma durante cuatro segundos. Yo te aviso cuando lo consigas.")
                )
            ),

            MorseModule(
                title      = "El Código Secreto",
                narration  = "Un panel pide una clave en código Morse. Escríbela con " +
                             "tus parpadeos.",
                hint       = "1 parpadeo = ·      2 parpadeos rápidos = —",
                // Solo E y T: son las dos letras de un único símbolo, ideales para
                // aprender la diferencia entre punto y raya sin encadenar señales.
                letterPool = "ET".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Este panel pide una clave en código Morse. Es más fácil de lo que " +
                        "parece: un parpadeo normal es un punto, y dos parpadeos seguidos y " +
                        "rápidos son una raya. Te diré qué letra necesito y la verás en pantalla.")
                )
            ),

            YesNoModule(
                title     = "El Guardián de la Sala",
                narration = "Un guardián antiguo te cierra el paso. Responde moviendo " +
                            "la cabeza.",
                hint      = "Asiente para SÍ  ·  Niega para NO",
                questions = listOf(
                    YesNoQuestion("El guardián pregunta: ¿vienes en son de paz?", expectedYes = true),
                    YesNoQuestion("¿Piensas llevarte el tesoro de la sala?",      expectedYes = false)
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Un guardián antiguo bloquea la salida. Para responderle, asiente con " +
                        "la cabeza si tu respuesta es sí, o niega si es no. Te pediré que " +
                        "repitas el gesto para confirmar, así no cuentan los movimientos sin querer.")
                )
            ),

            BlinkClenchModule(
                title       = "La Cerradura Final",
                narration   = "La última puerta tiene dos mecanismos: una llave y un cerrojo.",
                hint        = "Primero parpadea · Después aprieta la mandíbula",
                jawWindowMs = 5500L,   // tutorial: ventana amplia para no frustrar
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Última puerta. Tiene dos mecanismos y hay que accionarlos en orden. " +
                        "Primero parpadea para girar la llave. Después aprieta la mandíbula " +
                        "para descorrer el cerrojo. Tendrás tiempo de sobra.")
                )
            )
        )
    )

    // ── Historia 2: Aventura Espacial ─────────────────────────────────────────
    //
    // Nivel intermedio. Introduce una BIFURCACIÓN real: en la sala 2 el jugador
    // elige la ruta y su respuesta decide qué salas juega.
    //
    //   índice 2 · «¿Atajo por el cinturón?»
    //        SÍ  → 3  cinturón de asteroides (Morse) → 4 maniobra evasiva → 5 → 6
    //        NO  → 5  ruta larga: se salta las salas 3 y 4 y va al desenlace
    //
    // El desenlace (5 y 6) es común a las dos rutas.

    val SPACE = EscapeRoomDef(
        id                   = "space",
        name                 = "Aventura Espacial",
        introVideoResId      = com.tfg.temieeg.R.raw.espacial_intro,
        transitionVideoResId = com.tfg.temieeg.R.raw.espacial_transicion,
        modules = listOf(

            // 0 ── Briefing
            RobotAnimModule(
                title     = "Puente de mando",
                narration = "La nave ha perdido el rumbo. Recibes las instrucciones de a bordo.",
                hint      = "Escucha el informe de la nave",
                delayMs   = 4000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "30"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Alerta. La nave ha perdido el rumbo y tú eres el único tripulante " +
                        "despierto. Soy la inteligencia de a bordo y voy a guiarte."),
                    RobotAction(RobotAction.Type.WAIT,      "800"),
                    RobotAction(RobotAction.Type.TURN,      "45"),
                    RobotAction(RobotAction.Type.WAIT,      "600"),
                    RobotAction(RobotAction.Type.TURN,      "-45"),
                    RobotAction(RobotAction.Type.SPEAK,     "Sistemas revisados. Empezamos.")
                )
            ),

            // 1 ── Calma
            CalmModule(
                title           = "Cámara de criogenia",
                narration       = "Sales del sueño criogénico. El sistema necesita tus " +
                                  "constantes estables antes de liberarte.",
                hint            = "Mantén la calma para estabilizar tus constantes",
                secondsRequired = 6,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Acabas de salir del sueño criogénico. La cápsula no te liberará hasta " +
                        "que tus constantes se estabilicen: respira hondo y mantén la calma " +
                        "durante seis segundos.")
                )
            ),

            // 2 ── Elección de ruta (bifurcación)
            YesNoModule(
                title     = "Rumbo de la nave",
                narration = "Dos rutas hasta la estación. Tú decides cuál tomamos.",
                hint      = "Asiente para SÍ  ·  Niega para NO",
                questions = listOf(
                    YesNoQuestion("¿Confirmas que los motores responden?", expectedYes = true),
                    // Última pregunta: al llevar goto, decide la ruta y salta de sala.
                    // Cualquier pregunta posterior no llegaría a formularse.
                    YesNoQuestion(
                        text      = "Atajo por el cinturón de asteroides: más corto, pero peligroso. ¿Lo tomamos?",
                        gotoOnYes = 3,   // ruta peligrosa: dos salas extra
                        gotoOnNo  = 5    // ruta larga: directo al desenlace
                    )
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Tenemos dos rutas posibles hasta la estación y la decisión es tuya. " +
                        "Responde asintiendo o negando con la cabeza.")
                )
            ),

            // 3 ── Ruta peligrosa: Morse
            MorseModule(
                title      = "El cinturón de asteroides",
                narration  = "Entre las rocas hay una baliza de rescate. Contéstale en Morse.",
                hint       = "1 parpadeo = ·      2 parpadeos rápidos = —",
                letterPool = "ESITAN".toList(),
                videoResId = com.tfg.temieeg.R.raw.espacial_ruta_asteroides,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Ruta peligrosa confirmada. Entre los asteroides hay una baliza de " +
                        "rescate emitiendo en Morse. Respóndele con tus parpadeos para que " +
                        "nos abra un corredor seguro.")
                )
            ),

            // 4 ── Ruta peligrosa: reflejos
            BlinkClenchModule(
                title       = "Maniobra evasiva",
                narration   = "Un asteroide viene de frente. Escudo y propulsor, en ese orden.",
                hint        = "Parpadea para el escudo · Aprieta la mandíbula para impulsar",
                jawWindowMs = 4500L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "20"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "¡Asteroide de frente! Parpadea para levantar el escudo y aprieta la " +
                        "mandíbula enseguida para activar el propulsor.")
                )
            ),

            // 5 ── Desenlace común
            CalmModule(
                title           = "Reserva de oxígeno",
                narration       = "Queda poco oxígeno. Cuanto más tranquilo respires, más durará.",
                hint            = "Mantén la calma para ahorrar oxígeno",
                secondsRequired = 6,
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "La reserva de oxígeno está al mínimo. Cuanto más tranquila sea tu " +
                        "respiración, más nos durará. Mantén la calma seis segundos.")
                )
            ),

            // 6 ── Final común
            BlinkClenchModule(
                title       = "Secuencia de aterrizaje",
                narration   = "La estación te abre la compuerta. Ejecuta la secuencia final.",
                hint        = "Parpadea para alinear · Aprieta la mandíbula para acoplar",
                jawWindowMs = 4000L,
                videoResId  = com.tfg.temieeg.R.raw.espacial_aterrizaje,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Estación a la vista. Última maniobra: parpadea para alinear la nave " +
                        "y aprieta la mandíbula para completar el acoplamiento.")
                )
            )
        )
    )

    // ── Historia 3: El Castillo Encantado ─────────────────────────────────────
    //
    // Nivel exigente: letras Morse de tres símbolos, calma más larga y ventana
    // de mandíbula corta. La bifurcación aquí penaliza el error en vez de
    // ofrecer una ruta:
    //
    //   índice 1 · el espejo miente
    //        acierto → 3  sigue camino
    //        fallo   → 2  sala de castigo, y desde ahí continúa a 3
    //
    // Así el error cuesta una sala extra, pero nunca deja al jugador atascado.

    val CASTLE = EscapeRoomDef(
        id      = "castle",
        name    = "El Castillo Encantado",
        modules = listOf(

            // 0 ── Morse difícil
            MorseModule(
                title      = "El Grimorio",
                narration  = "Un libro de hechizos exige la runa correcta, trazada con " +
                             "puntos y rayas.",
                hint       = "1 parpadeo = ·      2 parpadeos rápidos = —",
                // Letras de tres símbolos: más largas de trazar que las del tutorial.
                letterPool = "RUDKGO".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "28"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Este grimorio solo se abre con la runa exacta. Las runas de este " +
                        "castillo son más largas que las que has visto hasta ahora: tres " +
                        "señales cada una. Tómate tu tiempo, verás el trazo en pantalla.")
                )
            ),

            // 1 ── El espejo (bifurcación por acierto/fallo)
            YesNoModule(
                title     = "El Espejo que Miente",
                narration = "Un espejo encantado te interroga. Dicen que solo miente " +
                            "cuando le conviene.",
                hint      = "Asiente para SÍ  ·  Niega para NO",
                questions = listOf(
                    YesNoQuestion("¿Has cruzado ya la sala del grimorio?", expectedYes = true),
                    YesNoQuestion("¿Te fías de lo que dice un espejo encantado?", expectedYes = false),
                    // Al llevar goto, esta pregunta decide el camino y salta de sala.
                    YesNoQuestion(
                        text      = "El espejo te ofrece un atajo. ¿Rechazas su ayuda?",
                        gotoOnYes = 3,   // desconfiar era lo correcto: sigues camino
                        gotoOnNo  = 2    // aceptar el atajo: caes en la sala de los susurros
                    )
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Un espejo encantado te cierra el paso y quiere hacerte preguntas. " +
                        "Ten cuidado con lo que aceptas: en este castillo la ayuda gratis " +
                        "suele salir cara.")
                )
            ),

            // 2 ── Castigo por fiarse del espejo
            MorseModule(
                title      = "El Eco del Hechizo",
                narration  = "El atajo era una trampa. Para salir del eco hay que repetir " +
                             "la runa que lo cerró.",
                hint       = "1 parpadeo = ·      2 parpadeos rápidos = —",
                letterPool = "RUDK".toList(),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "20"),
                    RobotAction(RobotAction.Type.TURN,      "60"),
                    RobotAction(RobotAction.Type.WAIT,      "600"),
                    RobotAction(RobotAction.Type.TURN,      "-60"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Te has fiado del espejo y el atajo era una trampa. Estás en la sala " +
                        "del eco. Repite la runa que la cerró y te dejará salir.")
                )
            ),

            // 3 ── Calma larga
            CalmModule(
                title           = "La Cripta",
                narration       = "El frío de la cripta solo se soporta con la mente serena.",
                hint            = "Mantén la calma, ahora durante más tiempo",
                secondsRequired = 8,   // el más largo de los tres niveles
                robotActions    = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "30"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Has llegado a la cripta. Aquí el frío solo se soporta con la mente " +
                        "serena, y hace falta más aguante que antes: ocho segundos de calma " +
                        "sin perderla.")
                )
            ),

            // 4 ── Final con ventana corta
            BlinkClenchModule(
                title       = "El Portón de Hierro",
                narration   = "El portón cede un instante. Hay que aprovecharlo.",
                hint        = "Parpadea y aprieta la mandíbula sin demora",
                jawWindowMs = 3000L,   // ventana corta: exige reaccionar rápido
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "El portón de hierro es lo único que te separa de la salida, pero " +
                        "solo cede un instante. Parpadea para soltar el pestillo y aprieta " +
                        "la mandíbula enseguida, sin esperar.")
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
