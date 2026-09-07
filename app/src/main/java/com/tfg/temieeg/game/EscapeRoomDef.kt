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

            // Bienvenida temática (sin reto BCI): el robot abre la historia.
            RobotAnimModule(
                title     = "Ante la mazmorra",
                narration = "Las antorchas se encienden. Tu mente es la única llave para " +
                            "salir de aquí.",
                hint      = "El robot te da la bienvenida",
                delayMs   = 5000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "20"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Bienvenido, aventurero. Estás ante una vieja mazmorra y solo tu mente " +
                        "podrá abrir sus puertas. ¿Preparado? Empezamos."),
                    RobotAction(RobotAction.Type.TURN,      "45"),
                    RobotAction(RobotAction.Type.WAIT,      "500"),
                    RobotAction(RobotAction.Type.TURN,      "-45"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25")
                )
            ),

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
            ),

            // Celebración temática final (sin reto BCI): cierra la historia.
            RobotAnimModule(
                title     = "¡Has escapado!",
                narration = "Las puertas se abren de par en par. Lo has conseguido: has " +
                            "escapado usando solo tu mente.",
                hint      = "El robot celebra tu hazaña",
                delayMs   = 6000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.SPEAK,
                        "¡Lo has conseguido! Has escapado usando solo la fuerza de tu mente. " +
                        "Enhorabuena, aventurero."),
                    RobotAction(RobotAction.Type.TILT_HEAD, "10"),
                    RobotAction(RobotAction.Type.TURN,      "360"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25")
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
            ),

            // Celebración temática final (sin reto BCI): cierra la historia.
            RobotAnimModule(
                title     = "Estación alcanzada",
                narration = "El acoplamiento ha sido un éxito. La tripulación está a salvo " +
                            "gracias a ti.",
                hint      = "El robot celebra la misión",
                delayMs   = 6000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.SPEAK,
                        "Acoplamiento completado. Bienvenido a bordo de la estación, piloto. " +
                        "La misión ha sido un éxito."),
                    RobotAction(RobotAction.Type.TILT_HEAD, "10"),
                    RobotAction(RobotAction.Type.TURN,      "90"),
                    RobotAction(RobotAction.Type.WAIT,      "600"),
                    RobotAction(RobotAction.Type.TURN,      "-90"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25")
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
    //   índice 2 · el espejo miente
    //        acierto → 4  sigue camino
    //        fallo   → 3  sala de castigo, y desde ahí continúa a 4
    //
    // Así el error cuesta una sala extra, pero nunca deja al jugador atascado.

    val CASTLE = EscapeRoomDef(
        id                   = "castle",
        name                 = "El Castillo Encantado",
        introVideoResId      = com.tfg.temieeg.R.raw.castillo_intro,
        transitionVideoResId = com.tfg.temieeg.R.raw.castillo_transicion,
        modules = listOf(

            // 0 ── Bienvenida temática (sin reto BCI)
            RobotAnimModule(
                title     = "El castillo despierta",
                narration = "Las puertas del castillo se cierran a tu espalda. La única salida " +
                            "está en tu mente.",
                hint      = "El robot te da la bienvenida",
                delayMs   = 5500L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "20"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Bienvenido al castillo encantado. Sus puertas se han cerrado a tu " +
                        "espalda y solo una mente serena podrá liberarte. El castillo despierta."),
                    RobotAction(RobotAction.Type.TURN,      "60"),
                    RobotAction(RobotAction.Type.WAIT,      "600"),
                    RobotAction(RobotAction.Type.TURN,      "-60"),
                    RobotAction(RobotAction.Type.TILT_HEAD, "28")
                )
            ),

            // 1 ── Morse difícil
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

            // 2 ── El espejo (bifurcación por acierto/fallo)
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
                        gotoOnYes = 4,   // desconfiar era lo correcto: sigues camino
                        gotoOnNo  = 3    // aceptar el atajo: caes en la sala de los susurros
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

            // 3 ── Castigo por fiarse del espejo
            MorseModule(
                title      = "El Eco del Hechizo",
                narration  = "El atajo era una trampa. Para salir del eco hay que repetir " +
                             "la runa que lo cerró.",
                hint       = "1 parpadeo = ·      2 parpadeos rápidos = —",
                letterPool = "RUDK".toList(),
                videoResId = com.tfg.temieeg.R.raw.castillo_eco_hechizo,
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

            // 4 ── Calma larga
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

            // 5 ── Final con ventana corta
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
            ),

            // Celebración temática final (sin reto BCI): cierra la historia.
            RobotAnimModule(
                title     = "El hechizo roto",
                narration = "El portón cede con un crujido y la maldición del castillo se " +
                            "desvanece. Eres libre.",
                hint      = "El robot celebra tu victoria",
                delayMs   = 6000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.SPEAK,
                        "El hechizo se ha roto y el castillo te deja marchar. Has demostrado " +
                        "tener una mente serena y valiente."),
                    RobotAction(RobotAction.Type.TILT_HEAD, "10"),
                    RobotAction(RobotAction.Type.TURN,      "360"),
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

    // ── Calentamiento de gestos ───────────────────────────────────────────────
    //
    // Minitutorial práctico, común a todos los niveles y saltable. NO aparece en
    // el selector (no está en `all`): el motor lo ejecuta antes del nivel elegido
    // si el usuario acepta. Sirve para comprobar que la diadema detecta los cuatro
    // gestos antes de que empiece a contar la partida. Sin bifurcaciones.

    val WARMUP = EscapeRoomDef(
        id   = "warmup",
        name = "Calentamiento de gestos",
        modules = listOf(

            RobotAnimModule(
                title     = "Vamos a probar los gestos",
                narration = "Antes de empezar vamos a comprobar que la diadema detecta bien " +
                            "tus gestos. Esto no cuenta para nada: es solo para practicar.",
                hint      = "Escucha al robot",
                delayMs   = 5000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Vamos a comprobar que te detecto bien. Tranquilo, esto no puntúa: " +
                        "solo practicamos.")
                )
            ),

            YesNoModule(
                title     = "Asentir y negar",
                narration = "Practica los gestos de cabeza. Primero asiente para decir sí y " +
                            "luego niega para decir no.",
                hint      = "Asiente para SÍ  ·  Niega para NO",
                questions = listOf(
                    YesNoQuestion("Asiente con la cabeza para decir SÍ.", expectedYes = true),
                    YesNoQuestion("Ahora niega con la cabeza para decir NO.", expectedYes = false)
                ),
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Primero asiente con la cabeza para decir sí. Luego niega para decir no.")
                )
            ),

            BlinkClenchModule(
                title       = "Parpadeo y mandíbula",
                narration   = "Ahora los dos últimos gestos: parpadea para accionar y, justo " +
                              "después, aprieta la mandíbula.",
                hint        = "Parpadea → aprieta la mandíbula",
                jawWindowMs = 6000L,   // ventana muy amplia: es práctica, sin prisa
                robotActions = listOf(
                    RobotAction(RobotAction.Type.TILT_HEAD, "25"),
                    RobotAction(RobotAction.Type.SPEAK,
                        "Último paso. Parpadea una vez y, justo después, aprieta la mandíbula. " +
                        "Tienes tiempo de sobra.")
                )
            ),

            RobotAnimModule(
                title     = "¡Listo!",
                narration = "Perfecto, te detecto bien. Empezamos la aventura.",
                hint      = "Preparado para empezar",
                delayMs   = 4000L,
                robotActions = listOf(
                    RobotAction(RobotAction.Type.SPEAK,
                        "Perfecto. Te detecto sin problema. Empezamos la aventura."),
                    RobotAction(RobotAction.Type.TILT_HEAD, "25")
                )
            )
        )
    )

    // ── Lista completa (MainActivity la itera para el selector) ───────────────

    val all: List<EscapeRoomDef> = listOf(CLASSIC, SPACE, CASTLE, NAV_TEST)
}
